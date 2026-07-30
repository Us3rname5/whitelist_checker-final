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
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
// ENUMS
// =============================================
enum class Screen { MAIN, HISTORY, SETTINGS, INFO }
enum class ConnectionStatus {
    NO_SIM, NO_INTERNET, WIFI_AND_MOBILE, MOBILE_ONLY, VPN_ACTIVE
}

// =============================================
// MAIN ACTIVITY
// =============================================
class MainActivity : ComponentActivity() {
    
    fun checkConnectionStatus(context: Context): ConnectionStatus {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        
        val hasSim = tm.simState != TelephonyManager.SIM_STATE_ABSENT && 
                     tm.simState != TelephonyManager.SIM_STATE_UNKNOWN
        if (!hasSim) return ConnectionStatus.NO_SIM
        
        val activeNetwork = cm.activeNetwork ?: return ConnectionStatus.NO_INTERNET
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return ConnectionStatus.NO_INTERNET
        
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return ConnectionStatus.VPN_ACTIVE
        }
        
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
                Toast.makeText(context, "история пуста", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val sb = StringBuilder()
            sb.append("whitelist checker - история проверок\n")
            sb.append("=====================================\n\n")
            list.forEach { entry ->
                val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(entry.timestamp))
                sb.append("$date | ${if (entry.isRestricted) "ОГРАНИЧЕНИЯ" else "СВОБОДА"}\n")
                sb.append("   статусы: ${entry.statusesJson}\n")
                if (entry.location != null) sb.append("   локация: ${entry.location}\n\n")
            }
            val file = File(context.cacheDir, "history_${System.currentTimeMillis()}.txt")
            file.writeText(sb.toString())
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "экспорт истории"))
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

    // Общие логи для всего приложения
    var appLogs by remember { mutableStateOf<List<String>>(emptyList()) }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = {
                    if (targetState.ordinal > initialState.ordinal) {
                        slideInHorizontally(initialOffsetX = { it }) + fadeIn() togetherWith
                        slideOutHorizontally(targetOffsetX = { -it }) + fadeOut()
                    } else {
                        slideInHorizontally(initialOffsetX = { -it }) + fadeIn() togetherWith
                        slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
                    }
                },
                label = "screen_transition"
            ) { screen ->
                when (screen) {
                    Screen.MAIN -> MainScreen(activity, historyRepo, appLogs) { newLogs ->
                        appLogs = newLogs
                    }
                    Screen.HISTORY -> HistoryScreen(activity, historyRepo, appLogs)
                    Screen.SETTINGS -> SettingsScreen(historyRepo)
                    Screen.INFO -> InfoScreen()
                }
            }

            FloatingNavigationBar(
                currentScreen = currentScreen,
                onScreenSelected = { currentScreen = it },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
            )
        }
    }
}

