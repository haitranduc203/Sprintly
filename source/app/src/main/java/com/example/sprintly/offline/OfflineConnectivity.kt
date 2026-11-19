package com.example.sprintly.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
internal class OfflineConnectivity @Inject constructor(@ApplicationContext context: Context) {
    private val manager = checkNotNull(context.getSystemService(ConnectivityManager::class.java))
    private val mutableConnected = MutableStateFlow(isConnected())
    val connected = mutableConnected.asStateFlow()

    init {
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            private var defaultNetwork = manager.activeNetwork

            override fun onAvailable(network: Network) {
                defaultNetwork = network
                mutableConnected.value = true
            }

            override fun onLost(network: Network) {
                if (defaultNetwork != network) return
                defaultNetwork = null
                mutableConnected.value = false
            }
        })
    }

    fun networkHandle(): Long? = manager.activeNetwork?.networkHandle

    fun isConnected(): Boolean = networkHandle() != null
}
