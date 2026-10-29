package ai.deepseek.dsh

import android.content.res.AssetManager
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 把 APK assets 里的 dsh-payload.zip 解压到应用私有目录。
 * zip 内条目分两类：
 *  - dsh/...   DeepSeek Harness 自包含运行时（node_modules 为 pnpm 布局，含相对符号链接）
 *  - node/...  Android aarch64 的 Node 26 可执行文件及其依赖库
 * 符号链接信息记录在 zip 根的 dsh-symlinks.manifest（Android 的 ZipEntry 不提供 externalAttributes），
 * 解压时跳过这些条目、最后按清单用 Os.symlink 还原，保持 pnpm 解析结构。
 */
class PayloadExtractor(private val filesDir: File) {

    companion object {
        private const val TAG = "PayloadExtractor"
        private const val ASSET_NAME = "dsh-payload.zip"
        private const val MANIFEST_NAME = "dsh-symlinks.manifest"
        private const val PAYLOAD_VERSION = "10"
    }

    private val marker = File(filesDir, ".payload-v$PAYLOAD_VERSION")

    val dshDir: File = File(filesDir, "dsh")
    val nodeDir: File = File(filesDir, "node")
    val nodeBinary: File = File(nodeDir, "node")
    val dshBinJs: File = File(dshDir, "lib/bin.js")

    fun isExtracted(): Boolean =
        marker.exists() && nodeBinary.exists() && nodeBinary.canExecute() && dshBinJs.exists()

    /** 解压全部 payload；onProgress(count, total, label) 在 IO 线程回调。 */
    fun extract(assets: AssetManager, onProgress: (Int, Int, String) -> Unit) {
        Log.i(TAG, "extracting payload ...")
        marker.delete()
        dshDir.deleteRecursively()
        nodeDir.deleteRecursively()

        // 第一遍：读取符号链接清单
        val symlinks = HashMap<String, String>()
        assets.open(ASSET_NAME).use { raw ->
            ZipInputStream(raw.buffered(256 * 1024)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (entry.name == MANIFEST_NAME) {
                        val text = zis.readBytes().toString(Charsets.UTF_8)
                        text.lineSequence().forEach { line ->
                            if (line.isBlank()) return@forEach
                            val tab = line.indexOf('\t')
                            if (tab > 0) {
                                symlinks[line.substring(0, tab)] = line.substring(tab + 1)
                            }
                        }
                        break
                    }
                    entry = zis.nextEntry
                }
            }
        }
        Log.i(TAG, "manifest: ${symlinks.size} symlinks")

        // 第二遍：解压文件（跳过符号链接条目），最后创建符号链接
        var count = 0
        assets.open(ASSET_NAME).use { raw ->
            ZipInputStream(raw.buffered(256 * 1024)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory && name != MANIFEST_NAME && !symlinks.containsKey(name)) {
                        val dest = File(filesDir, name)
                        dest.parentFile?.mkdirs()
                        FileOutputStream(dest).use { out -> zis.copyTo(out, 256 * 1024) }
                        if (name == "node/node") {
                            Os.chmod(dest.absolutePath, 0b111101101 /* 0755 */)
                        }
                    }
                    count++
                    if (count % 400 == 0) onProgress(count, -1, name)
                    entry = zis.nextEntry
                }
                onProgress(count, count, "done")
            }
        }

        var linked = 0
        for ((path, target) in symlinks) {
            val dest = File(filesDir, path)
            runCatching {
                dest.parentFile?.mkdirs()
                if (dest.exists()) dest.delete()
                Os.symlink(target, dest.absolutePath)
                linked++
            }.onFailure { Log.w(TAG, "symlink failed: $path -> $target", it) }
        }
        marker.createNewFile()
        Log.i(TAG, "payload extracted: files=$count symlinks=$linked node=${nodeBinary.canExecute()}")
    }
}
