package ai.deepseek.dsh

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.system.Os
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File

/**
 * 前台服务：托管内嵌的 Node 进程（dsh web）。
 * 进程以本应用 UID 运行，因此天然继承应用的存储权限，
 * Agent 可直接读写手机共享存储中的文件。
 */
class DshService : Service() {

    companion object {
        private const val TAG = "DshService"
        private const val NOTIF_ID = 1001
        private const val PORT = 3080

        const val ACTION_START = "ai.deepseek.dsh.START"
        const val ACTION_STOP = "ai.deepseek.dsh.STOP"

        private val tokenUrlRegex = Regex("""https?://127\.0\.0\.1:\d+/\?token=[A-Za-z0-9_\-]+""")

        @Volatile
        var instance: DshService? = null
            private set
    }

    private var nodeProcess: Process? = null
    private var pumpThread: Thread? = null

    @Volatile
    var serverUrl: String? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** 服务当前使用的工作目录（授权后 MainActivity 据此判断是否需要重启） */
    @Volatile
    var activeWorkspace: File? = null
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        stopNode()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopNode()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> start()
        }
        return START_STICKY
    }

    private fun start() {
        startForegroundCompat()
        if (nodeProcess != null && nodeProcess!!.isAlive) return
        val extractor = PayloadExtractor(filesDir)
        if (!extractor.isExtracted()) {
            lastError = "payload not extracted"
            Log.e(TAG, "payload missing")
            return
        }

        val node = extractor.nodeBinary
        val binJs = extractor.dshBinJs

        // 防御性确保可执行位（解压时应已设置；个别 ROM 的 umask 可能吃掉它）
        if (!node.canExecute()) {
            node.setExecutable(true, false)
            node.setReadable(true, false)
        }

        // 工作目录：优先共享存储（/sdcard/dsh-workspace，Agent 直接操作手机文件），
        // 写入测试失败时回退应用私有目录，保证服务一定能起来。
        val workspace: File = StorageAccess.resolveWorkspace(filesDir)
        if (!workspace.canWrite()) {
            lastError = "工作目录不可写: ${workspace.absolutePath}"
            DshState.setStatus(lastError!!)
            return
        }
        if (!node.canExecute()) {
            lastError = "Node 可执行文件不可执行（请重装或清除应用数据后重试）"
            DshState.setStatus(lastError!!)
            return
        }

        val dshHome = File(filesDir, ".dsh").apply { mkdirs() }
        activeWorkspace = workspace

        // TLS 信任修复：手机网络可能存在 TLS 中间人（自建根证书），浏览器因 Android
        // 系统信任该根证书而正常，但内嵌 Node 只认自带的 118 个根证书，导致所有
        // https 请求报 SELF_SIGNED_CERT_IN_CHAIN。这里把 Android 各 CA 存储
        // （系统 + apex + 用户，含上一轮运行时学习到的中间人根证书）合并成
        // PEM bundle，通过 NODE_EXTRA_CA_CERTS 追加进 Node 的信任库。
        val learnedCaDir = File(filesDir, "learned-ca").apply { mkdirs() }
        val caBundle = buildCaBundle(learnedCaDir)
        if (caBundle != null) Log.i(TAG, "ca bundle ready: ${caBundle.absolutePath} (${caBundle.length()} bytes)")
        else Log.w(TAG, "ca bundle empty: no readable CA store")

        val pb = ProcessBuilder(
            node.absolutePath,
            binJs.absolutePath,
            "web",
            "--no-open",
            "--port",
            PORT.toString()
        )
        pb.directory(workspace)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env["HOME"] = filesDir.absolutePath
        env["DSH_HOME"] = dshHome.absolutePath
        env["LD_LIBRARY_PATH"] = extractor.nodeDir.absolutePath
        env["PATH"] = "${extractor.nodeDir.absolutePath}:/system/bin:/system/xbin"
        // 终端 / 交互 shell 默认走系统 shell。设备可能继承了 Termux 的
        // SHELL（/data/data/com.termux/.../bash），本应用无权限访问，
        // 会导致“打开终端失败”。显式指向系统自带的 /system/bin/sh。
        env["SHELL"] = "/system/bin/sh"
        env["TMPDIR"] = cacheDir.absolutePath
        env["LANG"] = "C.UTF-8"
        env["LC_ALL"] = "C.UTF-8"
        env["NODE_OPTIONS"] = ""
        env["NPM_CONFIG_UPDATE_NOTIFIER"] = "false"
        env["DSH_CA_DIR"] = learnedCaDir.absolutePath
        if (caBundle != null) env["NODE_EXTRA_CA_CERTS"] = caBundle.absolutePath

        try {
            Log.i(TAG, "starting node: ${node.absolutePath} ${binJs.absolutePath} web (cwd=${workspace})")
            val proc = pb.start()
            nodeProcess = proc
            serverUrl = null
            lastError = null
            DshState.setStatus("正在启动 DeepSeek Harness 服务…")

            pumpThread = Thread {
                try {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        Log.i(TAG, "node| $line")
                        DshState.appendLog(line)
                        val m = tokenUrlRegex.find(line)
                        if (m != null && serverUrl == null) {
                            serverUrl = m.value
                            DshState.setServerUrl(m.value)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "stdout pump ended", e)
                }
                val code = try { proc.waitFor() } catch (_: Exception) { -1 }
                Log.w(TAG, "node exited code=$code")
                if (serverUrl == null) {
                    lastError = "Node 进程退出 (code=$code)"
                    DshState.setStatus("服务进程退出 (code=$code)，请重新打开应用")
                }
                nodeProcess = null
            }.apply { isDaemon = true; start() }
        } catch (e: Exception) {
            Log.e(TAG, "failed to start node", e)
            lastError = e.message
            DshState.setStatus("启动失败: ${e.message}")
        }
    }

    private fun stopNode() {
        try {
            pumpThread?.interrupt()
        } catch (_: Exception) {}
        nodeProcess?.let { p ->
            try { p.destroy() } catch (_: Exception) {}
            try { p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
            if (p.isAlive) try { p.destroyForcibly() } catch (_: Exception) {}
        }
        nodeProcess = null
        serverUrl = null
    }

    /**
     * 合并 Android 的 CA 存储为 Node 可用的 PEM bundle（NODE_EXTRA_CA_CERTS）：
     *  - /system/etc/security/cacerts            系统预置根证书
     *  - /system/etc/security/cacerts_google     部分 ROM 的 Google 附加根证书
     *  - /apex/com.android.conscrypt/cacerts     Android 14+ 的 apex 存储
     *  - /data/misc/keychain/cacerts-added       用户安装的证书（通常不可读，试即可）
     *  - filesDir/learned-ca 下的 .pem             运行时学习到的中间人根证书
     * Android 的证书文件可能带前导文本，只抽取标准 PEM 块。
     */
    private fun buildCaBundle(learnedCaDir: File): File? {
        val pemBlock = Regex("-----BEGIN CERTIFICATE-----[\\s\\S]*?-----END CERTIFICATE-----")
        val blocks = LinkedHashSet<String>()
        val dirs = listOf(
            "/system/etc/security/cacerts",
            "/system/etc/security/cacerts_google",
            "/apex/com.android.conscrypt/cacerts",
            "/data/misc/keychain/cacerts-added"
        )
        for (dir in dirs) {
            val d = File(dir)
            val files = try { d.listFiles() } catch (_: Exception) { null }
            if (files == null) {
                Log.i(TAG, "ca dir unreadable: $dir")
                continue
            }
            var count = 0
            for (f in files) {
                try {
                    val text = f.readText()
                    pemBlock.findAll(text).forEach { blocks.add(it.value); count++ }
                } catch (_: Exception) {}
            }
            Log.i(TAG, "ca dir loaded: $dir ($count certs)")
        }
        try {
            learnedCaDir.listFiles()?.forEach { f ->
                if (f.name.endsWith(".pem")) {
                    val text = f.readText()
                    pemBlock.findAll(text).forEach { blocks.add(it.value) }
                }
            }
        } catch (_: Exception) {}
        if (blocks.isEmpty()) return null
        val out = File(filesDir, "node-ca-bundle.pem")
        out.writeText(blocks.joinToString("\n") { "$it\n" })
        Log.i(TAG, "ca bundle built: ${blocks.size} certs -> ${out.absolutePath}")
        return out
    }

    private fun startForegroundCompat() {
        val notification: Notification = NotificationCompat.Builder(this, DshApplication.CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        notification.contentIntent = pi

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        NotificationManagerCompat.from(this)
            .notify(NOTIF_ID, notification)
    }
}
