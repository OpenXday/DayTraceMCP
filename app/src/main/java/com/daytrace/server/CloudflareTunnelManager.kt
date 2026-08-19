package com.daytrace.server

import android.content.Context
import android.util.Log
import com.daytrace.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Cloudflare Quick Tunnel (trycloudflare) 管理器
 *
 * 在手机上运行 cloudflared 二进制文件，创建一个临时隧道，
 * 将本地 MCP Server 端口暴露到公网（https://xxx.trycloudflare.com）。
 *
 * 二进制文件不打入 APK，而是在运行时根据设备 ABI 从 GitHub Releases 下载。
 * 下载后存放在 filesDir/bin/cloudflared，并设置可执行权限。
 *
 * 工作流程：
 * 1. 检查本地是否已有 cloudflared 二进制
 * 2. 如果没有，从 GitHub 下载对应架构的二进制
 * 3. 设置可执行权限
 * 4. 启动子进程：cloudflared tunnel --url http://localhost:PORT --no-autoupdate
 * 5. 读取输出解析 trycloudflare URL
 */
object CloudflareTunnelManager {

    private const val TAG = "DayTrace/Tunnel"
    private const val BINARY_NAME = "cloudflared"
    private const val MAX_URL_WAIT_MS = 30_000L  // 最多等待 30 秒获取 URL

    /**
     * cloudflared 版本（对应 GitHub Releases tag）
     */
    private const val CLOUDFLARED_VERSION = "2026.8.2"

    /**
     * 各 ABI 对应的 GitHub 下载文件名
     *
     * GitHub Release 上的命名规则：
     *   arm64-v8a    → cloudflared-linux-arm64
     *   armeabi-v7a  → cloudflared-linux-arm
     *   x86_64       → cloudflared-linux-amd64
     *   x86          → cloudflared-linux-386
     */
    private val ABI_TO_GITHUB_ASSET = mapOf(
        "arm64-v8a" to "cloudflared-linux-arm64",
        "armeabi-v7a" to "cloudflared-linux-arm",
        "x86_64" to "cloudflared-linux-amd64",
        "x86" to "cloudflared-linux-386"
    )

    /** 获取当前设备应下载的 GitHub asset 名称 */
    fun getExpectedAssetName(): String? {
        val abis = android.os.Build.SUPPORTED_ABIS
        for (abi in abis) {
            ABI_TO_GITHUB_ASSET[abi]?.let { return it }
        }
        return null
    }

    /** 获取当前设备 ABI（用于 UI 显示） */
    fun getDeviceAbi(): String {
        val abis = android.os.Build.SUPPORTED_ABIS
        for (abi in abis) {
            if (ABI_TO_GITHUB_ASSET.containsKey(abi)) return abi
        }
        return abis.firstOrNull() ?: "unknown"
    }

    /** GitHub 下载 URL */
    fun getDownloadUrl(): String? {
        val asset = getExpectedAssetName() ?: return null
        return "https://github.com/cloudflare/cloudflared/releases/download/$CLOUDFLARED_VERSION/$asset"
    }

    /** 二进制存放路径 */
    fun getBinaryFile(context: Context): File {
        return File(context.filesDir, "bin/$BINARY_NAME")
    }

    /** 二进制是否已下载且可执行 */
    fun isBinaryReady(context: Context): Boolean {
        val file = getBinaryFile(context)
        return file.exists() && file.canExecute() && file.length() > 1_000_000  // 至少 1MB
    }

