package dev.awbridge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class LatLon(val lat: Double, val lon: Double)

/** One turn of a route. [shapeIndex] is the route point where it happens. */
class Maneuver(val type: Int, val instruction: String, val shapeIndex: Int)

class Route(
    val points: List<LatLon>,
    val maneuvers: List<Maneuver>,
    /** The route line as a precision 6 polyline, sent to the watch as is. */
    val encoded: String,
    val destination: LatLon,
    val seconds: Double
) {
    /** Distance in metres from the start to each route point. */
    val along = DoubleArray(points.size)

    init {
        for (i in 1 until points.size) {
            along[i] = along[i - 1] + Geo.distance(points[i - 1], points[i])
        }
    }

    val length: Double
        get() = if (along.isEmpty()) 0.0 else along[along.size - 1]
}

object Geo {
    private const val EARTH = 6_371_000.0

    fun distance(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH * asin(min(1.0, sqrt(h)))
    }

    fun metres(m: Double): String =
        if (m < 1000) {
            "${max(10, (m / 10).roundToInt() * 10)} m"
        } else {
            String.format(Locale.US, "%.1f km", m / 1000).replace('.', ',')
        }

    fun duration(seconds: Double): String {
        val minutes = max(1, (seconds / 60).roundToInt())
        return if (minutes < 60) "$minutes phút" else "${minutes / 60} giờ ${minutes % 60} phút"
    }
}

/**
 * Motorbike routing on OpenStreetMap data through the public Valhalla server
 * run by FOSSGIS. Google Maps does not expose its route, so the line drawn on
 * the watch and the turns that go with it both come from here.
 */
object Router {
    private const val ENDPOINT = "https://valhalla1.openstreetmap.de/route"
    private const val USER_AGENT = "AWBridge/1.0 (+https://github.com/cthpAiden/mi10-watch-bridge)"

    /** Blocking. Throws when there is no network or no route. */
    fun fetch(from: LatLon, to: LatLon): Route {
        val body = JSONObject()
            .put(
                "locations",
                JSONArray()
                    .put(JSONObject().put("lat", from.lat).put("lon", from.lon))
                    .put(JSONObject().put("lat", to.lat).put("lon", to.lon))
            )
            .put("costing", "motor_scooter")
            .put("directions_options", JSONObject().put("language", "vi-VN").put("units", "kilometers"))
            .toString()
            .toByteArray(Charsets.UTF_8)

        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", USER_AGENT)
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code != 200) {
                val detail = try {
                    conn.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
                } catch (_: Throwable) {
                    ""
                }
                val message = try {
                    JSONObject(detail).optString("error")
                } catch (_: Throwable) {
                    ""
                }
                throw IOException(if (message.isNotEmpty()) message else "máy chủ chỉ đường trả mã $code")
            }
            val json = JSONObject(conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
            val trip = json.getJSONObject("trip")
            val leg = trip.getJSONArray("legs").getJSONObject(0)
            val encoded = leg.getString("shape")
            val points = decode(encoded)
            if (points.size < 2) throw IOException("tuyến đường rỗng")
            val list = leg.getJSONArray("maneuvers")
            val maneuvers = ArrayList<Maneuver>()
            for (i in 0 until list.length()) {
                val m = list.getJSONObject(i)
                maneuvers.add(
                    Maneuver(
                        m.optInt("type"),
                        clean(m.optString("instruction")),
                        m.optInt("begin_shape_index").coerceIn(0, points.size - 1)
                    )
                )
            }
            return Route(points, maneuvers, encoded, to, trip.getJSONObject("summary").optDouble("time", 0.0))
        } finally {
            conn.disconnect()
        }
    }

    /** "Rẽ phải vào Lê Lợi/Đường Lê Lợi." becomes "Rẽ phải vào Lê Lợi". */
    private fun clean(instruction: String): String =
        instruction.substringBefore('/').trim().trimEnd('.')

    /** Decodes a precision 6 encoded polyline. */
    fun decode(encoded: String): List<LatLon> {
        val out = ArrayList<LatLon>()
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            var shift = 0
            var result = 0L
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lat += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            shift = 0
            result = 0L
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lon += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            out.add(LatLon(lat / 1e6, lon / 1e6))
        }
        return out
    }
}

/** Follows the rider along a route and works out the next turn. Not thread safe. */
class RouteProgress(val route: Route) {

    class State(
        val next: Maneuver?,
        val toTurn: Double,
        val remaining: Double,
        val offRoute: Double
    )

    private var segment = 0

