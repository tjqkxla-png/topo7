package kr.koseco.gandansurvey.v90.coord

import kr.koseco.gandansurvey.v90.model.*
import java.util.Locale
import kotlin.math.*

/**
 * 한국 측량 좌표계 정밀 변환 + 도면 객체스냅(OSNAP: 끝점/중간점/교차점/노드) + Web Mercator 타일 좌표 변환 엔진
 */
object CoordinateConverter {

    private const val GRS80_A = 6378137.0
    private const val GRS80_INV_F = 298.257222101

    private const val BESSEL_A = 6377397.155
    private const val BESSEL_INV_F = 299.1528128

    private const val DX = -145.907
    private const val DY = 505.034
    private const val DZ = 685.756
    private const val RX_ARCSEC = -1.162
    private const val RY_ARCSEC = 2.347
    private const val RZ_ARCSEC = 1.592
    private const val SCALE_PPM = 6.342

    data class TmCoordinate(
        val xNorthing: Double,
        val yEasting: Double,
        val zElevation: Double,
        val resolvedZone: KoreaTmZone
    )

    data class LatLonCoordinate(
        val latitudeDeg: Double,
        val longitudeDeg: Double,
        val ellipsoidalHeight: Double
    )

    data class StakeoutGuidance(
        val horizontalDistanceM: Double,
        val slantDistanceM: Double,
        val targetAzimuthDeg: Double,
        val relativeArrowDeg: Float,
        val compassDirectionKor: String,
        val deltaNorthingM: Double,
        val deltaEastingM: Double,
        val forwardBackM: Double,
        val rightLeftM: Double,
        val cutFillHeightM: Double,
        val isWithin10mZone: Boolean,
        val isWithin1mZone: Boolean
    )

    data class CogoInverseResult(
        val fromName: String,
        val toName: String,
        val horizontalDistM: Double,
        val slantDistM: Double,
        val azimuthDeg: Double,
        val azimuthDms: String,
        val deltaX: Double,
        val deltaY: Double,
        val deltaZ: Double,
        val slopePercent: Double
    )

    fun computeGroundElevation(
        orthometricHeightM: Double,
        antennaHeightM: Double,
        measureType: AntennaMeasureType,
        imuTiltDeg: Float = 0f,
        imuCompensate: Boolean = false
    ): Double {
        val totalAnt = antennaHeightM + measureType.phaseCenterOffsetM
        val verticalAnt = if (imuCompensate && imuTiltDeg > 0.05f) {
            totalAnt * cos(Math.toRadians(imuTiltDeg.toDouble()))
        } else {
            totalAnt
        }
        return orthometricHeightM - verticalAnt
    }

    fun wgs84ToTm(
        latDeg: Double,
        lonDeg: Double,
        orthometricHeightM: Double,
        antennaHeightM: Double,
        antennaType: AntennaMeasureType,
        coordSystem: CoordinateSystemType,
        zone: KoreaTmZone,
        imuTiltDeg: Float = 0f,
        imuCompensate: Boolean = false,
        headingDeg: Float = 0f
    ): TmCoordinate {
        val activeZone = KoreaTmZone.resolveZone(lonDeg, zone)
        val groundZ = computeGroundElevation(orthometricHeightM, antennaHeightM, antennaType, imuTiltDeg, imuCompensate)

        if (coordSystem == CoordinateSystemType.GRS80_LATLON) {
            return TmCoordinate(latDeg, lonDeg, groundZ, activeZone)
        }

        val (targetLat, targetLon, a, invF) = if (coordSystem == CoordinateSystemType.BESSEL_TM) {
            val bessel = wgs84ToBesselLatLon(latDeg, lonDeg, orthometricHeightM)
            Quadruple(bessel.first, bessel.second, BESSEL_A, BESSEL_INV_F)
        } else {
            Quadruple(latDeg, lonDeg, GRS80_A, GRS80_INV_F)
        }

        var (n, e) = projectTransverseMercator(
            latDeg = targetLat,
            lonDeg = targetLon,
            a = a,
            invF = invF,
            lat0Deg = activeZone.originLatDeg,
            lon0Deg = activeZone.centralMeridianDeg,
            k0 = activeZone.scaleFactor,
            falseNorthing = activeZone.falseNorthing,
            falseEasting = activeZone.falseEasting
        )

        if (imuCompensate && imuTiltDeg > 0.1f && imuTiltDeg <= 60f) {
            val totalAnt = antennaHeightM + antennaType.phaseCenterOffsetM
            val horizShift = totalAnt * sin(Math.toRadians(imuTiltDeg.toDouble()))
            val azRad = Math.toRadians(headingDeg.toDouble())
            n += horizShift * cos(azRad)
            e += horizShift * sin(azRad)
        }

        return TmCoordinate(
            xNorthing = n,
            yEasting = e,
            zElevation = groundZ,
            resolvedZone = activeZone
        )
    }

