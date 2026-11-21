package com.wzqvip.glxsscast.uvc

import android.graphics.Bitmap
import android.util.Log
import android.widget.ImageView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/**
 * Compose 侧的 UVC 预览/拍照零件。**不修改 MainActivity**，要用时在任意 Composable 里写：
 *
 * ```kotlin
 * val camera = rememberUvcCamera()
 * var status by remember { mutableStateOf("未连接") }
 *
 * // 连接（权限沿用 UsbLink 的请求流程；或直接调 usbManager.requestPermission）
 * Button(onClick = {
 *     val dev = camera.findDevice()
 *     if (dev != null && camera.hasPermission(dev)) {
 *         // 想和 UsbLink 共用一个连接就传 UsbLink.get(context).connection
 *         val ok = camera.open(dev)
 *         status = if (ok) camera.negotiatedInfo() else (camera.lastError() ?: "open failed")
 *         if (ok) camera.start()
 *     } else status = "无权限 / 未找到眼镜"
 * }) { Text("打开摄像头") }
 *
 * UvcPreview(camera, Modifier.fillMaxWidth())
 * Text(status)
 * UvcCaptureButton(camera) { file -> status = "已保存 ${file.name}" }
 * ```
 */

private const val TAG = "UvcPreview"

/** 创建一个跟随 Composable 生命周期自动释放的 [UvcCamera] */
@Composable
fun rememberUvcCamera(): UvcCamera {
    val ctx = LocalContext.current
    val camera = remember { UvcCamera(ctx.applicationContext) }
    DisposableEffect(camera) {
        onDispose { camera.close() }
    }
    return camera
}

/**
 * 实时预览。用 `AndroidView` 包一个 ImageView，帧到达时直接 `setImageBitmap`，
 * 不走 Compose 重组（否则每帧一次重组，640x480@15fps 会很难看）。
 */
@Composable
fun UvcPreview(
    camera: UvcCamera,
    modifier: Modifier = Modifier.fillMaxWidth(),
    scaleType: ImageView.ScaleType = ImageView.ScaleType.FIT_CENTER
) {
    val holder = remember { mutableStateOf<ImageView?>(null) }

    DisposableEffect(camera) {
        val l = object : UvcCamera.Listener {
            override fun onFrame(bitmap: Bitmap, frameBytes: Int, fps: Float) {
                val iv = holder.value ?: return
                // 每帧一个 Runnable 是这里唯一的小开销；要更省可以复用同一个 Runnable
                iv.post { iv.setImageBitmap(bitmap) }
            }

            override fun onError(message: String) {
                Log.w(TAG, message)
            }
        }
        camera.listener = l
        // 已经出过帧的话，立即显示最后一帧（重组/旋转后不至于黑屏）
        camera.currentBitmap()?.let { holder.value?.setImageBitmap(it) }
        onDispose {
            if (camera.listener === l) camera.listener = null
        }
    }

    AndroidView(
        factory = { c ->
            ImageView(c).apply {
                this.scaleType = scaleType
                holder.value = this
            }
        },
        modifier = modifier,
        update = { holder.value = it }
    )
}

/** 拍照按钮：把当前帧存成 PNG（app 专属目录，零权限） */
@Composable
fun UvcCaptureButton(
    camera: UvcCamera,
    modifier: Modifier = Modifier,
    label: String = "拍照",
    onSaved: (File) -> Unit = {}
) {
    Button(
        onClick = { camera.saveSnapshot()?.let(onSaved) },
        modifier = modifier
    ) {
        Text(label)
    }
}
