// --- ПЕРЕКЛЮЧАТЕЛЬ УВЕДОМЛЕНИЙ (показываем только при обнаруженных ограничениях) ---
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
