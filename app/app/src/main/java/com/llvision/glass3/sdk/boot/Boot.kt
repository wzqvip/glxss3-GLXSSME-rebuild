package com.llvision.glass3.sdk.boot

/**
 * 绑定厂商 libboot.so 的 JNI（与 com.llvision.glass3.sdk.boot.Boot 的 RegisterNatives 对应）。
 * 仅用于把 G25_MainFW_*.img 灌入处于引导模式(03e7:2150)的眼镜。
 */
class Boot private constructor() {
    companion object {
        init {
            System.loadLibrary("usb100")
            System.loadLibrary("common")
            System.loadLibrary("boot")
        }

        private var instance: Boot? = null

        fun getInstance(): Boot = instance ?: Boot().also { instance = it }

        fun bootInit(vid: Int, pid: Int, fd: Int, busNum: Int, devAddr: Int, usbfs: String): Int =
            nativeBootInit(vid, pid, fd, busNum, devAddr, usbfs)

        fun bootDownload(imgPath: String, keyPath: String, cb: BootCallback): Int =
            nativeBootDownload(imgPath, keyPath, cb)

        fun bootDestroy() = nativeBootDestory()

        @JvmStatic private external fun nativeBootInit(vid: Int, pid: Int, fd: Int, busNum: Int, devAddr: Int, usbfs: String): Int
        @JvmStatic private external fun nativeBootDownload(imgPath: String, keyPath: String, cb: BootCallback): Int
        @JvmStatic private external fun nativeBootDestory()
    }
}
