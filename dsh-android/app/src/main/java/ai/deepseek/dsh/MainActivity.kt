package ai.deepseek.dsh

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), DshState.DshListener {

    private lateinit var webView: WebView
    private lateinit var announcement: TextView
    private lateinit var permButton: Button
    private lateinit var logHeader: LinearLayout
    private lateinit var logPanel: ScrollView
    private lateinit var logText: TextView
    private lateinit var logCopyButton: Button

    private val extractor by lazy { PayloadExtractor(filesDir) }
    private var urlLoaded = false
    private var announcementTapCount = 0

    /** 公告连点次数达到该值才显示/收起运行日志 */
    private val announceTapTarget = 10

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * 存储授权结果回调：以功能性写入测试为准，
     * 仍不可写则自动跳系统设置的“所有文件管理权限”页兜底。
     */
    private val storagePermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            if (StorageAccess.needsUserGrant(this)) {
                openAllFilesAccessSettings()
            } else {
                permButton.visibility = View.GONE
                maybeRestartRuntime()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        announcement = findViewById(R.id.announcement)
        permButton = findViewById(R.id.perm_button)
        logHeader = findViewById(R.id.log_header)
        logPanel = findViewById(R.id.log_panel)
        logText = findViewById(R.id.log_text)
        logCopyButton = findViewById(R.id.log_copy_button)

        setupAnnouncement()
        setupWebView()

        permButton.setOnClickListener { requestStorageAccess() }
        logCopyButton.setOnClickListener { copyLogToClipboard() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        DshState.addListener(this)

        if (extractor.isExtracted()) {
            permButton.visibility = if (StorageAccess.needsUserGrant(this)) View.VISIBLE else View.GONE
            startRuntime()
        } else {
            extractPayload()
        }
    }

    /**
     * 头部公告：一行文案 + 可点的 GitHub 链接。
     *  - 点链接 → 系统浏览器打开仓库地址；
     *  - 点公告其他区域 → 计数，连点 10 次在页面下方显示/收起运行日志。
     */
    private fun setupAnnouncement() {
        val text = getString(R.string.announcement_full)
        val spannable = SpannableString(text)
        val link = getString(R.string.announcement_link)
        val start = text.indexOf(link)
        if (start >= 0) {
            spannable.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.github_url))))
                    }
                }
            }, start, start + link.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        announcement.text = spannable
        announcement.movementMethod = LinkMovementMethod.getInstance()
        announcement.setOnClickListener {
            announcementTapCount += 1
            val remaining = announceTapTarget - announcementTapCount
            if (remaining in 4..9) {
                Toast.makeText(this, getString(R.string.announce_tap_hint, remaining), Toast.LENGTH_SHORT).show()
            }
            if (announcementTapCount >= announceTapTarget) {
                announcementTapCount = 0
                toggleRunLog()
            }
        }
    }

    /** 显示/收起页面下方的运行日志（公告连点 10 次触发） */
    private fun toggleRunLog() {
        val show = logHeader.visibility != View.VISIBLE
        logHeader.visibility = if (show) View.VISIBLE else View.GONE
        logPanel.visibility = if (show) View.VISIBLE else View.GONE
        Toast.makeText(
            this,
            if (show) R.string.announce_log_shown else R.string.announce_log_hidden,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun setupWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            textZoom = 100
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
        }
        WebView.setWebContentsDebuggingEnabled(true)
        webView.isLongClickable = true
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                // 本地服务与本地资产一律在 WebView 内打开；外部链接交给系统浏览器
                return if (url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")) {
                    false
                } else {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                    true
                }
            }
        }
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // targetSdk 28 的 legacy 应用在 Android 11+ 申请 WRITE_EXTERNAL_STORAGE
            // 仍会弹系统对话框，授予后即可直接读写 /sdcard。
            storagePermLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        } else {
            storagePermLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    /** 跳系统设置的“所有文件管理权限”页（部分 ROM 收紧 legacy 行为时的兜底） */
    private fun openAllFilesAccessSettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    /** 授权变化后，如服务在用私有目录兜底（或未运行），重启以切到共享存储工作目录 */
    private fun maybeRestartRuntime() {
        if (!extractor.isExtracted()) return
        val active = DshService.instance?.activeWorkspace
        val usingPrivate = active != null &&
            active.absolutePath.startsWith(filesDir.absolutePath)
        if (DshService.instance == null || usingPrivate) {
            restartRuntime()
        }
    }

    /** 重启运行时（先停后启，确保 Node 用新的工作目录重新拉起） */
    private fun restartRuntime() {
        startService(Intent(this, DshService::class.java).apply { action = DshService.ACTION_STOP })
        // stopSelf 是异步的，稍等再启动；若上一次仍在跑，start() 会 early-return，
        // 所以 STOP 与 START 之间留出间隔确保旧进程已退出。
        android.os.Handler(mainLooper).postDelayed({
            startService(Intent(this, DshService::class.java).apply { action = DshService.ACTION_START })
        }, 500)
    }

    private fun extractPayload() {
        announcement.text = "首次启动：正在解压运行时（约 400MB，需要 1-3 分钟）…"
        permButton.visibility = View.GONE
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    extractor.extract(assets) { current, total, _ ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            announcement.text = if (total > 0) {
                                "正在解压运行时… $current / $total"
                            } else {
                                "正在解压运行时… 已处理 $current 个文件"
                            }
                        }
                    }
                }
                announcement.text = "解压完成，正在启动服务…"
                permButton.visibility =
                    if (StorageAccess.needsUserGrant(this@MainActivity)) View.VISIBLE else View.GONE
                startRuntime()
            } catch (e: Exception) {
                announcement.text = "解压失败: ${e.message}"
                Toast.makeText(this@MainActivity, "解压失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startRuntime() {
        val intent = Intent(this, DshService::class.java).apply { action = DshService.ACTION_START }
        startService(intent)
    }

    override fun onStatus(status: String) {
        // 头部公告行兼任状态行：启动/异常状态临时占用，服务就绪后恢复公告
        announcement.text = status
        if (status.contains("失败") || status.contains("退出")) {
            Toast.makeText(this, status, Toast.LENGTH_LONG).show()
        }
    }

    override fun onServerUrl(url: String?) {
        if (url != null && !urlLoaded) {
            urlLoaded = true
            restoreAnnouncement()
            webView.loadUrl(url)
        }
    }

    /** 恢复头部公告（点个 Star 支持 · GitHub） */
    private fun restoreAnnouncement() {
        val text = getString(R.string.announcement_full)
        val spannable = SpannableString(text)
        val link = getString(R.string.announcement_link)
        val start = text.indexOf(link)
        if (start >= 0) {
            spannable.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.github_url))))
                    }
                }
            }, start, start + link.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        announcement.text = spannable
    }

    override fun onLog(line: String) {
        // 网络自检 / TLS 修复的诊断行（[dsh-net]）始终保留，其余仍按错误关键词过滤
        if (line.contains("[dsh-net]") || line.contains("error", true) || line.contains("failed", true) ||
            line.contains("token=")
        ) {
            logText.append(line + "\n")
        }
    }

    /** 复制按钮：把当前运行日志全文写入系统剪贴板 */
    private fun copyLogToClipboard() {
        val text = logText.text?.toString() ?: ""
        if (text.isBlank()) {
            Toast.makeText(this, R.string.log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("dsh-run-log", text))
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置返回后刷新权限状态（功能性写入测试）
        val needsGrant = StorageAccess.needsUserGrant(this)
        permButton.visibility = if (needsGrant) View.VISIBLE else View.GONE
        if (!needsGrant) {
            maybeRestartRuntime()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        DshState.removeListener(this)
        super.onDestroy()
    }
}
