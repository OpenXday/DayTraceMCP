package com.daytrace.server

import android.content.Context
import android.util.Log
import com.daytrace.util.Prefs
import com.daytrace.util.ShizukuHelper
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
import java.io.FileOutputStream
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
 * 工作流程：
 * 1. 检查 Shizuku 是否可用
 * 2. 从 GitHub 下载 cloudflared 二进制（按设备 ABI）
 * 3. 通过 Shizuku chmod 设置可执行权限
 * 4. 通过 Shizuku newProcess 启动 cloudflared tunnel
 * 5. 读取输出解析 trycloudflare URL
 */
object CloudflareTunnelManager {

    private const val TAG = "DayTrace/Tunnel"
    private const val BINARY_NAME = "cloudflared"
    private const val MAX_URL_WAIT_MS = 60_000L
    private const val SHELL_BINARY_PATH = "/data/local/tmp/cloudflared"

    private const val CLOUDFLARED_VERSION = "2026.8.2"

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

    // === Tunnel 进程管理 ===

    private var process: Process? = null
    private var scope: CoroutineScope? = null
    private var monitorJob: Job? = null
    private var processPid: Int = 0

    // === DNS 转发服务器 ===
    // Go 的纯 Go resolver 找不到 /etc/resolv.conf 时 fallback 到 [::1]:53，
    // 我们在 App 内启动一个微型 DNS 转发器监听该端口，把查询转发到真实 DNS
    private var dnsSocketV4: java.net.DatagramSocket? = null
    private var dnsSocketV6: java.net.DatagramSocket? = null
    private var dnsThread: Thread? = null
    @Volatile private var dnsForwarderRunning = false

    @Volatile
    var isRunning = false
        private set

    @Volatile
    var tunnelUrl: String = ""
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** 临时文件：cloudflared 输出日志（放在 /data/local/tmp/ 保证 shell 用户可写） */
    private fun getLogFile(context: Context): java.io.File {
        return java.io.File("/data/local/tmp/daytrace_cloudflared.log")
    }