    fun update(pos: LatLon): State {
        val lastSegment = route.points.size - 2
        // Look near the last known spot first: it is cheap and it does not jump
        // to another part of the route that happens to pass close by.
        var best = nearest(pos, max(0, segment - 3), min(lastSegment, segment + 80))
        if (best.distance > 60) {
            val anywhere = nearest(pos, 0, lastSegment)
            if (anywhere.distance < best.distance) best = anywhere
        }
        segment = best.segment
        val travelled = route.along[best.segment] +
            best.fraction * (route.along[best.segment + 1] - route.along[best.segment])
        val next = route.maneuvers.firstOrNull { route.along[it.shapeIndex] > travelled + 5 }
        val toTurn = if (next == null) 0.0 else route.along[next.shapeIndex] - travelled
        return State(next, toTurn, max(0.0, route.length - travelled), best.distance)
    }

    private class Hit(val segment: Int, val fraction: Double, val distance: Double)

    private fun nearest(pos: LatLon, from: Int, to: Int): Hit {
        var best = Hit(from, 0.0, Double.MAX_VALUE)
        val kx = cos(Math.toRadians(pos.lat)) * 111_320.0
        val ky = 110_540.0
        for (i in from..to) {
            val a = route.points[i]
            val b = route.points[i + 1]
            val ax = (a.lon - pos.lon) * kx
            val ay = (a.lat - pos.lat) * ky
            val bx = (b.lon - pos.lon) * kx
            val by = (b.lat - pos.lat) * ky
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + t * dx
            val py = ay + t * dy
            val d = sqrt(px * px + py * py)
            if (d < best.distance) best = Hit(i, t, d)
        }
        return best
    }
}

/** Draws the arrow that goes with a Valhalla maneuver type, in the watch's 1 bit icon format. */
object ManeuverIcon {
    private val cache = HashMap<Int, ByteArray?>()

    fun bits(type: Int): ByteArray? = cache.getOrPut(type) { NavParser.iconBits(draw(type)) }

    private fun draw(type: Int): Bitmap {
        val s = NavParser.ICON_SIZE
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 9f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

        when (type) {
            4, 5, 6 -> { // destination: a pin
                canvas.drawCircle(32f, 24f, 15f, stroke)
                canvas.drawCircle(32f, 24f, 5f, fill)
                canvas.drawLine(32f, 39f, 32f, 58f, stroke)
                return bmp
            }
            26, 27 -> { // roundabout: a ring with the way out on top
                canvas.drawCircle(32f, 36f, 14f, stroke)
                canvas.drawLine(32f, 22f, 32f, 8f, stroke)
                canvas.drawPath(head(32f, 6f, 0.0), fill)
                return bmp
            }
        }
        val angle = when (type) {
            9, 18, 20, 23 -> 45.0      // slight right, ramp or exit right, keep right
            2, 10 -> 90.0              // right
            11 -> 135.0                // sharp right
            16, 19, 21, 24 -> -45.0    // slight left, ramp or exit left, keep left
            3, 15 -> -90.0             // left
            14 -> -135.0               // sharp left
            12, 13 -> 180.0            // U-turn
            else -> 0.0                // straight on
        }
        if (angle == 180.0) {
            val path = Path().apply {
                moveTo(42f, 58f)
                lineTo(42f, 28f)
                cubicTo(42f, 10f, 20f, 10f, 20f, 28f)
                lineTo(20f, 40f)
            }
            canvas.drawPath(path, stroke)
            canvas.drawPath(head(20f, 46f, 180.0), fill)
            return bmp
        }
        // A stem from the bottom, bending towards the new direction.
        val rad = Math.toRadians(angle)
        val straight = angle == 0.0
        val bendX = 32f
        val bendY = if (straight) 20f else 36f
        val tipX = if (straight) bendX else bendX + (22 * sin(rad)).toFloat()
        val tipY = if (straight) bendY else bendY - (22 * cos(rad)).toFloat()
        val path = Path().apply {
            moveTo(32f, 58f)
            lineTo(bendX, bendY)
            if (!straight) lineTo(tipX, tipY)
        }
        canvas.drawPath(path, stroke)
        canvas.drawPath(head(tipX, tipY, angle), fill)
        return bmp
    }

    /** A triangle arrow head centred on (x, y), pointing along [angle] in degrees clockwise from up. */
    private fun head(x: Float, y: Float, angle: Double): Path {
        val rad = Math.toRadians(angle)
        val fx = sin(rad).toFloat()
        val fy = -cos(rad).toFloat()
        val size = 13f
        return Path().apply {
            moveTo(x + fx * size, y + fy * size)
            lineTo(x - fy * size, y + fx * size)
            lineTo(x + fy * size, y - fx * size)
            close()
        }
    }
}
