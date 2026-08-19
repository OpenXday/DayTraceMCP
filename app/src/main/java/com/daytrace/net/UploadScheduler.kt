package com.daytrace.net

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.daytrace.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 定时上传调度器
 *
 * 使用 AlarmManager 精确定时，在用户指定的时间点触发差分上传。
 *
 * 工作流程：
 * 1. 用户在设置中配置上传时间点（如 "09:00,12:00,18:00,23:00"）
 * 2. 开启定时上传后，为每个时间点设置 AlarmManager 定时任务
 * 3. 到达时间点后，UploadReceiver 收到广播，触发 DiffUploader.uploadDayDiff()
 * 4. 每次上传都是差分内容（只上传自上次上传以来的新增部分）
 *
 * AlarmManager 的优势：
 * - 即使 App 被杀死，系统也会唤醒
 * - 精确定时（比 Handler/Coroutine 更可靠）
 * - 适配 Doze 模式（使用 setExactAndAllowWhileIdle）
 */
object UploadScheduler {

    private const val TAG = "DayTrace/UploadSched"
    private const val REQUEST_CODE_BASE = 8000  // PendingIntent 请求码基数

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 设置所有定时上传任务
     * 根据用户配置的时间点列表，为每个时间点设置 AlarmManager
     */
    fun scheduleAll(context: Context) {
        if (!Prefs.isUploadAutoScheduled(context)) {
            cancelAll(context)
            return
        }

        val times = Prefs.getUploadTimes(context)
        if (times.isEmpty()) {
            Log.w(TAG, "No upload times configured")
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        times.forEachIndexed { index, timeStr ->
            try {
                val (hour, minute) = parseTime(timeStr)
                val triggerAt = getNextTriggerTime(hour, minute)

                val intent = createIntent(context, index)
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODE_BASE + index,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                // 使用 setExactAndAllowWhileIdle 确保在 Doze 模式下也能触发
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                } else {
                    alarmManager.setRepeating(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        AlarmManager.INTERVAL_DAY,
                        pendingIntent
                    )
                }

                val triggerStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                    .format(Date(triggerAt))
                Log.i(TAG, "Scheduled upload #$index at $timeStr -> next: $triggerStr")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule upload at $timeStr: ${e.message}")
            }
        }

        Log.i(TAG, "Scheduled ${times.size} upload tasks")
    }

    /**
     * 取消所有定时上传任务
     */
    fun cancelAll(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val times = Prefs.getUploadTimes(context)

        times.forEachIndexed { index, _ ->
            val intent = createIntent(context, index)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_BASE + index,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
        }

        Log.i(TAG, "Cancelled all upload tasks")
    }

    /**
     * 手动触发一次上传（用于测试或即时上传）
     */
    fun triggerManualUpload(context: Context) {
        scope.launch {
            val result = DiffUploader.uploadDayDiff(context)
            Log.i(TAG, "Manual upload result: ${result.message}")
        }
    }

    private fun createIntent(context: Context, index: Int): Intent {
        return Intent(context, UploadReceiver::class.java).apply {
            action = "com.daytrace.UPLOAD_TIMER"
            putExtra("time_index", index)
        }
    }

    /**
     * 计算下一次触发时间戳
     * 如果今天的这个时间已过，则设为明天同一时间
     */
    private fun getNextTriggerTime(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance(Locale.CHINA).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // 如果该时间今天已过，设为明天
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }

        return cal.timeInMillis
    }

    private fun parseTime(timeStr: String): Pair<Int, Int> {
        val parts = timeStr.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 9
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return hour.coerceIn(0, 23) to minute.coerceIn(0, 59)
    }
}

/**
 * 定时上传广播接收器
 *
 * AlarmManager 到达指定时间后发送广播，此类接收并触发上传
 */
class UploadReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DayTrace/UploadRecv"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.daytrace.UPLOAD_TIMER") return

        val timeIndex = intent.getIntExtra("time_index", -1)
        Log.i(TAG, "Upload timer fired: index=$timeIndex at ${System.currentTimeMillis()}")

        // 延迟 5 秒后执行上传（避免系统唤醒延迟）
        scope.launch {
            kotlinx.coroutines.delay(5000)

            val result = DiffUploader.uploadDayDiff(context)
            Log.i(TAG, "Upload result: ${result.message}")

            // 重新设置下一次定时（因为 setExactAndAllowWhileIdle 不会自动重复）
            UploadScheduler.scheduleAll(context)
        }
    }
}
