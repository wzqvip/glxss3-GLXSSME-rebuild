package com.wzqvip.glxsscast.usb

import android.content.Context
import com.wzqvip.glxsscast.diag.Oplog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * APK 内置固件的「首次运行解包 + 校验」。
 *
 * 背景：GLXSS3 上电后先以 `03E7:2150`（Myriad ROM 引导）出现，主机会把 1.5 MB 固件
 * `G25_MainFW_1.9.1.img` 灌进 DDR，它才重枚举成 `2E09:0030`。旧实现只认
 * `getExternalFilesDir(null)/G25_MainFW_1.9.1.img`，**文件不在 APK 里** —— 自己 `adb push`
 * 的开发机没问题，普通用户装完 APK 却没有这个文件，于是：
 *  - 插上处于引导模式的眼镜时「自动恢复」永远失败；
 *  - 恢复流程强依赖外部存储（可能未挂载/被清理），失败路径又没兜底。
 *
 * 现在固件随 APK 一起分发，首次运行解包到 **应用私有目录** `filesDir/firmware/`：
 *  - 不需要存储权限，不受 `getExternalFilesDir` 是否为 null 影响；
 *  - 先写 `*.tmp` → `fsync` → `sha256` 校验 → `rename`（原子），不会出现半个文件；
 *  - 已有文件按 `size + sha256` 校验（结果按 `length+mtime` 缓存，避免每次重哈希）；
 *  - 任何失败都返回 [Result.ok]=false + 人话消息，交给状态栏显示，**不抛到主线程**。
 *
 * 版权：镜像来自亮亮视野原厂 App 的 assets，版权归原厂所有，仅用于设备互操作性恢复，
 * 禁止商用（见 NOTICE.md）。
 */
object Firmware {

    /** APK 内目录：`assets/firmware/` */
    const val ASSET_DIR = "firmware"
    const val IMG_NAME = "G25_MainFW_1.9.1.img"
    const val KEY_NAME = "llvision.enkey"

    /** 原厂 1.9.1 镜像：1,525,136 B = 0x174590，尾部 0x80000 为 AES-128-ECB 密文 */
    const val IMG_SIZE = 1_525_136L
    const val IMG_SHA256 = "5b4246ba8a738f74be8938e0d174107e058a6303aa69f5538734f081dde68bca"

    /** `llvision.enkey`：128 B，只有厂商 `libboot` 那条路径用得到，纯 Kotlin 路径不需要 */
    const val KEY_SIZE = 128L
    const val KEY_SHA256 = "04063d14d1bdcbf34df093ae21d0ee12570145369b3ea2e71edbc32ba973676c"

    /** 大小上限：大于 16 MB 一定不是这块板的固件（防止 assets 被塞错文件把内存打爆） */
    private const val MAX_IMG = 16L * 1024 * 1024

    private const val BUF = 64 * 1024
    private const val TMP_SUFFIX = ".tmp"

    /** oplog 分类标签（Oplog.logf 的第一个参数） */
    private const val TAG_LOG = "BOOT"

    /** [ensure] 的结果：成功时 [file] 是可直接灌写的镜像路径 */
    data class Result(val ok: Boolean, val file: File?, val message: String)

    private val lock = Any()

    /** 校验缓存：`路径|length|mtime` → sha256，命中就不再重算 1.5 MB 的哈希 */
    private val verified = LinkedHashMap<String, String>()

    /** 应用私有固件目录：`filesDir/firmware/` */
    fun dir(ctx: Context): File = File(ctx.filesDir, ASSET_DIR)

    fun image(ctx: Context): File = File(dir(ctx), IMG_NAME)

    fun key(ctx: Context): File = File(dir(ctx), KEY_NAME)

    /** 不复制、不哈希的快照，只给 UI 显示用 */
    fun imageIfPresent(ctx: Context): File? =
        image(ctx).takeIf { it.isFile && it.length() == IMG_SIZE }

    /** 状态栏一行描述 */
    fun describe(ctx: Context): String {
        val f = image(ctx)
        return if (f.isFile) {
            "固件：内置 ${IMG_NAME} 已解包（${f.length() / 1024} KB → ${f.absolutePath}）"
        } else {
            "固件：未解包（点「重新解包内置固件」或插上眼镜时自动解包）"
        }
    }

