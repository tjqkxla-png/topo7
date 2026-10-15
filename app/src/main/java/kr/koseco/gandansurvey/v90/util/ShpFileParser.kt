package kr.koseco.gandansurvey.v90.util

import kr.koseco.gandansurvey.v90.coord.CoordinateConverter
import kr.koseco.gandansurvey.v90.model.CadEntityType
import kr.koseco.gandansurvey.v90.model.CadLayerEntity
import kr.koseco.gandansurvey.v90.model.KoreanCoordinateSystem
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Pure Kotlin ESRI Shapefile (.shp) Binary Parser for V-World (브이월드 연속지적도 및 지적도근점)
 * Supports Shape Types:
 *  - 1: Point, 11: PointZ, 21: PointM
 *  - 3: PolyLine, 13: PolyLineZ, 23: PolyLineM
 *  - 5: Polygon, 15: PolygonZ, 25: PolygonM
 *  - 8: MultiPoint
 *
 * Also performs automatic coordinate system detection & transformation into the current
 * project's KoreanCoordinateSystem (e.g., converting V-World EPSG:5179 UTM-K or WGS84 or
 * EPSG:5186 into the user's active project coordinate system).
 */
object ShpFileParser {

    data class ShpParseResult(
        val entities: List<CadLayerEntity>,
        val detectedSourceEpsg: String,
        val summaryMessage: String
    )

