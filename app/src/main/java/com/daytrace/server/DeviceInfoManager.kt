package com.daytrace.server

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.telephony.TelephonyManager
import android.util.Log
import com.daytrace.util.ShizukuHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设备信息与系统状态管理器
 *
 * 提供以下能力（由 MCP Server 工具调用）：
 *
 * 1. **设备信息**：型号、Android 版本、电量、内存、存储、屏幕分辨率、网络状态
 * 2. **App 使用统计**：各 App 今日使用时长排行（通过 UsageStatsManager 或 Shizuku）
 * 3. **已安装应用列表**：包名 + 应用名 + 是否系统 App + 版本号
 * 4. **电池历史**：电池状态、充电状态、电量历史（通过 Shizuku dumpsys）
 * 5. **剪贴板**：读取/写入系统剪贴板
 * 6. **发送通知**：向手机发送一条本地通知（AI 提醒用户用）
 * 7. **发送短信**：通过 Shizuku 发送短信（需 Shizuku shell 权限）
 */
class DeviceInfoManager(private val context: Context) {

    companion object {
        private const val TAG = "DayTrace/Device"

        private const val NOTIF_CHANNEL_ID = "DayTrace_alerts"
        private const val NOTIF_CHANNEL_NAME = "DayTrace 提醒"
    }

    // === 设备信息 ===

    /**
     * 获取设备完整信息
     */
    fun getDeviceInfo(): String {
        val batteryIntent = context.registerReceiver(null,
            android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val batteryLevel = batteryIntent?.let { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) (level * 100) / scale else -1
        } ?: -1

        val isCharging = batteryIntent?.let { intent ->
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        } ?: false

        val chargeType = batteryIntent?.let { intent ->
            when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC (充电器)"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线充电"
                else -> "未充电"
            }
        } ?: "未知"

        // 内存信息
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val totalMemMB = memInfo.totalMem / (1024 * 1024)
        val availMemMB = memInfo.availMem / (1024 * 1024)
        val usedMemMB = totalMemMB - availMemMB

        // 存储信息
        val internalStat = StatFs(context.filesDir.absolutePath)
        val totalStorageGB = internalStat.totalBytes / (1024.0 * 1024 * 1024)
        val availStorageGB = internalStat.availableBytes / (1024.0 * 1024 * 1024)
        val usedStorageGB = totalStorageGB - availStorageGB

        // 屏幕分辨率
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val density = displayMetrics.densityDpi

        // 网络信息
        val (netType, netConnected) = getNetworkInfo()

        // 设备型号
        val model = "${Build.MANUFACTURER} ${Build.MODEL}"
        val brand = Build.BRAND
        val androidVersion = Build.VERSION.RELEASE
        val sdkVersion = Build.VERSION.SDK_INT
        val buildId = Build.DISPLAY

