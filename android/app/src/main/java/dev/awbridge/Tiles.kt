package dev.awbridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Fetches map tiles for the watch, which has no internet of its own.
 *
 * Tiles come from the OpenStreetMap tile server, are recompressed to JPEG so
 * they travel faster over Bluetooth, and are kept in the app cache so each
 * tile is downloaded once. Map data (c) OpenStreetMap contributors.
 */
object Tiles {
    private const val URL_TEMPLATE = "https://tile.openstreetmap.org/%d/%d/%d.png"
    private const val USER_AGENT = "AWBridge/1.0 (+https://github.com/cthpAiden/mi10-watch-bridge)"

    /** A frame carries its length in two bytes, so a tile has to stay under 64 KB. */
    private const val MAX_BYTES = 60_000

    private val pool = Executors.newFixedThreadPool(2)
    private val inFlight = HashSet<String>()

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
                Logx.log("Tải ô bản đồ $key lỗi: ${t.message}")
                null
            }
            synchronized(inFlight) { inFlight.remove(key) }
            done(bytes)
        }
    }

    private fun load(ctx: Context, z: Int, x: Int, y: Int): ByteArray? {
        val file = File(ctx.cacheDir, "tiles/$z/$x/$y.jpg")
        if (file.isFile && file.length() > 0) return file.readBytes()

        val conn = URL(String.format(Locale.US, URL_TEMPLATE, z, x, y)).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 12000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        try {
            val code = conn.responseCode
            if (code != 200) {
                Logx.log("Máy chủ bản đồ trả mã $code cho ô $z/$x/$y")
                return null
            }
            val raw = conn.inputStream.use { it.readBytes() }
            val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
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
        } finally {
            conn.disconnect()
        }
    }
}
