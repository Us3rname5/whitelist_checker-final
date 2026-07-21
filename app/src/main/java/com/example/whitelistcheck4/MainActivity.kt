package com.example.whitelistcheck4

import android.Manifest
import android.content.Context
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

enum class NetworkStatus {
    OK, WIFI_ONLY, NO_MOBILE
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

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Убираем белую полосу сверху
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = android.graphics.Color.BLACK
        }

        val networkStatus = checkNetworkStatus(this)

        setContent {
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
            val scope = rememberCoroutineScope()
            val context = LocalContext.current

            // Пульсация для круга (только во время проверки)
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.15f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ), label = "pulseScale"
            )

            if (networkStatus == NetworkStatus.NO_MOBILE) {
                NoSimScreen(context)
                return@setContent
            }

            LaunchedEffect(networkStatus) {
                if (networkStatus == NetworkStatus.WIFI_ONLY) {
                    Toast.makeText(
                        context,
                        "для стабильной работы используйте только мобильный интернет. отключите wi‑fi.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            val backgroundColor by animateColorAsState(
                targetValue = when (isRestricted) {
                    true -> Color(0xFF1A1A1A)
                    false -> Color(0xFFF5F5F5)
                    null -> Color(0xFFEEEEEE)
                }, animationSpec = tween(400), label = "bg"
            )
            val contentColor by animateColorAsState(
                targetValue = when (isRestricted) {
                    true -> Color.White
                    false -> Color.Black
                    null -> Color.DarkGray
                }, animationSpec = tween(400), label = "text"
            )
            val accentColor by animateColorAsState(
                targetValue = when (isRestricted) {
                    true -> Color(0xFFCF6679)
                    false -> Color(0xFF4CAF50)
                    null -> Color.Gray
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
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
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

                        // --- КРУГЛАЯ КНОПКА ---
                        Box(
                            modifier = Modifier
                                .size(120.dp)
                                .scale(if (isChecking) pulseScale else 1f)
                                .background(
                                    color = when {
                                        isChecking -> Color(0xFF666666)
                                        isRestricted == true -> Color(0xFF333333)
                                        else -> Color(0xFFE0E0E0)
                                    },
                                    shape = CircleShape
                                )
                                .clickable(enabled = !isChecking) {
                                    isChecking = true
                                    scope.launch {
                                        try {
                                            resultText = "проверяю..."
                                            serviceStatuses = emptyList()
                                            isRestricted = null

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
                                                isChecking = false
                                                return@launch
                                            }

                                            var location = ""
                                            try {
                                                val loc = LocationServices.getFusedLocationProviderClient(
                                                    this@MainActivity
                                                ).lastLocation.await()
                                                location =
                                                    "координаты: ${"%.4f".format(loc.latitude)}, ${"%.4f".format(loc.longitude)}"
                                            } catch (e: Exception) {
                                                location = "геолокация недоступна"
                                            }

                                            val statuses = NetworkChecker.checkAll()
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
                                        } catch (e: Exception) {
                                            resultText = "ошибка: ${e.message}"
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
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Outlined.Wifi,
                                    contentDescription = null,
                                    tint = if (isRestricted == true) Color.White else Color.Black,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (isChecking) "..." else "проверить",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isRestricted == true) Color.White else Color.Black
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // --- РЕЗУЛЬТАТЫ ---
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
                                    color = if (isRestricted == true) Color(0xFFCF6679) else Color(0xFF4CAF50),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (isRestricted == true) Color(0x33CF6679) else Color(0x334CAF50),
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
                                                tint = if (service.isAccessible) Color(0xFF4CAF50) else Color(0xFFCF6679),
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(service.name.lowercase(), color = contentColor, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(32.dp))

                        // --- ПЕРЕКЛЮЧАТЕЛЬ УВЕДОМЛЕНИЙ (показываем только при isRestricted == true) ---
                        if (isRestricted == true) {
                            val switchTrackColor by animateColorAsState(
                                targetValue = if (notificationEnabled) accentColor.copy(alpha = 0.5f) else Color.LightGray,
                                animationSpec = tween(300), label = "track"
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    "push-уведомления".lowercase(),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = contentColor,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = notificationEnabled,
                                    onCheckedChange = { enabled ->
                                        notificationEnabled = enabled
                                        if (enabled) {
                                            NotificationWorker.schedule(this@MainActivity)
                                            Toast.makeText(this@MainActivity, "оповещения включены", Toast.LENGTH_SHORT).show()
                                        } else {
                                            NotificationWorker.cancel(this@MainActivity)
                                            Toast.makeText(this@MainActivity, "оповещения отключены", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = contentColor,
                                        checkedTrackColor = switchTrackColor,
                                        uncheckedThumbColor = Color.Gray,
                                        uncheckedTrackColor = Color.LightGray
                                    )
                                )
                            }
                            Text(
                                "при отключении белых списков придёт уведомление".lowercase(),
                                fontSize = 13.sp,
                                color = contentColor.copy(alpha = 0.6f),
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun NoSimScreen(context: Context) {
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
