// === NetworkChecker.kt ===
package com.example.whitelistcheck4

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import org.json.JSONArray
import org.json.JSONObject

data class ServiceStatus(
    val name: String,
    val url: String,
    val isAccessible: Boolean
)

object NetworkChecker {

    private const val PREFS_NAME = "whitelist_prefs"
    private const val KEY_SITES = "custom_sites"

    fun getSites(context: Context): List<Pair<String, String>> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SITES, null) ?: return defaultSites()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                obj.getString("name") to obj.getString("url")
            }
        } catch (e: Exception) {
            defaultSites()
        }
    }

    fun saveSites(context: Context, sites: List<Pair<String, String>>) {
        val arr = JSONArray()
        sites.forEach { (name, url) ->
            val obj = JSONObject().apply {
                put("name", name)
                put("url", url)
            }
            arr.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SITES, arr.toString())
            .apply()
    }

    private fun defaultSites(): List<Pair<String, String>> = listOf(
        "Госуслуги" to "https://gosuslugi.ru",
        "Яндекс" to "https://yandex.ru",
        "Google" to "https://www.google.com/generate_204",
        "Wikipedia" to "https://ru.wikipedia.org"
    )

    suspend fun checkAll(context: Context): List<ServiceStatus> = withContext(Dispatchers.IO) {
        val sites = getSites(context)
        sites.map { (name, url) ->
            val accessible = isReachable(url)
            ServiceStatus(name, url, accessible)
        }
    }

    private fun isReachable(urlString: String, retries: Int = 3): Boolean {
        var attempt = 0
        while (attempt < retries) {
            try {
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                connection.instanceFollowRedirects = true

                val startTime = System.currentTimeMillis()
                val code = connection.responseCode
                val elapsed = System.currentTimeMillis() - startTime
                android.util.Log.d("NetworkChecker", "$urlString responded in ${elapsed}ms with code $code")

                connection.disconnect()
                return when (code) {
                    in 200..299 -> true
                    204 -> true
                    else -> false
                }
            } catch (e: SocketTimeoutException) {
                android.util.Log.w("NetworkChecker", "Timeout for $urlString attempt $attempt")
            } catch (e: UnknownHostException) {
                android.util.Log.w("NetworkChecker", "DNS error for $urlString")
                return false
            } catch (e: Exception) {
                android.util.Log.w("NetworkChecker", "Error for $urlString: ${e.message}")
            }
            attempt++
            if (attempt < retries) {
                Thread.sleep(1000L * attempt)
            }
        }
        return false
    }

    fun isRestricted(statuses: List<ServiceStatus>): Boolean {
        if (statuses.isEmpty()) return false
        // Определяем российские сайты по названию (можно улучшить)
        val russianKeywords = listOf("госуслуги", "яндекс")
        val russianSites = statuses.filter { russianKeywords.any { keyword -> it.name.lowercase().contains(keyword) } }
        val foreignSites = statuses.filter { !russianKeywords.any { keyword -> it.name.lowercase().contains(keyword) } }

        if (russianSites.isEmpty() || foreignSites.isEmpty()) return false

        val allRussianOk = russianSites.all { it.isAccessible }
        val anyForeignBlocked = foreignSites.any { !it.isAccessible }
        return allRussianOk && anyForeignBlocked
    }
}
