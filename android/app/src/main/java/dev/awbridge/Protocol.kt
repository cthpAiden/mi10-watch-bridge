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
    const val T_NAV = 0x01          // JSON {a: active, d: distance, i: instruction, e: eta}
    const val T_ICON = 0x02         // width, height, then 1 bit per pixel, MSB first
    const val T_NOTIF = 0x03        // JSON {id, app, t: title, x: text, c: category}
    const val T_NOTIF_REMOVE = 0x04 // JSON {id}

    // Watch to phone.
    const val T_HELLO = 0x10        // JSON {v, os}
    const val T_ACTION = 0x12       // JSON {a: "ring" | "stopring"}

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

    fun forward(ctx: Context): Boolean = sp(ctx).getBoolean("forward", false)
    fun setForward(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("forward", v).apply()

    fun encrypt(ctx: Context): Boolean = sp(ctx).getBoolean("encrypt", false)
    fun setEncrypt(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("encrypt", v).apply()
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