    /**
     * 启动 Cloudflare Tunnel
     *
     * 通过 Shizuku 以 shell 权限执行 cloudflared 二进制。
     * Android 10+ 不允许 App 直接 execve ELF，必须走 Shizuku。
     *
     * 采用 nohup + 日志文件方案：
     * 1. 通过 Shizuku execShell 执行 nohup cloudflared ... & （后台运行）
     * 2. 输出重定向到日志文件
     * 3. 轮询通过 Shizuku cat 读取日志文件解析 URL
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
        // App 的 filesDir 对 shell 用户不可读，需要由 App 自己复制到 /data/local/tmp/
        val shellBinaryPath = SHELL_BINARY_PATH
        val logFile = getLogFile(ctx)
        val logPath = logFile.absolutePath

        // 清空旧日志
        ShizukuHelper.execShell("rm -f '$logPath'")

        Log.i(TAG, "Starting cloudflared tunnel for localhost:$localPort via Shizuku")

        try {
            // 3. 通过 Shizuku 将二进制从外部存储复制到 /data/local/tmp/ 并设置可执行权限
            //    外部存储目录 shell 用户可读，/data/local/tmp/ shell 用户可写
            val copyCmd = "cp '${binaryFile.absolutePath}' '$shellBinaryPath' && chmod 755 '$shellBinaryPath'"
            val copyResult = ShizukuHelper.execShell(copyCmd)
            if (copyResult.isFailure) {
                lastError = "复制 cloudflared 到 /data/local/tmp/ 失败: ${copyResult.exceptionOrNull()?.message}"
                Log.e(TAG, lastError!!)
                return@withContext false
            }
            Log.i(TAG, "Copied cloudflared to $shellBinaryPath")

            // 3b. 验证 cloudflared 能执行
            val versionResult = ShizukuHelper.execShell("'$shellBinaryPath' version 2>&1")
            if (versionResult.isFailure) {
                lastError = "cloudflared 无法执行: ${versionResult.exceptionOrNull()?.message}"
                Log.e(TAG, lastError!!)
                return@withContext false
            }
            Log.i(TAG, "cloudflared version: ${versionResult.getOrDefault("")}")

            // 4. DNS 修复 + 启动 cloudflared
            //
            // 根本问题：
            //   cloudflared 是 Go 静态编译（CGO_ENABLED=0），只能用纯 Go DNS resolver。
            //   纯 Go resolver 读 /etc/resolv.conf 获取 DNS 服务器。
            //   Android 上 /etc → /system/etc（只读），resolv.conf 通常不存在，
            //   Go fallback 到 [::1]:53（IPv6 localhost DNS），Android 不监听该端口，
            //   导致 "connection refused" 错误。
            //
            //   App 不能绑定 53 端口（特权端口，需要 root/shell 权限）。
            //   但通过 Shizuku 的 shell 用户可以绑定 53 端口！
            //
            // 方案：通过 Shizuku 启动一个简单的 DNS 转发脚本。
            //   Android 自带的 toybox 可能没有 nc -u（UDP），
            //   所以用 iptables 做 NAT 重定向：
            //   1. App 在高端口（如 5353）启动 DNS 转发器
            //   2. 通过 Shizuku 添加 iptables 规则：
            //      iptables -t nat -A OUTPUT -p udp --dport 53 -d 127.0.0.1 -j REDIRECT --to-port 5353
            //      ip6tables -t nat -A OUTPUT -p udp --dport 53 -d ::1 -j REDIRECT --to-port 5353
            //   3. Go 的 DNS 查询到 [::1]:53 被 iptables 重定向到 127.0.0.1:5353
            //   4. App 的 DNS 转发器收到查询，转发到真实 DNS 服务器

            // 4a. 获取系统 DNS 服务器
            val dnsProp = ShizukuHelper.execShell("getprop net.dns1 2>/dev/null; getprop net.dns2 2>/dev/null; getprop dhcp.wlan0.dns1 2>/dev/null; getprop dhcp.wlan0.dns2 2>/dev/null")
            val dnsServers = dnsProp.getOrDefault("").lines()
                .mapNotNull { line ->
                    val match = Regex("\\b(\\d+\\.\\d+\\.\\d+\\.\\d+)\\b").find(line.trim())
                    match?.value
                }
                .filter { it != "0.0.0.0" && it != "127.0.0.1" }
                .distinct()
            val primaryDns = dnsServers.firstOrNull() ?: "8.8.8.8"
            val secondaryDns = dnsServers.getOrElse(1) { "1.1.1.1" }
            Log.i(TAG, "System DNS servers: $dnsServers (primary=$primaryDns, secondary=$secondaryDns)")

            // 4b. 启动 App 内 DNS 转发器（监听高端口 5353）
            val dnsForwarderPort = 5353
            startDnsForwarder(listOf(primaryDns, secondaryDns), dnsForwarderPort)
            Log.i(TAG, "DNS forwarder started on port $dnsForwarderPort")

            // 4c. 通过 Shizuku 添加 iptables 规则，把 53 端口重定向到 5353
            //     先清除旧规则
            ShizukuHelper.execShell("iptables -t nat -D OUTPUT -p udp --dport 53 -d 127.0.0.1 -j REDIRECT --to-port $dnsForwarderPort 2>/dev/null; ip6tables -t nat -D OUTPUT -p udp --dport 53 -d ::1 -j REDIRECT --to-port $dnsForwarderPort 2>/dev/null; echo 'old rules cleaned'")

            // 添加新规则
            val iptablesCmd = buildString {
                append("iptables -t nat -A OUTPUT -p udp --dport 53 -d 127.0.0.1 -j REDIRECT --to-port $dnsForwarderPort 2>/dev/null && echo 'IPTABLES_V4_OK' || echo 'IPTABLES_V4_FAIL'; ")
                append("ip6tables -t nat -A OUTPUT -p udp --dport 53 -d ::1 -j REDIRECT --to-port $dnsForwarderPort 2>/dev/null && echo 'IPTABLES_V6_OK' || echo 'IPTABLES_V6_FAIL'; ")
                append("iptables -t nat -L OUTPUT -n 2>/dev/null | grep -i '53\\|5353\\|REDIRECT' || echo 'no iptables rules found'")
            }
            val iptablesResult = ShizukuHelper.execShell(iptablesCmd)
            Log.i(TAG, "iptables redirect: ${iptablesResult.getOrDefault("").take(300)}")

            // 4d. 同时尝试写入 /etc/resolv.conf（多保险）
            val resolvCmd = buildString {
                append("printf 'nameserver 127.0.0.1\\noptions timeout:2 attempts:3\\n' > /data/local/tmp/resolv.conf; ")
                append("cp /data/local/tmp/resolv.conf /etc/resolv.conf 2>/dev/null && echo 'RESOLV_WRITTEN'; ")
                append("echo '=== resolv.conf ==='; cat /etc/resolv.conf 2>/dev/null || echo 'RESOLV_UNAVAILABLE'")
            }
            val resolvResult = ShizukuHelper.execShell(resolvCmd)
            Log.i(TAG, "resolv.conf: ${resolvResult.getOrDefault("").take(200)}")

            // 启动 cloudflared
            // cloudflared 是 Go 静态编译（CGO_ENABLED=0），只有纯 Go resolver，
            // GODEBUG=netdns=go 强制纯 Go resolver，它会 fallback 到 [::1]:53，
            // iptables 把 [::1]:53 重定向到 App 的 5353 端口
            val shellCmd = "GODEBUG=netdns=go nohup '$shellBinaryPath' tunnel --no-autoupdate --url http://localhost:$localPort > '$logPath' 2>&1 &"
            Log.d(TAG, "Shell cmd: $shellCmd")

            val startResult = ShizukuHelper.execShellAsync(shellCmd)
            if (startResult.isFailure) {
                lastError = "Shizuku 执行失败: ${startResult.exceptionOrNull()?.message}"
                Log.e(TAG, lastError!!)
                return@withContext false
            }

            // execShellAsync 返回 "STARTED:0" 表示 shell 命令执行成功
            val startOutput = startResult.getOrDefault("")
            Log.i(TAG, "Start result: $startOutput")
            if (!startOutput.contains("STARTED:0")) {
                lastError = "后台启动命令失败: $startOutput"
                Log.e(TAG, lastError!!)
                return@withContext false
            }

            isRunning = true
            tunnelUrl = ""
            lastError = null

            // 5. 轮询通过 Shizuku cat 读取日志文件解析 URL（最多 60 秒）
            val startWait = System.currentTimeMillis()
            var lastLogContent = ""
            while (System.currentTimeMillis() - startWait < MAX_URL_WAIT_MS) {
                delay(2000)  // 每 2 秒读一次

                // 通过 Shizuku 读日志文件（shell 用户写的文件 App 可能读不到）
                val catResult = ShizukuHelper.execShell("cat '$logPath' 2>/dev/null")
                val logContent = catResult.getOrDefault("")
                if (logContent.isNotEmpty()) {
                    lastLogContent = logContent
                    Log.d(TAG, "Log: ${logContent.take(500)}")

                    // 解析 trycloudflare 隧道 URL
                    // cloudflared 日志格式："Your quick Tunnel has been created! Visit it at: https://xxx-yyy.trycloudflare.com"
                    // 需要排除 api.trycloudflare.com（cloudflared 的 API 端点，不是隧道 URL）
                    val urlMatch = Regex("https://(?!api\\.)[a-z0-9-]+\\.trycloudflare\\.com").find(logContent)
                    if (urlMatch != null) {
                        tunnelUrl = urlMatch.value
                        Prefs.setTunnelUrl(ctx, tunnelUrl)
                        Log.i(TAG, "Tunnel URL: $tunnelUrl")
                        return@withContext true
                    }

                    // 检测错误
                    val errorLine = logContent.lines().find {
                        it.contains("error", ignoreCase = true) || it.contains("failed", ignoreCase = true)
                    }
                    if (errorLine != null && tunnelUrl.isEmpty()) {
                        lastError = errorLine.trim()
                    }
                }

                // 检查进程是否还在运行（5 秒后才检查，给启动时间）
                if (System.currentTimeMillis() - startWait > 5000) {
                    val pgrepResult = ShizukuHelper.execShell("pgrep -f cloudflared")
                    if (pgrepResult.isFailure && tunnelUrl.isEmpty()) {
                        // 进程已退出
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

            // 超时，把日志内容放到错误信息里帮助排查
            lastError = if (lastLogContent.isNotEmpty()) {
                "等待 Tunnel URL 超时（60秒）。日志最后内容: ${lastLogContent.take(200)}"
            } else {
                "等待 Tunnel URL 超时（60秒），日志文件为空"
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

    /** 停止 Cloudflare Tunnel */
    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        scope?.cancel()
        scope = null