    fun tmToWgs84(
        xNorthing: Double,
        yEasting: Double,
        zElevation: Double,
        coordSystem: CoordinateSystemType,
        zone: KoreaTmZone
    ): LatLonCoordinate {
        if (coordSystem == CoordinateSystemType.GRS80_LATLON) {
            return LatLonCoordinate(xNorthing, yEasting, zElevation)
        }
        val activeZone = if (zone == KoreaTmZone.AUTO) KoreaTmZone.CENTRAL_ZONE2 else zone
        val a = if (coordSystem == CoordinateSystemType.BESSEL_TM) BESSEL_A else GRS80_A
        val invF = if (coordSystem == CoordinateSystemType.BESSEL_TM) BESSEL_INV_F else GRS80_INV_F

        val (lat, lon) = inverseTransverseMercator(
            northing = xNorthing,
            easting = yEasting,
            a = a,
            invF = invF,
            lat0Deg = activeZone.originLatDeg,
            lon0Deg = activeZone.centralMeridianDeg,
            k0 = activeZone.scaleFactor,
            falseNorthing = activeZone.falseNorthing,
            falseEasting = activeZone.falseEasting
        )

        return if (coordSystem == CoordinateSystemType.BESSEL_TM) {
            val wgs = besselToWgs84LatLon(lat, lon, zElevation)
            LatLonCoordinate(wgs.first, wgs.second, zElevation)
        } else {
            LatLonCoordinate(lat, lon, zElevation)
        }
    }

