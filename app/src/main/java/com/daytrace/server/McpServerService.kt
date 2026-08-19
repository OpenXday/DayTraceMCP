package com.daytrace.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.daytrace.util.Prefs

/**
 * 前台服务，持有 MCP Server 的生命周期
 *
 * Android 要求长时间运行的网络服务必须在前台运行（配以前台通知），
 * 否则系统会在后台杀掉进程。MCP Server 必须保持常驻才能让
 * Claude Desktop 等外部工具随时连接查询。
 */
class McpServerService : Service() {

    companion object {
        private const val TAG = "DayTrace/McpService"
        private const val CHANNEL_ID = "DayTrace_mcp"
        private const val NOTIF_ID = 9981

        @Volatile var isRunning = false
            private set

        @Volatile var mcpPort = 0
            private set

        fun start(context: Context) {
            val intent = Intent(context, McpServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, McpServerService::class.java))
        }
    }

    private var server: McpServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("MCP Server running on port ${Prefs.getMcpPort(this)}"))

        val port = Prefs.getMcpPort(this)
        server = McpServer(this, port)
        server?.startServer()
        isRunning = true
        mcpPort = server?.getPort() ?: port
        Log.i(TAG, "MCP Server foreground service started, port=$mcpPort")
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stopServer()
        server = null
        isRunning = false
        Log.i(TAG, "MCP Server foreground service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DayTrace MCP Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps MCP server running for external AI tools"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("DayTrace MCP")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("DayTrace MCP")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build()
        }
    }
}
