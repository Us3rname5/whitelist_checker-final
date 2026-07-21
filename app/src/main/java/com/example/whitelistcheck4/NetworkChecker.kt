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

    // Используем специальные URL для надёжной проверки
    private val servicesToCheck = listOf(
        "Госуслуги" to "https://gosuslugi.ru",
        "Яндекс" to "https://yandex.ru",
        "Google" to "https://www.google.com/generate_204",  // ← специальный URL для проверки
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
                // Добавляем реалистичный User-Agent, чтобы не выглядеть как бот
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                // Разрешаем автоматически следовать редиректам (коды 3xx)
                connection.instanceFollowRedirects = true
                val code = connection.responseCode
                connection.disconnect()

                // Считаем успехом:
                // - 200 (OK)
                // - 204 (No Content) – для Google generate_204
                // - 301, 302, 303, 307, 308 (редиректы) – если они обработаны, то мы получим конечный код
                // Но если instanceFollowRedirects = true, то после редиректа мы получим финальный код (200 или 204)
                // Поэтому проверяем: code in 200..299 или code == 204
                return when (code) {
                    in 200..299 -> true
                    204 -> true
                    else -> false
                }
            } catch (e: Exception) {
                // Логируем ошибку для отладки (можно убрать)
                e.printStackTrace()
            }
            attempt++
            if (attempt <= retries) Thread.sleep(500)
        }
        return false
    }

    // Логика ограничений: если Госуслуги доступны, а хотя бы один зарубежный сайт (Google или Wikipedia) недоступен
    fun isRestricted(statuses: List<ServiceStatus>): Boolean {
        val russianSites = statuses.take(1) // только Госуслуги
        val foreignSites = statuses.drop(2) // Google, Wikipedia (пропускаем Яндекс)
        val allRussianOk = russianSites.all { it.isAccessible }
        val anyForeignBlocked = foreignSites.any { !it.isAccessible }
        return allRussianOk && anyForeignBlocked
    }
}
