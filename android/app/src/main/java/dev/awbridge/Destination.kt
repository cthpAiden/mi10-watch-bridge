package dev.awbridge

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Turns whatever the user shared or typed into a point on the map.
 *
 * Understands plain "lat, lon", geo: links and Google Maps links that carry
 * coordinates (a dropped pin always does). A link or text without coordinates
 * is looked up by name with the OpenStreetMap geocoders Photon and Nominatim.
 */
object Destination {
    class Result(val point: LatLon, val label: String)

    private const val USER_AGENT = "AWBridge/1.0 (+https://github.com/cthpAiden/mi10-watch-bridge)"
    private const val NUMBER = """(-?\d{1,3}\.\d+)"""

    private val plain = Regex("""^\s*(-?\d{1,3}(?:\.\d+)?)\s*[,;\s]\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")
    private val link = Regex("""https?://\S+""")
    private val inUrl = listOf(
        Regex("!3d$NUMBER!4d$NUMBER"),
        Regex("""[?&](?:q|query|ll|sll|destination|daddr|center)=$NUMBER(?:,|%2C)\+?$NUMBER"""),
        Regex("""/(?:search|place|dir)/$NUMBER,\+?$NUMBER"""),
        Regex("@$NUMBER,$NUMBER")
    )

    /** Blocking. [near] biases the name search towards where the user is. Null when nothing matched. */
    fun resolve(input: String, near: LatLon?): Result? {
        val text = input.trim()
        if (text.isEmpty()) return null

        plain.find(text)?.let { m -> point(m.groupValues[1], m.groupValues[2])?.let { return Result(it, "Điểm đã chọn") } }

        if (text.startsWith("geo:", ignoreCase = true) || text.startsWith("google.navigation:", ignoreCase = true)) {
            return fromGeo(text, near)
        }

        val url = link.find(text)?.value
        val words = text.replace(link, " ").lines().map { it.trim() }.filter { it.isNotEmpty() }
        val label = words.firstOrNull() ?: "Điểm đến"
        if (url != null) {
            val expanded = expand(url)
            for (candidate in expanded) {
                coordinates(candidate)?.let { return Result(it, label) }
            }
            // No coordinates in the link: the place name is in its path.
            val named = expanded.lastOrNull()?.let { placeName(it) }
            if (words.isEmpty() && named != null) {
                return geocode(named, near)?.let { Result(it, named) }
            }
        }
        if (words.isEmpty()) return null
        // Name plus address first, then the name alone.
        val full = words.take(2).joinToString(", ")
        geocode(full, near)?.let { return Result(it, label) }
        if (words.size > 1) {
            geocode(words[1], near)?.let { return Result(it, label) }
            geocode(words[0], near)?.let { return Result(it, label) }
        }
        return null
    }

    private fun fromGeo(text: String, near: LatLon?): Result? {
        val body = text.substringAfter(':')
        val head = body.substringBefore('?')
        val query = Regex("""[?&]q=([^&]+)""").find(body)?.groupValues?.get(1)?.let { decode(it) }
        if (query != null) {
            // "lat,lon(label)" or free text.
            val coords = Regex("""^\s*$NUMBER\s*,\s*$NUMBER""").find(query)
            if (coords != null) {
                val name = query.substringAfter('(', "").substringBeforeLast(')', "").ifEmpty { "Điểm đến" }
                point(coords.groupValues[1], coords.groupValues[2])?.let { return Result(it, name) }
            }
        }
        val parts = head.split(',')
        if (parts.size >= 2) {
            val p = point(parts[0], parts[1])
            if (p != null && !(p.lat == 0.0 && p.lon == 0.0)) return Result(p, query ?: "Điểm đến")
        }
        if (query != null) return geocode(query, near)?.let { Result(it, query) }
        return null
    }

    private fun coordinates(url: String): LatLon? {
        for (pattern in inUrl) {
            val m = pattern.find(url) ?: continue
            point(m.groupValues[1], m.groupValues[2])?.let { return it }
        }
        return null
    }

    private fun placeName(url: String): String? {
        val raw = Regex("""/maps/(?:place|search)/([^/@?]+)""").find(url)?.groupValues?.get(1) ?: return null
        return decode(raw).replace('+', ' ').trim().ifEmpty { null }
    }

    private fun point(lat: String, lon: String): LatLon? {
        val a = lat.toDoubleOrNull() ?: return null
        val b = lon.toDoubleOrNull() ?: return null
        if (a < -90 || a > 90 || b < -180 || b > 180) return null
        return LatLon(a, b)
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Throwable) {
        s
    }

    /** Follows redirects by hand so every address on the way can be searched for coordinates. */
    private fun expand(start: String): List<String> {
        val seen = ArrayList<String>()
        var url = start
        for (hop in 0 until 6) {
            seen.add(decode(url))
            val conn = try {
                URL(url).openConnection() as HttpURLConnection
            } catch (_: Throwable) {
                break
            }
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
                val code = conn.responseCode
                val next = conn.getHeaderField("Location")
                if (code !in 300..399 || next.isNullOrEmpty()) break
                url = URL(URL(url), next).toString()
            } catch (_: Throwable) {
                break
            } finally {
                conn.disconnect()
            }
        }
        return seen
    }

    private fun geocode(query: String, near: LatLon?): LatLon? = photon(query, near) ?: nominatim(query)

    private fun photon(query: String, near: LatLon?): LatLon? = try {
        var url = "https://photon.komoot.io/api/?limit=1&q=" + URLEncoder.encode(query, "UTF-8")
        if (near != null) url += "&lat=${near.lat}&lon=${near.lon}"
        val features = JSONObject(get(url)).optJSONArray("features")
        val coords = features?.optJSONObject(0)?.optJSONObject("geometry")?.optJSONArray("coordinates")
        if (coords != null && coords.length() >= 2) LatLon(coords.getDouble(1), coords.getDouble(0)) else null
    } catch (_: Throwable) {
        null
    }

    private fun nominatim(query: String): LatLon? = try {
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=" +
            URLEncoder.encode(query, "UTF-8")
        val first = JSONArray(get(url)).optJSONObject(0)
        if (first != null) point(first.optString("lat"), first.optString("lon")) else null
    } catch (_: Throwable) {
        null
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept-Language", "vi")
        try {
            return conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }
}
