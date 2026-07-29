package com.example.whitelistcheck4

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
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
import androidx.core.content.FileProvider
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// =============================================
// ENUMS И DATA CLASSES
// =============================================
enum class Screen { MAIN, HISTORY, SETTINGS }
enum class ConnectionStatus { NO_SIM, NO_INTERNET, WIFI_AND_MOBILE, MOBILE_ONLY }

// =============================================
// MAIN ACTIVITY
// =============================================
class MainActivity : ComponentActivity() {
    
    private fun checkConnectionStatus(context: Context): ConnectionStatus {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        
        val hasSim = tm.simState != TelephonyManager.SIM_STATE_ABSENT && 
                     tm.simState != TelephonyManager.SIM_STATE_UNKNOWN
        if (!hasSim) return ConnectionStatus.NO_SIM
        
        val activeNetwork = cm.activeNetwork ?: return ConnectionStatus.NO_INTERNET
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return ConnectionStatus.NO_INTERNET
        
        // Убрали VALIDATED, чтобы работало при блокировках провайдера
        val isInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (!isInternet) return ConnectionStatus.NO_INTERNET
        
        val hasCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        
        return when {
            hasWifi && hasCellular -> ConnectionStatus.WIFI_AND_MOBILE
            else -> ConnectionStatus.MOBILE_ONLY
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = android.graphics.Color.BLACK
        }
        setContent {
            App(this)
        }
    }

    fun exportHistory(context: Context, repo: HistoryRepository) {
        kotlinx.coroutines.GlobalScope.launch {
            val list = repo.getHistory()
            if (list.isEmpty()) {
                Toast.makeText(context, "История пуста", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val sb = StringBuilder()
            sb.append("Whitelist Checker - история проверок\n")
            sb.append("=====================================\n\n")
            list.forEach { entry ->
                val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(entry.timestamp))
                sb.append("$date | ${if (entry.isRestricted) "ОГРАНИЧЕНИЯ" else "СВОБОДА"}\n")
                sb.append("   Статусы: ${entry.statusesJson}\n")
                if (entry.location != null) sb.append("   Локация: ${entry.location}\n\n")
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
    }
}

// =============================================
// APP NAVIGATION
// =============================================
@Composable
fun App(activity: MainActivity) {
    var currentScreen by remember { mutableStateOf(Screen.MAIN) }
    val context = LocalContext.current
    val historyRepo = remember { HistoryRepository(context) }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(bottom = 80.dp)) {
                when (currentScreen) {
                    Screen.MAIN -> MainScreen(activity, historyRepo)
                    Screen.HISTORY -> HistoryScreen(activity, historyRepo)
                    Screen.SETTINGS -> SettingsScreen(historyRepo)
                }
            }

            NavigationBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                containerColor = Color(0xFF1A1A1A),
                tonalElevation = 0.dp
            ) {
                listOf(
                    Triple(Icons.Default.Home, "главный", Screen.MAIN),
                    Triple(Icons.Default.History, "история", Screen.HISTORY),
                    Triple(Icons.Default.Settings, "настройки", Screen.SETTINGS)
                ).forEach { (icon, label, screen) ->
                    NavigationBarItem(
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(label, fontSize = 10.sp) },
                        selected = currentScreen == screen,
                        onClick = { currentScreen = screen },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = Color.White,
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray,
                            indicatorColor = Color(0xFF333333)
                        )
                    )
                }
            }
        }
    }
}

