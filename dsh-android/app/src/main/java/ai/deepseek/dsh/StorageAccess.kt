package ai.deepseek.dsh

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.FileOutputStream

/**
 * 存储访问探测。
 *
 * targetSdk = 28 的应用在 Android 10+ 上默认获得 legacy 外部存储行为：
 * 授予 WRITE_EXTERNAL_STORAGE 后即可自由读写共享存储。
 * 但部分 ROM / 策略可能收紧权限，因此不依赖 isExternalStorageManager()
 * 这类“语义判断”，而是直接做功能性写入测试——能否在目标工作目录
 * 创建并删除文件，才是 Node 进程能否操作手机文件的真实依据。
 */
object StorageAccess {

    /** Agent 的工作目录（共享存储），例如 /sdcard/dsh-workspace */
    fun workspaceDir(): File =
        File(Environment.getExternalStorageDirectory(), "dsh-workspace")

    /** 应用私有目录下的兜底工作目录 */
    fun privateWorkspaceDir(filesDir: File): File = File(filesDir, "workspace")

    /**
     * 真正可用的工作目录：优先共享存储，写入测试失败则回退私有目录。
     * 返回的目录保证存在且可写。
     */
    fun resolveWorkspace(filesDir: File): File {
        val shared = workspaceDir()
        if (canWrite(shared)) return shared
        val priv = privateWorkspaceDir(filesDir)
        priv.mkdirs()
        return priv
    }

    /** 共享存储是否可写（功能性测试，而非权限语义判断）。 */
    fun canWriteShared(): Boolean = canWrite(workspaceDir())

    /**
     * 在给定目录做“创建-写入-删除”测试。
     * 目录不存在时先尝试创建；任一步骤失败即视为不可写。
     */
    fun canWrite(dir: File): Boolean {
        return try {
            dir.mkdirs()
            if (!dir.isDirectory) return false
            val probe = File(dir, ".dsh-write-test")
            FileOutputStream(probe).use { it.write("ok".toByteArray()) }
            val ok = probe.exists() && probe.length() > 0
            probe.delete()
            ok
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 是否还需要引导用户去系统设置页授权。
     * 功能性测试失败时，才值得展示授权入口。
     */
    fun needsUserGrant(context: Context): Boolean {
        if (canWriteShared()) return false
        // 目标目录不可写：若系统设置里显示“所有文件管理权限”未开，
        // 提示用户去开启（部分 ROM 收紧 legacy 行为时的补救）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return !Environment.isExternalStorageManager()
        }
        return true
    }
}
