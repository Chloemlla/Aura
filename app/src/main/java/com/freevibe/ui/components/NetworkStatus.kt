package com.freevibe.ui.components

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.freevibe.R

/**
 * Observes the active network and returns `true` while the device is on a metered connection
 * (mobile data, metered hotspot), `false` on Wi‑Fi/Ethernet/unmetered — and `false` when there
 * is no active network (offline has its own handling; there is nothing to warn about).
 *
 * Backed by a default-network callback so the value updates live as the user moves between
 * Wi‑Fi and mobile data. Requires only ACCESS_NETWORK_STATE, which the app already holds.
 */
@Composable
fun rememberOnMeteredConnection(): Boolean {
    val context = LocalContext.current
    val connectivityManager = remember(context) {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }
    var metered by remember { mutableStateOf(connectivityManager.isActiveNetworkMeteredOrFalse()) }

    DisposableEffect(connectivityManager) {
        val cm = connectivityManager ?: return@DisposableEffect onDispose { }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                metered = cm.isActiveNetworkMeteredOrFalse()
            }

            override fun onLost(network: Network) {
                metered = cm.isActiveNetworkMeteredOrFalse()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }

    return metered
}

private fun ConnectivityManager?.isActiveNetworkMeteredOrFalse(): Boolean {
    val cm = this ?: return false
    // No active network → offline, not "metered": don't raise the data warning.
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}

/**
 * Dismissible banner shown while the device is on mobile data, warning that browsing wallpapers,
 * video wallpapers, and sounds downloads content and recommending Wi‑Fi.
 */
@Composable
fun MobileDataWarningBanner(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AuraStatusBanner(
        icon = Icons.Default.SignalCellularAlt,
        title = stringResource(R.string.data_warning_title),
        message = stringResource(R.string.data_warning_message),
        tone = MaterialTheme.colorScheme.tertiary,
        primaryAction = AuraStatusAction(
            label = stringResource(R.string.data_warning_dismiss),
            icon = Icons.Default.Check,
            onClick = onDismiss,
        ),
        modifier = modifier,
    )
}