// =============================================
// MAIN SCREEN
// =============================================
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen(activity: MainActivity, historyRepo: HistoryRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)

    val permissions = rememberMultiplePermissionsState(
        permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
    )

    var isChecking by remember { mutableStateOf(false) }
    var isRestricted by remember { mutableStateOf<Boolean?>(null) }
    var resultText by remember { mutableStateOf("") }
    var serviceStatuses by remember { mutableStateOf<List<ServiceStatus>>(emptyList()) }
    var locationInfo by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var connectionError by remember { mutableStateOf<String?>(null) }

    fun addLog(message: String) { logs = (logs + message).takeLast(15) }

    // Пульсация кругов
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulse1 by infiniteTransition.animateFloat(1f, 1.15f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p1")
    val pulse2 by infiniteTransition.animateFloat(1f, 1.25f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing, delayMillis = 200), RepeatMode.Reverse), label = "p2")
    val pulse3 by infiniteTransition.animateFloat(1f, 1.35f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing, delayMillis = 400), RepeatMode.Reverse), label = "p3")

    val accentColor = when (isRestricted) { true -> Color(0xFFE53935); false -> Color(0xFF4CAF50); else -> Color.White }

    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text("whitelist checker", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("проверка реальных ограничений интернета", fontSize = 14.sp, color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(48.dp))

            Box(modifier = Modifier.size(280.dp), contentAlignment = Alignment.Center) {
                if (isChecking) {
                    Box(Modifier.size(260.dp).scale(pulse3).clip(CircleShape).background(Color.White.copy(alpha = 0.05f)))
                    Box(Modifier.size(220.dp).scale(pulse2).clip(CircleShape).background(Color.White.copy(alpha = 0.1f)))
                    Box(Modifier.size(180.dp).scale(pulse1).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)))
                } else {
                    Box(Modifier.size(260.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.05f)))
                    Box(Modifier.size(220.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f)))
                    Box(Modifier.size(180.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)))
                }

                Box(
                    modifier = Modifier.size(140.dp).clip(CircleShape).background(Color.White)
                        .clickable(enabled = !isChecking && connectionError == null) {
                            val connStatus = activity.checkConnectionStatusPrivate(context)
                            when (connStatus) {
                                ConnectionStatus.NO_SIM -> connectionError = "нет sim-карты"
                                ConnectionStatus.NO_INTERNET -> connectionError = "нет интернет-соединения"
                                ConnectionStatus.WIFI_AND_MOBILE -> connectionError = "отключите Wi-Fi"
                                ConnectionStatus.MOBILE_ONLY -> {
                                    connectionError = null
                                    isChecking = true; resultText = ""; serviceStatuses = emptyList()
                                    isRestricted = null; locationInfo = ""
                                    addLog("▶ начата проверка")
                                    scope.launch {
                                        try {
                                            if (!permissions.allPermissionsGranted) {
                                                permissions.launchMultiplePermissionRequest()
                                                addLog("⏸ запрошены разрешения"); isChecking = false; return@launch
                                            }
                                            var location = ""
                                            try {
                                                val loc = LocationServices.getFusedLocationProviderClient(context).lastLocation.await()
                                                location = "координаты: %.4f, %.4f".format(loc.latitude, loc.longitude)
                                            } catch (e: Exception) { location = "геолокация недоступна" }
                                            
                                            val statuses = NetworkChecker.checkAll(context)
                                            serviceStatuses = statuses
                                            isRestricted = NetworkChecker.isRestricted(statuses)
                                            locationInfo = location
                                            resultText = if (isRestricted == true) 
                                                "обнаружены ограничения интернета.\nнекоторые зарубежные сайты недоступны." 
                                            else 
                                                "всё в порядке. все проверенные сервисы доступны."
                                            
                                            val available = statuses.count { it.isAccessible }
                                            addLog("✅ проверка завершена, доступно $available из ${statuses.size}")
                                            statuses.forEach { addLog("  ${it.name}: ${if (it.isAccessible) "OK" else "❌"}") }
                                            historyRepo.saveCheck(isRestricted == true, statuses, locationInfo)
                                            addLog("📋 история сохранена")
                                            WidgetProvider.updateWidget(context, isRestricted, statuses)
                                            
                                            val lastRestricted = prefs.getBoolean("last_restricted", true)
                                            if (isRestricted == true && !lastRestricted) sendNotificationManual(context, " ограничения включены", "некоторые зарубежные сайты могут быть недоступны.")
                                            else if (isRestricted == false && lastRestricted) sendNotificationManual(context, "✅ ограничения сняты", "все сервисы снова доступны.")
                                            else if (isRestricted == false) sendNotificationManual(context, "💡 напоминание", "ограничения могут вернуться в любой момент.")
                                            prefs.edit().putBoolean("last_restricted", isRestricted == true).apply()
                                        } catch (e: Exception) {
                                            resultText = "ошибка: ${e.message}"; addLog("⚠ ошибка: ${e.message}"); e.printStackTrace()
                                        } finally { isChecking = false }
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.CellTower, null, tint = Color.Black, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(if (isChecking) "..." else "проверить", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            if (connectionError != null) {
                Text(connectionError!!, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFA726), textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFFFA726).copy(alpha = 0.15f), RoundedCornerShape(8.dp)).padding(16.dp))
                Spacer(Modifier.height(16.dp))
            }

            if (resultText.isNotEmpty()) {
                Text(resultText, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = accentColor, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().background(accentColor.copy(alpha = 0.15f), RoundedCornerShape(8.dp)).padding(16.dp))
                Spacer(Modifier.height(12.dp))
            }

            if (locationInfo.isNotEmpty()) {
                Text(locationInfo, fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f)); Spacer(Modifier.height(12.dp))
            }

            if (serviceStatuses.isNotEmpty()) {
                Text("статус сервисов:", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                serviceStatuses.forEach { service ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Icon(if (service.isAccessible) Icons.Filled.Check else Icons.Filled.Close, null,
                            tint = if (service.isAccessible) Color(0xFF4CAF50) else Color(0xFFE53935), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(service.name.lowercase(), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (logs.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("логи:", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                logs.forEach { log -> Text(log, fontSize = 11.sp, color = Color.White.copy(alpha = 0.4f), modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) }
            }
        }
    }
}

// Вспомогательная функция проверки соединения (вне Composable)
private fun MainActivity.checkConnectionStatusPrivate(context: Context): ConnectionStatus {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    val hasSim = tm.simState != TelephonyManager.SIM_STATE_ABSENT && tm.simState != TelephonyManager.SIM_STATE_UNKNOWN
    if (!hasSim) return ConnectionStatus.NO_SIM
    val activeNetwork = cm.activeNetwork ?: return ConnectionStatus.NO_INTERNET
    val caps = cm.getNetworkCapabilities(activeNetwork) ?: return ConnectionStatus.NO_INTERNET
    val hasCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    val hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    val isInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    if (!isInternet) return ConnectionStatus.NO_INTERNET
    return when { hasWifi && hasCellular -> ConnectionStatus.WIFI_AND_MOBILE; else -> ConnectionStatus.MOBILE_ONLY }
}

private fun sendNotificationManual(context: Context, title: String, message: String) {
    val prefs = context.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)
    if (!prefs.getBoolean("notifications_enabled", false)) return
    val channelId = NotificationWorker.CHANNEL_ID
    val intent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }
    val pendingIntent = android.app.PendingIntent.getActivity(context, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE)
    val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(message)
        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH).setContentIntent(pendingIntent).setAutoCancel(true).build()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            androidx.core.app.NotificationManagerCompat.from(context).notify(1, notification)
    } else { androidx.core.app.NotificationManagerCompat.from(context).notify(1, notification) }
}

// =============================================
// HISTORY SCREEN
// =============================================
@Composable
fun HistoryScreen(activity: MainActivity, historyRepo: HistoryRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var historyList by remember { mutableStateOf<List<HistoryEntity>>(emptyList()) }
    var showClearConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { historyList = historyRepo.getHistory() }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("история проверок", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text("${historyList.size} записей", fontSize = 14.sp, color = Color.White.copy(alpha = 0.5f))
        Spacer(Modifier.height(16.dp))

        if (historyList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("пока нет записей", fontSize = 16.sp, color = Color.White.copy(alpha = 0.4f))
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(historyList) { entry ->
                    val date = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(entry.timestamp))
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)), shape = RoundedCornerShape(12.dp)) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (entry.isRestricted) Icons.Filled.Warning else Icons.Filled.CheckCircle, null,
                                tint = if (entry.isRestricted) Color(0xFFE53935) else Color(0xFF4CAF50), modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(date, fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
                                Spacer(Modifier.height(2.dp))
                                Text(entry.statusesJson, fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f), maxLines = 2)
                            }
                            if (entry.location != null) Icon(Icons.Default.LocationOn, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { activity.exportHistory(context, historyRepo) }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(Color.White.copy(alpha = 0.3f)))) {
                Icon(Icons.Default.FileDownload, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("экспорт")
            }
            OutlinedButton(onClick = { showClearConfirm = true }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE53935)),
                border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFFE53935)))) {
                Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("очистить")
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(onDismissRequest = { showClearConfirm = false },
            title = { Text("очистить историю?", color = Color.White) },
            text = { Text("это действие нельзя отменить.", color = Color.White.copy(alpha = 0.7f)) },
            confirmButton = { TextButton(onClick = { scope.launch { historyRepo.clearHistory(); historyList = emptyList(); Toast.makeText(context, "история очищена", Toast.LENGTH_SHORT).show() }; showClearConfirm = false }) { Text("очистить", color = Color(0xFFE53935)) } },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("отмена", color = Color.White) } },
            containerColor = Color(0xFF1A1A1A)
        )
    }
}

