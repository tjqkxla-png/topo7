package kr.koseco.gandansurvey.v90.gnss

import kr.koseco.gandansurvey.v90.model.FixQuality
import kr.koseco.gandansurvey.v90.model.GnssStatus
import kr.koseco.gandansurvey.v90.model.SatelliteInfo
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hi-Target V90 Plus / V90 Standard NMEA-0183 Multi-Constellation Parser
 * Supports: $GPGGA/$GNGGA, $GPRMC/$GNRMC, $GPGSA/$GNGSA, $GPGST/$GNGST,
 *           $GPGSV/$GLGSV/$GAGSV/$GBGSV/$BDGSV/$GNGSV, and Hi-Target $PHTG / Tilt sensors.
 * Also supports extracting NMEA sentences embedded inside mixed binary/ASCII streams.
 */
class NmeaParser {

    private var currentStatus = GnssStatus()
    private val satelliteMap = mutableMapOf<String, SatelliteInfo>()
    private val usedPrns = mutableSetOf<Int>()

    fun getStatus(): GnssStatus = currentStatus

    fun reset() {
        currentStatus = GnssStatus()
        satelliteMap.clear()
        usedPrns.clear()
    }

    /**
     * Extracts all valid ASCII NMEA sentences (starting with '$' or '!') from a mixed line or buffer.
     */
    fun extractAndParseAll(rawChunk: String): GnssStatus {
        var idx = 0
        val len = rawChunk.length
        while (idx < len) {
            val start = rawChunk.indexOf('$', idx)
            if (start == -1) break
            var end = start + 1
            while (end < len && rawChunk[end] != '\r' && rawChunk[end] != '\n' && rawChunk[end] != '$') {
                end++
            }
            val candidate = rawChunk.substring(start, end).trim()
            if (candidate.length >= 6) {
                parseLine(candidate)
            }
            idx = if (rawChunk.getOrNull(end) == '$') end else end + 1
        }
        return currentStatus
    }

    fun parseLine(rawLine: String): GnssStatus {
        val dollarIdx = rawLine.indexOf('$')
        if (dollarIdx == -1) return currentStatus
        val line = rawLine.substring(dollarIdx).trim()
        if (line.length < 6) return currentStatus

        val starIdx = line.indexOf('*')
        val payload = if (starIdx != -1) {
            if (!verifyChecksum(line, starIdx)) {
                // Allow slightly truncated lines without strict checksum failure if needed,
                // or reject corrupted frames:
                return currentStatus
            }
            line.substring(0, starIdx)
        } else {
            line
        }

        val parts = payload.split(",")
        if (parts.isEmpty()) return currentStatus
        val type = parts[0].uppercase(Locale.US)

        when {
            type.endsWith("GGA") -> parseGga(parts, line)
            type.endsWith("RMC") -> parseRmc(parts)
            type.endsWith("GSA") -> parseGsa(parts)
            type.endsWith("GST") -> parseGst(parts)
            type.endsWith("GSV") -> parseGsv(type, parts)
            type.contains("PHTG") || type.contains("HTG") -> parseHiTargetProprietary(parts)
        }
        return currentStatus
    }

