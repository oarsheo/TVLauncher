package com.arshe.tvos.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class NetStatus(val online: Boolean, val vpn: Boolean)

@Composable
fun rememberNetStatus(): State<NetStatus> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(NetStatus(online = true, vpn = false)) }
    DisposableEffect(Unit) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        fun refresh() {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            state.value = NetStatus(
                online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            )
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
        }
        refresh()
        cm.registerDefaultNetworkCallback(cb)
        onDispose { cm.unregisterNetworkCallback(cb) }
    }
    return state
}

@Composable
fun rememberClock(): String {
    val time by produceState(now()) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            value = now()
        }
    }
    return time
}

private fun now() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