    /**
     * 도면 객체스냅(OSNAP) 탐색 엔진 (사용자 요청 5번: 외부 도면 끝점, 중간점, 교차점, 포인트 터치 스냅)
     */
    fun findNearestCadSnap(
        tapNorthing: Double,
        tapEasting: Double,
        maxRadiusMeters: Double,
        points: List<SurveyPoint>,
        entities: List<DxfCadEntity>
    ): CadSnapCandidate? {
        var best: CadSnapCandidate? = null
        var bestDist = maxRadiusMeters

        // 1) 기존 측점(노드) 스냅
        for (pt in points) {
            val d = hypot(pt.xNorthing - tapNorthing, pt.yEasting - tapEasting)
            if (d <= bestDist) {
                bestDist = d
                best = CadSnapCandidate(pt.xNorthing, pt.yEasting, pt.zElevation, CadSnapType.POINT_NODE, "측점:${pt.name}")
            }
        }

        // 2) 외부 DXF / SHP 도면 엔티티 끝점(변곡점) 및 중간점 스냅
        val segments = mutableListOf<Quadruple<Double, Double, Double, Double>>()
        for (ent in entities) {
            val verts = ent.vertices
            for ((idx, v) in verts.withIndex()) {
                val zVal = ent.elevations.getOrNull(idx) ?: 0.0
                val dEnd = hypot(v.first - tapNorthing, v.second - tapEasting)
                if (dEnd <= bestDist) {
                    bestDist = dEnd
                    best = CadSnapCandidate(v.first, v.second, zVal, CadSnapType.ENDPOINT, ent.layer)
                }
            }
            if (verts.size >= 2) {
                for (i in 0 until verts.size - 1) {
                    val p1 = verts[i]
                    val p2 = verts[i + 1]
                    val midN = (p1.first + p2.first) / 2.0
                    val midE = (p1.second + p2.second) / 2.0
                    val dMid = hypot(midN - tapNorthing, midE - tapEasting)
                    if (dMid <= bestDist * 0.9) {
                        bestDist = dMid
                        best = CadSnapCandidate(midN, midE, 0.0, CadSnapType.MIDPOINT, ent.layer)
                    }
                    if (segments.size < 300) {
                        segments.add(Quadruple(p1.first, p1.second, p2.first, p2.second))
                    }
                }
                if (ent.isClosed && verts.size >= 3) {
                    val p1 = verts.last()
                    val p2 = verts.first()
                    if (segments.size < 300) {
                        segments.add(Quadruple(p1.first, p1.second, p2.first, p2.second))
                    }
                }
            }
        }

        // 3) 선분 교차점(Intersection) 스냅
        if (segments.size >= 2) {
            for (i in 0 until min(segments.size, 120)) {
                val s1 = segments[i]
                for (j in i + 1 until min(segments.size, 120)) {
                    val s2 = segments[j]
                    val inter = lineSegmentIntersection(
                        s1.first, s1.second, s1.third, s1.fourth,
                        s2.first, s2.second, s2.third, s2.fourth
                    )
                    if (inter != null) {
                        val dInt = hypot(inter.first - tapNorthing, inter.second - tapEasting)
                        if (dInt <= bestDist) {
                            bestDist = dInt
                            best = CadSnapCandidate(inter.first, inter.second, 0.0, CadSnapType.INTERSECTION, "교차점")
                        }
                    }
                }
            }
        }

        // 4) 끝점/교차점이 없으면 선분 위 최근접점(Nearest on Line) 스냅
        if (best == null) {
            for (s in segments) {
                val proj = projectPointToSegment(tapNorthing, tapEasting, s.first, s.second, s.third, s.fourth)
                val dProj = hypot(proj.first - tapNorthing, proj.second - tapEasting)
                if (dProj <= bestDist) {
                    bestDist = dProj
                    best = CadSnapCandidate(proj.first, proj.second, 0.0, CadSnapType.NEAREST_LINE, "도면선상")
                }
            }
        }

        return best
    }

    private fun lineSegmentIntersection(
        x1: Double, y1: Double, x2: Double, y2: Double,
        x3: Double, y3: Double, x4: Double, y4: Double
    ): Pair<Double, Double>? {
        val denom = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
        if (abs(denom) < 1e-10) return null
        val t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / denom
        val u = -((x1 - x2) * (y1 - y3) - (y1 - y2) * (x1 - x3)) / denom
        if (t in 0.001..0.999 && u in 0.001..0.999) {
            return Pair(x1 + t * (x2 - x1), y1 + t * (y2 - y1))
        }
        return null
    }

    fun geodeticToEcef(
        latDeg: Double,
        lonDeg: Double,
        h: Double,
        a: Double = GRS80_A,
        invF: Double = GRS80_INV_F
    ): DoubleArray {
        val f = 1.0 / invF
        val e2 = 2.0 * f - f * f
        val phi = Math.toRadians(latDeg)
        val lam = Math.toRadians(lonDeg)
        val n = a / sqrt(1.0 - e2 * sin(phi).pow(2))
        val x = (n + h) * cos(phi) * cos(lam)
        val y = (n + h) * cos(phi) * sin(lam)
        val z = (n * (1.0 - e2) + h) * sin(phi)
        return doubleArrayOf(x, y, z)
    }

    fun formatDms(degValue: Double): String {
        val absVal = abs(degValue)
        val d = floor(absVal).toInt()
        val mFull = (absVal - d) * 60.0
        val m = floor(mFull).toInt()
        val s = (mFull - m) * 60.0
        val sign = if (degValue < 0) "-" else ""
        return String.format(Locale.US, "%s%d° %02d' %08.5f\"", sign, d, m, s)
    }

    fun toPackedDmsDouble(degValue: Double): Double {
        val sign = if (degValue < 0) -1.0 else 1.0
        val absVal = abs(degValue)
        val d = floor(absVal).toInt()
        val mFull = (absVal - d) * 60.0
        val m = floor(mFull).toInt()
        val s = (mFull - m) * 60.0
        return sign * (d + m / 100.0 + s / 10000.0)
    }

