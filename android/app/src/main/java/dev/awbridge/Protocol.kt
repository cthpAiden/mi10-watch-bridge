package dev.awbridge

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Wire protocol shared with the watchOS app.
 *
 * The phone is a BLE peripheral with one service. The watch subscribes to TX
 * (phone to watch, notify) and writes to RX (watch to phone). Both directions
 * carry the same framing: 0xA5, type, length (2 bytes big endian), payload.
 * A frame may span several BLE packets.
 */
object Protocol {
    val SERVICE: UUID = UUID.fromString("7a1d0001-5e3b-4c6a-9f2d-8b4e1c0a9d10")
    val TX: UUID = UUID.fromString("7a1d0002-5e3b-4c6a-9f2d-8b4e1c0a9d10")
    val RX: UUID = UUID.fromString("7a1d0003-5e3b-4c6a-9f2d-8b4e1c0a9d10")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val MAGIC = 0xA5

    // Phone to watch.
    const val T_NAV = 0x01          // JSON {a: active, d: distance, i: instruction, e: eta, v: tap counter, n: [{d, i, t}] turns after this one}
    const val T_ICON = 0x02         // width, height, then 1 bit per pixel, MSB first
    const val T_NOTIF = 0x03        // JSON {id, app, t: title, x: text, c: category}
    const val T_NOTIF_REMOVE = 0x04 // JSON {id}
    const val T_LOC = 0x05          // JSON {la, lo, b: bearing in degrees or -1, ac: accuracy in m, ag: age in s}
    const val T_TILE = 0x06         // z, x (4 bytes BE), y (4 bytes BE), status (0 = ok, 1 = unavailable, 2 = stand-in, ask again), then the image (PNG, or JPEG when the PNG is too large)
    const val T_NOTIF_CLEAR = 0x07  // empty: forget the list, a full resend follows
    const val T_ROUTE = 0x08        // JSON {p: polyline6 of the route or "", la, lo: destination}

    // Watch to phone.
    const val T_HELLO = 0x10        // JSON {v, os}
    const val T_ACTION = 0x12       // JSON {a: "ring" | "stopring" | "stoproute"}
    const val T_TILE_REQ = 0x13     // JSON {z, x, y, hd}: the watch wants this map tile; hd = 1 for a double resolution cut
    const val T_MAP = 0x14          // JSON {on}: start or stop sending the phone's position
    const val T_NOTIF_DISMISS = 0x15 // JSON {id}: dismissed on the watch, dismiss on the phone too

    fun frame(type: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(4 + payload.size)
        out[0] = MAGIC.toByte()
        out[1] = type.toByte()
        out[2] = ((payload.size shr 8) and 0xFF).toByte()
        out[3] = (payload.size and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 4, payload.size)
        return out
    }
}

/** Reassembles frames from a stream of BLE packets. Not thread safe. */
class FrameParser(private val onFrame: (Int, ByteArray) -> Unit) {
    private var buf = ByteArray(0)

    fun reset() {
        buf = ByteArray(0)
    }

    fun feed(chunk: ByteArray) {
        buf += chunk
        while (true) {
            var start = 0
            while (start < buf.size && (buf[start].toInt() and 0xFF) != Protocol.MAGIC) start++
            if (start > 0) buf = buf.copyOfRange(start, buf.size)
            if (buf.size < 4) return
            val len = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
            if (buf.size < 4 + len) return
            val type = buf[1].toInt() and 0xFF
            val payload = buf.copyOfRange(4, 4 + len)
            buf = buf.copyOfRange(4 + len, buf.size)
            onFrame(type, payload)
        }
    }
}

object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("awbridge", Context.MODE_PRIVATE)

    // Both on by default: notifications are mirrored, and only over a paired, encrypted link.
    fun forward(ctx: Context): Boolean = sp(ctx).getBoolean("forward", true)
    fun setForward(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("forward", v).apply()

    fun encrypt(ctx: Context): Boolean = sp(ctx).getBoolean("encrypt", true)
    fun setEncrypt(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("encrypt", v).apply()

    // The destination survives a restart of the app, so a route in progress picks up again.
    private const val ROUTE_MAX_AGE_MS = 3 * 60 * 60 * 1000L

    fun saveRoute(ctx: Context, lat: Double, lon: Double, label: String) = sp(ctx).edit()
        .putString("routeLat", lat.toString())
        .putString("routeLon", lon.toString())
        .putString("routeLabel", label)
        .putLong("routeAt", System.currentTimeMillis())
        .apply()

    fun clearRoute(ctx: Context) = sp(ctx).edit()
        .remove("routeLat").remove("routeLon").remove("routeLabel").remove("routeAt")
        .apply()

    /** Latitude, longitude and label of a destination set in the last few hours, or null. */
    fun savedRoute(ctx: Context): Triple<Double, Double, String>? {
        val prefs = sp(ctx)
        val age = System.currentTimeMillis() - prefs.getLong("routeAt", 0L)
        if (age < 0 || age > ROUTE_MAX_AGE_MS) return null
        val lat = prefs.getString("routeLat", null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString("routeLon", null)?.toDoubleOrNull() ?: return null
        return Triple(lat, lon, prefs.getString("routeLabel", null) ?: "điểm đến")
    }
}

/** Small in-memory log shown in the main screen, newest line first. */
object Logx {
    private val lines = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun log(s: String) {
        android.util.Log.i("AWBridge", s)
        lines.add(fmt.format(Date()) + "  " + s)
        while (lines.size > 200) lines.removeAt(0)
    }

    @Synchronized
    fun text(): String = lines.asReversed().joinToString("\n")
}
