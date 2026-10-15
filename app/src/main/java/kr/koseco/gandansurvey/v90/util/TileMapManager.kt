package kr.koseco.gandansurvey.v90.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kr.koseco.gandansurvey.v90.model.BackgroundMapType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * Web Slippy Tile Map Manager for real-time surveying background maps:
 * - GOOGLE_SATELLITE (구글 항공사진)
 * - DAUM_AERIAL (다음/카카오 스카이뷰 항공사진)
 * - NAVER_SATELLITE (네이버 위성/항공사진)
 * - DAUM_ROAD (다음/카카오 일반지도)
 *
 * Converts Web Mercator (z, x, y) tiles to WGS84 bounds so they can be accurately
 * projected onto the Korean Plane Rectangular Coordinate (N, E) canvas.
 */
class TileMapManager(
    private val scope: CoroutineScope,
    private val onTileLoaded: () -> Unit
) {
    private val memoryCache = object : LruCache<String, ImageBitmap>(96) {}
    private val inFlightKeys = HashSet<String>()

    data class TileQuad(
        val z: Int,
        val x: Int,
        val y: Int,
        val northLat: Double,
        val southLat: Double,
        val westLon: Double,
        val eastLon: Double,
        val bitmap: ImageBitmap?
    )

    fun getVisibleTiles(
        mapType: BackgroundMapType,
        centerLat: Double,
        centerLon: Double,
        metersPerPixel: Double
    ): List<TileQuad> {
        if (mapType == BackgroundMapType.NONE) return emptyList()
        if (centerLat !in 30.0..43.0 || centerLon !in 120.0..135.0) return emptyList()

        val zoom = computeOptimalZoom(centerLat, metersPerPixel)
        val centerTileX = lonToTileX(centerLon, zoom)
        val centerTileY = latToTileY(centerLat, zoom)

        val result = ArrayList<TileQuad>(16)
        for (dx in -1..1) {
            for (dy in -2..2) {
                val tx = centerTileX + dx
                val ty = centerTileY + dy
                val maxTile = (1 shl zoom) - 1
                if (tx < 0 || tx > maxTile || ty < 0 || ty > maxTile) continue

                val key = "${mapType.name}:$zoom:$tx:$ty"
                val cached = synchronized(memoryCache) { memoryCache.get(key) }
                if (cached == null) {
                    requestTileAsync(mapType, zoom, tx, ty, key)
                }

                val north = tileYToLat(ty, zoom)
                val south = tileYToLat(ty + 1, zoom)
                val west = tileXToLon(tx, zoom)
                val east = tileXToLon(tx + 1, zoom)

                result.add(
                    TileQuad(
                        z = zoom,
                        x = tx,
                        y = ty,
                        northLat = north,
                        southLat = south,
                        westLon = west,
                        eastLon = east,
                        bitmap = cached
                    )
                )
            }
        }
        return result
    }

    private fun requestTileAsync(
        mapType: BackgroundMapType,
        z: Int,
        x: Int,
        y: Int,
        key: String
    ) {
        synchronized(inFlightKeys) {
            if (inFlightKeys.contains(key)) return
            inFlightKeys.add(key)
        }
        scope.launch(Dispatchers.IO) {
            try {
                val urls = buildCandidateTileUrls(mapType, z, x, y)
                var loadedBmp: Bitmap? = null
                for (u in urls) {
                    loadedBmp = downloadBitmap(u)
                    if (loadedBmp != null) break
                }
                if (loadedBmp != null) {
                    val img = loadedBmp.asImageBitmap()
                    synchronized(memoryCache) {
                        memoryCache.put(key, img)
                    }
                    onTileLoaded()
                }
            } catch (_: Exception) {
            } finally {
                synchronized(inFlightKeys) {
                    inFlightKeys.remove(key)
                }
            }
        }
    }

    private fun buildCandidateTileUrls(
        mapType: BackgroundMapType,
        z: Int,
        x: Int,
        y: Int
    ): List<String> {
        val sub = (x + y) and 3
        return when (mapType) {
            BackgroundMapType.GOOGLE_SATELLITE -> listOf(
                "https://mt$sub.google.com/vt/lyrs=y&hl=ko&x=$x&y=$y&z=$z",
                "https://mt$sub.google.com/vt/lyrs=s&hl=ko&x=$x&y=$y&z=$z"
            )
            BackgroundMapType.NAVER_SATELLITE -> listOf(
                "https://nrbe.pstatic.net/styles/satellite/$z/$x/$y.jpg",
                "https://map.pstatic.net/nrb/styles/satellite/$z/$x/$y.jpg?mt=bg.ol.ts.lko",
                "https://mt$sub.google.com/vt/lyrs=s&hl=ko&x=$x&y=$y&z=$z"
            )
            BackgroundMapType.DAUM_AERIAL -> listOf(
                "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x",
                "https://mt$sub.google.com/vt/lyrs=s&hl=ko&x=$x&y=$y&z=$z"
            )
            BackgroundMapType.DAUM_ROAD -> listOf(
                "https://mt$sub.google.com/vt/lyrs=m&hl=ko&x=$x&y=$y&z=$z",
                "https://tile.openstreetmap.org/$z/$x/$y.png"
            )
            BackgroundMapType.NONE -> emptyList()
        }
    }

    private fun downloadBitmap(urlStr: String): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; GandanSurveyV90) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36"
                )
                setRequestProperty("Accept", "image/webp,image/apng,image/*,*/*;q=0.8")
            }
            if (conn.responseCode == 200) {
                conn.inputStream.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun computeOptimalZoom(latDeg: Double, metersPerPixel: Double): Int {
        val equatorResolution = 156543.03392 * cos(Math.toRadians(latDeg))
        val rawZ = ln(equatorResolution / metersPerPixel.coerceAtLeast(0.05)) / ln(2.0)
        return rawZ.toInt().coerceIn(13, 20)
    }

    private fun lonToTileX(lon: Double, z: Int): Int {
        val n = (1 shl z).toDouble()
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, (1 shl z) - 1)
    }

    private fun latToTileY(lat: Double, z: Int): Int {
        val latRad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        val n = (1 shl z).toDouble()
        val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
        return floor(y).toInt().coerceIn(0, (1 shl z) - 1)
    }

    private fun tileXToLon(x: Int, z: Int): Double {
        val n = 2.0.pow(z.toDouble())
        return x / n * 360.0 - 180.0
    }

    private fun tileYToLat(y: Int, z: Int): Double {
        val n = PI - 2.0 * PI * y / 2.0.pow(z.toDouble())
        return Math.toDegrees(atan(0.5 * (exp(n) - exp(-n))))
    }
}