        return buildString {
            appendLine("=== Device Info ===")
            appendLine("Model: $model")
            appendLine("Brand: $brand")
            appendLine("Android: $androidVersion (SDK $sdkVersion)")
            appendLine("Build: $buildId")
            appendLine()
            appendLine("--- Battery ---")
            appendLine("Level: ${batteryLevel}%")
            appendLine("Charging: $isCharging")
            appendLine("Charge type: $chargeType")
            appendLine()
            appendLine("--- Memory ---")
            appendLine("Total: ${totalMemMB} MB")
            appendLine("Used: ${usedMemMB} MB")
            appendLine("Available: ${availMemMB} MB")
            appendLine()
            appendLine("--- Storage ---")
            appendLine("Total: ${"%.1f".format(totalStorageGB)} GB")
            appendLine("Used: ${"%.1f".format(usedStorageGB)} GB")
            appendLine("Available: ${"%.1f".format(availStorageGB)} GB")
            appendLine()
            appendLine("--- Display ---")
            appendLine("Resolution: ${screenWidth}x${screenHeight}")
            appendLine("Density: $density dpi")
            appendLine()
            appendLine("--- Network ---")
            appendLine("Connected: $netConnected")
            appendLine("Type: $netType")
        }
    }

    /** 获取网络信息 */
    private fun getNetworkInfo(): Pair<String, Boolean> {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(network)
            if (caps != null) {
                val type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "蜂窝数据"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
                    else -> "其他"
                }
                // 尝试获取 WiFi IP
                val wifiType = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    try {
                        val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                        val ip = wm?.connectionInfo?.ipAddress
                        if (ip != null && ip != 0) {
                            val ipStr = String.format(Locale.US, "%d.%d.%d.%d",
                                ip and 0xff, (ip shr 8) and 0xff,
                                (ip shr 16) and 0xff, (ip shr 24) and 0xff)
                            "$type (IP: $ipStr)"
                        } else type
                    } catch (_: Exception) { type }
                } else type
                wifiType to true
            } else {
                "无网络" to false
            }
        } catch (e: Exception) {
            "未知" to false
        }
    }

    // === App 使用统计 ===

    /**
     * 获取今日 App 使用时长统计
     *
     * 优先通过 UsageStatsManager（需要 PACKAGE_USAGE_STATS 权限），
     * 如果权限不足，通过 Shizuku `dumpsys usagestats` 获取。
     */
    fun getAppUsageStats(): String {
        // 方式1: UsageStatsManager（需要 SETTINGS_USAGE_STATS 权限）
        val stats = tryUsageStatsManager()
        if (stats.isNotEmpty()) {
            return formatUsageStats(stats, "UsageStatsManager")
        }

        // 方式2: Shizuku dumpsys
        if (ShizukuHelper.hasPermission()) {
            val result = ShizukuHelper.execShell("dumpsys usagestats")
            if (result.isSuccess) {
                return parseDumpsysUsageStats(result.getOrDefault(""))
            }
        }

        return "Unable to get app usage stats. Need PACKAGE_USAGE_STATS permission or Shizuku."
    }

    private fun tryUsageStatsManager(): List<AppUsageInfo> {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val calendar = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val startTime = calendar.timeInMillis
            val endTime = System.currentTimeMillis()

            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
            stats.map { s ->
                AppUsageInfo(
                    packageName = s.packageName,
                    totalTimeMs = s.totalTimeInForeground,
                    lastTimeUsed = s.lastTimeUsed
                )
            }.filter { it.totalTimeMs > 60_000 } // 只显示使用超过1分钟的
                .sortedByDescending { it.totalTimeMs }
        } catch (e: Exception) {
            Log.w(TAG, "UsageStatsManager failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseDumpsysUsageStats(output: String): String {
        // 从 dumpsys usagestats 输出中提取信息
        // 这个输出格式复杂且因设备而异，做简单提取
        val lines = output.lines()
        val appTimes = mutableListOf<Pair<String, Long>>()

        for (line in lines) {
            // 尝试匹配 "package: <pkg> time: <ms>" 等格式
            if (line.contains("time:") && line.contains("package:")) {
                val pkgMatch = Regex("package:\\s*([\\w.]+)").find(line)
                val timeMatch = Regex("time:\\s*(\\d+)").find(line)
                if (pkgMatch != null && timeMatch != null) {
                    val pkg = pkgMatch.groupValues[1]
                    val time = timeMatch.groupValues[1].toLongOrNull() ?: 0
                    if (time > 60_000) appTimes.add(pkg to time)
                }
            }
        }

        return if (appTimes.isNotEmpty()) {
            val sortedAppTimes = appTimes.sortedByDescending { it.second }
            val sb = StringBuilder("=== App Usage (via Shizuku dumpsys) ===\n")
            sb.appendLine("(Today, only apps > 1 minute)")
            sortedAppTimes.forEach { (pkg, time) ->
                val appName = getAppName(pkg)
                val minutes = time / 60000
                sb.appendLine("  $appName ($pkg): ${minutes}min")
            }
            sb.toString()
        } else {
            "Usage stats via dumpsys: no foreground time data found in output.\n" +
            "Tip: grant PACKAGE_USAGE_STATS permission via Shizuku:\n" +
            "  pm grant com.daytrace android.permission.PACKAGE_USAGE_STATS"
        }
    }

    private fun formatUsageStats(stats: List<AppUsageInfo>, source: String): String {
        val sb = StringBuilder("=== App Usage (via $source, today) ===\n")
        sb.appendLine("(Only apps used > 1 minute)")
        stats.forEach { info ->
            val appName = getAppName(info.packageName)
            val minutes = info.totalTimeMs / 60000
            val seconds = (info.totalTimeMs % 60000) / 1000
            val lastUsed = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(info.lastTimeUsed))
            sb.appendLine("  $appName ($info.packageName): ${minutes}m ${seconds}s (last: $lastUsed)")
        }
        return sb.toString()
    }

    private data class AppUsageInfo(
        val packageName: String,
        val totalTimeMs: Long,
        val lastTimeUsed: Long
    )

    // === 已安装应用列表 ===

    /**
     * 获取已安装应用列表
     *
     * @param includeSystem 是否包含系统应用（默认 false）
     */
    fun getInstalledApps(includeSystem: Boolean = false): String {
        val pm = context.packageManager
        val allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { appInfo ->
                if (includeSystem) true
                else !(appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0)
            }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        // 限制返回数量，防止设备上应用过多时输出过大
        val maxCount = 200
        val apps = allApps.take(maxCount)

        return buildString {
            appendLine("=== Installed Apps (${if (includeSystem) "all" else "user only"}, showing ${apps.size} of ${allApps.size} apps) ===")
            apps.forEach { ai ->
                val name = try { pm.getApplicationLabel(ai).toString() } catch (_: Exception) { "?" }
                val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val version = try {
                    val pkgInfo = pm.getPackageInfo(ai.packageName, 0)
                    pkgInfo.versionName ?: "?"
                } catch (_: Exception) { "?" }
                val tag = if (isSystem) "[SYS]" else "[APP]"
                appendLine("  $tag $name (${ai.packageName}) v$version")
            }
            if (allApps.size > maxCount) {
                appendLine("  ... ${allApps.size - maxCount} more apps not shown")
            }
        }
    }

    // === 电池历史 ===

    /**
     * 获取电池详细信息
     */
    fun getBatteryHistory(): String {
        val batteryIntent = context.registerReceiver(null,
            android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val sb = StringBuilder("=== Battery Info ===\n")

        if (batteryIntent != null) {
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1

            val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val statusStr = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
                BatteryManager.BATTERY_STATUS_FULL -> "已充满"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
                else -> "未知"
            }

            val plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            val pluggedStr = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC 充电器"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线充电"
                else -> "未连接"
            }

            val temp = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            val voltage = batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            val technology = batteryIntent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)

            val health = batteryIntent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
            val healthStr = when (health) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
                BatteryManager.BATTERY_HEALTH_DEAD -> "已损坏"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "过压"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "故障"
                else -> "未知"
            }

            sb.appendLine("Level: $pct%")
            sb.appendLine("Status: $statusStr")
            sb.appendLine("Plugged: $pluggedStr")
            sb.appendLine("Health: $healthStr")
            sb.appendLine("Technology: $technology")
            if (temp >= 0) sb.appendLine("Temperature: ${temp / 10.0}°C")
            if (voltage >= 0) sb.appendLine("Voltage: ${voltage / 1000.0}V")
        } else {
            sb.appendLine("Unable to get battery info")
        }

        // 通过 Shizuku 获取电池历史
        if (ShizukuHelper.hasPermission()) {
            sb.appendLine()
            sb.appendLine("--- Battery History (via Shizuku) ---")
            val result = ShizukuHelper.execShell("dumpsys battery")
            if (result.isSuccess) {
                val output = result.getOrDefault("")
                // 提取关键信息
                output.lines().take(20).forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("Battery Historian")) {
                        sb.appendLine(trimmed)
                    }
                }
            }
        }

        return sb.toString()
    }

    // === 剪贴板 ===

    /**
     * 读取系统剪贴板内容
     */
    fun getClipboard(): String {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (!cm.hasPrimaryClip()) {
                return "Clipboard is empty."
            }
            val clip = cm.primaryClip ?: return "Clipboard is empty."
            if (clip.itemCount == 0) return "Clipboard is empty."

            val sb = StringBuilder("=== Clipboard ===\n")
            for (i in 0 until clip.itemCount) {
                val item = clip.getItemAt(i)
                val text = item.text?.toString() ?: ""
                val label = item.text?.toString()?.take(50) ?: ""
                if (text.isNotEmpty()) {
                    if (clip.itemCount > 1) sb.appendLine("[Item $i]")
                    sb.appendLine(text)
                    if (clip.itemCount > 1) sb.appendLine()
                }
            }
            if (sb.length == "=== Clipboard ===\n".length) {
                sb.appendLine("(clipboard has non-text content)")
            }
            sb.toString()
        } catch (e: Exception) {
            "Error reading clipboard: ${e.message}"
        }
    }

    /**
     * 写入内容到系统剪贴板
     */
    fun setClipboard(text: String): String {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("DayTrace", text))
            "Clipboard set to: ${text.take(200)}${if (text.length > 200) "..." else ""}"
        } catch (e: Exception) {
            "Error setting clipboard: ${e.message}"
        }
    }

    // === 发送通知 ===

    /**
     * 发送一条本地通知到手机
     *
     * @param title 通知标题
     * @param message 通知内容
     * @param priority 优先级: "low" / "default" / "high"
     */
    fun sendNotification(title: String, message: String, priority: String = "default"): String {
        return try {
            val pm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 创建通知渠道（仅首次创建，后续不可修改 importance）
            createNotificationChannel(pm)

            val importance = when (priority) {
                "high" -> NotificationManager.IMPORTANCE_HIGH
                "low" -> NotificationManager.IMPORTANCE_LOW
                else -> NotificationManager.IMPORTANCE_DEFAULT
            }

            // 每次发通知用不同的 ID
            val notifId = System.currentTimeMillis().toInt() and 0x7FFFFFFF

            val notification = Notification.Builder(context, NOTIF_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setAutoCancel(true)
                .build()

            pm.notify(notifId, notification)

            "Notification sent: $title (ID: $notifId, priority: $priority)"
        } catch (e: Exception) {
            "Error sending notification: ${e.message}"
        }
    }

    private fun createNotificationChannel(pm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                NOTIF_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "AI-generated alerts and reminders"
            }
            pm.createNotificationChannel(channel)
        }
    }

    // === 发送短信 ===

    /**
     * 通过 Shizuku 发送短信
     *
     * 使用 `am broadcast -a com.android.internal.intent.action.SMS_SEND` 方式，
     * 或者通过 Shizuku shell 执行 `service call isms` 方式。
     * 这是一种受限操作，需要 Shizuku 权限。
     *
     * 注意：此功能仅供 AI 在用户明确要求时使用。
     *
     * @param phoneNumber 目标手机号
     * @param message 短信内容
     */
    fun sendTextMessage(phoneNumber: String, message: String): String {
        if (!ShizukuHelper.hasPermission()) {
            return "Error: Shizuku permission not granted. Required for sending SMS."
        }

        // 尝试通过 SmsManager 直接发送（需要 SEND_SMS 权限）
        val hasPerm = context.checkSelfPermission(android.Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

        if (hasPerm) {
            return try {
                val sm = android.telephony.SmsManager.getDefault()
                val parts = sm.divideMessage(message)
                sm.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
                "SMS sent to $phoneNumber: ${message.take(100)}"
            } catch (e: Exception) {
                Log.e(TAG, "SmsManager failed: ${e.message}")
                "Error sending SMS via SmsManager: ${e.message}"
            }
        }

        // 没有 SEND_SMS 权限时，通过 Shizuku 授予权限后重试
        return try {
            ShizukuHelper.grantRuntimePermissions(context, listOf(android.Manifest.permission.SEND_SMS))
            // 再次检查
            val nowHasPerm = context.checkSelfPermission(android.Manifest.permission.SEND_SMS) ==
                PackageManager.PERMISSION_GRANTED
            if (nowHasPerm) {
                val sm = android.telephony.SmsManager.getDefault()
                val parts = sm.divideMessage(message)
                sm.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
                "SMS sent to $phoneNumber (via Shizuku-granted permission): ${message.take(100)}"
            } else {
                "Error: SEND_SMS permission could not be granted. Cannot send SMS."
            }
        } catch (e: Exception) {
            "Error sending SMS: ${e.message}"
        }
    }

    // === 工具方法 ===

    private fun getAppName(packageName: String): String {
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName
        }
    }
}
