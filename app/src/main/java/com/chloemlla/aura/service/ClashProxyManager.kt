package com.chloemlla.aura.service

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.chloemlla.aura.BuildConfig
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory

/**
 * Holder for a static [ClashProxyManager] reference, set during app startup
 * so that standalone OkHttp clients (e.g. [VideoCropScreen]'s sharedHttpClient)
 * can reach the proxy state without Hilt injection.
 */
object ClashProxyHolder {
    @Volatile
    var instance: ClashProxyManager? = null
}

/**
 * Entry point for accessing [ClashProxyManager] from non-Hilt classes (e.g.
 * composable functions that need one-shot proxy configuration).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ClashProxyManagerEntryPoint {
    fun clashProxyManager(): ClashProxyManager
}

/**
 * Manages Clash proxy compatibility for Aura.
 *
 * Detects installed Clash Meta for Android variants, monitors VPN network state,
 * and adapts network traffic so that Aura's HTTP requests (OkHttp) and subprocess
 * downloads (FFmpeg, yt-dlp) route through Clash.
 *
 * ## Strategy
 *
 * - **Clash VPN active**: the process is bound to the VPN network via
 *   [ConnectivityManager.bindProcessToNetwork]; in-process OkHttp clients use
 *   [Proxy.NO_PROXY] to avoid stacking a manual proxy on the tunnel. Subprocesses
 *   (FFmpeg, yt-dlp) receive `http_proxy` / `https_proxy` environment variables
 *   pointing to Clash's local mixed-port (default 127.0.0.1:7890).
 *
 * - **Clash installed but VPN inactive**: OkHttp and subprocesses both use the
 *   detected Clash HTTP proxy address.
 *
 * - **No Clash detected**: no proxy adaptation.
 *
 * ## Reference implementations
 *
 * This class adapts patterns from Project-Lumen's [ClashPartnerCompat],
 * PiliPlus's [ClashCompat], and NexAI's [ClashCompatChannel] — all of which
 * integrate with Clash Meta for Android via the Kr328/MetaCubeX partner
 * StatusProvider protocol.
 */