        // Process API 的 destroy 不再使用（改用 nohup 后台方式）
        process?.let { proc ->
            try {
                proc.destroy()
            } catch (e: Exception) {
                // 忽略
            }
        }
        process = null

        // 通过 Shizuku kill cloudflared 进程
        if (isShizukuAvailable()) {
            try {
                ShizukuHelper.execShell("pkill -f cloudflared")
                Log.i(TAG, "Killed cloudflared via Shizuku pkill")
            } catch (e: Exception) {
                Log.w(TAG, "pkill cloudflared failed: ${e.message}")
            }
        }

        // 停止 DNS 转发器
        stopDnsForwarder()

        isRunning = false
        tunnelUrl = ""
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

    // === DNS 转发服务器 ===
    //
    // Go 的纯 Go DNS resolver 在 Android 上找不到 /etc/resolv.conf 时，
    // fallback 到 [::1]:53（IPv6 localhost DNS）。
    // Android 不监听该端口，导致 "connection refused"。
    //
    // 我们在 App 内启动一个微型 UDP DNS 转发器：
    //   - 监听 127.0.0.1:53 和 [::1]:53
    //   - 收到 DNS 查询后转发到系统真实 DNS 服务器
    //   - 返回 DNS 响应给 Go resolver
    //
    // 这样 Go 的 DNS 查询就能正常工作，cloudflared 可以连接 api.trycloudflare.com。

