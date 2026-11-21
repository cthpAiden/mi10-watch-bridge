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
 * Tiles come from OpenStreetMap tile servers, are recompressed to JPEG so
 * they travel faster over Bluetooth, and are kept in the app cache so each
 * tile is downloaded once. The watch screen has twice the pixel density map
 * tiles are drawn for, so it gets "sharp" tiles cut from double resolution
 * ones. Map data (c) OpenStreetMap contributors.
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

    /** A tile for the watch: the JPEG, and whether it is only the stand-in for a sharper one. */
    class Result(val bytes: ByteArray, val provisional: Boolean)

    /**
     * Calls [done] on a worker thread with the tile, or null if it is unavailable.
     *
     * @param sharp the watch draws this tile pixel for pixel on its high density
     *   screen, so it is cut from a double resolution tile of the zoom level
     *   above: same map, same label size, twice the detail.
     */
    fun fetch(ctx: Context, z: Int, x: Int, y: Int, sharp: Boolean, done: (Result?) -> Unit) {
        val key = "$z/$x/$y/$sharp"
        synchronized(inFlight) {
            if (!inFlight.add(key)) return
        }
        pool.execute {
            val result = try {
                if (sharp) loadSharp(ctx, z, x, y) else load(ctx, z, x, y)?.let { Result(it, false) }
            } catch (t: Throwable) {
                reportFailure(ctx, t)
                null
            }
            synchronized(inFlight) { inFlight.remove(key) }
            done(result)
        }
    }

    // ------------------------------------------------------------ sharp tiles

    /** Double resolution (512 pixel) tiles of the standard OpenStreetMap style, hosted by FAU Erlangen. */
    private const val SHARP_HOST = "osm.rrze.fau.de"
    private const val SHARP_QUALITY = 85

    /** After a failure the sharp server is left alone until this moment (elapsedRealtime). */
    @Volatile
    private var sharpDownUntil = 0L

    @Volatile
    private var sharpDownLogged = false

    private val sourceLocks = HashMap<String, Any>()

    private fun loadSharp(ctx: Context, z: Int, x: Int, y: Int): Result? {
        val file = File(ctx.cacheDir, "tiles/sharp/$z/$x/$y.jpg")
        if (file.isFile && file.length() > 0) return Result(file.readBytes(), false)

        val source = try {
            if (SystemClock.elapsedRealtime() < sharpDownUntil) null else sharpSource(ctx, z - 1, x shr 1, y shr 1)
        } catch (e: IOException) {
            sharpDownUntil = SystemClock.elapsedRealtime() + 60_000L
            if (!sharpDownLogged) {
                sharpDownLogged = true
                Logx.log("Máy chủ bản đồ nét chưa trả lời (${e.message}): tạm dùng bản đồ thường")
            }
            null
        }
        if (source != null) {
            sharpDownLogged = false
            // The quarter of the double resolution tile that covers this square.
            val half = source.width / 2
            val quarter = Bitmap.createBitmap(source, (x and 1) * half, (y and 1) * half, half, half)
            val out = jpeg(quarter, SHARP_QUALITY)
            if (quarter !== source) quarter.recycle()
            source.recycle()
            if (out == null) return null
            file.parentFile?.mkdirs()
            file.writeBytes(out)
            return Result(out, false)
        }
        // No sharp tile for now: the ordinary tile of the same square. Its labels
        // are small on the watch, so the watch asks again later and does not keep it.
        var failure: IOException? = null
        for (attempt in 0 until ATTEMPTS) {
            if (attempt > 0) Thread.sleep(700L * attempt)
            try {
                val raw = fetchRaw(z, x, y)
                val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                    ?: throw IOException("ô bản đồ không đọc được")
                val out = jpeg(bitmap, SHARP_QUALITY)
                bitmap.recycle()
                return out?.let { Result(it, true) }
            } catch (e: IOException) {
                failure = e
            }
        }
        throw failure ?: IOException("không rõ lỗi")
    }

    /** The 512 pixel tile, from the cache or the network. Four watch tiles are cut from each. */
    private fun sharpSource(ctx: Context, z: Int, x: Int, y: Int): Bitmap? {
        if (z !in 0..19) return null
        val key = "$z/$x/$y"
        val lock = synchronized(sourceLocks) { sourceLocks.getOrPut(key) { Any() } }
        try {
            // One download even when all four quarters are asked for at once.
            return synchronized(lock) { readOrDownloadSource(ctx, z, x, y) }
        } finally {
            synchronized(sourceLocks) { sourceLocks.remove(key) }
        }
    }

    private fun readOrDownloadSource(ctx: Context, z: Int, x: Int, y: Int): Bitmap {
        val file = File(ctx.cacheDir, "tiles/source/$z/$x/$y.png")
        if (file.isFile && file.length() > 0) {
            val cached = BitmapFactory.decodeFile(file.path)
            if (cached != null) return cached
            file.delete()
        }
        val path = "/osmhd/$z/$x/$y.png"
        val raw = try {
            viaSystem("https://$SHARP_HOST$path", readTimeoutMs = 15_000)
        } catch (_: UnknownHostException) {
            val response = DirectHttp.get(
                SHARP_HOST, path, USER_AGENT, HttpsURLConnection.getDefaultHostnameVerifier()
            )
            if (response.code != 200) throw IOException("máy chủ trả mã ${response.code}")
            response.body
        }
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size)
            ?: throw IOException("ô bản đồ nét không đọc được")
        file.parentFile?.mkdirs()
        file.writeBytes(raw)
        return bitmap
    }

    /** JPEG small enough for one Bluetooth frame, or null when even low quality is too large. */
    private fun jpeg(bitmap: Bitmap, startQuality: Int): ByteArray? {
        var quality = startQuality
        var out: ByteArray
        do {
            val buffer = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, buffer)
            out = buffer.toByteArray()
            quality -= 15
        } while (out.size > MAX_BYTES && quality > 10)
        return if (out.size > MAX_BYTES) null else out
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
        val out = jpeg(bitmap, 65)
        bitmap.recycle()
        if (out == null) return null
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

    private fun viaSystem(url: String, readTimeoutMs: Int = 12_000): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = readTimeoutMs
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
