package com.daytrace.server

import android.content.Context
import android.util.Log
import com.daytrace.util.Prefs
import com.daytrace.util.ShizukuHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.FileOutputStream
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Cloudflare Quick Tunnel (trycloudflare) 管理器
 *
 * 在手机上运行 cloudflared 二进制文件，创建一个临时隧道，
 * 将本地 MCP Server 端口暴露到公网（https://xxx.trycloudflare.com）。
 *
 * Android 10+ (API 29+) 禁止 App 直接 execve ELF 二进制文件，
 * 因此 cloudflared 必须通过 Shizuku 以 shell 权限运行。
 *
 * == DNS 问题解决方案 ==
 *
 * cloudflared 是 Go 静态编译（CGO_ENABLED=0），只能用纯 Go DNS resolver。
 * Android 上 /etc/resolv.conf 不存在，Go fallback 到 [::1]:53，导致 connection refused。
 * 非 root 设备不能绑定 53 端口、不能改 resolv.conf、不能用 iptables。
 *
 * 方案：上层 App 完成所有域名解析，通过 --edge IP + credentials-file 规避 cloudflared 内部 DNS：
 * 1. App 通过 OkHttp 调用 POST https://api.trycloudflare.com/tunnel 创建 quick tunnel（App 有正常 DNS）
 * 2. App 通过 Java InetAddress 解析 region1.argotunnel.com 获取 Edge IP
 * 3. 构造 credentials JSON 文件 + YAML 配置文件（含 edge IP）
 * 4. 通过 Shizuku 启动 `cloudflared tunnel --config <yml> run`
 *    配置中的 edge 字段让 cloudflared 直接连 IP，不需要 DNS
 */
object CloudflareTunnelManager {

    private const val TAG = "DayTrace/Tunnel"
    private const val BINARY_NAME = "cloudflared"
    private const val MAX_URL_WAIT_MS = 60_000L
    private const val SHELL_BINARY_PATH = "/data/local/tmp/cloudflared"
    private const val SHELL_CRED_PATH = "/data/local/tmp/daytrace_cred.json"
    private const val SHELL_CONFIG_PATH = "/data/local/tmp/daytrace_config.yml"
    private const val SHELL_LOG_PATH = "/data/local/tmp/daytrace_cloudflared.log"

    private const val CLOUDFLARED_VERSION = "2026.8.2"

    // Cloudflare Edge 域名（用于 App 解析 IP）
    private const val EDGE_DOMAIN = "region1.argotunnel.com"
    private const val EDGE_PORT = 7844

    private val ABI_TO_GITHUB_ASSET = mapOf(
        "arm64-v8a" to "cloudflared-linux-arm64",
        "armeabi-v7a" to "cloudflared-linux-arm",
        "x86_64" to "cloudflared-linux-amd64",
        "x86" to "cloudflared-linux-386"
    )

    fun getExpectedAssetName(): String? {
        val abis = android.os.Build.SUPPORTED_ABIS
        for (abi in abis) {
            ABI_TO_GITHUB_ASSET[abi]?.let { return it }
        }
        return null
    }

    fun getDeviceAbi(): String {
        val abis = android.os.Build.SUPPORTED_ABIS
        for (abi in abis) {
            if (ABI_TO_GITHUB_ASSET.containsKey(abi)) return abi
        }
        return abis.firstOrNull() ?: "unknown"
    }

    fun getDownloadUrl(): String? {
        val asset = getExpectedAssetName() ?: return null
        return "https://github.com/cloudflare/cloudflared/releases/download/$CLOUDFLARED_VERSION/$asset"
    }

    fun getBinaryFile(context: Context): java.io.File {
        // 使用外部存储目录，shell 用户（Shizuku）可以读取
        // 内部存储 filesDir 对 shell 用户不可读
        return java.io.File(context.getExternalFilesDir(null), "bin/$BINARY_NAME")
    }

    /** Shizuku 是否可用（已运行 + 已授权） */
    fun isShizukuAvailable(): Boolean {
        return ShizukuHelper.isShizukuRunning() && ShizukuHelper.hasPermission()
    }

