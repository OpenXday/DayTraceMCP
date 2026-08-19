package com.daytrace.util

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val NAME = "DayTrace_prefs"
    private const val KEY_CAPTURE_ENABLED = "capture_enabled"
    private const val KEY_EXCLUDED_PACKAGES = "excluded_packages"

    // Webhook
    private const val KEY_WEBHOOK_ENABLED = "webhook_enabled"
    private const val KEY_WEBHOOK_URL = "webhook_url"
    private const val KEY_WEBHOOK_TOKEN = "webhook_token"
    private const val KEY_WEBHOOK_PER_MESSAGE = "webhook_per_message"  // 逐条推送（默认关闭）

    // MCP Server
    private const val KEY_MCP_ENABLED = "mcp_enabled"
    private const val KEY_MCP_PORT = "mcp_port"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isCaptureEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_CAPTURE_ENABLED, true)

    fun setCaptureEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_CAPTURE_ENABLED, enabled).apply()
    }

    /** 默认排除的包名 */
    private val DEFAULT_EXCLUDED = setOf(
        "com.android.systemui",
        "com.android.settings",
        "com.google.android.permissioncontroller"
    )

    fun getExcludedPackages(ctx: Context): Set<String> {
        return prefs(ctx).getStringSet(KEY_EXCLUDED_PACKAGES, DEFAULT_EXCLUDED) ?: DEFAULT_EXCLUDED
    }

    fun isExcluded(ctx: Context, pkg: String): Boolean {
        return getExcludedPackages(ctx).contains(pkg)
    }

    // === Webhook ===

    fun isWebhookEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_WEBHOOK_ENABLED, false)

    fun setWebhookEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_WEBHOOK_ENABLED, enabled).apply()
    }

    fun getWebhookUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_WEBHOOK_URL, "") ?: ""

    fun setWebhookUrl(ctx: Context, url: String) {
        prefs(ctx).edit().putString(KEY_WEBHOOK_URL, url).apply()
    }

    fun getWebhookToken(ctx: Context): String =
        prefs(ctx).getString(KEY_WEBHOOK_TOKEN, "") ?: ""

    fun setWebhookToken(ctx: Context, token: String) {
        prefs(ctx).edit().putString(KEY_WEBHOOK_TOKEN, token).apply()
    }

    /**
     * 是否逐条推送 Webhook（默认关闭）
     * 关闭时，Webhook 只在手动触发或定时摘要时推送
     */
    fun isWebhookPerMessageEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_WEBHOOK_PER_MESSAGE, false)

    fun setWebhookPerMessageEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_WEBHOOK_PER_MESSAGE, enabled).apply()
    }

    // === MCP Server ===

    fun isMcpEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_MCP_ENABLED, false)

    fun setMcpEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_MCP_ENABLED, enabled).apply()
    }

    fun getMcpPort(ctx: Context): Int =
        prefs(ctx).getInt(KEY_MCP_PORT, 9876)

    fun setMcpPort(ctx: Context, port: Int) {
        prefs(ctx).edit().putInt(KEY_MCP_PORT, port).apply()
    }
}
