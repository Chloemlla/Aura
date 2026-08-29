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

/** CMFA 授予 Aura 的 `partnerStatus` 读取层级，对应 provider 的 `accessTier` 字段。 */
enum class ClashAccess { Unavailable, Denied, Basic, Full }

/**
 * 读出 CMFA 授予的层级。
 *
 * apiVersion 3 起 `accessTier` 明确回传 `denied` / `basic` / `full`；更早的 CMFA 不带这个字段，
 * 但那时只要能读到内容就等于拿到了全部字段，所以按 [ClashAccess.Full] 处理。
 */
internal fun parseClashAccess(values: Map<String, Any?>): ClashAccess =
    when (values["accessTier"] as? String) {
        "denied" -> ClashAccess.Denied
        "basic" -> ClashAccess.Basic
        "full" -> ClashAccess.Full
        else -> if (values.isEmpty()) ClashAccess.Unavailable else ClashAccess.Full
    }

/**
 * 把 CMFA 的机器可读 `deniedReason` 翻成用户能照着做的一句中文。
 *
 * 这些取值来自 CMFA 的 `PartnerAccessResolver`；未知取值原样带出，便于对着 logcat 排查。
 */
internal fun describeDeniedReason(reason: String?): String = when (reason) {
    "pending_user_approval" -> "等待在 Clash 中确认配对：打开 Clash 主页或点击配对通知即可授权"
    "denied_by_user" -> "已在 Clash 中拒绝授权，可在 Clash 主页「伙伴应用」里撤销"
    "signer_unverified" -> "Clash 未登记 Aura 的签名证书，只开放基础状态；在「伙伴应用」里允许即可读取完整状态"
    "not_partner" -> "Clash 没把 Aura 认成伙伴应用，请更新 Clash 到支持伙伴配对的版本"
    "no_signature" -> "Clash 读不到 Aura 的签名信息，无法完成配对"
    null -> "Clash 未说明原因"
    else -> "Clash 返回原因：$reason"
}

/**
 * 一次 `partnerStatus` 查询的结果：层级、拒绝原因，以及真正读到的字段。
 * [values] 只在层级可读（Basic/Full）时非空——被拒时返回的 bundle 也非空，但只带
 * apiVersion/accessTier/deniedReason，绝不能把它当成一份全 false 的状态。
 */
private data class PartnerRead(
    val access: ClashAccess,
    val deniedReason: String?,
    val values: Map<String, Any?>?,
)

private val UNAVAILABLE_PARTNER = PartnerRead(ClashAccess.Unavailable, null, null)

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
    private val _partnerRead = AtomicReference(UNAVAILABLE_PARTNER)
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
        val partnerAccess: ClashAccess = ClashAccess.Unavailable,
        val partnerDeniedReason: String? = null,
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
        _partnerRead.set(queryPartnerStatus(detected))
        val vpn = findVpnNetwork()
        _vpnNetwork.set(vpn)
        applyVpnBinding()
        notifyListeners()
    }

    // ── Internal ───────────────────────────────────────────────────────

    private fun buildState(): ClashState {
        val pkg = _clashPackage.get()
        val vpn = _vpnNetwork.get()
        val read = _partnerRead.get()
        val status = read.values
        // Provider 状态可信（Basic/Full）时以它为准。被拒时返回的 bundle 也非空但没有任何
        // 状态字段，必须退回「Clash 已装且 VPN 活跃」的启发式——否则会把一次拒绝误判成
        // 「Clash 没在路由」，在一条活着的隧道上再叠一层手动代理。
        val clashVpnRunning = if (status != null) {
            status["vpnRunning"] as? Boolean == true &&
                status["partnerAppAutoAdapt"] as? Boolean == true
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
            partnerAccess = read.access,
            partnerDeniedReason = read.deniedReason,
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

    /**
     * 查一次 `partnerStatus`，返回层级、拒绝原因与真正读到的字段。
     *
     * 三种失败要分开：Provider 缺失或 binder 异常（旧版 Clash，没有伙伴接口）、CMFA 明确拒绝
     * （带 `deniedReason`，用户照着做就能解决）、以及只授予基础层。混成一句「读不到状态」时
     * 用户无从下手，这也是路由决策会踩坑的根因。
     */
    private fun queryPartnerStatus(pkg: String?): PartnerRead {
        if (pkg == null) return UNAVAILABLE_PARTNER
        val bundle = try {
            val uri = android.net.Uri.parse("content://$pkg$partnerAuthoritySuffix")
            context.contentResolver.call(uri, "partnerStatus", null, null)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Partner status query failed for $pkg: ${e.message}")
            }
            null
        } ?: return UNAVAILABLE_PARTNER
        val values = bundle.toValueMap()
        val access = parseClashAccess(values)
        val reason = values["deniedReason"] as? String
        if (access != ClashAccess.Full) {
            Log.d(TAG, "伙伴状态受限：$pkg tier=$access reason=$reason")
        }
        return PartnerRead(
            access = access,
            deniedReason = reason,
            values = values.takeIf { access != ClashAccess.Denied },
        )
    }

    @Suppress("DEPRECATION")
    private fun Bundle.toValueMap(): Map<String, Any?> =
        keySet().associateWith { get(it) }

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