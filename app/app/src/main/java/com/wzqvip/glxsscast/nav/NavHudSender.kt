package com.wzqvip.glxsscast.nav

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.wzqvip.glxsscast.diag.Oplog
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale

/**
 * 导航 HUD 数据源：GPS 朝向 + 手动 bearing/distance → Wi-Fi UDP JSON。
 *
 * 协议与 Pi 端 `linux/glxss/nav.py` 一致（每秒一包，UTF-8 JSON）：
 * `{"bearing": 45, "distance": 120, "label": "右转", "heading": 40}`
 *
 * * `heading`：LocationManager 的 `Location.getBearing()`（手机当前朝向）。
 *   没有定位/静止时没有 bearing，此时 JSON 里省略该字段，Pi 用 bearing 直接当箭头角。
 * * `bearing` / `distance` / `label`：目前是 App 里的手动测试值；后续接
 *   GPS 路线导航时只需替换这三个字段的来源。
 */
class NavHudSender(private val context: Context) {

    data class SendResult(val ok: Boolean, val bytes: Int = 0, val error: String = "")

    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    @Volatile private var heading: Float? = null
    @Volatile private var accuracy: Float? = null
    @Volatile private var provider = ""
    @Volatile private var error = ""
    @Volatile private var started = false

    private var listener: LocationListener? = null

    /** 是否已授予精确定位权限（Android 12 起只给模糊定位时 GPS bearing 不可用）。 */
    val hasLocationPermission: Boolean
        get() = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    val gpsAvailable: Boolean
        get() = try {
            locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        } catch (t: Throwable) {
            false
        }

    fun currentHeading(): Float? = heading

    fun accuracyMeters(): Float? = accuracy

    fun statusText(): String {
        if (!hasLocationPermission) return "未授权定位（可用下方手动 heading）"
        if (!gpsAvailable) return "GPS 未开启（可用下方手动 heading）"
        val h = heading
        val acc = accuracy
        return when {
            h == null -> "GPS 已开，等待 bearing（静止/未定位时无方向）"
            acc != null -> String.format(
                Locale.US, "GPS heading %.0f°（±%.0fm）", h, acc
            )
            else -> String.format(Locale.US, "GPS heading %.0f°", h)
        }
    }

    /** 开始监听 GPS（幂等）；权限不足时只记录错误，不抛异常。 */
    fun start() {
        if (started) return
        if (!hasLocationPermission) {
            error = "缺少 ACCESS_FINE_LOCATION"
            return
        }
        val manager = locationManager ?: run {
            error = "设备没有 LocationManager"
            return
        }
        val callback = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                updateFromLocation(location)
            }

            override fun onProviderEnabled(provider: String) {
                this@NavHudSender.provider = provider
            }

            override fun onProviderDisabled(provider: String) {
                if (this@NavHudSender.provider == provider) {
                    this@NavHudSender.provider = ""
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                // 旧 API 回调，忽略。
            }
        }
        listener = callback
        try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, callback, Looper.getMainLooper()
            )
            provider = LocationManager.GPS_PROVIDER
            started = true
            error = ""
            Oplog.log("NAV", "GPS heading 监听已启动")
        } catch (t: SecurityException) {
            error = "定位权限被拒绝：${t.message}"
            Oplog.logf("NAV", "GPS 启动失败：%s", error)
        } catch (t: Throwable) {
            error = "GPS 启动失败：${t.javaClass.simpleName}: ${t.message}"
            Oplog.logf("NAV", "GPS 启动失败：%s", error)
        }
    }

    fun stop() {
        val callback = listener
        listener = null
        started = false
        if (callback == null) return
        try {
            locationManager?.removeUpdates(callback)
        } catch (t: Throwable) {
            Oplog.logf("NAV", "GPS 停止失败：%s", t.message)
        }
    }

    private fun updateFromLocation(location: Location) {
        try {
            if (location.hasBearing()) {
                heading = (location.bearing + 360f) % 360f
            }
            if (location.hasAccuracy()) {
                accuracy = location.accuracy
            }
            error = ""
        } catch (t: Throwable) {
            Oplog.logf("NAV", "读取 Location 失败：%s", t.message)
        }
    }

    /** 组一包 JSON；heading 为 null 时省略字段（Pi 直接把 bearing 当箭头角）。 */
    fun buildPacket(
        bearing: Float,
        distance: Float,
        label: String,
        heading: Float?
    ): String {
        val obj = JSONObject()
        obj.put("bearing", round1(bearing))
        obj.put("distance", round1(distance.coerceAtLeast(0f)))
        obj.put("label", label)
        if (heading != null) {
            obj.put("heading", round1((heading + 360f) % 360f))
        }
        return obj.toString()
    }

    /** 发一包 UDP；每次新建 socket（1 Hz，开销可忽略），失败返回明确错误。 */
    fun send(
        ip: String,
        port: Int,
        bearing: Float,
        distance: Float,
        label: String,
        heading: Float?
    ): SendResult {
        val host = ip.trim()
        if (host.isEmpty()) return SendResult(false, error = "Pi IP 为空")
        if (port !in 1..65535) return SendResult(false, error = "端口必须在 1..65535")
        return try {
            val json = buildPacket(bearing, distance, label, heading)
            val data = json.toByteArray(Charsets.UTF_8)
            val address = InetAddress.getByName(host)
            DatagramSocket().use { socket ->
                socket.soTimeout = 1500
                socket.send(DatagramPacket(data, data.size, address, port))
            }
            SendResult(true, data.size)
        } catch (t: Throwable) {
            error = "${t.javaClass.simpleName}: ${t.message}"
            SendResult(false, error = error)
        }
    }

    private fun round1(value: Float): Double =
        Math.round(value * 10f) / 10.0
}
