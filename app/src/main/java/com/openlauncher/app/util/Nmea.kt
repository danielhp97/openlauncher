package com.openlauncher.app.util

/** One position from an external NMEA source (GPS2IP on the rider's iPhone). */
data class NmeaFix(
    val utcTime: String,          // hhmmss(.sss) as sent — only used to drop repeated sentences
    val latitude: Double,
    val longitude: Double,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val altitudeM: Double? = null,
    val hdop: Float? = null
)

/**
 * Minimal NMEA 0183 reader for phone-to-unit GPS sharing. GPS2IP (incl. the full
 * version) only sends RMC, which is enough for position, speed and heading; GGA is
 * used for altitude and HDOP when a source sends it.
 */
class NmeaReader {

    private var lastGga: GgaInfo? = null
    private var lastKey: String? = null

    /** Feeds one line; returns a new fix for each valid RMC, null otherwise. */
    fun accept(rawLine: String): NmeaFix? {
        val line = rawLine.trim()
        if (!Nmea.checksumOk(line)) return null
        val body = Nmea.body(line)
        val fields = body.split(',')
        val type = fields[0].takeLast(3)
        return when (type) {
            "GGA" -> { lastGga = Nmea.parseGga(fields); null }
            "RMC" -> {
                val fix = Nmea.parseRmc(fields) ?: return null
                // GPS2IP writes every sentence twice
                val key = "${fix.utcTime}|${fix.latitude}|${fix.longitude}"
                if (key == lastKey) return null
                lastKey = key
                val gga = lastGga?.takeIf { it.utcTime == fix.utcTime }
                fix.copy(altitudeM = gga?.altitudeM, hdop = gga?.hdop)
            }
            else -> null
        }
    }
}

internal data class GgaInfo(val utcTime: String, val altitudeM: Double?, val hdop: Float?)

internal object Nmea {

    private const val KNOTS_TO_MPS = 0.514444f

    /** Lines without a checksum are accepted; lines with a wrong one are dropped. */
    fun checksumOk(line: String): Boolean {
        if (!line.startsWith("$")) return false
        val star = line.lastIndexOf('*')
        if (star < 0) return true
        val expected = line.substring(star + 1).take(2).toIntOrNull(16) ?: return false
        var sum = 0
        for (i in 1 until star) sum = sum xor line[i].code
        return sum == expected
    }

    fun body(line: String): String {
        val star = line.lastIndexOf('*')
        return line.substring(1, if (star < 0) line.length else star)
    }

    /** $xxRMC,time,status,lat,N/S,lon,E/W,speedKnots,course,date,... — null unless status is A. */
    fun parseRmc(f: List<String>): NmeaFix? {
        if (f.size < 9 || f[2] != "A") return null
        val lat = coordinate(f[3], f[4], degreeDigits = 2) ?: return null
        val lon = coordinate(f[5], f[6], degreeDigits = 3) ?: return null
        return NmeaFix(
            utcTime    = f[1],
            latitude   = lat,
            longitude  = lon,
            speedMps   = f[7].toFloatOrNull()?.let { it * KNOTS_TO_MPS },
            bearingDeg = f[8].toFloatOrNull()
        )
    }

    /** $xxGGA,time,lat,N/S,lon,E/W,quality,sats,hdop,alt,M,... */
    fun parseGga(f: List<String>): GgaInfo? {
        if (f.size < 10 || (f[6].toIntOrNull() ?: 0) == 0) return null
        return GgaInfo(f[1], f[9].toDoubleOrNull(), f[8].toFloatOrNull())
    }

    /** ddmm.mmmm / dddmm.mmmm plus hemisphere → signed decimal degrees. */
    fun coordinate(value: String, hemisphere: String, degreeDigits: Int): Double? {
        if (value.length <= degreeDigits) return null
        val degrees = value.substring(0, degreeDigits).toIntOrNull() ?: return null
        val minutes = value.substring(degreeDigits).toDoubleOrNull() ?: return null
        if (minutes >= 60.0) return null
        val abs = degrees + minutes / 60.0
        return when (hemisphere) {
            "N", "E" -> abs
            "S", "W" -> -abs
            else -> null
        }
    }
}