    fun fromPackedDmsDouble(packed: Double): Double {
        val sign = if (packed < 0) -1.0 else 1.0
        val absVal = abs(packed)
        val d = floor(absVal).toInt()
        val rem = (absVal - d) * 100.0
        val m = floor(rem + 1e-9).toInt()
        val s = (rem - m) * 100.0
        return sign * (d + m / 60.0 + s / 3600.0)
    }

    fun calculateStakeout(
        currentX: Double,
        currentY: Double,
        currentZ: Double,
        targetX: Double,
        targetY: Double,
        targetZ: Double,
        referenceHeadingDeg: Float = 0f,
        farBellRadiusM: Double = 10.0,
        nearBeepRadiusM: Double = 1.0
    ): StakeoutGuidance {
        val dN = targetX - currentX
        val dE = targetY - currentY
        val dZ = targetZ - currentZ

        val hDist = hypot(dN, dE)
        val sDist = sqrt(dN * dN + dE * dE + dZ * dZ)
        var targetAz = Math.toDegrees(atan2(dE, dN))
        if (targetAz < 0) targetAz += 360.0

        var relAngle = (targetAz - referenceHeadingDeg.toDouble()) % 360.0
        if (relAngle > 180.0) relAngle -= 360.0
        if (relAngle < -180.0) relAngle += 360.0

        val relRad = Math.toRadians(relAngle)
        val forwardBack = hDist * cos(relRad)
        val rightLeft = hDist * sin(relRad)

        val dirs = arrayOf("북(N)", "북동(NE)", "동(E)", "남동(SE)", "남(S)", "남서(SW)", "서(W)", "북서(NW)")
        val dirIdx = ((targetAz + 22.5) % 360.0 / 45.0).toInt().coerceIn(0, 7)

        return StakeoutGuidance(
            horizontalDistanceM = hDist,
            slantDistanceM = sDist,
            targetAzimuthDeg = targetAz,
            relativeArrowDeg = relAngle.toFloat(),
            compassDirectionKor = dirs[dirIdx],
            deltaNorthingM = dN,
            deltaEastingM = dE,
            forwardBackM = forwardBack,
            rightLeftM = rightLeft,
            cutFillHeightM = dZ,
            isWithin10mZone = hDist <= farBellRadiusM,
            isWithin1mZone = hDist <= nearBeepRadiusM
        )
    }

    fun calculateInverse(p1: SurveyPoint, p2: SurveyPoint): CogoInverseResult {
        val dx = p2.xNorthing - p1.xNorthing
        val dy = p2.yEasting - p1.yEasting
        val dz = p2.zElevation - p1.zElevation
        val hDist = hypot(dx, dy)
        val sDist = sqrt(dx * dx + dy * dy + dz * dz)
        var az = Math.toDegrees(atan2(dy, dx))
        if (az < 0) az += 360.0
        val slopePct = if (hDist > 1e-6) (dz / hDist) * 100.0 else 0.0
        return CogoInverseResult(
            fromName = p1.name,
            toName = p2.name,
            horizontalDistM = hDist,
            slantDistM = sDist,
            azimuthDeg = az,
            azimuthDms = formatDms(az),
            deltaX = dx,
            deltaY = dy,
            deltaZ = dz,
            slopePercent = slopePct
        )
    }

    fun calculatePolygonAreaAndPerimeter(points: List<Pair<Double, Double>>): Pair<Double, Double> {
        if (points.size < 2) return Pair(0.0, 0.0)
        var perimeter = 0.0
        for (i in 0 until points.size - 1) {
            perimeter += hypot(points[i + 1].first - points[i].first, points[i + 1].second - points[i].second)
        }
        if (points.size < 3) return Pair(0.0, perimeter)
        perimeter += hypot(points.first().first - points.last().first, points.first().second - points.last().second)

        var area2 = 0.0
        for (i in points.indices) {
            val j = (i + 1) % points.size
            area2 += points[i].first * points[j].second - points[j].first * points[i].second
        }
        return Pair(abs(area2) / 2.0, perimeter)
    }

    fun projectPointToSegment(
        px: Double, py: Double,
        ax: Double, ay: Double,
        bx: Double, by: Double
    ): Pair<Double, Double> {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-12) return Pair(ax, ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        return Pair(ax + t * dx, ay + t * dy)
    }

