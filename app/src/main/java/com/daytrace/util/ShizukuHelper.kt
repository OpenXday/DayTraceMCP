package com.daytrace.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shizuku 工具类
 *
 * 核心用途：以 shell 权限执行 `settings put secure` 命令，
 * 一键开启无障碍服务和通知监听权限，无需用户手动翻设置。
 *
 * 前提：手机上已安装并启动 Shizuku（通过无线调试或ADB启动）。
 *
 * 注意：Android 14+ 上 shell 用户写入 enabled_accessibility_services 后，
 * 系统会异步验证服务配置。如果服务刚安装/更新，可能需要手动确认。
 */
object ShizukuHelper {

    private const val TAG = "DayTrace/Shizuku"
    private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001

    /** Shizuku 是否已在运行 */
    fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    /** 是否已获得 Shizuku 授权 */
    fun hasPermission(): Boolean {
        return if (isShizukuRunning()) {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else false
    }

    /** 请求 Shizuku 权限（会弹系统对话框） */
    fun requestPermission() {
        if (isShizukuRunning() && !hasPermission()) {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        }
    }

    /**
     * 通过 Shizuku 执行 shell 命令（以 shell 身份）
     *
     * Shizuku 13.x SDK 中 newProcess 是公开 static 方法:
     *   public static Process newProcess(String[] cmd, String[] env, String dir)
     *
     * 注意：此方法会阻塞等待命令完成。对于 `nohup ... &` 等后台命令，
     * 请使用 [execShellAsync] 避免阻塞。
     */
    fun execShell(command: String): Result<String> {
        return try {
            if (!hasPermission()) {
                Log.e(TAG, "execShell: permission not granted")
                return Result.failure(IllegalStateException("Shizuku permission not granted"))
            }

            Log.d(TAG, "execShell: $command")

            val process = try {
                val method = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                method.isAccessible = true
                method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            } catch (e1: NoSuchMethodException) {
                Log.w(TAG, "newProcess via reflection failed, trying alternative")
                tryAlternativeExec(command) ?: run {
                    Log.e(TAG, "All exec methods failed")
                    return Result.failure(RuntimeException("Cannot execute shell command via Shizuku"))
                }
            }

            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            Log.d(TAG, "execShell result: exit=$exitCode, out=${output.take(200)}, err=${error.take(200)}")

            if (exitCode == 0) {
                Result.success(output.trim())
            } else {
                Result.failure(RuntimeException("Exit $exitCode: $error"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "execShell failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 通过 Shizuku 执行后台 shell 命令（不阻塞等待）
     *
     * 专门用于 `nohup ... &` 等后台启动命令。
     * 不会等待进程完成，立即返回。输出由调用者通过日志文件读取。
     *
     * 实现方式：将命令包装在 `sh -c 'cmd; echo DONE_$?'` 中，
     * 并通过 `execShell` 执行——确保 shell 本身会立即退出。
     */
    fun execShellAsync(command: String): Result<String> {
        // 用 execShell 执行后台命令，但确保 shell 立即退出
        // 注意：`&` 后面不能跟 `;`（shell 语法错误），用换行符分隔
        val wrappedCmd = "$command\necho \"STARTED:$?\""
        return execShell(wrappedCmd)
    }

    /**
     * 备用方案：通过 Shizuku binder 直接调用
     */
    private fun tryAlternativeExec(command: String): Process? {
        return try {
            val binderField = Shizuku::class.java.getDeclaredField("binder")
            binderField.isAccessible = true
            val binder = binderField.get(null) as? IBinder

            if (binder != null) {
                val serviceStubClass = Class.forName("rikka.shizuku.server.IShizukuService\$Stub")
                val asInterfaceMethod = serviceStubClass.getDeclaredMethod("asInterface", IBinder::class.java)
                val service = asInterfaceMethod.invoke(null, binder)

                val newProcessMethod = serviceStubClass.getMethod("newProcess",
                    Array<String>::class.java, Array<String>::class.java, String::class.java)
                newProcessMethod.invoke(service, arrayOf("sh", "-c", command), null, null) as? Process
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "tryAlternativeExec failed: ${e.message}")
            null
        }
    }

    /**
     * 一键开启无障碍服务
     *
     * 策略：
     * 1. 先用 Shizuku shell 执行 settings put
     * 2. 验证是否成功
     * 3. 如果验证失败（Android 14+ 可能拒绝 shell 写入 accessibility），
     *    返回特殊错误，UI 层引导用户手动开启
     */
    fun enableAccessibilityService(context: Context, serviceClass: Class<*>): Result<Unit> {
        val component = ComponentName(context, serviceClass)
        val serviceString = component.flattenToString()

        val current = execShell("settings get secure enabled_accessibility_services")
            .getOrDefault("")
            .trim()

        Log.i(TAG, "enableAccessibilityService: current=$current")
        Log.i(TAG, "enableAccessibilityService: adding=$serviceString")

        // 如果已经在列表中，直接返回成功
        if (current.contains(serviceString)) {
            Log.i(TAG, "enableAccessibilityService: already enabled")
            // 确保 accessibility_enabled = 1
            execShell("settings put secure accessibility_enabled 1")
            return Result.success(Unit)
        }

        val serviceList = if (current.isNotEmpty() && current != "null") {
            "$current:$serviceString"
        } else {
            serviceString
        }

        // 执行 settings put
        val cmd = "settings put secure enabled_accessibility_services '$serviceList'"
        Log.i(TAG, "enableAccessibilityService: cmd=$cmd")

        val result = execShell(cmd)
        if (result.isFailure) {
            Log.e(TAG, "enableAccessibilityService: settings put failed: ${result.exceptionOrNull()?.message}")
            return Result.failure(result.exceptionOrNull()!!)
        }

        // 确保 accessibility_enabled = 1
        execShell("settings put secure accessibility_enabled 1")

        // 验证是否成功写入
        Thread.sleep(500)  // 给系统一点时间处理
        val verify = execShell("settings get secure enabled_accessibility_services")
            .getOrDefault("")
            .trim()
        Log.i(TAG, "enableAccessibilityService: verify=$verify")

        return if (verify.contains(serviceString)) {
            Log.i(TAG, "enableAccessibilityService: SUCCESS")
            Result.success(Unit)
        } else {
            // Shizuku shell 写入后被系统移除了
            // 这在 Android 14+ 上会发生在某些设备上
            Log.w(TAG, "enableAccessibilityService: settings put succeeded but system removed the entry")
            Log.w(TAG, "enableAccessibilityService: this is likely due to Android 14+ security restrictions")
            Result.failure(AccessibilityAutoEnableException())
        }
    }

    /**
     * 特殊异常：Shizuku 写入无障碍设置后系统移除了条目
     * UI 层应捕获此异常并引导用户手动开启
     */
    class AccessibilityAutoEnableException : Exception(
        "系统安全限制无法自动开启无障碍服务，请手动开启"
    )

    /**
     * 一键开启通知监听权限
     */
    fun enableNotificationListener(context: Context, serviceClass: Class<*>): Result<Unit> {
        val component = ComponentName(context, serviceClass)
        val serviceString = component.flattenToString()

        val current = execShell("settings get secure enabled_notification_listeners")
            .getOrDefault("")
            .trim()

        Log.i(TAG, "enableNotificationListener: current=$current")

        if (current.contains(serviceString)) {
            Log.i(TAG, "enableNotificationListener: already enabled")
            return Result.success(Unit)
        }

        val serviceList = if (current.isNotEmpty() && current != "null") {
            "$current:$serviceString"
        } else {
            serviceString
        }

        val cmd = "settings put secure enabled_notification_listeners '$serviceList'"
        Log.i(TAG, "enableNotificationListener: cmd=$cmd")

        val result = execShell(cmd)
        if (result.isFailure) {
            Log.e(TAG, "enableNotificationListener: failed: ${result.exceptionOrNull()?.message}")
            return Result.failure(result.exceptionOrNull()!!)
        }

        // 验证
        val verify = execShell("settings get secure enabled_notification_listeners")
            .getOrDefault("")
            .trim()
        Log.i(TAG, "enableNotificationListener: verify=$verify")

        return if (verify.contains(serviceString)) {
            Log.i(TAG, "enableNotificationListener: SUCCESS")
            Result.success(Unit)
        } else {
            Log.e(TAG, "enableNotificationListener: verification failed")
            Result.failure(RuntimeException("Service not found in notification listener list after enable"))
        }
    }

    /** 检查无障碍服务是否已启用 */
    fun isAccessibilityEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val expected = ComponentName(context, serviceClass).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(expected)
    }

    /** 检查通知监听是否已启用 */
    fun isNotificationListenerEnabled(context: Context): Boolean {
        val flat = ComponentName(context, com.daytrace.service.NotificationCaptureService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabled.contains(flat)
    }
}
