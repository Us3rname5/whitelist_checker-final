package com.example.whitelistcheck4

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.permissions.* // Для разрешений
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

enum class NetworkStatus {
    OK, WIFI_ONLY, NO_MOBILE
}

enum class ConnectionStatus {
    NO_SIM,
    NO_INTERNET,
    WIFI_AND_MOBILE,
    MOBILE_ONLY
}

class MainActivity : ComponentActivity() {

    private fun checkConnectionStatus(context: Context): ConnectionStatus {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        val hasSim =
            tm.simState != TelephonyManager.SIM_STATE_ABSENT && tm.simState != TelephonyManager.SIM_STATE_UNKNOWN
        if (!hasSim) return ConnectionStatus.NO_SIM

        val activeNetwork = cm.activeNetwork ?: return ConnectionStatus.NO_INTERNET
        val caps = cm.getNetworkCapabilities(activeNetwork)
            ?: return ConnectionStatus.NO_INTERNET

        val hasCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isInternet =
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        if (!isInternet) return ConnectionStatus.NO_INTERNET

        return when {
            hasWifi && hasCellular -> ConnectionStatus.WIFI_AND_MOBILE
            hasCellular && !hasWifi -> ConnectionStatus.MOBILE_ONLY
            else -> ConnectionStatus.MOBILE_ONLY
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = android.graphics.Color.BLACK
        }

        setContent {
            val connectionStatus = remember { checkConnectionStatus(this) } // Вызов перенесён сюда

            when (connectionStatus) {
                ConnectionStatus.NO_SIM -> NoSimScreen()
                ConnectionStatus.NO_INTERNET -> InfoScreen("проверка недоступна", "нет интернет-соединения")
                ConnectionStatus.WIFI_AND_MOBILE -> InfoScreen("проверка недоступна", "отключите Wi-Fi")
                ConnectionStatus.MOBILE_ONLY -> MainScreen()
            }
        }
    }

    fun exportHistory(context: Context, repo: HistoryRepository) {
        kotlinx.coroutines.GlobalScope.launch {
            val list = repo.getHistory()
            if (list.isEmpty()) {
                (context as? android.app.Activity)?.runOnUiThread {
                    Toast.makeText(context, "История пуста", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val sb = StringBuilder()
            sb.append("Whitelist Checker - история проверок\n")
            sb.append("=====================================\n\n")
            list.forEach { entry ->
                val date = SimpleDateFormat(
                    "dd.MM.yyyy HH:mm",
                    Locale.getDefault()
                ).format(Date(entry.timestamp))
                sb.append("$date | ${if (entry.isRestricted) "ОГРАНИЧЕНИЯ" else "СВОБОДА"}\n")
                sb.append("   Статусы: ${entry.statusesJson}\n")
                if (entry.location != null) sb.append("   Локация: ${entry.location}\n")
                sb.append("\n")
            }
            val file = File(context.cacheDir, "history_${System.currentTimeMillis()}.txt")
            file.writeText(sb.toString())
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Экспорт истории"))
        }
    }
}

@Composable
fun NoSimScreen() {
    val context = LocalContext.current
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A1A)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text(
                text = "нет sim-карты".lowercase(),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = """
                    для работы приложения необходима мобильная сеть.
                    вы можете приобрести sim-карту в любом салоне связи:
                    • мтс
                    • мегафон
                    • теле2 и других.""".trimMargin(),
                fontSize = 16.sp,
                color = Color.White.copy(alpha = 0.8f),
                softWrap = true,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = { (context as? android.app.Activity)?.finish() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCF6679), contentColor = Color.White),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(
                    text = "закрыть приложение".lowercase(),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun InfoScreen(title: String, message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(text = message, fontSize = 16.sp)
    }
}

@Composable
fun MainScreen() {
    // 🔥 Новые официальные API для разрешений
    val locationPermissionState = rememberPermissionState(Manifest.permission.ACCESS_FINE_LOCATION)
    val notificationPermissionState = rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)

    var resultText by remember { mutableStateOf("") }
    var isRestricted by remember { mutableStateOf<Boolean?>(null) }
    var serviceStatuses by remember { mutableStateOf<List<ServiceStatus>>(emptyList()) }
    var locationInfo by remember { mutableStateOf("") }
    var isChecking by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showSitesDialog by remember { mutableStateOf(false) }
    var historyList by remember { mutableStateOf<List<HistoryEntity>>(emptyList()) }
    var intervalMinutes by remember { mutableStateOf(15) }
    
    // Инициализация списка сайтов ДЛЯ КОМПОНУЕМЫХ функций
    LaunchedEffect(Unit) {
        val sites = NetworkChecker.getSites(LocalContext.current)
        showSitesDialog = false // Если был открыт диалог при старте
    }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val historyRepo = remember { HistoryRepository(context) }
    val prefs = context.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)

    LaunchedEffect(Unit) {
        intervalMinutes = prefs.getInt("interval_minutes", 15)
    }

    fun addLog(message: String) {
        logs = (logs + message).takeLast(15)
    }

    // Анимации
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "pulseScale"
    )