    private fun projectTransverseMercator(
        latDeg: Double,
        lonDeg: Double,
        a: Double,
        invF: Double,
        lat0Deg: Double,
        lon0Deg: Double,
        k0: Double,
        falseNorthing: Double,
        falseEasting: Double
    ): Pair<Double, Double> {
        val f = 1.0 / invF
        val e2 = 2.0 * f - f * f
        val ep2 = e2 / (1.0 - e2)

        val phi = Math.toRadians(latDeg)
        val lam = Math.toRadians(lonDeg)
        val phi0 = Math.toRadians(lat0Deg)
        val lam0 = Math.toRadians(lon0Deg)

        val sinPhi = sin(phi)
        val cosPhi = cos(phi)
        val tanPhi = tan(phi)

        val nRadius = a / sqrt(1.0 - e2 * sinPhi * sinPhi)
        val t = tanPhi * tanPhi
        val c = ep2 * cosPhi * cosPhi
        val aLon = (lam - lam0) * cosPhi

        val m = meridianArc(phi, a, e2)
        val m0 = meridianArc(phi0, a, e2)

        val a2 = aLon * aLon
        val a3 = a2 * aLon
        val a4 = a2 * a2
        val a5 = a4 * aLon
        val a6 = a4 * a2

        val xNorthing = falseNorthing + k0 * (
            (m - m0) + nRadius * tanPhi * (
                a2 / 2.0 +
                (5.0 - t + 9.0 * c + 4.0 * c * c) * a4 / 24.0 +
                (61.0 - 58.0 * t + t * t + 600.0 * c - 330.0 * ep2) * a6 / 720.0
            )
        )

        val yEasting = falseEasting + k0 * nRadius * (
            aLon +
            (1.0 - t + c) * a3 / 6.0 +
            (5.0 - 18.0 * t + t * t + 72.0 * c - 58.0 * ep2) * a5 / 120.0
        )

        return Pair(xNorthing, yEasting)
    }

    private fun inverseTransverseMercator(
        northing: Double,
        easting: Double,
        a: Double,
        invF: Double,
        lat0Deg: Double,
        lon0Deg: Double,
        k0: Double,
        falseNorthing: Double,
        falseEasting: Double
    ): Pair<Double, Double> {
        val f = 1.0 / invF
        val e2 = 2.0 * f - f * f
        val ep2 = e2 / (1.0 - e2)
        val e1 = (1.0 - sqrt(1.0 - e2)) / (1.0 + sqrt(1.0 - e2))

        val phi0 = Math.toRadians(lat0Deg)
        val lam0 = Math.toRadians(lon0Deg)
        val m0 = meridianArc(phi0, a, e2)

        val m = m0 + (northing - falseNorthing) / k0
        val mu = m / (a * (1.0 - e2 / 4.0 - 3.0 * e2 * e2 / 64.0 - 5.0 * e2 * e2 * e2 / 256.0))

        val phi1 = mu +
            (1.5 * e1 - 27.0 * e1.pow(3) / 32.0) * sin(2.0 * mu) +
            (21.0 * e1 * e1 / 16.0 - 55.0 * e1.pow(4) / 32.0) * sin(4.0 * mu) +
            (151.0 * e1.pow(3) / 96.0) * sin(6.0 * mu) +
            (1097.0 * e1.pow(4) / 512.0) * sin(8.0 * mu)

        val sinPhi1 = sin(phi1)
        val cosPhi1 = cos(phi1)
        val tanPhi1 = tan(phi1)

        val n1 = a / sqrt(1.0 - e2 * sinPhi1 * sinPhi1)
        val r1 = a * (1.0 - e2) / (1.0 - e2 * sinPhi1 * sinPhi1).pow(1.5)
        val t1 = tanPhi1 * tanPhi1
        val c1 = ep2 * cosPhi1 * cosPhi1
        val d = (easting - falseEasting) / (n1 * k0)

        val d2 = d * d
        val d3 = d2 * d
        val d4 = d2 * d2
        val d5 = d4 * d
        val d6 = d4 * d2

        val latRad = phi1 - (n1 * tanPhi1 / r1) * (
            d2 / 2.0 -
            (5.0 + 3.0 * t1 + 10.0 * c1 - 4.0 * c1 * c1 - 9.0 * ep2) * d4 / 24.0 +
            (61.0 + 90.0 * t1 + 298.0 * c1 + 45.0 * t1 * t1 - 252.0 * ep2 - 3.0 * c1 * c1) * d6 / 720.0
        )

        val lonRad = lam0 + (
            d -
            (1.0 + 2.0 * t1 + c1) * d3 / 6.0 +
            (5.0 - 2.0 * c1 + 28.0 * t1 - 3.0 * c1 * c1 + 8.0 * ep2 + 24.0 * t1 * t1) * d5 / 120.0
        ) / cosPhi1

        return Pair(Math.toDegrees(latRad), Math.toDegrees(lonRad))
    }

