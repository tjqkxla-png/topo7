package kr.koseco.gandansurvey.v90.gnss

import android.util.Base64
import kr.koseco.gandansurvey.v90.model.NtripConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.abs

/**
 * NGII (국토지리정보원 RTS2.ngii.go.kr:2101) & Commercial NTRIP v1.0 / v2.0 Client
 * Streams RTCM3 corrections and periodically uplinks receiver $GPGGA for VRS virtual base generation.
 */
class NtripClient(
    private val scope: CoroutineScope,
    private val onRtcmReceived: (ByteArray, Int, Int) -> Unit,
    private val onStatusChanged: (Boolean, String) -> Unit
) {
    private var streamJob: Job? = null
    private var socket: Socket? = null

    @Volatile
    private var latestGgaSentence: String = ""

    fun updateLatestGga(gga: String) {
        if (gga.startsWith("$") && gga.contains("GGA")) {
            latestGgaSentence = gga
        }
    }

    /**
     * Fetches available mountpoints from NTRIP Caster sourcetable.
     */
    suspend fun fetchMountpoints(host: String, port: Int): Result<List<String>> = withContext(Dispatchers.IO) {
        val tempSocket = Socket()
        try {
            tempSocket.connect(InetSocketAddress(host.trim(), port), 6000)
            tempSocket.soTimeout = 6000

            val req = buildString {
                append("GET / HTTP/1.0\r\n")
                append("User-Agent: NTRIP GandanSurveyV90/2.0\r\n")
                append("Accept: */*\r\n")
                append("Connection: close\r\n\r\n")
            }
            val out = tempSocket.getOutputStream()
            out.write(req.toByteArray(StandardCharsets.US_ASCII))
            out.flush()

            val reader = BufferedReader(InputStreamReader(tempSocket.getInputStream(), StandardCharsets.US_ASCII))
            val mounts = mutableListOf<String>()
            var line: String? = reader.readLine()
            while (line != null) {
                if (line.startsWith("STR;")) {
                    val tokens = line.split(";")
                    if (tokens.size > 1 && tokens[1].isNotBlank()) {
                        val mountName = tokens[1].trim()
                        val format = tokens.getOrNull(3)?.trim() ?: ""
                        mounts.add(if (format.isNotEmpty()) "$mountName ($format)" else mountName)
                    }
                }
                if (line.contains("ENDSOURCETABLE")) break
                line = reader.readLine()
            }
            if (mounts.isEmpty()) {
                mounts.addAll(
                    listOf(
                        "VRS-RTCM32 (RTCM 3.2 MSM4)",
                        "VRS-RTCM31 (RTCM 3.1)",
                        "FKP-RTCM32 (RTCM 3.2)",
                        "SSR-RTCM32 (RTCM 3.2)"
                    )
                )
            }
            Result.success(mounts)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                tempSocket.close()
            } catch (_: Exception) {}
        }
    }

    fun startStreaming(
        config: NtripConfig,
        fallbackLat: Double,
        fallbackLon: Double,
        fallbackHeight: Double
    ) {
        stopStreaming()
        val cleanMount = config.mountpoint.substringBefore(" ").trim()
        streamJob = scope.launch(Dispatchers.IO) {
            try {
                onStatusChanged(false, "VRS 서버(${config.host}:${config.port}) 접속 중...")
                val s = Socket()
                s.connect(InetSocketAddress(config.host.trim(), config.port), 8000)
                s.soTimeout = 15000
                socket = s

                val authRaw = "${config.username.trim()}:${config.password.trim()}"
                val authB64 = Base64.encodeToString(authRaw.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)

                val requestHeader = buildString {
                    append("GET /$cleanMount HTTP/1.0\r\n")
                    append("Host: ${config.host.trim()}\r\n")
                    append("User-Agent: NTRIP GandanSurveyV90/2.0\r\n")
                    append("Accept: */*\r\n")
                    append("Authorization: Basic $authB64\r\n")
                    append("\r\n")
                }

                val out = s.getOutputStream()
                out.write(requestHeader.toByteArray(StandardCharsets.US_ASCII))
                out.flush()

                val input = s.getInputStream()
                val headerBuf = ByteArray(1024)
                val headerLen = input.read(headerBuf)
                if (headerLen <= 0) {
                    onStatusChanged(false, "VRS 서버 응답 없음")
                    stopStreaming()
                    return@launch
                }
                val headerStr = String(headerBuf, 0, headerLen, StandardCharsets.US_ASCII)
                if (!headerStr.contains("ICY 200 OK", ignoreCase = true) &&
                    !headerStr.contains("200 OK", ignoreCase = true)
                ) {
                    val firstLine = headerStr.lineSequence().firstOrNull() ?: "응답 오류"
                    onStatusChanged(false, "VRS 인증/마운트 거부: $firstLine")
                    stopStreaming()
                    return@launch
                }

                onStatusChanged(true, "VRS 연결됨 ($cleanMount)")

                // Send initial GGA sentence immediately for VRS virtual station initialization
                val initialGga = latestGgaSentence.ifBlank {
                    synthesizeGga(fallbackLat, fallbackLon, fallbackHeight)
                }
                out.write("$initialGga\r\n".toByteArray(StandardCharsets.US_ASCII))
                out.flush()

                var lastGgaSentTime = System.currentTimeMillis()
                var bytesWindow = 0
                var windowStart = System.currentTimeMillis()
                val buffer = ByteArray(4096)

                while (isActive && !s.isClosed) {
                    val readCount = input.read(buffer)
                    if (readCount == -1) break
                    if (readCount > 0) {
                        bytesWindow += readCount
                        val now = System.currentTimeMillis()
                        val elapsedSec = ((now - windowStart) / 1000.0).coerceAtLeast(0.5)
                        val bps = (bytesWindow / elapsedSec).toInt()
                        if (now - windowStart >= 2000L) {
                            bytesWindow = 0
                            windowStart = now
                        }
                        onRtcmReceived(buffer, readCount, bps)
                    }

                    val now = System.currentTimeMillis()
                    if (config.sendGgaPeriodSec > 0 && now - lastGgaSentTime >= config.sendGgaPeriodSec * 1000L) {
                        val ggaToSend = latestGgaSentence.ifBlank {
                            synthesizeGga(fallbackLat, fallbackLon, fallbackHeight)
                        }
                        out.write("$ggaToSend\r\n".toByteArray(StandardCharsets.US_ASCII))
                        out.flush()
                        lastGgaSentTime = now
                    }
                }
                onStatusChanged(false, "VRS 스트림 종료됨")
            } catch (e: Exception) {
                onStatusChanged(false, "VRS 오류: ${e.message ?: "연결 끊김"}")
            } finally {
                closeSocketQuietly()
            }
        }
    }

    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
        closeSocketQuietly()
    }

    private fun closeSocketQuietly() {
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
    }

    /**
     * Builds a valid NMEA $GPGGA sentence with XOR checksum if receiver GGA hasn't arrived yet.
     */
    private fun synthesizeGga(lat: Double, lon: Double, height: Double): String {
        val latHem = if (lat >= 0) "N" else "S"
        val lonHem = if (lon >= 0) "E" else "W"
        val absLat = abs(lat)
        val absLon = abs(lon)
        val latDeg = absLat.toInt()
        val latMin = (absLat - latDeg) * 60.0
        val lonDeg = absLon.toInt()
        val lonMin = (absLon - lonDeg) * 60.0

        val body = String.format(
            Locale.US,
            "GPGGA,000001.00,%02d%08.5f,%s,%03d%08.5f,%s,1,12,0.9,%.3f,M,24.8,M,,",
            latDeg, latMin, latHem,
            lonDeg, lonMin, lonHem,
            height
        )
        var xor = 0
        for (ch in body) {
            xor = xor xor ch.code
        }
        return String.format(Locale.US, "\$%s*%02X", body, xor)
    }
}
