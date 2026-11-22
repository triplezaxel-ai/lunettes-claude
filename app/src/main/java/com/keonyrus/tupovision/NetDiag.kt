package com.keonyrus.tupovision

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A one-line picture of the network as this app sees it, written to the log when a request cannot
 * leave the phone. The app can be cut off while the browser is not: a VPN or DNS filter that only
 * covers some apps, or a saver mode that blocks apps that are not on screen.
 */
object NetDiag {
  private val HOSTS = listOf("auth.openai.com", "api.openai.com", "www.google.com")

  suspend fun report(context: Context): String =
      withContext(Dispatchers.IO) {
        val parts = ArrayList<String>()
        for (host in HOSTS) {
          val resolved = runCatching { InetAddress.getByName(host) }.isSuccess
          parts.add("$host " + if (resolved) "resolves" else "DOES NOT resolve")
        }
        runCatching {
          val connectivity = context.getSystemService(ConnectivityManager::class.java)
          val network = connectivity.activeNetwork
          val capabilities = network?.let { connectivity.getNetworkCapabilities(it) }
          val link = network?.let { connectivity.getLinkProperties(it) }
          val kind =
              when {
                capabilities == null -> "blocked for this app"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
              }
          parts.add("network: $kind")
          if (link != null && link.isPrivateDnsActive) {
            parts.add("Private DNS: " + (link.privateDnsServerName ?: "automatic"))
          }
          val saver =
              when (connectivity.restrictBackgroundStatus) {
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "on"
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "on, this app exempt"
                else -> "off"
              }
          parts.add("data saver: $saver")
          val power = context.getSystemService(PowerManager::class.java)
          parts.add("battery saver: " + if (power.isPowerSaveMode) "on" else "off")
        }
        parts.joinToString("; ")
      }
}