    /**
     * 从 GitHub 下载 cloudflared 二进制
     *
     * @param onProgress 下载进度回调 (0-100)
     * @return 下载是否成功
     */
    suspend fun downloadBinary(
        context: Context,
        onProgress: ((Int) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
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

                // 设置可执行权限
                if (!targetFile.setExecutable(true, true)) {
                    Log.w(TAG, "Failed to set executable permission")
                }

                Log.i(TAG, "Downloaded cloudflared: ${targetFile.length()} bytes -> ${targetFile.absolutePath}")
                onProgress?.invoke(100)
                Result.success(targetFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            // 清理不完整的文件
            if (targetFile.exists() && targetFile.length() < 1_000_000) {
                targetFile.delete()
            }
            Result.failure(e)
        }
    }

    /** 删除已下载的二进制文件 */
    fun deleteBinary(context: Context): Boolean {
        val file = getBinaryFile(context)
        return file.delete()
    }

    // === Tunnel 进程管理 ===

    private var process: Process? = null
    private var scope: CoroutineScope? = null
    private var monitorJob: Job? = null

    @Volatile
    var isRunning = false
        private set

    @Volatile
    var tunnelUrl: String = ""
        private set

    @Volatile
    var lastError: String? = null
        private set

    /**
     * 启动 Cloudflare Tunnel
     *
     * 前提：二进制已下载（调用前先检查 isBinaryReady）
     */
    suspend fun start(context: Context, localPort: Int): Boolean = withContext(Dispatchers.IO) {
        if (isRunning) {
            Log.w(TAG, "Tunnel already running, stopping first...")
            stop()
        }

        val ctx = context.applicationContext

        if (!isBinaryReady(ctx)) {
            lastError = "cloudflared 二进制未就绪，请先下载"
            return@withContext false
        }

        val binaryFile = getBinaryFile(ctx)
        Log.i(TAG, "Starting cloudflared tunnel for localhost:$localPort")

        try {
            val pb = ProcessBuilder(
                binaryFile.absolutePath,
                "tunnel",
                "--no-autoupdate",
                "--url", "http://localhost:$localPort"
            )
            pb.redirectErrorStream(true)
            pb.directory(ctx.filesDir)

            val proc = pb.start()
            process = proc
            isRunning = true
            tunnelUrl = ""
            lastError = null

            // 监控协程读取输出
            val job = SupervisorJob()
            scope = CoroutineScope(Dispatchers.IO + job)
            monitorJob = scope!!.launch {
                try {
                    val reader = proc.inputStream.bufferedReader()
                    while (proc.isAlive) {
                        val line = reader.readLine() ?: break
                        Log.d(TAG, "[cloudflared] $line")

                        // 解析 trycloudflare URL
                        val urlMatch = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com").find(line)
                        if (urlMatch != null) {
                            tunnelUrl = urlMatch.value
                            Prefs.setTunnelUrl(ctx, tunnelUrl)
                            Log.i(TAG, "Tunnel URL: $tunnelUrl")
                        }

                        // 检测错误
                        if (line.contains("error", ignoreCase = true) || line.contains("failed", ignoreCase = true)) {
                            if (tunnelUrl.isEmpty()) {
                                lastError = line.trim()
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (proc.isAlive) {
                        Log.e(TAG, "Error reading cloudflared output: ${e.message}", e)
                    }
                } finally {
                    isRunning = false
                    Log.i(TAG, "cloudflared process ended")
                }
            }

            // 等待 URL 出现（最多 30 秒）
            val startWait = System.currentTimeMillis()
            while (System.currentTimeMillis() - startWait < MAX_URL_WAIT_MS) {
                if (tunnelUrl.isNotEmpty()) {
                    Log.i(TAG, "Tunnel ready: $tunnelUrl")
                    return@withContext true
                }
                if (!proc.isAlive) {
                    lastError = lastError ?: "cloudflared 进程意外退出"
                    isRunning = false
                    return@withContext false
                }
                delay(500)
            }

            lastError = "等待 Tunnel URL 超时"
            Log.w(TAG, lastError!!)
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start tunnel: ${e.message}", e)
            lastError = "启动失败: ${e.message}"
            isRunning = false
            return@withContext false
        }
    }

    /** 停止 Cloudflare Tunnel */
    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        scope?.cancel()
        scope = null

        process?.let { proc ->
            try {
                proc.destroy()
                Thread.sleep(500)
                if (proc.isAlive) {
                    proc.destroyForcibly()
                }
                Log.i(TAG, "cloudflared process stopped")
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping cloudflared: ${e.message}")
            }
        }
        process = null
        isRunning = false
        tunnelUrl = ""
    }

    /** 检查当前设备是否支持 */
    fun isSupported(): Boolean {
        return getExpectedAssetName() != null
    }

    /** 获取 cloudflared 版本（用于调试） */
    suspend fun getVersion(context: Context): String? = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        if (!isBinaryReady(ctx)) return@withContext null
        val binaryFile = getBinaryFile(ctx)
        try {
            val proc = ProcessBuilder(binaryFile.absolutePath, "version").start()
            proc.waitFor(5, TimeUnit.SECONDS)
            proc.inputStream.bufferedReader().readText().trim()
        } catch (e: Exception) {
            null
        }
    }
}
