package com.brr.app.reminder

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

data class PermissionSnapshot(
    val notifications: Boolean,
    val exactAlarms: Boolean,
    val fullScreen: Boolean,
    val batteryUnrestricted: Boolean,
    val policy: DeviceAlertPolicy,
) {
    val allGood get() = notifications && exactAlarms && fullScreen && batteryUnrestricted
}

/** Vérifie l'état des autorisations et ouvre les bons écrans de réglages selon la version d'Android. */
class PermissionCenter(private val context: Context, private val scheduler: AlarmScheduler) {

    fun snapshot(): PermissionSnapshot = PermissionSnapshot(
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        exactAlarms = scheduler.canScheduleExact(),
        fullScreen = canUseFullScreen(),
        batteryUnrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
        policy = DeviceAlertPolicy.read(context),
    )

    fun canUseFullScreen(): Boolean =
        if (Build.VERSION.SDK_INT >= 34) context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent() else true

    fun needsRuntimeNotificationPermission() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun notificationSettingsIntent(): Intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun exactAlarmIntent(): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")) else null

    fun fullScreenIntent(): Intent? = if (Build.VERSION.SDK_INT >= 34)
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")) else null

    @SuppressLint("BatteryLife") // Appli de rappels : l'exemption est demandée explicitement à l'utilisateur.
    fun batteryIntent(): Intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    fun batteryFallbackIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun appDetailsIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    /** Lance un écran de réglages ; retourne false si aucun écran compatible n'existe. */
    fun launch(intent: Intent?, fallback: Intent? = appDetailsIntent()): Boolean {
        for (i in listOfNotNull(intent, fallback)) {
            try {
                context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) { }
        }
        return false
    }
}
