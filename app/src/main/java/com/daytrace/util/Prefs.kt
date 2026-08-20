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
    private const val KEY_WEBHOOK_PER_MESSAGE = "webhook_per_message"

    // MCP Server
private const val KEY_MCP_ENABLED = "mcp_enabled"
private const val KEY_MCP_PORT = "mcp_port"
private const val KEY_MCP_AUTH_ENABLED = "mcp_auth_enabled"
private const val KEY_MCP_TOKEN = "mcp_token"

    // === Skill 驱动上传 ===
    private const val KEY_UPLOAD_ENABLED = "upload_enabled"
    private const val KEY_UPLOAD_SKILL_ID = "upload_skill_id"
    private const val KEY_UPLOAD_TIMES = "upload_times"
    private const val KEY_UPLOAD_AUTO_SCHEDULED = "upload_auto_scheduled"
    // 凭证存储前缀: "skill_cred_<skillId>_<credId>"
    private const val KEY_PREFIX_SKILL_CRED = "skill_cred"

    // === 后台可见性 ===
    private const val KEY_HIDE_FROM_RECENTS = "hide_from_recents"

    // === Cloudflare Tunnel ===
    private const val KEY_TUNNEL_ENABLED = "tunnel_enabled"
    private const val KEY_TUNNEL_URL = "tunnel_url"

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

    fun isMcpAuthEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_MCP_AUTH_ENABLED, false)

    fun setMcpAuthEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_MCP_AUTH_ENABLED, enabled).apply()
    }

    fun getMcpToken(ctx: Context): String =
        prefs(ctx).getString(KEY_MCP_TOKEN, "") ?: ""

    fun setMcpToken(ctx: Context, token: String) {
        prefs(ctx).edit().putString(KEY_MCP_TOKEN, token).apply()
    }

    // === Skill 驱动上传 ===

    fun isUploadEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_UPLOAD_ENABLED, false)

    fun setUploadEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_UPLOAD_ENABLED, enabled).apply()
    }

    fun getUploadSkillId(ctx: Context): String =
        prefs(ctx).getString(KEY_UPLOAD_SKILL_ID, "biji") ?: "biji"

    fun setUploadSkillId(ctx: Context, skillId: String) {
        prefs(ctx).edit().putString(KEY_UPLOAD_SKILL_ID, skillId).apply()
    }

    /** 获取某个 Skill 的所有凭证值 */
    fun getSkillCredentials(ctx: Context, skillId: String): Map<String, String> {
        val prefix = "${KEY_PREFIX_SKILL_CRED}_${skillId}_"
        val result = mutableMapOf<String, String>()
        for ((key, value) in prefs(ctx).all) {
            if (key.startsWith(prefix)) {
                val credId = key.removePrefix(prefix)
                result[credId] = value.toString()
            }
        }
        return result
    }

    /** 设置某个 Skill 的某个凭证值 */
    fun setSkillCredential(ctx: Context, skillId: String, credId: String, value: String) {
        val key = "${KEY_PREFIX_SKILL_CRED}_${skillId}_$credId"
        prefs(ctx).edit().putString(key, value).apply()
    }

    /** 定时上传时间点列表 */
    fun getUploadTimes(ctx: Context): List<String> {
        val raw = prefs(ctx).getString(KEY_UPLOAD_TIMES, "09:00,12:00,18:00,23:00") ?: "09:00,12:00,18:00,23:00"
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun setUploadTimes(ctx: Context, times: List<String>) {
        prefs(ctx).edit().putString(KEY_UPLOAD_TIMES, times.joinToString(",")).apply()
    }

    fun isUploadAutoScheduled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_UPLOAD_AUTO_SCHEDULED, false)

    fun setUploadAutoScheduled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_UPLOAD_AUTO_SCHEDULED, enabled).apply()
    }

    // === 通用 Long 存取 ===

    fun getLong(ctx: Context, key: String, default: Long = 0L): Long =
        prefs(ctx).getLong(key, default)

    fun putLong(ctx: Context, key: String, value: Long) {
        prefs(ctx).edit().putLong(key, value).apply()
    }

    // === 通用 String 存取 ===

    fun getString(ctx: Context, key: String, default: String = ""): String =
        prefs(ctx).getString(key, default) ?: default

    fun putString(ctx: Context, key: String, value: String) {
        prefs(ctx).edit().putString(key, value).apply()
    }

    // === 后台可见性 ===

    /** 是否从最近任务列表中隐藏（默认 false = 显示） */
    fun isHideFromRecents(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_HIDE_FROM_RECENTS, false)

    fun setHideFromRecents(ctx: Context, hidden: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_HIDE_FROM_RECENTS, hidden).apply()
    }

    // === Cloudflare Tunnel ===

    /** 是否启用 Cloudflare Tunnel（trycloudflare） */
    fun isTunnelEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_TUNNEL_ENABLED, false)

    fun setTunnelEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_TUNNEL_ENABLED, enabled).apply()
    }

    /** 获取上次保存的 Tunnel 公网 URL */
    fun getTunnelUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_TUNNEL_URL, "") ?: ""

    fun setTunnelUrl(ctx: Context, url: String) {
        prefs(ctx).edit().putString(KEY_TUNNEL_URL, url).apply()
    }
}
