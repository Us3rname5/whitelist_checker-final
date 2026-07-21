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

    // Список для проверки: первые два – государственные/российские, остальные – зарубежные
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

    // Новая логика: ограничение есть, если хотя бы один зарубежный сайт недоступен,
    // но все государственные доступны.
    fun isRestricted(statuses: List<ServiceStatus>): Boolean {
        val russianSites = statuses.take(2) // первые два – гос
        val foreignSites = statuses.drop(2) // остальные – зарубежные

        val allRussianOk = russianSites.all { it.isAccessible }
        val anyForeignBlocked = foreignSites.any { !it.isAccessible }

        return allRussianOk && anyForeignBlocked
    }
}
