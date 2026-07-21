package com.example.whitelistcheck4

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class ServiceStatus(
    val name: String,
    val url: String,
    val isAccessible: Boolean
)

object NetworkChecker {

    private val servicesToCheck = listOf(
        "youtube" to "https://www.youtube.com",
        "telegram" to "https://telegram.org",
        "whatsapp" to "https://web.whatsapp.com",
        "openvpn" to "https://openvpn.net"
    )

    suspend fun checkAll(): List<ServiceStatus> = withContext(Dispatchers.IO) {
        servicesToCheck.map { (name, url) ->
            val accessible = isReachable(url)
            ServiceStatus(name, url, accessible)
        }
    }

    private fun isReachable(urlString: String): Boolean {
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            connection.disconnect()
            code == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            false
        }
    }

    fun isRestricted(statuses: List<ServiceStatus>): Boolean {
        return statuses.any { !it.isAccessible }
    }
}
