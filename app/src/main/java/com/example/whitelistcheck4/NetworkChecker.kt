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

- app/build.gradle.kts:
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0"
}

android {
    namespace = "com.example.whitelistcheck4"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.whitelistcheck4"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("com.google.android.gms:play-services-location:21.1.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("com.google.accompanist:accompanist-permissions:0.34.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
}

- ic.launcher.xml:
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/white"/>
    <foreground>
        <inset android:inset="25%">
            <shape android:shape="rectangle">
                <solid android:color="#000000"/>
                <corners android:radius="5dp"/>
            </shape>
        </inset>
    </foreground>
</adaptive-icon>

- colors.xml:
<resources>
    <color name="white">#FFFFFF</color>
</resources>

- themes.xml:
<resources>
    <style name="Theme.WhiteListCheck" parent="android:Theme.Material.Light.NoActionBar">
        <item name="android:colorPrimary">#000000</item>
        <item name="android:colorPrimaryDark">#FFFFFF</item>
        <item name="android:colorAccent">#888888</item>
        <item name="android:textColorPrimary">#000000</item>
        <item name="android:windowBackground">#FFFFFF</item>
    </style>
</resources>