@Singleton
class ClashProxyManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Known Clash Meta for Android package names, ordered by preference. */
    private val clashPackages = listOf(
        "com.github.metacubex.clash",
        "com.github.metacubex.clash.alpha",
        "com.github.metacubex.clash.meta",
        "com.github.kr328.clash",
    )

    /** ContentProvider authority suffix for partner status queries. */
    private val partnerAuthoritySuffix = ".status"

    /** Default Clash mixed-port HTTP proxy address. */
    private val defaultClashProxyAddress = InetSocketAddress("127.0.0.1", 7890)

    // ── State ──────────────────────────────────────────────────────────

    private val _clashPackage = AtomicReference<String?>(null)
    private val _vpnNetwork = AtomicReference<Network?>(null)
    private val _processBound = AtomicBoolean(false)
    private val _autoAdaptEnabled = AtomicBoolean(true)
    private val _partnerStatus = AtomicReference<Bundle?>(null)
    private val _proxyAddress = AtomicReference(defaultClashProxyAddress)

    private val listeners = CopyOnWriteArrayList<ClashStateListener>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Snapshot of the current Clash proxy state. */
    data class ClashState(
        val clashInstalled: Boolean,
        val clashPackage: String?,
        val vpnActive: Boolean,
        val clashVpnRunning: Boolean,
        val autoAdaptEnabled: Boolean,
        val processBound: Boolean,
        val proxyAddress: InetSocketAddress?,
    ) {
        val isClashRouting: Boolean
            get() = autoAdaptEnabled && clashVpnRunning

        val shouldSkipManualProxy: Boolean
            get() = isClashRouting && processBound
    }

    /** Listener for state changes. */
    fun interface ClashStateListener {
        fun onClashStateChanged(state: ClashState)
    }

    // ── Public API ─────────────────────────────────────────────────────

    /** Current Clash state snapshot. */
    fun state(): ClashState = buildState()

    /**
     * Whether the in-process proxy should be skipped because Clash VPN is
     * already routing traffic through process-level VPN binding.
     */
    fun shouldSkipManualProxy(): Boolean {
        val state = buildState()
        return state.isClashRouting && state.processBound
    }

    /**
     * Proxy address for subprocesses (FFmpeg, yt-dlp) and fallback OkHttp.
     * Returns the Clash mixed-port address when Clash is installed, null otherwise.
     */
    fun proxyAddress(): InetSocketAddress? =
        if (state().clashInstalled) (_proxyAddress.get() ?: defaultClashProxyAddress) else null

    /**
     * Environment variable map for subprocess network proxy.
     * Returns `http_proxy` and `https_proxy` entries when Clash is detected,
     * or an empty map when no proxy is needed.
     */
    fun proxyEnvVars(): Map<String, String> {
        val addr = proxyAddress() ?: return emptyMap()
        val proxyUrl = "http://${addr.hostString}:${addr.port}"
        return mapOf("http_proxy" to proxyUrl, "https_proxy" to proxyUrl)
    }

    /**
     * Resolve the [java.net.Proxy] that in-process HTTP clients should use at
     * this moment.
     *
     * Returns [java.net.Proxy.NO_PROXY] when the Clash VPN is verified to be
     * routing the process (so connections ride the tunnel directly), the
     * detected Clash mixed-port HTTP proxy when Clash is installed, or
     * [java.net.Proxy.NO_PROXY] when no Clash is detected.
     */
    fun resolveHttpProxy(): java.net.Proxy =
        if (shouldSkipManualProxy()) {
            java.net.Proxy.NO_PROXY
        } else {
            val addr = proxyAddress()
            if (addr != null) {
                java.net.Proxy(java.net.Proxy.Type.HTTP, addr)
            } else {
                java.net.Proxy.NO_PROXY
            }
        }

    /**
     * Creates a [SocketFactory] that binds each socket to the Clash VPN network
     * when active. This provides reliable per-socket VPN binding as a complement
     * to the process-level [ConnectivityManager.bindProcessToNetwork], which is
     * unreliable on Android 10+.
     *
     * OkHttp connects sockets it creates with `createSocket()` (no-arg), so the
     * socket is bound to the VPN network before the connection is established.
     * When no Clash VPN is routing, the delegate socket factory is used directly.
     */
    fun createVpnSocketFactory(delegate: SocketFactory = SocketFactory.getDefault()): SocketFactory {
        return object : SocketFactory() {
            private fun bind(socket: Socket) {
                if (!buildState().isClashRouting) return
                val vpn = _vpnNetwork.get() ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    runCatching { vpn.bindSocket(socket) }
                }
            }

            override fun createSocket(): Socket {
                val socket = delegate.createSocket()
                bind(socket)
                return socket
            }

            override fun createSocket(host: String, port: Int): Socket {
                val socket = delegate.createSocket(host, port)
                bind(socket)
                return socket
            }

            override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket {
                val socket = delegate.createSocket(host, port, localHost, localPort)
                bind(socket)
                return socket
            }

            override fun createSocket(host: java.net.InetAddress, port: Int): Socket {
                val socket = delegate.createSocket(host, port)
                bind(socket)
                return socket
            }

            override fun createSocket(host: java.net.InetAddress, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket {
                val socket = delegate.createSocket(host, port, localHost, localPort)
                bind(socket)
                return socket
            }
        }
    }

    /**
     * Apply proxy environment variables to a [ProcessBuilder] for subprocess
     * downloads (FFmpeg, yt-dlp). No-op if Clash is not detected.
     */
    fun applyProxyToProcessBuilder(pb: ProcessBuilder) {
        proxyEnvVars().forEach { (key, value) ->
            pb.environment().putIfAbsent(key, value)
        }
    }

    /**
     * Enable or disable Clash auto-adapt. When enabled, the process is bound
     * to the Clash VPN network and manual proxy is skipped. Default: enabled.
     */
    fun setAutoAdaptEnabled(enabled: Boolean) {
        if (_autoAdaptEnabled.getAndSet(enabled) != enabled) {
            if (enabled) {
                refresh()
            } else {
                unbindProcess()
            }
            notifyListeners()
        }
    }

    /** Add a state change listener. */
    fun addListener(listener: ClashStateListener) {
        listeners.add(listener)
    }

    /** Remove a state change listener. */
    fun removeListener(listener: ClashStateListener) {
        listeners.remove(listener)
    }

    /**
     * Initialize and start monitoring. Call from [Application.onCreate].
     * Sets a global [ProxySelector] so that all HTTP clients (OkHttp,
     * HttpURLConnection, etc.) route through Clash when available.
     */
    fun start() {
        ClashProxyHolder.instance = this
        installGlobalProxySelector()
        refresh()
        startNetworkWatch()
    }

    /**
     * Installs a global [ProxySelector] that delegates to this manager,
     * covering all [java.net.URL.openConnection] calls (including NewPipe's
     * DownloaderImpl) and OkHttp clients without their own proxy config.
     */
    private fun installGlobalProxySelector() {
        try {
            ProxySelector.setDefault(object : ProxySelector() {
                override fun select(uri: URI?): List<java.net.Proxy> {
                    return if (shouldSkipManualProxy()) {
                        listOf(java.net.Proxy.NO_PROXY)
                    } else {
                        val addr = proxyAddress()
                        if (addr != null) {
                            listOf(java.net.Proxy(java.net.Proxy.Type.HTTP, addr))
                        } else {
                            listOf(java.net.Proxy.NO_PROXY)
                        }
                    }
                }
                override fun connectFailed(uri: URI?, sa: SocketAddress?, e: java.io.IOException?) {}
            })
        } catch (_: SecurityException) {
            // Some environments restrict setting the default ProxySelector.
        }
    }

    /**
     * Refresh the Clash state: detect packages, query partner status, and
     * apply VPN binding if appropriate.
     */
    fun refresh() {
        val detected = detectClashPackage()
        _clashPackage.set(detected)
        _partnerStatus.set(queryPartnerStatus(detected))
        val vpn = findVpnNetwork()
        _vpnNetwork.set(vpn)
        applyVpnBinding()
        notifyListeners()
    }

    // ── Internal ───────────────────────────────────────────────────────

    private fun buildState(): ClashState {
        val pkg = _clashPackage.get()
        val vpn = _vpnNetwork.get()
        val status = _partnerStatus.get()
        val clashVpnRunning = if (status != null) {
            status.getBoolean("vpnRunning", false) && status.getBoolean("partnerAppAutoAdapt", false)
        } else {
            pkg != null && vpn != null
        }
        return ClashState(
            clashInstalled = pkg != null,
            clashPackage = pkg,
            vpnActive = vpn != null,
            clashVpnRunning = clashVpnRunning,
            autoAdaptEnabled = _autoAdaptEnabled.get(),
            processBound = _processBound.get(),
            proxyAddress = _proxyAddress.get(),
        )
    }

    private fun detectClashPackage(): String? {
        val pm = context.packageManager
        for (pkg in clashPackages) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: PackageManager.NameNotFoundException) {
                continue
            }
        }
        return null
    }

    private fun queryPartnerStatus(pkg: String?): Bundle? {
        if (pkg == null) return null
        return try {
            val uri = android.net.Uri.parse("content://$pkg$partnerAuthoritySuffix")
            context.contentResolver.call(uri, "partnerStatus", null, null)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Partner status query failed for $pkg: ${e.message}")
            }
            null
        }
    }

    private fun findVpnNetwork(): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .build()
            val networks = cm.allNetworks
            for (network in networks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return network
                }
            }
        }
        return null
    }

    private fun applyVpnBinding() {
        val state = buildState()
        if (state.isClashRouting && state.vpnActive) {
            val vpn = _vpnNetwork.get() ?: return
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            val result = runCatching {
                cm.bindProcessToNetwork(vpn)
            }
            if (result.isSuccess) {
                _processBound.set(true)
            } else {
                _processBound.set(false)
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "VPN process binding failed: ${result.exceptionOrNull()?.message}")
                }
            }
        } else {
            unbindProcess()
        }
    }

    private fun unbindProcess() {
        if (_processBound.getAndSet(false)) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            runCatching {
                cm.bindProcessToNetwork(null)
            }
        }
    }

    private fun startNetworkWatch() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                    _vpnNetwork.set(network)
                    refresh()
                }
            }

            override fun onLost(network: Network) {
                if (_vpnNetwork.get() == network) {
                    _vpnNetwork.set(null)
                    refresh()
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    _vpnNetwork.set(network)
                    refresh()
                } else if (_vpnNetwork.get() == network) {
                    _vpnNetwork.set(null)
                    refresh()
                }
            }
        }
        this.networkCallback = callback

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build()
        cm.registerNetworkCallback(request, callback)
    }

    private fun notifyListeners() {
        val state = buildState()
        listeners.forEach { it.onClashStateChanged(state) }
    }

    private companion object {
        private const val TAG = "ClashProxyManager"
    }
}