    private fun parseGga(parts: List<String>, rawSentence: String) {
        if (parts.size < 10) return
        val utcTime = formatUtc(parts.getOrNull(1) ?: "")
        val lat = parseNmeaCoord(parts.getOrNull(2) ?: "", parts.getOrNull(3) ?: "N")
        val lon = parseNmeaCoord(parts.getOrNull(4) ?: "", parts.getOrNull(5) ?: "E")
        val fixCode = parts.getOrNull(6)?.toIntOrNull() ?: 0
        val satUsed = parts.getOrNull(7)?.toIntOrNull() ?: 0
        val hdop = parts.getOrNull(8)?.toDoubleOrNull() ?: 99.9
        val orthoH = parts.getOrNull(9)?.toDoubleOrNull() ?: 0.0
        val geoidSep = parts.getOrNull(11)?.toDoubleOrNull() ?: 0.0
        val diffAge = parts.getOrNull(13)?.toDoubleOrNull() ?: 0.0
        val baseId = parts.getOrNull(14) ?: ""

        val ellipsoidalH = orthoH + geoidSep
        val fixQuality = FixQuality.fromGgaCode(fixCode)

        val estHrms = if (currentStatus.hrms > 0.0 && currentStatus.hrms < 50.0) {
            currentStatus.hrms
        } else {
            when (fixQuality) {
                FixQuality.RTK_FIXED -> 0.008 * hdop.coerceIn(0.7, 2.5)
                FixQuality.RTK_FLOAT -> 0.18 * hdop.coerceIn(0.8, 3.0)
                FixQuality.DGPS -> 0.45 * hdop.coerceIn(0.8, 3.0)
                FixQuality.SINGLE -> 1.5 * hdop.coerceIn(0.8, 3.0)
                else -> 99.9
            }
        }
        val estVrms = if (currentStatus.vrms > 0.0 && currentStatus.vrms < 50.0) {
            currentStatus.vrms
        } else {
            estHrms * 1.65
        }

        val trackedCount = maxOf(satelliteMap.size, satUsed)

        currentStatus = currentStatus.copy(
            latitude = if (lat != 0.0) lat else currentStatus.latitude,
            longitude = if (lon != 0.0) lon else currentStatus.longitude,
            ellipsoidalHeight = if (lat != 0.0) ellipsoidalH else currentStatus.ellipsoidalHeight,
            orthometricHeight = if (lat != 0.0) orthoH else currentStatus.orthometricHeight,
            nmeaGeoidSeparation = geoidSep,
            fixQuality = fixQuality,
            satellitesUsed = satUsed,
            satellitesTracked = trackedCount,
            hdop = hdop,
            hrms = estHrms,
            vrms = estVrms,
            diffAge = diffAge,
            baseStationId = baseId,
            utcTime = utcTime.ifEmpty { currentStatus.utcTime },
            lastGgaSentence = rawSentence
        )
    }

    private fun parseRmc(parts: List<String>) {
        if (parts.size < 9) return
        val statusChar = parts.getOrNull(2) ?: "V"
        val lat = parseNmeaCoord(parts.getOrNull(3) ?: "", parts.getOrNull(4) ?: "N")
        val lon = parseNmeaCoord(parts.getOrNull(5) ?: "", parts.getOrNull(6) ?: "E")
        val knots = parts.getOrNull(7)?.toDoubleOrNull() ?: 0.0
        val heading = parts.getOrNull(8)?.toDoubleOrNull() ?: 0.0

        var updatedFix = currentStatus.fixQuality
        if (statusChar == "A" && updatedFix == FixQuality.NO_FIX && lat != 0.0) {
            updatedFix = FixQuality.SINGLE
        }

        currentStatus = currentStatus.copy(
            latitude = if (lat != 0.0) lat else currentStatus.latitude,
            longitude = if (lon != 0.0) lon else currentStatus.longitude,
            fixQuality = updatedFix,
            speedKmh = knots * 1.852,
            headingDeg = heading
        )
    }

    private fun parseGsa(parts: List<String>) {
        if (parts.size < 17) return
        for (i in 3..14) {
            val prn = parts.getOrNull(i)?.toIntOrNull()
            if (prn != null && prn > 0) {
                usedPrns.add(prn)
            }
        }
        val pdop = parts.getOrNull(15)?.toDoubleOrNull() ?: currentStatus.pdop
        val hdop = parts.getOrNull(16)?.toDoubleOrNull() ?: currentStatus.hdop
        val vdop = parts.getOrNull(17)?.toDoubleOrNull() ?: currentStatus.vdop

        currentStatus = currentStatus.copy(
            pdop = pdop,
            hdop = hdop,
            vdop = vdop
        )
    }

    private fun parseGst(parts: List<String>) {
        if (parts.size < 9) return
        val stdLat = parts.getOrNull(6)?.toDoubleOrNull() ?: return
        val stdLon = parts.getOrNull(7)?.toDoubleOrNull() ?: return
        val stdAlt = parts.getOrNull(8)?.toDoubleOrNull() ?: return
        val hrms = sqrt(stdLat * stdLat + stdLon * stdLon)
        currentStatus = currentStatus.copy(
            hrms = hrms,
            vrms = stdAlt
        )
    }