    private fun meridianArc(phi: Double, a: Double, e2: Double): Double {
        val e4 = e2 * e2
        val e6 = e4 * e2
        val a0 = 1.0 - e2 / 4.0 - 3.0 * e4 / 64.0 - 5.0 * e6 / 256.0
        val a2 = (3.0 / 8.0) * (e2 + e4 / 4.0 + 15.0 * e6 / 128.0)
        val a4 = (15.0 / 256.0) * (e4 + 3.0 * e6 / 4.0)
        val a6 = 35.0 * e6 / 3072.0
        return a * (a0 * phi - a2 * sin(2.0 * phi) + a4 * sin(4.0 * phi) - a6 * sin(6.0 * phi))
    }

    private fun wgs84ToBesselLatLon(latDeg: Double, lonDeg: Double, h: Double): Pair<Double, Double> {
        val xyz = geodeticToEcef(latDeg, lonDeg, h, GRS80_A, GRS80_INV_F)
        val rx = Math.toRadians(RX_ARCSEC / 3600.0)
        val ry = Math.toRadians(RY_ARCSEC / 3600.0)
        val rz = Math.toRadians(RZ_ARCSEC / 3600.0)
        val s = 1.0 - SCALE_PPM * 1e-6

        val bx = (xyz[0] - DX) * s + rz * (xyz[1] - DY) - ry * (xyz[2] - DZ)
        val by = -rz * (xyz[0] - DX) + (xyz[1] - DY) * s + rx * (xyz[2] - DZ)
        val bz = ry * (xyz[0] - DX) - rx * (xyz[1] - DY) + (xyz[2] - DZ) * s
        return ecefToGeodetic(bx, by, bz, BESSEL_A, BESSEL_INV_F)
    }

    private fun besselToWgs84LatLon(latDeg: Double, lonDeg: Double, h: Double): Pair<Double, Double> {
        val xyz = geodeticToEcef(latDeg, lonDeg, h, BESSEL_A, BESSEL_INV_F)
        val rx = Math.toRadians(RX_ARCSEC / 3600.0)
        val ry = Math.toRadians(RY_ARCSEC / 3600.0)
        val rz = Math.toRadians(RZ_ARCSEC / 3600.0)
        val s = 1.0 + SCALE_PPM * 1e-6

        val wx = DX + s * (xyz[0] - rz * xyz[1] + ry * xyz[2])
        val wy = DY + s * (rz * xyz[0] + xyz[1] - rx * xyz[2])
        val wz = DZ + s * (-ry * xyz[0] + rx * xyz[1] + xyz[2])
        return ecefToGeodetic(wx, wy, wz, GRS80_A, GRS80_INV_F)
    }

    private fun ecefToGeodetic(x: Double, y: Double, z: Double, a: Double, invF: Double): Pair<Double, Double> {
        val f = 1.0 / invF
        val e2 = 2.0 * f - f * f
        val p = sqrt(x * x + y * y)
        var phi = atan2(z, p * (1.0 - e2))
        repeat(5) {
            val n = a / sqrt(1.0 - e2 * sin(phi).pow(2))
            val h = p / cos(phi) - n
            phi = atan2(z, p * (1.0 - e2 * n / (n + h)))
        }
        val lam = atan2(y, x)
        return Pair(Math.toDegrees(phi), Math.toDegrees(lam))
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
