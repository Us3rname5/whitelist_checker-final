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
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState
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

enum class OnboardingStep {
    WELCOME, LOCATION_REASON, LOCATION_PERMISSION,
    NOTIFICATION_REASON, NOTIFICATION_PERMISSION, THANKS, NONE
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

        // Блокировка VPN
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return ConnectionStatus.VPN_ACTIVE

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
        val autoCheck = intent?.getBooleanExtra("AUTO_START_CHECK", false) == true
        setContent { App(this, autoStartCheck = autoCheck) }
    }

    fun exportHistory(context: Context, repo: HistoryRepository) {
        kotlinx.coroutines.GlobalScope.launch {
            val list = repo.getHistory()
            if (list.isEmpty()) {
                Toast.makeText(context, "история пуста", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val sb = StringBuilder()
            sb.append("whitelist checker - история проверок\n=====================================\n\n")
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
fun App(activity: MainActivity, autoStartCheck: Boolean = false) {
    var currentScreen by remember { mutableStateOf(Screen.MAIN) }
    val context = LocalContext.current
    val historyRepo = remember { HistoryRepository(context) }
    val prefs = context.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)

    var appLogs by remember { mutableStateOf<List<String>>(emptyList()) }
    var isRestricted by remember { mutableStateOf<Boolean?>(null) }

    var onboardingStep by remember {
        mutableStateOf(
            if (prefs.getBoolean("onboarding_completed", false)) OnboardingStep.NONE
            else OnboardingStep.WELCOME
        )
    }

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
                    Screen.MAIN -> MainScreen(
                        activity = activity,
                        historyRepo = historyRepo,
                        appLogs = appLogs,
                        isRestricted = isRestricted,
                        autoStartCheck = autoStartCheck
                    ) { newLogs, newRestricted ->
                        appLogs = newLogs
                        isRestricted = newRestricted
                    }
                    Screen.HISTORY -> HistoryScreen(activity, historyRepo, appLogs)
                    Screen.SETTINGS -> SettingsScreen(historyRepo)
                    Screen.INFO -> InfoScreen()
                }
            }

            FloatingNavigationBar(
                currentScreen = currentScreen,
                onScreenSelected = { currentScreen = it },
                isRestricted = isRestricted,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
            )

            if (onboardingStep != OnboardingStep.NONE) {
                OnboardingDialog(
                    step = onboardingStep,
                    onComplete = {
                        prefs.edit().putBoolean("onboarding_completed", true).apply()
                        onboardingStep = OnboardingStep.NONE
                    },
                    onNext = { onboardingStep = it }
                )
            }
        }
    }
}

// =============================================
// КОНФЕТТИ
// =============================================
data class ConfettiParticle(val x: Float, val delay: Float, val speed: Float, val color: Int, val size: Float)

@Composable
fun ConfettiEffect() {
    val particles = remember {
        val rnd = kotlin.random.Random(42)
        List(60) { i ->
            ConfettiParticle(
                x = rnd.nextFloat(),
                delay = rnd.nextFloat() * 1200f,
                speed = 300f + rnd.nextFloat() * 500f,
                color = i % 4,
                size = 8f + rnd.nextFloat() * 10f
            )
        }
    }
    val progress by rememberInfiniteTransition(label = "confetti").animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2500, easing = LinearEasing), RepeatMode.Restart),
        label = "p"
    )
    Canvas(Modifier.fillMaxSize()) {
        val colors = listOf(Color(0xFFFF9800), Color(0xFF4CAF50), Color(0xFF3B82F6), Color(0xFFE53935))
        particles.forEach { p ->
            val y = ((progress * p.speed + p.delay) % (size.height + 40f)) - 20f
            drawRect(
                colors[p.color],
                Offset(p.x * size.width, y),
                Size(p.size, p.size * 0.6f)
            )
        }
    }
}

