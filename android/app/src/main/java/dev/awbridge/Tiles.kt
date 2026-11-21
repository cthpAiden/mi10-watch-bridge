package dev.awbridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.PowerManager
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection
import java.util.Locale
import java.util.concurrent.Executors

/** What stands between this app and the internet, in words for the in-app log and status. */
object Net {
    /** True when Android (or MIUI) no longer limits what the app does in the background. */
    fun unrestricted(ctx: Context): Boolean {
        val power = ctx.getSystemService(PowerManager::class.java) ?: return true
        return power.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /** Why the app cannot reach the internet right now, or null when the network looks fine. */
    @Suppress("DEPRECATION")
    fun problem(ctx: Context): String? {
        val manager = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val info: NetworkInfo? = try {
            manager.activeNetworkInfo
        } catch (_: Throwable) {
            null
        }
        // Android reports "no network" to an app whose traffic it is blocking.
        if (info?.detailedState == NetworkInfo.DetailedState.BLOCKED) {
            return "Android đang chặn mạng của AW Bridge khi chạy nền"
        }
        if (manager.activeNetwork == null || info?.isConnected != true) {
            return if (unrestricted(ctx)) "điện thoại đang không có mạng"
            else "app không có mạng (điện thoại mất mạng, hoặc mạng bị chặn khi chạy nền)"
        }
        return null
    }

    /** One line that tells the user what to do about a failed download. */
    fun advice(ctx: Context): String {
        val power = ctx.getSystemService(PowerManager::class.java)
        val hints = ArrayList<String>()
        if (!unrestricted(ctx)) hints.add("bấm nút 4 và nút 5")
        if (power?.isPowerSaveMode == true) hints.add("tắt Tiết kiệm pin của máy")
        val manager = ctx.getSystemService(ConnectivityManager::class.java)
        if (manager?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) {
            hints.add("tắt Tiết kiệm dữ liệu hoặc cho AW Bridge dùng dữ liệu nền")
        }
        return if (hints.isEmpty()) "Kiểm tra Wi-Fi hoặc 4G." else "Cách sửa: ${hints.joinToString(", ")}."
    }
}

/**
 * Fetches map tiles for the watch, which has no internet of its own.
 *
 * Tiles come from the OpenStreetMap tile server, are recompressed to JPEG so
 * they travel faster over Bluetooth, and are kept in the app cache so each
 * tile is downloaded once. Map data (c) OpenStreetMap contributors.
 */
object Tiles {
    private const val HOST = "tile.openstreetmap.org"
    private const val BACKUP_TEMPLATE = "https://tile.openstreetmap.de/%d/%d/%d.png"
    private const val USER_AGENT = "AWBridge/1.0 (+https://github.com/cthpAiden/mi10-watch-bridge)"

    /** A frame carries its length in two bytes, so a tile has to stay under 64 KB. */
    private const val MAX_BYTES = 60_000
    private const val ATTEMPTS = 3

    private val pool = Executors.newFixedThreadPool(2)
    private val inFlight = HashSet<String>()
    private var lastFailureLogAt = 0L
    private var unreportedFailures = 0

    /** Until this moment (elapsedRealtime) requests skip the system DNS, which is known to fail. */
    @Volatile
    private var directUntil = 0L

    @Volatile
    private var bypassLogged = false

    /** Calls [done] on a worker thread with the JPEG bytes, or null if the tile is unavailable. */
    fun fetch(ctx: Context, z: Int, x: Int, y: Int, done: (ByteArray?) -> Unit) {
        val key = "$z/$x/$y"
        synchronized(inFlight) {
            if (!inFlight.add(key)) return
        }
        pool.execute {
            val bytes = try {
                load(ctx, z, x, y)
            } catch (t: Throwable) {
                reportFailure(ctx, t)
                null
            }
            synchronized(inFlight) { inFlight.remove(key) }
            done(bytes)
        }
    }

    /** One log line per burst of failures, saying why and what to do. */
    @Synchronized
    private fun reportFailure(ctx: Context, error: Throwable) {
        unreportedFailures++
        val now = SystemClock.elapsedRealtime()
        if (lastFailureLogAt != 0L && now - lastFailureLogAt < 15_000) return
        lastFailureLogAt = now
        val count = unreportedFailures
        unreportedFailures = 0
        val cause = Net.problem(ctx) ?: "máy chủ bản đồ không trả lời (${error.javaClass.simpleName}: ${error.message})"
        Logx.log("Không tải được bản đồ ($count ô): $cause. ${Net.advice(ctx)}")
    }

    private fun load(ctx: Context, z: Int, x: Int, y: Int): ByteArray? {
        val file = File(ctx.cacheDir, "tiles/$z/$x/$y.jpg")
        if (file.isFile && file.length() > 0) return file.readBytes()

        var failure: IOException? = null
        for (attempt in 0 until ATTEMPTS) {
            if (attempt > 0) Thread.sleep(700L * attempt)
            try {
                return download(z, x, y, file)
            } catch (e: IOException) {
                failure = e
            }
        }
        throw failure ?: IOException("không rõ lỗi")
    }

    private fun download(z: Int, x: Int, y: Int, file: File): ByteArray? {
        val raw = fetchRaw(z, x, y)
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size)
            ?: throw IOException("ô bản đồ không đọc được")
        var quality = 65
        var out: ByteArray
        do {
            val buffer = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, buffer)
            out = buffer.toByteArray()
            quality -= 15
        } while (out.size > MAX_BYTES && quality > 10)
        bitmap.recycle()
        if (out.size > MAX_BYTES) return null
        file.parentFile?.mkdirs()
        file.writeBytes(out)
        return out
    }

    /**
     * The PNG of one tile. Some internet providers (seen on Viettel) refuse to
     * resolve openstreetmap.org, so the phone is online but the usual request
     * fails with "unknown host". Then the server's address is looked up over
     * HTTPS at a public resolver and the tile is fetched from that address.
     */
    private fun fetchRaw(z: Int, x: Int, y: Int): ByteArray {
        val path = "/$z/$x/$y.png"
        if (SystemClock.elapsedRealtime() >= directUntil) {
            try {
                return viaSystem("https://$HOST$path")
            } catch (_: UnknownHostException) {
                // The network's DNS does not know the map server: go around it below.
            }
        }
        try {
            val response = DirectHttp.get(HOST, path, USER_AGENT, HttpsURLConnection.getDefaultHostnameVerifier())
            if (response.code != 200) throw IOException("máy chủ trả mã ${response.code}")
            // It works: skip the lookup that is known to fail for a while.
            directUntil = SystemClock.elapsedRealtime() + 10 * 60_000L
            if (!bypassLogged) {
                bypassLogged = true
                Logx.log("DNS của mạng này chặn openstreetmap.org: app tự tìm địa chỉ máy chủ bản đồ")
            }
            return response.body
        } catch (e: IOException) {
            // Last resort: the same map data from the German OpenStreetMap server, whose name is not blocked.
            try {
                return viaSystem(String.format(Locale.US, BACKUP_TEMPLATE, z, x, y))
            } catch (_: IOException) {
                throw e
            }
        }
    }

    private fun viaSystem(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 12000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        try {
            val code = conn.responseCode
            if (code != 200) throw IOException("máy chủ trả mã $code")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
