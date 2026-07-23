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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
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

    private fun checkNetworkStatus(context: Context): NetworkStatus {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val hasSim = tm.simState != TelephonyManager.SIM_STATE_ABSENT && tm.simState != TelephonyManager.SIM_STATE_UNKNOWN
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val hasCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val hasWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        return when {
            !hasSim || !hasCellular -> NetworkStatus.NO_MOBILE
            hasWifi -> NetworkStatus.WIFI_ONLY
            else -> NetworkStatus.OK
        }
    }

    private fun checkConnectionStatus(context: Context): ConnectionStatus {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        val hasSim = tm.simState != TelephonyManager.SIM_STATE_ABSENT && tm.simState != TelephonyManager.SIM_STATE_UNKNOWN
        if (!hasSim) return ConnectionStatus.NO_SIM

        val activeNetwork = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(activeNetwork)
        if (caps == null) return ConnectionStatus.NO_INTERNET

        val hasCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        if (!isInternet) return ConnectionStatus.NO_INTERNET

        return when {
            hasWifi && hasCellular -> ConnectionStatus.WIFI_AND_MOBILE
            hasCellular && !hasWifi -> ConnectionStatus.MOBILE_ONLY
            else -> ConnectionStatus.MOBILE_ONLY
        }
    }

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = android.graphics.Color.BLACK
        }

        val connectionStatus = checkConnectionStatus(this)

        setContent {
            // Проверяем статус соединения и показываем соответствующий экран
            when (connectionStatus) {
                ConnectionStatus.NO_SIM -> {
                    NoSimScreen()
                    return@setContent
                }
                ConnectionStatus.NO_INTERNET -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("проверка недоступна", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("нет интернет-соединения", fontSize = 16.sp)
                    }
                    return@setContent
                }
                ConnectionStatus.WIFI_AND_MOBILE -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("проверка недоступна", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("отключите Wi-Fi", fontSize = 16.sp)
                    }
                    return@setContent
                }
                else -> {
                    // MOBILE_ONLY — продолжаем с основным UI
                }
            }

            // --- ОСНОВНОЙ UI (только при MOBILE_ONLY) ---
            val permissions = rememberMultiplePermissionsState(
                permissions = listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS
                )
            )

            var resultText by remember { mutableStateOf("") }
            var isRestricted by remember { mutableStateOf<Boolean?>(null) }
            var notificationEnabled by remember { mutableStateOf(false) }
            var serviceStatuses by remember { mutableStateOf<List<ServiceStatus>>(emptyList()) }
            var locationInfo by remember { mutableStateOf("") }
            var isChecking by remember { mutableStateOf(false) }
            var logs by remember { mutableStateOf<List<String>>(emptyList()) }
            var showHistoryDialog by remember { mutableStateOf(false) }
            var showSettingsDialog by remember { mutableStateOf(false) }
            var showSitesDialog by remember { mutableStateOf(false) }
            var historyList by remember { mutableStateOf<List<HistoryEntity>>(emptyList()) }
            var intervalMinutes by remember { mutableStateOf(15) }
            var customSites by remember { mutableStateOf(NetworkChecker.getSites(LocalContext.current)) }
            var newSiteName by remember { mutableStateOf("") }
            var newSiteUrl by remember { mutableStateOf("") }

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

            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.08f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ), label = "pulseScale"
            )

            val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val isNight = currentHour in 22..23 || currentHour in 0..6

            val backgroundColor by animateColorAsState(
                targetValue = when {
                    isRestricted == true && !isNight -> Color(0xFFFFFFFF)
                    isRestricted == true && isNight -> Color(0xFF1A1A1A)
                    isRestricted == false && !isNight -> Color(0xFF1A1A1A)
                    isRestricted == false && isNight -> Color(0xFFEEEEEE)
                    else -> Color(0xFFEEEEEE)
                }, animationSpec = tween(400), label = "bg"
            )
            val contentColor by animateColorAsState(
                targetValue = when {
                    isRestricted == true -> Color.Black
                    isRestricted == false -> Color.White
                    else -> Color.DarkGray
                }, animationSpec = tween(400), label = "text"
            )
            val accentColor by animateColorAsState(
                targetValue = when {
                    isRestricted == true -> Color(0xFFE53935)
                    isRestricted == false -> Color(0xFF4CAF50)
                    else -> Color.Gray
                }, animationSpec = tween(400), label = "accent"
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
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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

                            // КРУГЛАЯ КНОПКА
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

                                                if (ContextCompat.checkSelfPermission(
                                                        this@MainActivity,
                                                        Manifest.permission.ACCESS_FINE_LOCATION
                                                    ) != PackageManager.PERMISSION_GRANTED
                                                ) {
                                                    permissions.launchMultiplePermissionRequest()
                                                    Toast.makeText(
                                                        this@MainActivity,
                                                        "Запросили разрешение на геолокацию",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                    addLog("⏸ запрошено разрешение")
                                                    isChecking = false
                                                    return@launch
                                                }

                                                var location = ""
                                                try {
                                                    val loc = LocationServices.getFusedLocationProviderClient(
                                                        this@MainActivity
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
                                                    this@MainActivity,
                                                    "Доступно: $available из ${statuses.size}",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                addLog("✅ проверка завершена, доступно $available из ${statuses.size}")
                                                statuses.forEach { addLog("  ${it.name}: ${if (it.isAccessible) "OK" else "❌"}") }

                                                historyRepo.saveCheck(isRestricted == true, statuses, locationInfo)
                                                historyList = historyRepo.getHistory()
                                                addLog("📋 история сохранена")

                                                // Обновляем виджет
                                                WidgetProvider.updateWidget(context, isRestricted, statuses)

                                            } catch (e: Exception) {
                                                resultText = "ошибка: ${e.message}"
                                                addLog("⚠ ошибка: ${e.message}")
                                                Toast.makeText(
                                                    this@MainActivity,
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
                                // Рисуем 4 палочки с помощью Canvas
                                androidx.compose.foundation.Canvas(modifier = Modifier.size(80.dp)) {
                                    val barWidth = size.width / 10f
                                    val barGap = size.width / 20f
                                    val maxHeight = size.height * 0.8f
                                    val heights = if (isChecking) {
                                        listOf(
                                            maxHeight * 0.3f + (maxHeight * 0.7f * (1 + kotlin.math.sin(System.currentTimeMillis() / 300f)) / 2),
                                            maxHeight * 0.5f + (maxHeight * 0.5f * (1 + kotlin.math.sin(System.currentTimeMillis() / 400f + 1f)) / 2),
                                            maxHeight * 0.7f + (maxHeight * 0.3f * (1 + kotlin.math.sin(System.currentTimeMillis() / 500f + 2f)) / 2),
                                            maxHeight * 0.9f + (maxHeight * 0.1f * (1 + kotlin.math.sin(System.currentTimeMillis() / 600f + 3f)) / 2)
                                        )
                                    } else {
                                        listOf(maxHeight * 0.3f, maxHeight * 0.5f, maxHeight * 0.7f, maxHeight * 0.9f)
                                    }

                                    val barColor = if (isRestricted == true) Color.White else Color.Black

                                    heights.forEachIndexed { index, height ->
                                        val left = index * (barWidth + barGap) + barGap
                                        val bottom = size.height * 0.9f
                                        val top = bottom - height
                                        drawRect(
                                            color = barColor,
                                            topLeft = Offset(left, top),
                                            size = Size(barWidth, height),
                                            cornerRadius = CornerRadius(4f, 4f)
                                        )
                                    }
                                }

                                Text(
                                    text = if (isChecking) "..." else "проверить",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isRestricted == true) Color.White else Color.Black,
                                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            // РЕЗУЛЬТАТЫ
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
                                        Text("статус сервисов:".lowercase(), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = contentColor)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        serviceStatuses.forEach { service ->
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                                Icon(
                                                    if (service.isAccessible) Icons.Filled.Check else Icons.Filled.Close,
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

                            // ЛОГИ
                            if (logs.isNotEmpty()) {
                                Text("логи:".lowercase(), fontSize = 12.sp, color = contentColor.copy(alpha = 0.6f))
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
                                .clickable {
                                    showSettingsDialog = true
                                }
                                .align(Alignment.TopEnd)
                        )

                        Icon(
                            imageVector = Icons.Default.Web,
                            contentDescription = "Сайты",
                            tint = contentColor.copy(alpha = 0.7f),
                            modifier = Modifier
                                .padding(16.dp)
                                .size(28.dp)
                                .clickable {
                                    customSites = NetworkChecker.getSites(context)
                                    showSitesDialog = true
                                }
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
                                    scope.launch {
                                        exportHistory(context, historyRepo)
                                    }
                                }
                                .align(Alignment.BottomEnd)
                        )
                    }
                }
            }

            // --- Диалог истории ---
            if (showHistoryDialog) {
                Dialog(onDismissRequest = { showHistoryDialog = false }) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .heightIn(max = 400.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("история проверок", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            if (historyList.isEmpty()) {
                                Text("пока нет записей", color = Color.Gray)
                            } else {
                                LazyColumn {
                                    items(historyList) { entry ->
                                        val date = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(entry.timestamp))
                                        Text(
                                            text = "$date | ${if (entry.isRestricted) "🚫" else "✅"} | ${entry.statusesJson}",
                                            fontSize = 13.sp,
                                            modifier = Modifier.padding(vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { showHistoryDialog = false },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("закрыть")
                            }
                        }
                    }
                }
            }

            // --- Диалог настроек ---
            if (showSettingsDialog) {
                Dialog(onDismissRequest = { showSettingsDialog = false }) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("настройки", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text("push-уведомления", fontSize = 16.sp, modifier = Modifier.weight(1f))
                                Switch(
                                    checked = notificationEnabled,
                                    onCheckedChange = { enabled ->
                                        notificationEnabled = enabled
                                        if (enabled) {
                                            NotificationWorker.schedule(context)
                                            Toast.makeText(context, "оповещения включены", Toast.LENGTH_SHORT).show()
                                            addLog("🔔 уведомления включены")
                                        } else {
                                            NotificationWorker.cancel(context)
                                            Toast.makeText(context, "оповещения отключены", Toast.LENGTH_SHORT).show()
                                            addLog("🔕 уведомления отключены")
                                        }
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text("интервал проверки", fontSize = 16.sp, modifier = Modifier.weight(1f))
                                Button(
                                    onClick = {
                                        val intervals = listOf(5, 15, 30, 60)
                                        val index = intervals.indexOf(intervalMinutes)
                                        val nextIndex = (index + 1) % intervals.size
                                        intervalMinutes = intervals[nextIndex]
                                        prefs.edit().putInt("interval_minutes", intervalMinutes).apply()
                                        NotificationWorker.reschedule(context)
                                        Toast.makeText(context, "интервал: $intervalMinutes мин", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
                                ) {
                                    Text("$intervalMinutes мин", fontWeight = FontWeight.Bold)
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { showSettingsDialog = false },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("закрыть")
                            }
                        }
                    }
                }
            }

            // --- Диалог управления сайтами ---
            if (showSitesDialog) {
                Dialog(onDismissRequest = { showSitesDialog = false }) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .heightIn(max = 400.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("управление сайтами", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))

                            LazyColumn(modifier = Modifier.weight(1f)) {
                                items(customSites) { site ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("${site.first} (${site.second})", fontSize = 14.sp)
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Удалить",
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clickable {
                                                    customSites = customSites.filter { it != site }
                                                    NetworkChecker.saveSites(context, customSites)
                                                    Toast.makeText(context, "Сайт удалён", Toast.LENGTH_SHORT).show()
                                                }
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Row {
                                OutlinedTextField(
                                    value = newSiteName,
                                    onValueChange = { newSiteName = it },
                                    label = { Text("Название") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                OutlinedTextField(
                                    value = newSiteUrl,
                                    onValueChange = { newSiteUrl = it },
                                    label = { Text("URL") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                            }
                            Button(
                                onClick = {
                                    if (newSiteName.isNotBlank() && newSiteUrl.isNotBlank()) {
                                        val newSite = newSiteName.trim() to newSiteUrl.trim()
                                        if (customSites.none { it.first == newSite.first || it.second == newSite.second }) {
                                            customSites = customSites + newSite
                                            NetworkChecker.saveSites(context, customSites)
                                            Toast.makeText(context, "Сайт добавлен", Toast.LENGTH_SHORT).show()
                                            newSiteName = ""
                                            newSiteUrl = ""
                                        } else {
                                            Toast.makeText(context, "Такой сайт уже есть", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        Toast.makeText(context, "Заполните оба поля", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Добавить")
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { showSitesDialog = false },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("закрыть")
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun exportHistory(context: Context, repo: HistoryRepository) {
        val list = repo.getHistory()
        if (list.isEmpty()) {
            Toast.makeText(context, "История пуста", Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder()
        sb.append("Whitelist Checker - история проверок\n")
        sb.append("=====================================\n\n")
        list.forEach { entry ->
            val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(entry.timestamp))
            sb.append("$date | ${if (entry.isRestricted) "ОГРАНИЧЕНИЯ" else "СВОБОДА"}\n")
            sb.append("   Статусы: ${entry.statusesJson}\n")
            if (entry.location != null) sb.append("   Локация: ${entry.location}\n")
            sb.append("\n")
        }
        val file = File(context.cacheDir, "history_${System.currentTimeMillis()}.txt")
        file.writeText(sb.toString())
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Экспорт истории"))
    }

    @Composable
    fun NoSimScreen() {
        val context = LocalContext.current
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A1A)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Text("нет sim-карты".lowercase(), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "для работы приложения необходима мобильная сеть.\nвы можете приобрести sim-карту в любом салоне связи:\n• мтс\n• мегафон\n• теле2 и других.".lowercase(),
                    fontSize = 16.sp,
                    color = Color.White.copy(alpha = 0.8f)
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = { (context as? android.app.Activity)?.finish() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCF6679), contentColor = Color.White),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("закрыть приложение".lowercase(), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