// =============================================
// SETTINGS SCREEN
// =============================================
@Composable
fun SettingsScreen(historyRepo: HistoryRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)

    var notificationEnabled by remember { mutableStateOf(prefs.getBoolean("notifications_enabled", false)) }
    var intervalMinutes by remember { mutableStateOf(prefs.getInt("interval_minutes", 15)) }
    var customSites by remember { mutableStateOf(NetworkChecker.getSites(context)) }
    var showAddSiteDialog by remember { mutableStateOf(false) }
    var newSiteName by remember { mutableStateOf("") }
    var newSiteUrl by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text("настройки", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Spacer(Modifier.height(24.dp))

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)), shape = RoundedCornerShape(12.dp)) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Notifications, null, tint = Color.White, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("push-уведомления", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("оповещения об изменениях", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
                    }
                    Switch(checked = notificationEnabled, onCheckedChange = { enabled ->
                        notificationEnabled = enabled; prefs.edit().putBoolean("notifications_enabled", enabled).apply()
                        if (enabled) { NotificationWorker.schedule(context); Toast.makeText(context, "уведомления включены", Toast.LENGTH_SHORT).show() }
                        else { NotificationWorker.cancel(context); Toast.makeText(context, "уведомления отключены", Toast.LENGTH_SHORT).show() }
                    }, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF4CAF50), checkedTrackColor = Color(0xFF4CAF50).copy(alpha = 0.3f), uncheckedThumbColor = Color.Gray, uncheckedTrackColor = Color.Gray.copy(alpha = 0.3f)))
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, null, tint = Color.White, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("интервал проверки", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("каждые $intervalMinutes минут", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Slider(value = intervalMinutes.toFloat(), onValueChange = { newValue ->
                        intervalMinutes = newValue.toInt(); prefs.edit().putInt("interval_minutes", intervalMinutes).apply(); NotificationWorker.reschedule(context)
                    }, valueRange = 5f..60f, steps = 10, colors = SliderDefaults.colors(thumbColor = Color(0xFF4CAF50), activeTrackColor = Color(0xFF4CAF50), inactiveTrackColor = Color(0xFF333333)))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("5 мин", fontSize = 10.sp, color = Color.White.copy(alpha = 0.4f)); Text("60 мин", fontSize = 10.sp, color = Color.White.copy(alpha = 0.4f))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth().weight(1f, fill = false), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Web, null, tint = Color.White, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Text("проверяемые сайты", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 300.dp)) {
                        items(customSites) { site ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(site.first, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text(site.second, fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f), maxLines = 1)
                                }
                                IconButton(onClick = { customSites = customSites.filter { it != site }; NetworkChecker.saveSites(context, customSites); Toast.makeText(context, "сайт удалён", Toast.LENGTH_SHORT).show() }) {
                                    Icon(Icons.Default.Delete, "Удалить", tint = Color(0xFFE53935), modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(onClick = { showAddSiteDialog = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
            containerColor = Color(0xFF4CAF50), contentColor = Color.White, shape = CircleShape) {
            Icon(Icons.Default.Add, "Добавить сайт")
        }
    }

    if (showAddSiteDialog) {
        AlertDialog(onDismissRequest = { showAddSiteDialog = false },
            title = { Text("добавить сайт", color = Color.White) },
            text = { Column {
                OutlinedTextField(value = newSiteName, onValueChange = { newSiteName = it }, label = { Text("название", color = Color.White.copy(alpha = 0.5f)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF4CAF50), unfocusedBorderColor = Color.White.copy(alpha = 0.3f), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = newSiteUrl, onValueChange = { newSiteUrl = it }, label = { Text("URL", color = Color.White.copy(alpha = 0.5f)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF4CAF50), unfocusedBorderColor = Color.White.copy(alpha = 0.3f), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
            }},
            confirmButton = { TextButton(onClick = {
                if (newSiteName.isNotBlank() && newSiteUrl.isNotBlank()) {
                    val newSite = newSiteName.trim() to newSiteUrl.trim()
                    if (customSites.none { it.first == newSite.first }) { customSites = customSites + newSite; NetworkChecker.saveSites(context, customSites); Toast.makeText(context, "сайт добавлен", Toast.LENGTH_SHORT).show(); newSiteName = ""; newSiteUrl = ""; showAddSiteDialog = false }
                    else Toast.makeText(context, "такой сайт уже есть", Toast.LENGTH_SHORT).show()
                }
            }) { Text("добавить", color = Color(0xFF4CAF50)) } },
            dismissButton = { TextButton(onClick = { showAddSiteDialog = false; newSiteName = ""; newSiteUrl = "" }) { Text("отмена", color = Color.White) } },
            containerColor = Color(0xFF1A1A1A)
        )
    }
}
