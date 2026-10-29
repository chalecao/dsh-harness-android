package ai.deepseek.dsh

import android.os.Handler
import android.os.Looper

/** 服务与界面之间的轻量状态通道（主线程分发）。 */
object DshState {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var status: String = "初始化…"
        private set

    @Volatile
    var serverUrl: String? = null
        private set

    private val logs = ArrayDeque<String>(200)
    val logSnapshot: List<String>
        get() = synchronized(logs) { logs.toList() }

    private val listeners = mutableListOf<DshListener>()

    interface DshListener {
        fun onStatus(status: String) {}
        fun onServerUrl(url: String?) {}
        fun onLog(line: String) {}
    }

    fun addListener(l: DshListener) {
        synchronized(listeners) { listeners.add(l) }
        main.post {
            l.onStatus(status)
            l.onServerUrl(serverUrl)
            synchronized(logs) { logs.toList().forEach { l.onLog(it) } }
        }
    }

    fun removeListener(l: DshListener) {
        synchronized(listeners) { listeners.remove(l) }
    }

    fun setStatus(s: String) {
        status = s
        val snapshot = synchronized(listeners) { listeners.toList() }
        main.post { snapshot.forEach { it.onStatus(s) } }
    }

    fun setServerUrl(url: String?) {
        serverUrl = url
        val snapshot = synchronized(listeners) { listeners.toList() }
        main.post { snapshot.forEach { it.onServerUrl(url) } }
    }

    fun appendLog(line: String) {
        synchronized(logs) {
            logs.addLast(line)
            while (logs.size > 200) logs.removeFirst()
        }
        val snapshot = synchronized(listeners) { listeners.toList() }
        main.post { snapshot.forEach { it.onLog(line) } }
    }
}