    fun parseShpBytes(
        bytes: ByteArray,
        targetSystem: KoreanCoordinateSystem,
        sourceSystemHint: KoreanCoordinateSystem? = null,
        layerLabel: String = "SHP 지적도"
    ): ShpParseResult {
        if (bytes.size < 100) {
            return ShpParseResult(emptyList(), "오류", "유효한 SHP 파일이 아닙니다 (크기 부족).")
        }

        val headerBuf = ByteBuffer.wrap(bytes, 0, 100)
        headerBuf.order(ByteOrder.BIG_ENDIAN)
        val fileCode = headerBuf.int
        if (fileCode != 9994) {
            return ShpParseResult(emptyList(), "오류", "SHP 헤더 코드(9994)가 일치하지 않습니다.")
        }

        headerBuf.order(ByteOrder.LITTLE_ENDIAN)
        val shapeType = headerBuf.getInt(32)
        val minX = headerBuf.getDouble(36)
        val minY = headerBuf.getDouble(44)

        // Detect source coordinate system from bounding box if not explicitly provided
        val detectedSource = sourceSystemHint ?: detectCoordinateSystemFromBounds(minX, minY, targetSystem)

        val entities = mutableListOf<CadLayerEntity>()
        var offset = 100
        val totalLen = bytes.size
        var pointCount = 0
        var lineCount = 0
        var polyCount = 0

        while (offset + 8 <= totalLen) {
            val recHeader = ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.BIG_ENDIAN)
            val recordNumber = recHeader.int
            val contentLengthWords = recHeader.int
            val contentBytes = contentLengthWords * 2
            offset += 8

            if (contentBytes <= 0 || offset + contentBytes > totalLen) break

            val recBuf = ByteBuffer.wrap(bytes, offset, contentBytes).order(ByteOrder.LITTLE_ENDIAN)
            val recShapeType = recBuf.int

            when (recShapeType) {
                0 -> {
                    // Null shape
                }
                1, 11, 21 -> {
                    // Point / PointZ / PointM
                    if (recBuf.remaining() >= 16) {
                        val rawX = recBuf.double
                        val rawY = recBuf.double
                        val rawZ = if (recShapeType == 11 && recBuf.remaining() >= 8) recBuf.double else 0.0
                        val (n, e) = transformToTarget(rawX, rawY, detectedSource, targetSystem)
                        pointCount++
                        entities.add(
                            CadLayerEntity(
                                id = UUID.randomUUID().toString(),
                                type = CadEntityType.POINT,
                                layerName = layerLabel,
                                verticesNE = listOf(Pair(n, e)),
                                elevationZ = rawZ,
                                textLabel = "도근점-$recordNumber",
                                colorHex = 0xFF38BDF8,
                                sourceType = "SHP"
                            )
                        )
                    }
                }
                8, 18, 28 -> {
                    // MultiPoint
                    if (recBuf.remaining() >= 36) {
                        recBuf.position(recBuf.position() + 32) // skip bbox
                        val numPoints = recBuf.int
                        for (i in 0 until numPoints) {
                            if (recBuf.remaining() < 16) break
                            val rawX = recBuf.double
                            val rawY = recBuf.double
                            val (n, e) = transformToTarget(rawX, rawY, detectedSource, targetSystem)
                            pointCount++
                            entities.add(
                                CadLayerEntity(
                                    id = UUID.randomUUID().toString(),
                                    type = CadEntityType.POINT,
                                    layerName = layerLabel,
                                    verticesNE = listOf(Pair(n, e)),
                                    textLabel = "SHP-$recordNumber-${i + 1}",
                                    colorHex = 0xFF38BDF8,
                                    sourceType = "SHP"
                                )
                            )
                        }
                    }
                }
                3, 5, 13, 15, 23, 25 -> {
                    // PolyLine (3,13,23) or Polygon (5,15,25)
                    val isPolygon = (recShapeType == 5 || recShapeType == 15 || recShapeType == 25)
                    if (recBuf.remaining() >= 40) {
                        recBuf.position(recBuf.position() + 32) // skip bbox (minX, minY, maxX, maxY)
                        val numParts = recBuf.int
                        val numPoints = recBuf.int
                        if (numParts > 0 && numPoints > 0 && recBuf.remaining() >= numParts * 4 + numPoints * 16) {
                            val partIndices = IntArray(numParts)
                            for (p in 0 until numParts) {
                                partIndices[p] = recBuf.int
                            }
                            val allPts = ArrayList<Pair<Double, Double>>(numPoints)
                            for (ptIdx in 0 until numPoints) {
                                val rawX = recBuf.double
                                val rawY = recBuf.double
                                allPts.add(transformToTarget(rawX, rawY, detectedSource, targetSystem))
                            }

                            for (p in 0 until numParts) {
                                val startIdx = partIndices[p].coerceIn(0, allPts.size)
                                val endIdx = if (p + 1 < numParts) {
                                    partIndices[p + 1].coerceIn(startIdx, allPts.size)
                                } else {
                                    allPts.size
                                }
                                if (endIdx - startIdx >= 2) {
                                    val ring = allPts.subList(startIdx, endIdx).toList()
                                    if (isPolygon) polyCount++ else lineCount++
                                    entities.add(
                                        CadLayerEntity(
                                            id = UUID.randomUUID().toString(),
                                            type = if (isPolygon) CadEntityType.POLYGON else CadEntityType.POLYLINE,
                                            layerName = layerLabel,
                                            verticesNE = ring,
                                            isClosed = isPolygon,
                                            colorHex = if (isPolygon) 0xFFF59E0B else 0xFF38BDF8,
                                            sourceType = "SHP"
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
            offset += contentBytes
        }

        val sourceLabel = detectedSource?.displayName ?: "WGS84 경위도(EPSG:4326)"
        val summary = "SHP 로드 완료 (원본: $sourceLabel -> ${targetSystem.displayName}) | 필지/폴리곤 ${polyCount}개, 선형 ${lineCount}개, 점 ${pointCount}개"
        return ShpParseResult(entities, sourceLabel, summary)
    }

    /**
     * Automatically detects whether SHP X/Y coordinates are:
     * - WGS84 Lon/Lat (X ~ 124..132, Y ~ 33..39)
     * - UTM-K EPSG:5179 (X(East) ~ 800,000..1,300,000, Y(North) ~ 1,400,000..2,200,000)
     * - GRS80 TM 200,000 False Easting (X(East) ~ 100,000..350,000, Y(North) ~ 350,000..700,000)
     */
    private fun detectCoordinateSystemFromBounds(
        x: Double,
        y: Double,
        targetSystem: KoreanCoordinateSystem
    ): KoreanCoordinateSystem? {
        // Case 1: WGS84 Longitude / Latitude
        if (x in 120.0..135.0 && y in 30.0..43.0) {
            return null // null signifies WGS84 Lon/Lat
        }
        // Case 2: UTM-K (EPSG:5179) where X(East) is ~900,000..1,200,000 and Y(North) is ~1,500,000..2,100,000
        if (x in 700000.0..1400000.0 && y in 1300000.0..2300000.0) {
            return KoreanCoordinateSystem.UTMK_GRS80
        }
        // Case 3: Standard Korean TM (X=East ~ 100,000..400,000, Y=North ~ 300,000..750,000)
        // Use the active project coordinate system if it's already a TM zone, or Central GRS80
        return if (targetSystem == KoreanCoordinateSystem.UTMK_GRS80) {
            KoreanCoordinateSystem.CENTRAL_GRS80
        } else {
            targetSystem
        }
    }

    /**
     * Note: In ESRI Shapefiles (.shp), coordinate pairs are always stored in (X=East/Lon, Y=North/Lat) order.
     * In Korean surveying (`SurveyPoint` / `CadLayerEntity`), coordinates are stored as `(N=North, E=East)`.
     */
    private fun transformToTarget(
        shpXEastOrLon: Double,
        shpYNorthOrLat: Double,
        sourceSystem: KoreanCoordinateSystem?,
        targetSystem: KoreanCoordinateSystem
    ): Pair<Double, Double> {
        // If source is WGS84 (Lon, Lat)
        if (sourceSystem == null) {
            val proj = CoordinateConverter.wgs84ToPlane(
                latDeg = shpYNorthOrLat,
                lonDeg = shpXEastOrLon,
                ellipsoidalHeight = 0.0,
                system = targetSystem
            )
            return Pair(proj.northing, proj.easting)
        }
        // If source system equals target system, simply map SHP (X=East, Y=North) -> (N=North, E=East)
        if (sourceSystem == targetSystem) {
            return Pair(shpYNorthOrLat, shpXEastOrLon)
        }
        // Otherwise convert Source Plane (N=shpY, E=shpX) -> WGS84 -> Target Plane
        val (lat, lon) = CoordinateConverter.planeToWgs84(
            northing = shpYNorthOrLat,
            easting = shpXEastOrLon,
            system = sourceSystem
        )
        val proj = CoordinateConverter.wgs84ToPlane(
            latDeg = lat,
            lonDeg = lon,
            ellipsoidalHeight = 0.0,
            system = targetSystem
        )
        return Pair(proj.northing, proj.easting)
    }
}