// =============================================
// ONBOARDING
// =============================================
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun OnboardingDialog(
    step: OnboardingStep,
    onComplete: () -> Unit,
    onNext: (OnboardingStep) -> Unit
) {
    val locationPermission = rememberPermissionState(Manifest.permission.ACCESS_FINE_LOCATION)
    val notificationPermission = rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)

    Dialog(onDismissRequest = { }) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
        ) {
            Box {
                if (step == OnboardingStep.THANKS) ConfettiEffect()
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (step) {
                        OnboardingStep.WELCOME -> {
                            Icon(Icons.Default.CellTower, null, tint = Color(0xFF3B82F6), modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("whitelist checker", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "приложение проверяет доступность зарубежных сайтов через мобильный интернет.\n\nесли российские сервисы работают, а зарубежные нет — значит, в вашей сети действуют ограничения.",
                                fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center, lineHeight = 20.sp
                            )
                            Spacer(Modifier.height(24.dp))
                            Button(onClick = { onNext(OnboardingStep.LOCATION_REASON) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)), shape = RoundedCornerShape(12.dp)) {
                                Text("далее", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        OnboardingStep.LOCATION_REASON -> {
                            Icon(Icons.Default.LocationOn, null, tint = Color(0xFF3B82F6), modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("зачем нужна геолокация?", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "мы сохраняем координаты каждой проверки, чтобы вы могли отследить, где именно были обнаружены ограничения.\n\nпожалуйста, включите точную геолокацию и выберите \"всегда\" или \"при использовании приложения\".",
                                fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center, lineHeight = 20.sp
                            )
                            Spacer(Modifier.height(24.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onNext(OnboardingStep.NOTIFICATION_REASON) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), shape = RoundedCornerShape(12.dp)) {
                                    Text("пропустить", fontSize = 12.sp)
                                }
                                Button(onClick = { locationPermission.launchPermissionRequest(); onNext(OnboardingStep.LOCATION_PERMISSION) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)), shape = RoundedCornerShape(12.dp)) {
                                    Text("разрешить", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        OnboardingStep.LOCATION_PERMISSION -> {
                            val granted = locationPermission.status == PermissionStatus.Granted
                            Icon(if (granted) Icons.Default.CheckCircle else Icons.Default.Warning, null, tint = if (granted) Color(0xFF4CAF50) else Color(0xFFFFA726), modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("геолокация", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                if (granted) "отлично! теперь приложение будет сохранять местоположение каждой проверки."
                                else "разрешение не получено. вы можете включить его позже в настройках системы.",
                                fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center, lineHeight = 20.sp
                            )
                            Spacer(Modifier.height(24.dp))
                            Button(onClick = { onNext(OnboardingStep.NOTIFICATION_REASON) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)), shape = RoundedCornerShape(12.dp)) {
                                Text("далее", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        OnboardingStep.NOTIFICATION_REASON -> {
                            Icon(Icons.Default.Notifications, null, tint = Color(0xFF3B82F6), modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("зачем нужны уведомления?", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "мы будем отправлять уведомления, когда статус ограничений изменится.\n\nуведомления по умолчанию выключены — вы сможете включить их позже в настройках.",
                                fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center, lineHeight = 20.sp
                            )
                            Spacer(Modifier.height(24.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onNext(OnboardingStep.THANKS) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), shape = RoundedCornerShape(12.dp)) {
                                    Text("пропустить", fontSize = 12.sp)
                                }
                                Button(onClick = { notificationPermission.launchPermissionRequest(); onNext(OnboardingStep.NOTIFICATION_PERMISSION) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)), shape = RoundedCornerShape(12.dp)) {
                                    Text("разрешить", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        OnboardingStep.NOTIFICATION_PERMISSION -> {
                            val granted = notificationPermission.status == PermissionStatus.Granted
                            Icon(if (granted) Icons.Default.CheckCircle else Icons.Default.Warning, null, tint = if (granted) Color(0xFF4CAF50) else Color(0xFFFFA726), modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("уведомления", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                if (granted) "разрешение получено! включите уведомления в настройках приложения, когда захотите."
                                else "разрешение не получено. вы можете включить уведомления позже в настройках.",
                                fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center, lineHeight = 20.sp
                            )
                            Spacer(Modifier.height(24.dp))
                            Button(onClick = { onNext(OnboardingStep.THANKS) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)), shape = RoundedCornerShape(12.dp)) {
                                Text("далее", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        OnboardingStep.THANKS -> {
                            Icon(Icons.Default.EmojiEvents, null, tint = Color(0xFFFFA726), modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("спасибо за понимание!", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(12.dp))
                            Text("всё готово. приятного пользования!", fontSize = 16.sp, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
                            Spacer(Modifier.height(24.dp))
                            Button(onClick = onComplete, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFA726)), shape = RoundedCornerShape(12.dp)) {
                                Text("начать", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        OnboardingStep.NONE -> {}
                    }
                }
            }
        }
    }
}

// =============================================
// FLOATING NAVIGATION BAR
// =============================================
@Composable
fun FloatingNavigationBar(
    currentScreen: Screen,
    onScreenSelected: (Screen) -> Unit,
    isRestricted: Boolean?,
    modifier: Modifier = Modifier
) {
    val items = listOf(
        Triple(Icons.Default.Home, "главный", Screen.MAIN),
        Triple(Icons.Default.History, "история", Screen.HISTORY),
        Triple(Icons.Default.Settings, "настройки", Screen.SETTINGS),
        Triple(Icons.Default.Info, "информация", Screen.INFO)
    )

    val activeColor = when (currentScreen) {
        Screen.MAIN -> when (isRestricted) {
            true -> Color.Black
            false -> Color.White
            null -> Color(0xFF2A2A2A)
        }
        Screen.HISTORY -> Color(0xFF3B82F6)
        Screen.SETTINGS -> Color(0xFF4CAF50)
        Screen.INFO -> Color(0xFFFF9800)
    }
    val activeTextColor = when {
        currentScreen == Screen.MAIN && isRestricted == false -> Color.Black
        else -> Color.White
    }

    Box(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF1A1A1A).copy(alpha = 0.95f))
            .padding(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEach { (icon, label, screen) ->
                val isSelected = currentScreen == screen
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isSelected) activeColor else Color.Transparent)
                        .clickable { onScreenSelected(screen) }
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(icon, contentDescription = label, tint = if (isSelected) activeTextColor else Color.Gray, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(label, fontSize = 9.sp, color = if (isSelected) activeTextColor else Color.Gray, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
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
    isRestricted: Boolean?,
    autoStartCheck: Boolean = false,
    onStateUpdate: (List<String>, Boolean?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)
    val permissions = rememberMultiplePermissionsState(
        permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
    )

    var isChecking by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf("") }
    var serviceStatuses by remember { mutableStateOf<List<ServiceStatus>>(emptyList()) }
    var locationInfo by remember { mutableStateOf("") }
    var connectionError by remember { mutableStateOf<String?>(null) }
    var showHowItWorks by remember { mutableStateOf(false) }
    var testMode by remember { mutableStateOf(prefs.getBoolean("test_mode", false)) }

    fun addLog(message: String) {
        onStateUpdate((appLogs + message).takeLast(50), isRestricted)
    }

    // Волновая (поочерёдная) пульсация кругов
    val wave = rememberInfiniteTransition(label = "wave")
    val amp = if (isChecking) 0.12f else 0.05f
    val w1 by wave.animateFloat(1f, 1f + amp, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "w1")
    val w2 by wave.animateFloat(1f, 1f + amp, infiniteRepeatable(tween(700, delayMillis = 180, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "w2")
    val w3 by wave.animateFloat(1f, 1f + amp, infiniteRepeatable(tween(700, delayMillis = 360, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "w3")

    // Радар: быстрый оборот при проверке, медленный в покое
    val radar = rememberInfiniteTransition(label = "radar")
    val radarAngle by radar.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(if (isChecking) 1000 else 4000, easing = LinearEasing), RepeatMode.Restart),
        label = "radarAngle"
    )

    // Расширение кругов до краёв (но не на весь экран)
    val expand by animateFloatAsState(
        targetValue = if (isChecking) 1.5f else 1f,
        animationSpec = tween(800, easing = FastOutSlowInEasing), label = "expand"
    )

    // Чёрно-белая тема
    val backgroundColor by animateColorAsState(
        targetValue = if (isRestricted == true) Color.White else Color.Black,
        animationSpec = tween(600, easing = FastOutSlowInEasing), label = "bg"
    )
    val textColor by animateColorAsState(
        targetValue = if (isRestricted == true) Color.Black else Color.White,
        animationSpec = tween(600, easing = FastOutSlowInEasing), label = "text"
    )

    // Логика проверки (вынесена для автостарта из виджета)
    val startCheck: () -> Unit = {
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
                        val restricted = if (testMode) true else NetworkChecker.isRestricted(statuses)
                        locationInfo = location
                        resultText = if (restricted)
                            "обнаружены ограничения интернета.\nнекоторые зарубежные сайты недоступны."
                        else
                            "всё в порядке. все проверенные сервисы доступны."
                        val available = statuses.count { it.isAccessible }
                        addLog("✅ проверка завершена, доступно $available из ${statuses.size}")
                        statuses.forEach { addLog("  ${it.name}: ${if (it.isAccessible) "OK" else "❌"}") }
                        historyRepo.saveCheck(restricted, statuses, locationInfo)
                        addLog("📋 история сохранена")
                        WidgetProvider.updateWidget(context, restricted, statuses)
                        onStateUpdate(appLogs, restricted)

                        val lastRestricted = prefs.getBoolean("last_restricted", true)
                        if (restricted && !lastRestricted)
                            sendNotificationManual(context, "⚠️ ограничения включены", "некоторые зарубежные сайты могут быть недоступны.")
                        else if (!restricted && lastRestricted)
                            sendNotificationManual(context, "✅ ограничения сняты", "все сервисы снова доступны.")
                        else if (!restricted)
                            sendNotificationManual(context, "💡 напоминание", "ограничения могут вернуться в любой момент.")
                        prefs.edit().putBoolean("last_restricted", restricted).apply()
                    } catch (e: Exception) {
                        resultText = "ошибка: ${e.message}"
                        addLog("⚠ ошибка: ${e.message}")
                    } finally {
                        isChecking = false
                    }
                }
            }
        }
    }

    // Автостарт проверки из виджета
    LaunchedEffect(autoStartCheck) {
        if (autoStartCheck) {
            delay(600)
            startCheck()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(backgroundColor).padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            AnimatedContent(
                targetState = isChecking,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                label = "title"
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

            Box(modifier = Modifier.size(280.dp), contentAlignment = Alignment.Center) {
                // Волна кругов
                Box(Modifier.size(260.dp).scale(w3 * expand).clip(CircleShape).background(textColor.copy(alpha = 0.08f)))
                Box(Modifier.size(220.dp).scale(w2 * expand).clip(CircleShape).background(textColor.copy(alpha = 0.14f)))
                Box(
                    Modifier.size(180.dp).scale(w1).clip(CircleShape).background(textColor.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    // Радар
                    Canvas(Modifier.size(170.dp)) {
                        val radius = size.minDimension / 2 - 6.dp.toPx()
                        drawArc(
                            color = textColor.copy(alpha = 0.8f),
                            startAngle = radarAngle,
                            sweepAngle = 90f,
                            useCenter = false,
                            topLeft = Offset(size.width / 2 - radius, size.height / 2 - radius),
                            size = Size(radius * 2, radius * 2),
                            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }

                    // Белая кнопка
                    Box(
                        modifier = Modifier
                            .size(140.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable(enabled = !isChecking && connectionError == null) { startCheck() },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.CellTower, null, tint = Color.Black, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(4.dp))
                            Text(if (isChecking) "..." else "проверить", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { showHowItWorks = true }) {
                Icon(Icons.Default.Info, null, tint = textColor.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("как это работает?", fontSize = 12.sp, color = textColor.copy(alpha = 0.6f))
            }

            Spacer(Modifier.height(24.dp))

            if (connectionError != null) {
                Text(connectionError!!, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFA726), textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFFFA726).copy(alpha = 0.15f), RoundedCornerShape(8.dp)).padding(16.dp))
                Spacer(Modifier.height(16.dp))
            }
            if (resultText.isNotEmpty()) {
                Text(resultText, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = textColor, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().background(textColor.copy(alpha = 0.1f), RoundedCornerShape(8.dp)).padding(16.dp))
                Spacer(Modifier.height(12.dp))
            }
            if (locationInfo.isNotEmpty()) {
                Text(locationInfo, fontSize = 11.sp, color = textColor.copy(alpha = 0.5f))
                Spacer(Modifier.height(12.dp))
            }
            if (serviceStatuses.isNotEmpty()) {
                Text("статус сервисов:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = textColor.copy(alpha = 0.7f), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                serviceStatuses.forEach { service ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Icon(if (service.isAccessible) Icons.Filled.Check else Icons.Filled.Close, null,
                            tint = if (service.isAccessible) Color(0xFF4CAF50) else Color(0xFFE53935), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(service.name.lowercase(), color = textColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showHowItWorks) {
        InfoTooltipDialog(
            "как это работает?",
            "приложение проверяет доступность российских (госуслуги, яндекс) и зарубежных (google, wikipedia) сайтов через мобильный интернет.\n\nесли российские работают, а зарубежные нет — значит, в вашей сети действуют ограничения.\n\nпроверка работает только при отключённых Wi-Fi и VPN.",
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
        Card(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = Color(0xFF3B82F6), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Spacer(Modifier.height(12.dp))
                Text(content, fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f), lineHeight = 16.sp)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onDismiss, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)), shape = RoundedCornerShape(8.dp)) {
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
    var showTab by remember { mutableStateOf(0) }
    var showClearConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { historyList = historyRepo.getHistory() }

    Column(Modifier.fillMaxSize().padding(24.dp).padding(bottom = 100.dp)) {
        Text("история проверок", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text("${historyList.size} записей", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(if (showTab == 0) Color(0xFF3B82F6) else Color(0xFF1A1A1A)).clickable { showTab = 0 }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Text("история", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (showTab == 0) Color.White else Color.Gray)
            }
            Box(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(if (showTab == 1) Color(0xFF3B82F6) else Color(0xFF1A1A1A)).clickable { showTab = 1 }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Text("логи", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (showTab == 1) Color.White else Color.Gray)
            }
        }
        Spacer(Modifier.height(16.dp))

        if (showTab == 0) {
            if (historyList.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("пока нет записей", fontSize = 14.sp, color = Color.White.copy(alpha = 0.4f))
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(historyList, key = { it.id }) { entry ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                    scope.launch {
                                        historyRepo.deleteEntry(entry.id)
                                        historyList = historyRepo.getHistory()
                                        Toast.makeText(context, "запись удалена", Toast.LENGTH_SHORT).show()
                                    }
                                    true
                                } else false
                            }
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {
                                Box(
                                    Modifier.fillMaxSize().background(Color(0xFFE53935), RoundedCornerShape(12.dp)).padding(end = 24.dp),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Icon(Icons.Default.Delete, null, tint = Color.White, modifier = Modifier.size(24.dp))
                                }
                            }
                        ) {
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
            }
        } else {
            if (appLogs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("логи появятся после проверки", fontSize = 14.sp, color = Color.White.copy(alpha = 0.4f))
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(appLogs) { log ->
                        Text(log, fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { activity.exportHistory(context, historyRepo) }, Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), shape = RoundedCornerShape(20.dp)) {
                Icon(Icons.Default.FileDownload, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("экспорт", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { showClearConfirm = true }, Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE53935)), shape = RoundedCornerShape(20.dp)) {
                Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("очистить", fontSize = 12.sp)
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(onDismissRequest = { showClearConfirm = false },
            title = { Text("очистить историю?", color = Color.White, fontSize = 14.sp) },
            text = { Text("это действие нельзя отменить.", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp) },
            confirmButton = { TextButton(onClick = { scope.launch { historyRepo.clearHistory(); historyList = emptyList(); Toast.makeText(context, "история очищена", Toast.LENGTH_SHORT).show() }; showClearConfirm = false }) { Text("очистить", color = Color(0xFFE53935), fontSize = 12.sp) } },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("отмена", color = Color.White, fontSize = 12.sp) } },
            containerColor = Color(0xFF1A1A1A), shape = RoundedCornerShape(16.dp))
    }
}

// =============================================
// HISTORY CARD
// =============================================
@Composable
fun HistoryCard(entry: HistoryEntity, onDelete: () -> Unit) {
    val date = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(entry.timestamp))
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(entry.timestamp))
    val statuses = entry.statusesJson.split("|").map { s ->
        val p = s.split(":")
        if (p.size == 2) p[0] to (p[1] == "true") else "" to false
    }.filter { it.first.isNotEmpty() }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("$date | $time", fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold)
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "Удалить", tint = Color(0xFFE53935), modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            statuses.forEach { (name, ok) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Text("$name: ", fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
                    Icon(if (ok) Icons.Filled.Check else Icons.Filled.Close, null, tint = if (ok) Color(0xFF4CAF50) else Color(0xFFE53935), modifier = Modifier.size(14.dp))
                }
            }
            if (entry.location != null) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(entry.location, fontSize = 10.sp, color = Color.White.copy(alpha = 0.4f))
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
    var testMode by remember { mutableStateOf(prefs.getBoolean("test_mode", false)) }
    var showAddSiteDialog by remember { mutableStateOf(false) }
    var showInfoTooltip by remember { mutableStateOf<String?>(null) }
    var newSiteName by remember { mutableStateOf("") }
    var newSiteUrl by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(24.dp).padding(bottom = 100.dp)) {
            Text("настройки", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Spacer(Modifier.height(24.dp))

            // Уведомления
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)), shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Notifications, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("push-уведомления", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("оповещения об изменениях", fontSize = 10.sp, color = Color.White.copy(alpha = 0.5f))
                    }
                    IconButton(onClick = { showInfoTooltip = "уведомления" }) { Icon(Icons.Default.Info, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(16.dp)) }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = notificationEnabled, onCheckedChange = { enabled ->
                        notificationEnabled = enabled
                        prefs.edit().putBoolean("notifications_enabled", enabled).apply()
                        if (enabled) { NotificationWorker.schedule(context); Toast.makeText(context, "уведомления включены", Toast.LENGTH_SHORT).show() }
                        else { NotificationWorker.cancel(context); Toast.makeText(context, "уведомления отключены", Toast.LENGTH_SHORT).show() }
                    }, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF4CAF50), checkedTrackColor = Color(0xFF4CAF50).copy(alpha = 0.3f), uncheckedThumbColor = Color.Gray, uncheckedTrackColor = Color.Gray.copy(alpha = 0.3f)))
                }
            }
            Spacer(Modifier.height(12.dp))

            // Интервал
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("интервал проверки", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("каждые $intervalMinutes минут", fontSize = 10.sp, color = Color.White.copy(alpha = 0.5f))
                        }
                        IconButton(onClick = { showInfoTooltip = "интервал" }) { Icon(Icons.Default.Info, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(16.dp)) }
                    }
                    Spacer(Modifier.height(12.dp))
                    Slider(value = intervalMinutes.toFloat(), onValueChange = { v ->
                        intervalMinutes = v.toInt()
                        prefs.edit().putInt("interval_minutes", intervalMinutes).apply()
                        NotificationWorker.reschedule(context)
                    }, valueRange = 5f..60f, steps = 10, colors = SliderDefaults.colors(thumbColor = Color(0xFF4CAF50), activeTrackColor = Color(0xFF4CAF50), inactiveTrackColor = Color(0xFF333333)))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("5 мин", fontSize = 9.sp, color = Color.White.copy(alpha = 0.4f)); Text("60 мин", fontSize = 9.sp, color = Color.White.copy(alpha = 0.4f))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // Тестовый режим
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)), shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Science, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("тестовый режим", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("симуляция ограничений для проверки анимации", fontSize = 10.sp, color = Color.White.copy(alpha = 0.5f))
                    }
                    Switch(checked = testMode, onCheckedChange = { testMode = it; prefs.edit().putBoolean("test_mode", it).apply() },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFF9800), checkedTrackColor = Color(0xFFFF9800).copy(alpha = 0.3f), uncheckedThumbColor = Color.Gray, uncheckedTrackColor = Color.Gray.copy(alpha = 0.3f)))
                }
            }
            Spacer(Modifier.height(12.dp))

            // Сайты
            Card(Modifier.fillMaxWidth().weight(1f, fill = false), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Web, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("проверяемые сайты", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { showInfoTooltip = "сайты" }) { Icon(Icons.Default.Info, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(16.dp)) }
                    }
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 300.dp)) {
                        items(customSites) { site ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(site.first, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text(site.second, fontSize = 10.sp, color = Color.White.copy(alpha = 0.5f), maxLines = 1)
                                }
                                IconButton(onClick = { customSites = customSites.filter { it != site }; NetworkChecker.saveSites(context, customSites); Toast.makeText(context, "сайт удалён", Toast.LENGTH_SHORT).show() }) {
                                    Icon(Icons.Default.Delete, "Удалить", tint = Color(0xFFE53935), modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(onClick = { showAddSiteDialog = true },
            Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 100.dp),
            containerColor = Color(0xFF4CAF50), contentColor = Color.White, shape = CircleShape) {
            Icon(Icons.Default.Add, "Добавить сайт", modifier = Modifier.size(20.dp))
        }
    }

    when (showInfoTooltip) {
        "уведомления" -> InfoTooltipDialog("push-уведомления", "при включении приложение будет отправлять уведомления при изменении статуса ограничений.", onDismiss = { showInfoTooltip = null })
        "интервал" -> InfoTooltipDialog("интервал проверки", "как часто приложение будет автоматически проверять статус ограничений в фоновом режиме.", onDismiss = { showInfoTooltip = null })
        "сайты" -> InfoTooltipDialog("проверяемые сайты", "список сайтов, которые приложение проверяет на доступность. вы можете добавлять и удалять сайты.", onDismiss = { showInfoTooltip = null })
    }

    if (showAddSiteDialog) {
        AlertDialog(onDismissRequest = { showAddSiteDialog = false },
            title = { Text("добавить сайт", color = Color.White, fontSize = 14.sp) },
            text = {
                Column {
                    OutlinedTextField(value = newSiteName, onValueChange = { newSiteName = it }, label = { Text("название", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF4CAF50), unfocusedBorderColor = Color.White.copy(alpha = 0.3f), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = newSiteUrl, onValueChange = { newSiteUrl = it }, label = { Text("URL", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF4CAF50), unfocusedBorderColor = Color.White.copy(alpha = 0.3f), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
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
            containerColor = Color(0xFF1A1A1A), shape = RoundedCornerShape(16.dp))
    }
}

// =============================================
// INFO SCREEN
// =============================================
@Composable
fun InfoScreen() {
    Column(Modifier.fillMaxSize().padding(24.dp).padding(bottom = 100.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("информация", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        Spacer(Modifier.height(24.dp))

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("whitelist checker", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text("приложение проверяет доступность зарубежных сайтов через мобильный интернет. если российские сервисы работают, а зарубежные нет — значит, в вашей сети действуют ограничения.\n\nпомогает отслеживать реальное состояние доступа к интернету в вашей сети.", fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f), lineHeight = 18.sp)
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("версия", fontSize = 10.sp, color = Color.White.copy(alpha = 0.5f)); Text("1.0", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold) }
                    Column(horizontalAlignment = Alignment.End) { Text("разработчик", fontSize = 10.sp, color = Color.White.copy(alpha = 0.5f)); Text("whitelist checker team", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("как использовать", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(12.dp))
                listOf("отключите Wi-Fi и VPN", "нажмите кнопку \"проверить\"", "дождитесь результатов", "просматривайте историю проверок").forEachIndexed { index, step ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Text("${index + 1}.", fontSize = 12.sp, color = Color(0xFFFF9800), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text(step, fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f))
                    }
                }
            }
        }
    }
}

// =============================================
// HELPER
// =============================================
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
    } else {
        androidx.core.app.NotificationManagerCompat.from(context).notify(1, notification)
    }
}