    /** 二进制是否已下载且可执行 */
    fun isBinaryReady(context: Context): Boolean {
        val file = getBinaryFile(context)
        return file.exists() && file.length() > 1_000_000
    }

    /**
     * 从 GitHub 下载 cloudflared 二进制
     * 下载后通过 Shizuku 设置可执行权限
     */
    suspend fun downloadBinary(
        context: Context,
        onProgress: ((Int) -> Unit)? = null
    ): Result<java.io.File> = withContext(Dispatchers.IO) {
        val url = getDownloadUrl()
        if (url == null) {
            return@withContext Result.failure(RuntimeException("不支持当前设备架构: ${android.os.Build.SUPPORTED_ABIS.joinToString()}"))
        }

        val targetFile = getBinaryFile(context)
        targetFile.parentFile?.mkdirs()

        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()

            Log.i(TAG, "Downloading cloudflared from $url")

            val request = Request.Builder().url(url).get()
                .header("User-Agent", "DayTrace/0.3")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(RuntimeException("下载失败: HTTP ${response.code}"))
                }

                val body = response.body ?: return@withContext Result.failure(RuntimeException("响应体为空"))
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L

                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (true) {
                            bytesRead = input.read(buffer)
                            if (bytesRead == -1) break
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalBytes > 0 && onProgress != null) {
                                val percent = ((downloadedBytes * 100) / totalBytes).toInt()
                                onProgress(percent)
                            }
                        }
                    }
                }

                // 通过 Shizuku 设置可执行权限（Android 10+ 不允许 App 直接 chmod）
                val chmodResult = ShizukuHelper.execShell("chmod 755 '${targetFile.absolutePath}'")
                if (chmodResult.isFailure) {
                    Log.w(TAG, "chmod via Shizuku failed: ${chmodResult.exceptionOrNull()?.message}")
                    // 也尝试用 Java API（可能在旧版本 Android 上有效）
                    if (!targetFile.setExecutable(true, true)) {
                        Log.w(TAG, "setExecutable also failed")
                    }
                }

                Log.i(TAG, "Downloaded cloudflared: ${targetFile.length()} bytes -> ${targetFile.absolutePath}")
                onProgress?.invoke(100)
                Result.success(targetFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            if (targetFile.exists() && targetFile.length() < 1_000_000) {
                targetFile.delete()
            }
            Result.failure(e)
        }
    }

    fun deleteBinary(context: Context): Boolean {
        val file = getBinaryFile(context)
        return file.delete()
    }

    // === Tunnel 状态 ===

    @Volatile
    var isRunning = false
        private set

    @Volatile
    var tunnelUrl: String = ""
        private set

    @Volatile
    var lastError: String? = null
        private set

    // 存储 quick tunnel API 返回的 hostname
    @Volatile
    private var tunnelHostname: String = ""

    /**
     * 启动 Cloudflare Tunnel
     *
     * 流程：
     * 1. 检查 Shizuku + 二进制
     * 2. App 调用 Cloudflare API 创建 quick tunnel（App 有正常 DNS）
     * 3. App 解析 Edge IP（region1.argotunnel.com）
     * 4. 构造 credentials JSON + YAML 配置文件
     * 5. 通过 Shizuku 后台启动 cloudflared tunnel --config <yml> run
     * 6. 轮询日志确认连接成功
     */
    suspend fun start(context: Context, localPort: Int): Boolean = withContext(Dispatchers.IO) {
        if (isRunning) {
            Log.w(TAG, "Tunnel already running, stopping first...")
            stop()
        }

        val ctx = context.applicationContext

        // 1. 检查 Shizuku
        if (!isShizukuAvailable()) {
            lastError = "需要 Shizuku 运行并授权才能启动隧道"
            return@withContext false
        }

        // 2. 检查二进制
        if (!isBinaryReady(ctx)) {
            lastError = "cloudflared 二进制未就绪，请先下载"
            return@withContext false
        }

        val binaryFile = getBinaryFile(ctx)
        val shellBinaryPath = SHELL_BINARY_PATH

        Log.i(TAG, "Starting cloudflared tunnel for localhost:$localPort via Shizuku")

        try {
            // 3. 先杀掉残留的 cloudflared 进程，避免 "Text file busy" 错误
            ShizukuHelper.execShell("pkill -9 -f cloudflared 2>/dev/null; sleep 1; rm -f '$shellBinaryPath' 2>/dev/null")
            // 短暂等待文件锁释放
            Thread.sleep(500)

            // 复制二进制到 /data/local/tmp/ 并设置可执行权限
            val copyCmd = "cp '${binaryFile.absolutePath}' '$shellBinaryPath' && chmod 755 '$shellBinaryPath'"
            val copyResult = ShizukuHelper.execShell(copyCmd)
            if (copyResult.isFailure) {
                // 如果仍然失败，尝试用 cat 方式复制（绕过 Text file busy）
                val catResult = ShizukuHelper.execShell("cat '${binaryFile.absolutePath}' > '$shellBinaryPath' && chmod 755 '$shellBinaryPath'")
                if (catResult.isFailure) {
                    lastError = "复制 cloudflared 到 /data/local/tmp/ 失败: ${copyResult.exceptionOrNull()?.message}"
                    Log.e(TAG, lastError!!)
                    return@withContext false
                }
            }
            Log.i(TAG, "Copied cloudflared to $shellBinaryPath")

            // 4. 创建 Quick Tunnel — App 调用 Cloudflare API（App 有正常 DNS 解析能力）
            Log.i(TAG, "Creating quick tunnel via Cloudflare API...")
            val tunnelInfo = createQuickTunnel()
            if (tunnelInfo == null) {
                lastError = "创建 Quick Tunnel 失败: ${lastError ?: "未知错误"}"
                Log.e(TAG, lastError!!)
                return@withContext false
            }

            tunnelUrl = "https://${tunnelInfo.hostname}"
            tunnelHostname = tunnelInfo.hostname
            Log.i(TAG, "Quick tunnel created: $tunnelUrl (id=${tunnelInfo.tunnelId})")

            // 5. 解析 Cloudflare Edge IP（App 有正常 DNS）
            Log.i(TAG, "Resolving Edge IPs for $EDGE_DOMAIN...")
            val edgeIps = resolveEdgeIps()
            if (edgeIps.isEmpty()) {
                lastError = "无法解析 Cloudflare Edge IP ($EDGE_DOMAIN)"
                Log.e(TAG, lastError!!)
                return@withContext false
            }
            Log.i(TAG, "Edge IPs: $edgeIps")

            // 6. 构造 credentials JSON 文件
            val credJson = JSONObject().apply {
                put("AccountTag", tunnelInfo.accountTag)
                put("TunnelSecret", tunnelInfo.secret)
                put("TunnelID", tunnelInfo.tunnelId)
            }.toString()
            // 写入 App 可写目录，再通过 Shizuku cp 到 /data/local/tmp/
            val tmpCredFile = java.io.File(ctx.getExternalFilesDir(null), "daytrace_cred.json")
            FileOutputStream(tmpCredFile).use { it.write(credJson.toByteArray()) }
            ShizukuHelper.execShell("cp '${tmpCredFile.absolutePath}' '$SHELL_CRED_PATH' && chmod 644 '$SHELL_CRED_PATH'")
            Log.i(TAG, "Credentials file written to $SHELL_CRED_PATH")

            // 7. 构造 YAML 配置文件
            val edgeLines = edgeIps.joinToString("\n") { "  - $it:$EDGE_PORT" }
            val yamlConfig = buildString {
                append("tunnel: ${tunnelInfo.tunnelId}\n")
                append("credentials-file: $SHELL_CRED_PATH\n")
                append("edge:\n")
                append(edgeLines).append("\n")
                append("url: http://localhost:$localPort\n")
                append("no-autoupdate: true\n")
            }
            val tmpConfigFile = java.io.File(ctx.getExternalFilesDir(null), "daytrace_config.yml")
            FileOutputStream(tmpConfigFile).use { it.write(yamlConfig.toByteArray()) }
            ShizukuHelper.execShell("cp '${tmpConfigFile.absolutePath}' '$SHELL_CONFIG_PATH' && chmod 644 '$SHELL_CONFIG_PATH'")
            Log.i(TAG, "Config file written to $SHELL_CONFIG_PATH")

            // 8. 清空旧日志
            ShizukuHelper.execShell("rm -f '$SHELL_LOG_PATH'")

            // 9. 通过 Shizuku 后台启动 cloudflared
            //    使用 --no-prechecks 跳过连通性检查（那些检查也需要 DNS）
            //    GODEBUG=netdns=go 强制纯 Go resolver（但 edge IP 会直接使用，不需要 DNS）
            val shellCmd = "GODEBUG=netdns=go nohup '$shellBinaryPath' tunnel --no-autoupdate --no-prechecks --config $SHELL_CONFIG_PATH run > '$SHELL_LOG_PATH' 2>&1 &"
            Log.d(TAG, "Shell cmd: $shellCmd")

            val startResult = ShizukuHelper.execShellAsync(shellCmd)
            if (startResult.isFailure) {
                lastError = "Shizuku 执行失败: ${startResult.exceptionOrNull()?.message}"
                Log.e(TAG, lastError!!)
                return@withContext false
            }

            val startOutput = startResult.getOrDefault("")
            Log.i(TAG, "Start result: $startOutput")
            if (!startOutput.contains("STARTED:0")) {
                lastError = "后台启动命令失败: $startOutput"
                Log.e(TAG, lastError!!)
                return@withContext false
            }

            isRunning = true
            lastError = null

            // 10. 轮询日志确认连接成功
            val startWait = System.currentTimeMillis()
            var lastLogContent = ""
            while (System.currentTimeMillis() - startWait < MAX_URL_WAIT_MS) {
                delay(2000)

                val catResult = ShizukuHelper.execShell("cat '$SHELL_LOG_PATH' 2>/dev/null")
                val logContent = catResult.getOrDefault("")
                if (logContent.isNotEmpty()) {
                    lastLogContent = logContent
                    Log.d(TAG, "Log: ${logContent.take(500)}")

                    // 检测 "Registered tunnel connection" 表示连接成功
                    if (logContent.contains("Registered tunnel connection")) {
                        val registeredCount = Regex("Registered tunnel connection").findAll(logContent).count()
                        Log.i(TAG, "Tunnel connected! ($registeredCount connections registered)")
                        Prefs.setTunnelUrl(ctx, tunnelUrl)
                        return@withContext true
                    }

                    // 检测致命错误
                    val fatalError = logContent.lines().find {
                        it.contains("FATAL", ignoreCase = true) ||
                        it.contains("no more connections active and exiting", ignoreCase = true)
                    }
                    if (fatalError != null) {
                        lastError = fatalError.trim()
                        Log.e(TAG, "Fatal error: $lastError")
                        isRunning = false
                        return@withContext false
                    }
                }

                // 检查进程是否还在运行
                if (System.currentTimeMillis() - startWait > 5000) {
                    val pgrepResult = ShizukuHelper.execShell("pgrep -f cloudflared")
                    if (pgrepResult.isFailure) {
                        lastError = if (lastLogContent.isNotEmpty()) {
                            lastLogContent.lines().lastOrNull { it.isNotBlank() } ?: "cloudflared 进程意外退出"
                        } else {
                            "cloudflared 进程意外退出（日志为空）"
                        }
                        Log.e(TAG, "Process exited: $lastError")
                        isRunning = false
                        return@withContext false
                    }
                }
            }

            // 超时
            lastError = if (lastLogContent.isNotEmpty()) {
                "等待 Tunnel 连接超时（60秒）。日志最后内容: ${lastLogContent.take(200)}"
            } else {
                "等待 Tunnel 连接超时（60秒），日志文件为空"
            }
            Log.w(TAG, lastError!!)
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start tunnel: ${e.message}", e)
            lastError = "启动失败: ${e.message}"
            isRunning = false
            return@withContext false
        }
    }

    /**
     * 通过 Cloudflare API 创建 Quick Tunnel
     * App 有正常的 DNS 解析能力，可以直接调用 API
     *
     * POST https://api.trycloudflare.com/tunnel
     * 返回: { "success": true, "result": { "id", "name", "hostname", "account_tag", "secret" } }
     */
    private suspend fun createQuickTunnel(): QuickTunnelInfo? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("https://api.trycloudflare.com/tunnel")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "DayTrace/0.3")
                .header("Content-Type", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    lastError = "Cloudflare API 返回 HTTP ${response.code}"
                    Log.e(TAG, lastError!!)
                    return@withContext null
                }

                val body = response.body?.string()
                if (body.isNullOrEmpty()) {
                    lastError = "Cloudflare API 返回空响应"
                    return@withContext null
                }

                Log.d(TAG, "API response: ${body.take(500)}")
                val json = JSONObject(body)

                if (!json.optBoolean("success", false)) {
                    val errors = json.optJSONArray("errors")
                    lastError = if (errors != null && errors.length() > 0) {
                        "Cloudflare API 错误: ${errors.getJSONObject(0).optString("message", "未知")}"
                    } else {
                        "Cloudflare API 返回 success=false"
                    }
                    return@withContext null
                }

                val result = json.getJSONObject("result")
                QuickTunnelInfo(
                    tunnelId = result.getString("id"),
                    hostname = result.getString("hostname"),
                    accountTag = result.getString("account_tag"),
                    secret = result.getString("secret")
                )
            }
        } catch (e: Exception) {
            lastError = "调用 Cloudflare API 失败: ${e.message}"
            Log.e(TAG, lastError!!, e)
            null
        }
    }

    /**
     * 解析 Cloudflare Edge IP 地址
     * App 通过 Java InetAddress 解析 region1.argotunnel.com
     */
    private fun resolveEdgeIps(): List<String> {
        return try {
            val addresses = InetAddress.getAllByName(EDGE_DOMAIN)
            addresses.map { it.hostAddress }
                .filter { it != null && it.contains(".") }  // 只用 IPv4
                .distinct()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve Edge IPs: ${e.message}", e)
            emptyList()
        }
    }

    /** 停止 Cloudflare Tunnel */
    fun stop() {
        // 通过 Shizuku kill cloudflared 进程（SIGKILL 确保杀掉）
        if (isShizukuAvailable()) {
            try {
                ShizukuHelper.execShell("pkill -9 -f cloudflared 2>/dev/null")
                Log.i(TAG, "Killed cloudflared via Shizuku pkill -9")
                // 等待文件锁释放
                Thread.sleep(500)
            } catch (e: Exception) {
                Log.w(TAG, "pkill cloudflared failed: ${e.message}")
            }
        }

        // 清理临时文件
        if (isShizukuAvailable()) {
            try {
                ShizukuHelper.execShell("rm -f '$SHELL_CRED_PATH' '$SHELL_CONFIG_PATH' '$SHELL_BINARY_PATH'")
            } catch (e: Exception) {
                // 忽略
            }
        }

        isRunning = false
        tunnelUrl = ""
        tunnelHostname = ""
    }

    fun isSupported(): Boolean {
        return getExpectedAssetName() != null
    }

    /** 获取 cloudflared 版本（通过 Shizuku 执行） */
    suspend fun getVersion(context: Context): String? = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        if (!isBinaryReady(ctx)) return@withContext null
        val binaryFile = getBinaryFile(ctx)
        try {
            val result = ShizukuHelper.execShell("'${binaryFile.absolutePath}' version")
            result.getOrNull()?.trim()
        } catch (e: Exception) {
            null
        }
    }

    /** Quick Tunnel API 返回信息 */
    private data class QuickTunnelInfo(
        val tunnelId: String,
        val hostname: String,
        val accountTag: String,
        val secret: String
    )
}
