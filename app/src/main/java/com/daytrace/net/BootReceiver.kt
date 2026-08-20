package com.daytrace.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.daytrace.server.McpServerService
import com.daytrace.util.Prefs

/**
 * 开机/更新后自启动
 *
 * - BOOT_COMPLETED: 手机重启后恢复定时上传
 * - MY_PACKAGE_REPLACED: App 更新后恢复定时上传
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DayTrace/Boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "Received: ${intent.action}")

                // 恢复定时上传
                if (Prefs.isUploadAutoScheduled(context)) {
                    UploadScheduler.scheduleAll(context)
                    Log.i(TAG, "Upload scheduler restored")
                }

                // 恢复 MCP Server
                if (Prefs.isMcpEnabled(context)) {
                    try {
                        McpServerService.start(context)
                        Log.i(TAG, "MCP Server restored")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restore MCP server: ${e.message}")
                    }
                }
            }
        }
    }
}