    private fun parseGsv(talker: String, parts: List<String>) {
        if (parts.size < 4) return
        val constellation = when {
            talker.contains("GP") -> "GPS"
            talker.contains("GL") -> "GLONASS"
            talker.contains("GA") -> "Galileo"
            talker.contains("GB") || talker.contains("BD") -> "BeiDou"
            else -> "GNSS"
        }

        val totalSatsInView = parts.getOrNull(3)?.toIntOrNull() ?: 0

        var idx = 4
        while (idx + 3 < parts.size) {
            val prn = parts.getOrNull(idx)?.toIntOrNull()
            val elev = parts.getOrNull(idx + 1)?.toIntOrNull() ?: 0
            val azim = parts.getOrNull(idx + 2)?.toIntOrNull() ?: 0
            val snrStr = parts.getOrNull(idx + 3)?.takeWhile { it.isDigit() } ?: ""
            val snr = snrStr.toIntOrNull() ?: 0

            if (prn != null && prn > 0) {
                val key = "$constellation-$prn"
                satelliteMap[key] = SatelliteInfo(
                    prn = prn,
                    constellation = constellation,
                    elevation = elev,
                    azimuth = azim,
                    snr = snr,
                    usedInFix = usedPrns.contains(prn) || snr >= 25
                )
            }
            idx += 4
        }

        val list = satelliteMap.values.sortedWith(compareBy({ it.constellation }, { it.prn }))
        val trackedCount = maxOf(list.size, currentStatus.satellitesUsed, totalSatsInView)
        currentStatus = currentStatus.copy(
            satellites = list,
            satellitesTracked = trackedCount
        )
    }

    /**
     * Parses Hi-Target V90 proprietary IMU/Tilt sentence if output by the receiver
     */
    private fun parseHiTargetProprietary(parts: List<String>) {
        val tilt = parts.getOrNull(2)?.toDoubleOrNull() ?: return
        val azimuth = parts.getOrNull(3)?.toDoubleOrNull() ?: 0.0
        val battery = parts.getOrNull(4)?.toIntOrNull() ?: currentStatus.receiverBatteryPercent
        currentStatus = currentStatus.copy(
            imuTiltAngleDeg = tilt,
            imuTiltAzimuthDeg = azimuth,
            imuTiltActive = tilt > 0.5,
            receiverBatteryPercent = battery
        )
    }

    /**
     * Computes IMU pole tip ground displacement (dN, dE, dH) from tilt angle, azimuth, and pole height.
     */
    fun computeTiltCorrection(
        tiltDeg: Double,
        azimuthDeg: Double,
        poleHeight: Double
    ): Triple<Double, Double, Double> {
        if (tiltDeg <= 0.05 || poleHeight <= 0.0) return Triple(0.0, 0.0, poleHeight)
        val tiltRad = Math.toRadians(tiltDeg)
        val azimRad = Math.toRadians(azimuthDeg)
        val horizShift = poleHeight * sin(tiltRad)
        val dN = -horizShift * cos(azimRad)
        val dE = -horizShift * sin(azimRad)
        val effectiveVertical = poleHeight * cos(tiltRad)
        return Triple(dN, dE, effectiveVertical)
    }

    private fun parseNmeaCoord(raw: String, hemi: String): Double {
        if (raw.isBlank()) return 0.0
        val dotIdx = raw.indexOf('.')
        if (dotIdx < 2) return 0.0
        val degPart = raw.substring(0, dotIdx - 2).toDoubleOrNull() ?: return 0.0
        val minPart = raw.substring(dotIdx - 2).toDoubleOrNull() ?: return 0.0
        var dec = degPart + (minPart / 60.0)
        if (hemi.equals("S", ignoreCase = true) || hemi.equals("W", ignoreCase = true)) {
            dec = -dec
        }
        return dec
    }

    private fun formatUtc(raw: String): String {
        if (raw.length < 6) return raw
        val hh = raw.substring(0, 2)
        val mm = raw.substring(2, 4)
        val ss = raw.substring(4, 6)
        return "$hh:$mm:$ss UTC"
    }

    private fun verifyChecksum(line: String, starIdx: Int): Boolean {
        if (starIdx + 3 > line.length) return false
        val expectedHex = line.substring(starIdx + 1, starIdx + 3)
        val expected = expectedHex.toIntOrNull(16) ?: return false
        var xor = 0
        for (i in 1 until starIdx) {
            xor = xor xor line[i].code
        }
        return xor == expected
    }
}