    /**
     * 启动 DNS 转发服务器
     *
     * 监听 127.0.0.1:port，把 DNS 查询转发到 upstreamDns 列表中的服务器。
     * 使用守护线程处理请求，不阻塞调用者。
     *
     * @param upstreamDns 上游 DNS 服务器列表
     * @param port 监听端口（默认 5353，高端口不需要特权）
     */
    private fun startDnsForwarder(upstreamDns: List<String>, port: Int = 5353) {
        stopDnsForwarder() // 先停止旧的

        try {
            // 绑定 0.0.0.0:port（监听所有接口，包括 127.0.0.1 和 ::1）
            dnsSocketV4 = java.net.DatagramSocket(port)
            dnsSocketV4?.soTimeout = 0 // 无超时，一直阻塞等待
            Log.i(TAG, "DNS forwarder listening on 0.0.0.0:$port")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bind DNS on port $port: ${e.message}")
        }

        if (dnsSocketV4 == null) {
            Log.e(TAG, "DNS forwarder: failed to bind socket on port $port!")
            return
        }

        dnsForwarderRunning = true
        dnsThread = Thread {
            val buffer = ByteArray(4096)
            Log.i(TAG, "DNS forwarder thread started")

            while (dnsForwarderRunning) {
                // 轮询 IPv4 和 IPv6 socket
                for (socket in listOfNotNull(dnsSocketV4, dnsSocketV6)) {
                    if (!dnsForwarderRunning) break
                    if (socket.isClosed) continue

                    try {
                        val packet = java.net.DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)

                        // 收到 DNS 查询，转发到上游 DNS 服务器
                        val queryData = packet.data.copyOfRange(0, packet.length)
                        val clientAddr = packet.address
                        val clientPort = packet.port

                        // 异步转发，不阻塞主循环
                        Thread {
                            forwardDnsQuery(queryData, clientAddr, clientPort, socket, upstreamDns)
                        }.start()
                    } catch (e: java.net.SocketTimeoutException) {
                        // 正常，继续
                    } catch (e: Exception) {
                        if (dnsForwarderRunning) {
                            Log.w(TAG, "DNS forwarder receive error: ${e.message}")
                        }
                    }
                }
                // 短暂 sleep 避免 CPU 空转（当两个 socket 都没有数据时）
                try { Thread.sleep(1) } catch (e: InterruptedException) { break }
            }

            Log.i(TAG, "DNS forwarder thread stopped")
        }.apply {
            isDaemon = true
            name = "DNS-Forwarder"
            start()
        }
    }

    /**
     * 转发 DNS 查询到上游服务器并返回响应
     */
    private fun forwardDnsQuery(
        queryData: ByteArray,
        clientAddr: java.net.InetAddress,
        clientPort: Int,
        receiveSocket: java.net.DatagramSocket,
        upstreamDns: List<String>
    ) {
        for (upstream in upstreamDns) {
            try {
                val upstreamAddr = java.net.InetAddress.getByName(upstream)
                val upstreamSocket = java.net.DatagramSocket()
                upstreamSocket.soTimeout = 3000 // 3 秒超时

                val queryPacket = java.net.DatagramPacket(
                    queryData, queryData.size,
                    upstreamAddr, 53
                )
                upstreamSocket.send(queryPacket)

                val responseBuffer = ByteArray(4096)
                val responsePacket = java.net.DatagramPacket(responseBuffer, responseBuffer.size)
                upstreamSocket.receive(responsePacket)

                // 把响应返回给客户端
                val replyPacket = java.net.DatagramPacket(
                    responsePacket.data, responsePacket.length,
                    clientAddr, clientPort
                )
                receiveSocket.send(replyPacket)
                upstreamSocket.close()
                return // 成功，不需要尝试其他上游
            } catch (e: Exception) {
                // 尝试下一个上游 DNS
            }
        }
    }

    /**
     * 停止 DNS 转发服务器
     */
    private fun stopDnsForwarder() {
        dnsForwarderRunning = false
        try { dnsSocketV4?.close() } catch (e: Exception) {}
        try { dnsSocketV6?.close() } catch (e: Exception) {}
        dnsSocketV4 = null
        dnsSocketV6 = null
        try { dnsThread?.interrupt() } catch (e: Exception) {}
        dnsThread = null
        Log.i(TAG, "DNS forwarder stopped")
    }
}