// =============================================
// FLOATING NAVIGATION BAR (ИСПРАВЛЕННЫЙ)
// =============================================
@Composable
fun FloatingNavigationBar(
    currentScreen: Screen,
    onScreenSelected: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = listOf(
        Triple(Icons.Default.Home, "главный", Screen.MAIN),
        Triple(Icons.Default.History, "история", Screen.HISTORY),
        Triple(Icons.Default.Settings, "настройки", Screen.SETTINGS),
        Triple(Icons.Default.Info, "информация", Screen.INFO)
    )

    Box(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF1A1A1A).copy(alpha = 0.95f))
            .padding(8.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items.forEach { (icon, label, screen) ->
                val isSelected = currentScreen == screen
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (isSelected) Color(0xFF3B82F6) else Color.Transparent
                        )
                        .clickable { onScreenSelected(screen) }
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            icon,
                            contentDescription = label,
                            tint = if (isSelected) Color.White else Color.Gray,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            label,
                            fontSize = 9.sp,
                            color = if (isSelected) Color.White else Color.Gray,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
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
fun MainScreen(
    activity: MainActivity,
    historyRepo: HistoryRepository,
    appLogs: List<String>,
    onLogsUpdate: (List<String>) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)

    val permissions = rememberMultiplePermissionsState(
        permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
    )

    // Запрос разрешений при первом открытии
    LaunchedEffect(Unit) {
        if (!permissions.allPermissionsGranted) {
            delay(500) // небольшая задержка для плавности
            permissions.launchMultiplePermissionRequest()
        }
    }

    var isChecking by remember { mutableStateOf(false) }
    var isRestricted by remember { mutableStateOf<Boolean?>(null) }
    var resultText by remember { mutableStateOf("") }
    var serviceStatuses by remember { mutableStateOf<List<ServiceStatus>>(emptyList()) }
    var locationInfo by remember { mutableStateOf("") }
    var connectionError by remember { mutableStateOf<String?>(null) }
    var showHowItWorks by remember { mutableStateOf(false) }

    fun addLog(message: String) {
        val newLogs = (appLogs + message).takeLast(50)
        onLogsUpdate(newLogs)
    }

    // Анимация пульсации кругов - ВСЕ пульсируют
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isChecking) 1.08f else 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = if (isChecking) 0.3f else 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    // Эффект радара - быстрый старт, потом медленное вращение
    val radarAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = if (isChecking) {
                // Быстрый оборот за 1 секунду, потом медленный
                tween(4000, easing = LinearEasing)
            } else {
                tween(8000, easing = LinearEasing)
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "radarAngle"
    )

    // Анимация расширения кругов до краёв при проверке
    val circleExpandScale by animateFloatAsState(
        targetValue = if (isChecking) 4f else 1f,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "circleExpand"
    )

    // Цвета темы (чёрная/белая)
    val backgroundColor by animateColorAsState(
        targetValue = if (isRestricted == true) Color.White else Color.Black,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "bgColor"
    )
    
    val textColor by animateColorAsState(
        targetValue = if (isRestricted == true) Color.Black else Color.White,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "textColor"
    )
    
    val accentColor by animateColorAsState(
        targetValue = when (isRestricted) {
            true -> Color(0xFF000000)
            false -> Color(0xFFFFFFFF)
            else -> Color.White
        },
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "accentColor"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Анимированный заголовок
            AnimatedContent(
                targetState = isChecking,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                label = "title_animation"
            ) { checking ->
                if (checking) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("идёт проверка...", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = textColor, textAlign = TextAlign.Center)
                        Text("пожалуйста, подождите", fontSize = 14.sp, color = textColor.copy(alpha = 0.6f), textAlign = TextAlign.Center)
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("whitelist checker", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = textColor, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text("проверка реальных ограничений интернета", fontSize = 14.sp, color = textColor.copy(alpha = 0.6f), textAlign = TextAlign.Center)
                    }
                }
            }
            
            Spacer(Modifier.height(48.dp))

            // Круглая кнопка с анимацией
            Box(
                modifier = Modifier.size(280.dp),
                contentAlignment = Alignment.Center
            ) {
                // Пульсирующие круги (все синхронно)
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .scale(pulseScale * if (isChecking) circleExpandScale else 1f)
                        .clip(CircleShape)
                        .background(textColor.copy(alpha = pulseAlpha * 0.5f))
                )
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .scale(pulseScale * if (isChecking) circleExpandScale else 1f)
                        .clip(CircleShape)
                        .background(textColor.copy(alpha = pulseAlpha))
                )
                
                // Маленький круг с эффектом радара
                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(textColor.copy(alpha = pulseAlpha * 1.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    // Вращающаяся дуга (радар)
                    Canvas(modifier = Modifier.size(160.dp)) {
                        val radius = size.minDimension / 2 - 10.dp.toPx()
                        val arcLength = 90f
                        drawArc(
                            color = backgroundColor.copy(alpha = 0.8f),
                            startAngle = radarAngle,
                            sweepAngle = arcLength,
                            useCenter = false,
                            topLeft = Offset(size.width / 2 - radius, size.height / 2 - radius),
                            size = Size(radius * 2, radius * 2),
                            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                    
                    // Центральная кнопка
                    Box(
                        modifier = Modifier
                            .size(140.dp)
                            .clip(CircleShape)
                            .background(backgroundColor)
                            .clickable(enabled = !isChecking && connectionError == null) {
                                val connStatus = activity.checkConnectionStatus(context)
                                when (connStatus) {
                                    ConnectionStatus.NO_SIM -> connectionError = "нет sim-карты"
                                    ConnectionStatus.NO_INTERNET -> connectionError = "нет интернет-соединения"
                                    ConnectionStatus.WIFI_AND_MOBILE -> connectionError = "отключите Wi-Fi"
                                    ConnectionStatus.VPN_ACTIVE -> connectionError = "отключите VPN"
                                    ConnectionStatus.MOBILE_ONLY -> {
                                        connectionError = null
                                        isChecking = true
                                        resultText = ""
                                        serviceStatuses = emptyList()
                                        isRestricted = null
                                        locationInfo = ""
                                        addLog("▶ начата проверка")
                                        
                                        scope.launch {
                                            try {
                                                if (!permissions.allPermissionsGranted) {
                                                    permissions.launchMultiplePermissionRequest()
                                                    addLog("⏸ запрошены разрешения")
                                                    isChecking = false
                                                    return@launch
                                                }
                                                
                                                var location = ""
                                                try {
                                                    val loc = LocationServices.getFusedLocationProviderClient(context).lastLocation.await()
                                                    location = "координаты: %.4f, %.4f".format(loc.latitude, loc.longitude)
                                                } catch (e: Exception) {
                                                    location = "геолокация недоступна"
                                                }
                                                
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
                                                if (isRestricted == true && !lastRestricted)
                                                    sendNotificationManual(context, "⚠️ ограничения включены", "некоторые зарубежные сайты могут быть недоступны.")
                                                else if (isRestricted == false && lastRestricted)
                                                    sendNotificationManual(context, "✅ ограничения сняты", "все сервисы снова доступны.")
                                                else if (isRestricted == false)
                                                    sendNotificationManual(context, "💡 напоминание", "ограничения могут вернуться в любой момент.")
                                                
                                                prefs.edit().putBoolean("last_restricted", isRestricted == true).apply()
                                            } catch (e: Exception) {
                                                resultText = "ошибка: ${e.message}"
                                                addLog("⚠ ошибка: ${e.message}")
                                                e.printStackTrace()
                                            } finally {
                                                isChecking = false
                                            }
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.CellTower,
                                null,
                                tint = textColor,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (isChecking) "..." else "проверить",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = textColor
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            // Кнопка "как это работает?"
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { showHowItWorks = true }
            ) {
                Icon(
                    Icons.Default.Info,
                    null,
                    tint = textColor.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "как это работает?",
                    fontSize = 12.sp,
                    color = textColor.copy(alpha = 0.6f)
                )
            }

            Spacer(Modifier.height(24.dp))

            // Ошибка соединения
            if (connectionError != null) {
                Text(
                    connectionError!!,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFA726),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFFA726).copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                        .padding(16.dp)
                )
                Spacer(Modifier.height(16.dp))
            }

            // Результат проверки
            if (resultText.isNotEmpty()) {
                Text(
                    resultText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isRestricted == true) Color(0xFF000000) else Color(0xFFFFFFFF),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isRestricted == true) Color(0xFF000000).copy(alpha = 0.1f) else Color(0xFFFFFFFF).copy(alpha = 0.1f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(16.dp)
                )
                Spacer(Modifier.height(12.dp))
            }

            // Локация
            if (locationInfo.isNotEmpty()) {
                Text(locationInfo, fontSize = 11.sp, color = textColor.copy(alpha = 0.5f))
                Spacer(Modifier.height(12.dp))
            }

            // Статусы сервисов
            if (serviceStatuses.isNotEmpty()) {
                Text(
                    "статус сервисов:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                serviceStatuses.forEach { service ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Icon(
                            if (service.isAccessible) Icons.Filled.Check else Icons.Filled.Close,
                            null,
                            tint = if (service.isAccessible) Color(0xFF4CAF50) else Color(0xFFE53935),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(service.name.lowercase(), color = textColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // Диалог "как это работает?"
    if (showHowItWorks) {
        InfoTooltipDialog(
            title = "как это работает?",
            content = "приложение проверяет доступность российских (госуслуги, яндекс) и зарубежных (google, wikipedia) сайтов через мобильный интернет.\n\nесли российские работают, а зарубежные нет — значит, в вашей сети действуют ограничения.\n\nпроверка работает только при отключённом Wi-Fi и VPN.",
            onDismiss = { showHowItWorks = false }
        )
    }
}

// =============================================
// INFO TOOLTIP DIALOG
// =============================================
@Composable
fun InfoTooltipDialog(title: String, content: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = Color(0xFF3B82F6), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Spacer(Modifier.height(12.dp))
                Text(content, fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f), lineHeight = 16.sp)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("понятно", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// =============================================
// HISTORY SCREEN
// =============================================
@Composable
fun HistoryScreen(activity: MainActivity, historyRepo: HistoryRepository, appLogs: List<String>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var historyList by remember { mutableStateOf<List<HistoryEntity>>(emptyList()) }
    var showTab by remember { mutableStateOf(0) } // 0 = история, 1 = логи
    var showClearConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        historyList = historyRepo.getHistory()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .padding(bottom = 100.dp) // отступ для островка
    ) {
        Text("история проверок", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text("${historyList.size} записей", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
        Spacer(Modifier.height(16.dp))

        // Переключатель История/Логи
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (showTab == 0) Color(0xFF3B82F6) else Color(0xFF1A1A1A))
                    .clickable { showTab = 0 }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("история", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (showTab == 0) Color.White else Color.Gray)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (showTab == 1) Color(0xFF3B82F6) else Color(0xFF1A1A1A))
                    .clickable { showTab = 1 }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("логи", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (showTab == 1) Color.White else Color.Gray)
            }
        }

        Spacer(Modifier.height(16.dp))

        // Контент
        if (showTab == 0) {
            if (historyList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("пока нет записей", fontSize = 14.sp, color = Color.White.copy(alpha = 0.4f))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(historyList, key = { it.id }) { entry ->
                        HistoryCard(entry) {
                            scope.launch {
                                historyRepo.deleteEntry(entry.id)
                                historyList = historyRepo.getHistory()
                                Toast.makeText(context, "запись удалена", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        } else {
            if (appLogs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("логи появятся после проверки", fontSize = 14.sp, color = Color.White.copy(alpha = 0.4f))
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(appLogs) { log ->
                        Text(log, fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        }

        // Кнопки действий
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { activity.exportHistory(context, historyRepo) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(Color.White.copy(alpha = 0.3f))),
                shape = RoundedCornerShape(20.dp)
            ) {
                Icon(Icons.Default.FileDownload, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("экспорт", fontSize = 12.sp)
            }

            OutlinedButton(
                onClick = { showClearConfirm = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE53935)),
                border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFFE53935))),
                shape = RoundedCornerShape(20.dp)
            ) {
                Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("очистить", fontSize = 12.sp)
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("очистить историю?", color = Color.White, fontSize = 14.sp) },
            text = { Text("это действие нельзя отменить.", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        historyRepo.clearHistory()
                        historyList = emptyList()
                        Toast.makeText(context, "история очищена", Toast.LENGTH_SHORT).show()
                    }
                    showClearConfirm = false
                }) {
                    Text("очистить", color = Color(0xFFE53935), fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("отмена", color = Color.White, fontSize = 12.sp)
                }
            },
            containerColor = Color(0xFF1A1A1A),
            shape = RoundedCornerShape(16.dp)
        )
    }
}

// =============================================
// HISTORY CARD (с удалением и новым форматом)
// =============================================
@Composable
fun HistoryCard(entry: HistoryEntity, onDelete: () -> Unit) {
    val date = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(entry.timestamp))
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(entry.timestamp))
    
    // Парсим statusesJson (формат: "Госуслуги:true|Яндекс:true|Google:true|Wikipedia:true")
    val statuses = entry.statusesJson.split("|").map { status ->
        val parts = status.split(":")
        if (parts.size == 2) {
            parts[0] to (parts[1] == "true")
        } else {
            "" to false
        }
    }.filter { it.first.isNotEmpty() }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Дата и время
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "$date | $time",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.5f),
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        "Удалить",
                        tint = Color(0xFFE53935),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            
            Spacer(Modifier.height(8.dp))
            
            // Статусы сайтов
            statuses.forEach { (name, isAccessible) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    Text(
                        "$name: ",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    Icon(
                        if (isAccessible) Icons.Filled.Check else Icons.Filled.Close,
                        null,
                        tint = if (isAccessible) Color(0xFF4CAF50) else Color(0xFFE53935),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            
            // Локация (если есть)
            if (entry.location != null) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.LocationOn,
                        null,
                        tint = Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        entry.location,
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}

// =============================================
// SETTINGS SCREEN
// =============================================
@Composable
fun SettingsScreen(historyRepo: HistoryRepository) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)

    var notificationEnabled by remember { mutableStateOf(prefs.getBoolean("notifications_enabled", false)) }
    var intervalMinutes by remember { mutableStateOf(prefs.getInt("interval_minutes", 15)) }
    var customSites by remember { mutableStateOf(NetworkChecker.getSites(context)) }
    var showAddSiteDialog by remember { mutableStateOf(false) }
    var showInfoTooltip by remember { mutableStateOf<String?>(null) }
    var newSiteName by remember { mutableStateOf("") }
    var newSiteUrl by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .padding(bottom = 100.dp) // отступ для островка
        ) {
            Text("настройки", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Spacer(Modifier.height(24.dp))

            // Уведомления
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Notifications, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("push-уведомления", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("оповещения об изменениях", fontSize = 10.sp, color = Color.White.copy(alpha = 0.5f))
                    }
                    IconButton(onClick = { showInfoTooltip = "уведомления" }) {
                        Icon(Icons.Default.Info, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = notificationEnabled,
                        onCheckedChange = { enabled ->
                            notificationEnabled = enabled
                            prefs.edit().putBoolean("notifications_enabled", enabled).apply()
                            if (enabled) {
                                NotificationWorker.schedule(context)
                                Toast.makeText(context, "уведомления включены", Toast.LENGTH_SHORT).show()
                            } else {
                                NotificationWorker.cancel(context)
                                Toast.makeText(context, "уведомления отключены", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF4CAF50),
                            checkedTrackColor = Color(0xFF4CAF50).copy(alpha = 0.3f),
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = Color.Gray.copy(alpha = 0.3f)
                        )
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Интервал проверки
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("интервал проверки", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("каждые $intervalMinutes минут", fontSize = 10.sp, color = Color.White.copy(alpha = 0.5f))
                        }
                        IconButton(onClick = { showInfoTooltip = "интервал" }) {
                            Icon(Icons.Default.Info, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Slider(
                        value = intervalMinutes.toFloat(),
                        onValueChange = { newValue ->
                            intervalMinutes = newValue.toInt()
                            prefs.edit().putInt("interval_minutes", intervalMinutes).apply()
                            NotificationWorker.reschedule(context)
                        },
                        valueRange = 5f..60f,
                        steps = 10,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF4CAF50),
                            activeTrackColor = Color(0xFF4CAF50),
                            inactiveTrackColor = Color(0xFF333333)
                        )
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("5 мин", fontSize = 9.sp, color = Color.White.copy(alpha = 0.4f))
                        Text("60 мин", fontSize = 9.sp, color = Color.White.copy(alpha = 0.4f))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Список сайтов
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Web, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("проверяемые сайты", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { showInfoTooltip = "сайты" }) {
                            Icon(Icons.Default.Info, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 300.dp)) {
                        items(customSites) { site ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(site.first, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text(site.second, fontSize = 10.sp, color = Color.White.copy(alpha = 0.5f), maxLines = 1)
                                }
                                IconButton(onClick = {
                                    customSites = customSites.filter { it != site }
                                    NetworkChecker.saveSites(context, customSites)
                                    Toast.makeText(context, "сайт удалён", Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(Icons.Default.Delete, "Удалить", tint = Color(0xFFE53935), modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // FAB для добавления сайта
        FloatingActionButton(
            onClick = { showAddSiteDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 100.dp), // отступ для островка
            containerColor = Color(0xFF4CAF50),
            contentColor = Color.White,
            shape = CircleShape
        ) {
            Icon(Icons.Default.Add, "Добавить сайт", modifier = Modifier.size(20.dp))
        }
    }

    // Tooltip диалоги
    when (showInfoTooltip) {
        "уведомления" -> InfoTooltipDialog("push-уведомления", "при включении приложение будет отправлять уведомления при изменении статуса ограничений.", onDismiss = { showInfoTooltip = null })
        "интервал" -> InfoTooltipDialog("интервал проверки", "как часто приложение будет автоматически проверять статус ограничений в фоновом режиме.", onDismiss = { showInfoTooltip = null })
        "сайты" -> InfoTooltipDialog("проверяемые сайты", "список сайтов, которые приложение проверяет на доступность. вы можете добавлять и удалять сайты.", onDismiss = { showInfoTooltip = null })
    }

    // Диалог добавления сайта
    if (showAddSiteDialog) {
        AlertDialog(
            onDismissRequest = { showAddSiteDialog = false },
            title = { Text("добавить сайт", color = Color.White, fontSize = 14.sp) },
            text = {
                Column {
                    OutlinedTextField(value = newSiteName, onValueChange = { newSiteName = it }, label = { Text("название", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp) }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF4CAF50), unfocusedBorderColor = Color.White.copy(alpha = 0.3f), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = newSiteUrl, onValueChange = { newSiteUrl = it }, label = { Text("URL", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp) }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF4CAF50), unfocusedBorderColor = Color.White.copy(alpha = 0.3f), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                }
            },
            confirmButton = { TextButton(onClick = {
                if (newSiteName.isNotBlank() && newSiteUrl.isNotBlank()) {
                    val newSite = newSiteName.trim() to newSiteUrl.trim()
                    if (customSites.none { it.first == newSite.first }) { customSites = customSites + newSite; NetworkChecker.saveSites(context, customSites); Toast.makeText(context, "сайт добавлен", Toast.LENGTH_SHORT).show(); newSiteName = ""; newSiteUrl = ""; showAddSiteDialog = false }
                    else Toast.makeText(context, "такой сайт уже есть", Toast.LENGTH_SHORT).show()
                }
            }) { Text("добавить", color = Color(0xFF4CAF50), fontSize = 12.sp) } },
            dismissButton = { TextButton(onClick = { showAddSiteDialog = false; newSiteName = ""; newSiteUrl = "" }) { Text("отмена", color = Color.White, fontSize = 12.sp) } },
            containerColor = Color(0xFF1A1A1A),
            shape = RoundedCornerShape(16.dp)
        )
    }
}

// =============================================
// INFO SCREEN
// =============================================
@Composable
fun InfoScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).padding(bottom = 100.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("информация", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        Spacer(Modifier.height(24.dp))

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("whitelist checker", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text("приложение проверяет доступность зарубежных сайтов через мобильный интернет. если российские сервисы работают, а зарубежные нет — значит, в вашей сети действуют ограничения.\n\nпомогает отслеживать реальное состояние доступа к интернету в вашей сети.", fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f), lineHeight = 18.sp)
                Spacer(Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("версия", fontSize = 10.sp, color = Color.White.copy(alpha = 0.5f)); Text("1.0", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold) }
                    Column(horizontalAlignment = Alignment.End) { Text("разработчик", fontSize = 10.sp, color = Color.White.copy(alpha = 0.5f)); Text("whitelist checker team", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("как использовать", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(12.dp))
                listOf("отключите Wi-Fi и VPN", "нажмите кнопку \"проверить\"", "дождитесь результатов", "просматривайте историю проверок").forEachIndexed { index, step ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Text("${index + 1}.", fontSize = 12.sp, color = Color(0xFF3B82F6), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text(step, fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f))
                    }
                }
            }
        }
    }
}

// =============================================
// HELPER FUNCTIONS
// =============================================
private fun sendNotificationManual(context: Context, title: String, message: String) {
    val prefs = context.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)
    if (!prefs.getBoolean("notifications_enabled", false)) return
    
    val channelId = NotificationWorker.CHANNEL_ID
    val intent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }
    val pendingIntent = android.app.PendingIntent.getActivity(context, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE)
    val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(message)
        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .build()
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            androidx.core.app.NotificationManagerCompat.from(context).notify(1, notification)
    } else {
        androidx.core.app.NotificationManagerCompat.from(context).notify(1, notification)
    }
}
