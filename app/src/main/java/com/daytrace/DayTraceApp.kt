package com.daytrace

import android.app.Application
import android.util.Log
import com.daytrace.data.MessageDatabase
import com.daytrace.server.McpServerService
import com.daytrace.util.Prefs

class DayTraceApp : Application() {

    val database: MessageDatabase by lazy { MessageDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()

        // 如果之前已开启 MCP server，App 启动时自动恢复
        if (Prefs.isMcpEnabled(this)) {
            try {
                McpServerService.start(this)
                Log.i("DayTraceApp", "Auto-started MCP server")
            } catch (e: Exception) {
                Log.e("DayTraceApp", "Failed to auto-start MCP server: ${e.message}")
            }
        }
    }
}