    /**
     * 确保 `filesDir/firmware/G25_MainFW_1.9.1.img` 可用：没有/损坏就从 assets 解包。
     *
     * 阻塞 IO，**必须在后台线程调用**（`Dispatchers.IO`）。内部加锁，可并发调用。
     */
    fun ensure(ctx: Context): Result = synchronized(lock) {
        val d = dir(ctx)
        val img = image(ctx)
        try {
            if (!d.isDirectory && !d.mkdirs()) {
                throw IOException("无法创建目录 ${d.absolutePath}")
            }
            if (!verifyCached(img, IMG_SIZE, IMG_SHA256)) {
                unpack(ctx, d, IMG_NAME, img, IMG_SIZE, IMG_SHA256)
            }
            // enkey 只有厂商 libboot 路径需要；失败只告警，不影响灌固件
            try {
                val key = key(ctx)
                if (!verifyCached(key, KEY_SIZE, KEY_SHA256)) {
                    unpack(ctx, d, KEY_NAME, key, KEY_SIZE, KEY_SHA256)
                }
            } catch (t: Throwable) {
                Oplog.logf(TAG_LOG, "enkey 解包失败（不影响灌固件）: %s", t.toString())
            }
            Result(true, img, "固件就绪（${img.length()} B，sha256 校验通过）")
        } catch (t: Throwable) {
            verified.clear()
            Oplog.logf(TAG_LOG, "固件解包失败 %s: %s", t.javaClass.simpleName, t.message)
            Result(false, null, "固件不可用: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 删掉私有目录里的固件缓存（下次 [ensure] 会重新从 assets 解包） */
    fun reset(ctx: Context) {
        synchronized(lock) {
            verified.clear()
            val d = dir(ctx)
            d.listFiles()?.forEach { f ->
                try {
                    f.delete()
                } catch (ignored: Throwable) {
                }
            }
            Oplog.logf(TAG_LOG, "固件缓存已清理: %s", d.absolutePath)
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun verifyCached(f: File, size: Long, sha: String): Boolean {
        if (!f.isFile || f.length() != size) return false
        val key = "${f.absolutePath}|${f.length()}|${f.lastModified()}"
        synchronized(verified) {
            if (verified[key] == sha) return true
        }
        val got = try {
            sha256(f)
        } catch (t: Throwable) {
            Oplog.logf(TAG_LOG, "固件哈希失败 %s: %s", f.name, t.message)
            return false
        }
        if (got != sha) {
            Oplog.logf(TAG_LOG, "固件校验不匹配 %s: %s != %s", f.name, got, sha)
            return false
        }
        synchronized(verified) {
            if (verified.size > 8) verified.clear()
            verified[key] = sha
        }
        return true
    }

    /** 从 assets 原子解包一个文件：写 .tmp → fsync → 校验 → rename */
    private fun unpack(ctx: Context, dir: File, name: String, dst: File, size: Long, sha: String) {
        val tmp = File(dir, name + TMP_SUFFIX)
        try {
            val total = ctx.assets.open("$ASSET_DIR/$name").use { input ->
                FileOutputStream(tmp, false).use { out ->
                    val buf = ByteArray(BUF)
                    var n = 0L
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        n += read
                        if (n > MAX_IMG) throw IOException("assets 固件过大（>$MAX_IMG B）")
                    }
                    out.flush()
                    // 先落盘再 rename，避免断电后 rename 出来的文件内容是空的
                    out.fd.sync()
                    n
                }
            }
            if (total != size) throw IOException("assets 大小异常: $total != $size")
            val got = sha256(tmp)
            if (got != sha) throw IOException("sha256 校验失败: $got")
            if (!tmp.renameTo(dst)) {
                // 有的文件系统不允许 rename 覆盖已存在文件：删掉目标再试一次
                dst.delete()
                if (!tmp.renameTo(dst)) throw IOException("rename 失败: ${tmp.name} -> ${dst.name}")
            }
            if (!dst.isFile || dst.length() != size) {
                throw IOException("解包后大小异常: ${dst.length()} != $size")
            }
            Oplog.logf(
                TAG_LOG, "内置固件解包完成 %s → %s（%d B）", name, dst.absolutePath, dst.length()
            )
        } finally {
            if (tmp.exists()) {
                try {
                    tmp.delete()
                } catch (ignored: Throwable) {
                }
            }
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(BUF)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        val out = StringBuilder(64)
        for (b in md.digest()) out.append("%02x".format(b))
        return out.toString()
    }
}