    // Состояние высот палочек
    val barHeights = remember { mutableStateListOf(0.3f, 0.5f, 0.7f, 0.9f) }

    // Анимация палочек
    LaunchedEffect(isChecking) {
        while (isChecking) {
            for ((index, _) in barHeights.withIndex()) {
                barHeights[index] = when (index) {
                    0 -> 0.3f + 0.7f * (1 + kotlin.math.sin(System.currentTimeMillis() / 300f)) / 2
                    1 -> 0.5f + 0.5f * (1 + kotlin.math.sin(System.currentTimeMillis() / 400f + 1f)) / 2
                    2 -> 0.7f + 0.3f * (1 + kotlin.math.sin(System.currentTimeMillis() / 500f + 2f)) / 2
                    else -> 0.9f + 0.1f * (1 + kotlin.math.sin(System.currentTimeMillis() / 600f + 3f)) / 2
                }
            }
            delay(50)
        }
    }

    // Цветовая схема
    val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val isNight = currentHour in 22..23 || currentHour in 0..6

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isRestricted == true && !isNight -> Color(0xFFFFFFFF)
            isRestricted == true && isNight -> Color(0xFF1A1A1A)
            isRestricted == false && !isNight -> Color(0xFF1A1A1A)
            isRestricted == false && isNight -> Color(0xFFEEEEEE)
            else -> Color(0xFFEEEEEE)
        }, animationSpec = tween(400)
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            isRestricted == true -> Color.Black
            isRestricted == false -> Color.White
            else -> Color.DarkGray
        }, animationSpec = tween(400)
    )
    val accentColor by animateColorAsState(
        targetValue = when {
            isRestricted == true -> Color(0xFFE53935)
            isRestricted == false -> Color(0xFF4CAF50)
            else -> Color.Gray
        }, animationSpec = tween(400)
    )

    MaterialTheme(
        colorScheme = lightColorScheme(
            background = backgroundColor,
            surface = backgroundColor,
            onSurface = contentColor,
            primary = accentColor,
            onPrimary = contentColor
        ),
        typography = Typography(
            bodyLarge = androidx.compose.ui.text.TextStyle(
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = contentColor
            )
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "whitelist checker".lowercase(),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = contentColor,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "проверка реальных ограничений интернета".lowercase(),
                        fontSize = 16.sp,
                        color = contentColor.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(32.dp))

                    // КНОПКА С ПАЛОЧКАМИ
                    Box(
                        modifier = Modifier
                            .size(160.dp)
                            .scale(if (isChecking) pulseScale else 1f)
                            .clip(CircleShape)
                            .background(
                                color = when {
                                    isChecking -> Color(0xFF666666)
                                    isRestricted == true -> Color(0xFF333333)
                                    else -> Color(0xFFE0E0E0)
                                }
                            )
                            .clickable(enabled = !isChecking) {
                                isChecking = true
                                scope.launch {
                                    try {
                                        resultText = "проверяю..."
                                        serviceStatuses = emptyList()
                                        isRestricted = null
                                        addLog("▶ начата проверка")

                                        // Проверка локации
                                        if (!locationPermissionState.hasPermission) {
                                            locationPermissionState.launchPermissionRequest()
                                            if (locationPermissionState.shouldShowRationale) {
                                                addLog("⏸ требуется пояснение для локации")
                                            }
                                            Toast.makeText(
                                                context,
                                                "Запрошено разрешение на геолокацию",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            isChecking = false
                                            return@launch
                                        }

                                        // Проверка уведомлений
                                        if (!notificationPermissionState.hasPermission) {
                                            notificationPermissionState.launchPermissionRequest()
                                            isChecking = false
                                            return@launch
                                        }

                                        var location = ""
                                        try {
                                            val loc = LocationServices.getFusedLocationProviderClient(
                                                context
                                            ).lastLocation.await()
                                            location = "координаты: ${"%.4f".format(loc.latitude)}, ${"%.4f".format(loc.longitude)}"
                                        } catch (e: Exception) {
                                            location = "геолокация недоступна"
                                        }

                                        val statuses = NetworkChecker.checkAll(context)
                                        serviceStatuses = statuses
                                        isRestricted = NetworkChecker.isRestricted(statuses)
                                        locationInfo = location

                                        resultText = if (isRestricted == true) {
                                            "обнаружены ограничения интернета.\nнекоторые зарубежные сайты недоступны."
                                        } else {
                                            "всё в порядке. все проверенные сервисы доступны."
                                        }

                                        val available = statuses.count { it.isAccessible }
                                        Toast.makeText(
                                            context,
                                            "Доступно: $available из ${statuses.size}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        addLog("✅ проверка завершена, доступно $available из ${statuses.size}")
                                        statuses.forEach { addLog("  ${it.name}: ${if (it.isAccessible) "OK" else "🚫"}") }

                                        historyRepo.saveCheck(isRestricted == true, statuses, locationInfo)
                                        historyList = historyRepo.getHistory()
                                        addLog("📋 история сохранена")

                                        WidgetProvider.updateWidget(context, isRestricted, statuses)

                                    } catch (e: Exception) {
                                        resultText = "ошибка: ${e.message}"
                                        addLog("⚠ ошибка: ${e.message}")
                                        Toast.makeText(
                                            context,
                                            "Ошибка: ${e.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        e.printStackTrace()
                                    } finally {
                                        isChecking = false
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.Bottom,
                            modifier = Modifier.height(48.dp)
                        ) {
                            repeat(4) { index ->
                                val heightFraction = barHeights.getOrElse(index) { 0.5f }
                                Box(
                                    modifier = Modifier
                                        .width(10.dp)
                                        .height((heightFraction * 48).dp)
                                        .background(
                                            color = if (isRestricted == true) Color.White else Color.Black,
                                            shape = RoundedCornerShape(2.dp)
                                        )
                                )
                            }
                            Text(
                                text = if (isChecking) "..." else "проверить",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isRestricted == true) Color.White else Color.Black,
                                modifier = Modifier.align(Alignment.BottomCenter) // Исправлено!
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    AnimatedVisibility(
                        visible = resultText.isNotEmpty(),
                        enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)),
                        exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(200))
                    ) {
                        Column {
                            Text(
                                text = resultText,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isRestricted == true) Color(0xFFE53935) else Color(0xFF4CAF50),
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isRestricted == true) Color(0x33E53935) else Color(0x334CAF50),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(16.dp)
                            )
                            if (locationInfo.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(locationInfo.lowercase(), fontSize = 12.sp, color = contentColor.copy(alpha = 0.5f))
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            if (serviceStatuses.isNotEmpty()) {
                                Text("статус сервисов:", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = contentColor)
                                Spacer(modifier = Modifier.height(8.dp))
                                serviceStatuses.forEach { service ->
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                        Icon(
                                            imageVector = if (service.isAccessible) Icons.Filled.Check else Icons.Filled.Close,
                                            contentDescription = null,
                                            tint = if (service.isAccessible) Color(0xFF4CAF50) else Color(0xFFE53935),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(service.name.lowercase(), color = contentColor, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (logs.isNotEmpty()) {
                        Text("логи:", lowercase = true, fontSize = 12.sp, color = contentColor.copy(alpha = 0.6f))
                        logs.forEach { log ->
                            Text(
                                log,
                                fontSize = 10.sp,
                                color = contentColor.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
                            )
                        }
                    }
                }

                // ИКОНКИ ПО ПЕРИМЕТРУ
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = "История",
                    tint = contentColor.copy(alpha = 0.7f),
                    modifier = Modifier
                        .padding(16.dp)
                        .size(28.dp)
                        .clickable {
                            scope.launch {
                                historyList = historyRepo.getHistory()
                                showHistoryDialog = true
                            }
                        }
                        .align(Alignment.TopStart)
                )

                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Настройки",
                    tint = contentColor.copy(alpha = 0.7f),
                    modifier = Modifier
                        .padding(16.dp)
                        .size(28.dp)
                        .clickable { showSettingsDialog = true }
                        .align(Alignment.TopEnd)
                )

                Icon(
                    imageVector = Icons.Default.Web,
                    contentDescription = "Сайты",
                    tint = contentColor.copy(alpha = 0.7f),
                    modifier = Modifier
                        .padding(16.dp)
                        .size(28.dp)
                        .clickable { showSitesDialog = true }
                        .align(Alignment.BottomStart)
                )

                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Экспорт",
                    tint = contentColor.copy(alpha = 0.7f),
                    modifier = Modifier
                        .padding(16.dp)
                        .size(28.dp)
                        .clickable {
                            val activity = context as? MainActivity
                            activity?.exportHistory(context, historyRepo)
                        }
                        .align(Alignment.BottomEnd)
                )
            }
        }
    }

    // -------- ДИАЛОГИ --------
    if (showHistoryDialog) {
        HistoryDialog(
            historyList = historyList,
            onDismiss = { showHistoryDialog = false }
        )
    }
    if (showSettingsDialog) {
        SettingsDialog(
            notificationEnabled = notificationEnabled,
            onNotificationToggle = { enabled ->
                NotificationWorker.scheduleOrCancel(context, enabled)
                addLog("🔔 уведомления: $enabled")
            },
            intervalMinutes = intervalMinutes,
            onIntervalChange = { newInterval ->
                intervalMinutes = newInterval
                prefs.edit().putInt("interval_minutes", newInterval).apply()
                NotificationWorker.reschedule(context, newInterval)
                Toast.makeText(context, "интервал: $newInterval мин", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showSettingsDialog = false }
        )
    }
    if (showSitesDialog) {
        SitesDialog(
            sites = customSites,
            onSitesChange = { newSites ->
                customSites = newSites
                NetworkChecker.saveSites(context, customSites)
            },
            onDismiss = { showSitesDialog = false }
        )
    }
}

// Диалоги ниже — это просто заглушки. Ты можешь оставить свои оригинальные реализации.
@Composable
fun HistoryDialog(historyList: List<Any>, onDismiss: () -> Unit) {}
@Composable
fun SettingsDialog(notificationEnabled: Boolean, onNotificationToggle: (Boolean) -> Unit, intervalMinutes: Int, onIntervalChange: (Int) -> Unit, onDismiss: () -> Unit) {}
@Composable
fun SitesDialog(sites: List<Pair<String, String>>, onSitesChange: (List<Pair<String, String>>) -> Unit, onDismiss: () -> Unit) {}
