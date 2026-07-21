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
        "Госуслуги" to "https://gosuslugi.ru",
        "Яндекс" to "https://yandex.ru",
        "Google" to "https://google.com",
        "Wikipedia" to "https://wikipedia.org"
    )

    suspend fun checkAll(): List<ServiceStatus> = withContext(Dispatchers.IO) {
        servicesToCheck.map { (name, url) ->
            val accessible = isReachable(url)
            ServiceStatus(name, url, accessible)
        }
    }

    private fun isReachable(urlString: String, retries: Int = 2): Boolean {
        var attempt = 0
        while (attempt <= retries) {
            try {
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                val code = connection.responseCode
                connection.disconnect()
                if (code == HttpURLConnection.HTTP_OK) return true
            } catch (e: Exception) {
                // игнорируем и пробуем снова
            }
            attempt++
            if (attempt <= retries) Thread.sleep(500) // ждём 0.5 сек перед повторной попыткой
        }
        return false
    }

    // Новая логика: ограничение есть, если Госуслуги доступны, а хотя бы один зарубежный сайт недоступен.
    // Яндекс не учитывается, чтобы избежать ложных срабатываний при его временной недоступности.
    fun isRestricted(statuses: List<ServiceStatus>): Boolean {
        val russianSites = statuses.take(1) // ТОЛЬКО Госуслуги
        val foreignSites = statuses.drop(2) // Google, Wikipedia (пропускаем Яндекс)
        val allRussianOk = russianSites.all { it.isAccessible }
        val anyForeignBlocked = foreignSites.any { !it.isAccessible }
        return allRussianOk && anyForeignBlocked
    }
}
