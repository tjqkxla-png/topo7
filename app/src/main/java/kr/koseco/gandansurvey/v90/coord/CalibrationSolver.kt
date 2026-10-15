package kr.koseco.gandansurvey.v90.coord

import java.util.Locale
import kotlin.math.*

object CalibrationSolver {

    enum class TransformMethod(val displayName: String) {
        ONE_STEP("OneStep (10km 이내 표준)"),
        TWO_STEP("TwoStep (10km 이상)"),
        BURSA_WOLF("BursaWolf"),
        MOLODENSKY("MolodenskyBadek")
    }

    enum class ResidualDistributionMode(val korName: String) {
        NONE("안함"),
        INV_DIST("1/거리"),
        INV_DIST_SQ("1/거리²"),
        SQRT_DIST4("√(거리⁴)")
    }

    data class CalibrationPair(
        val id: Int,
        val pointName: String,
        val localN: Double,
        val localE: Double,
        val localZ: Double,
        val gnssN: Double,
        val gnssE: Double,
        val gnssZ: Double,
        val wgsLat: Double = 36.6424,
        val wgsLon: Double = 127.4890,
        val wgsEllipH: Double = 78.420,
        val excluded: Boolean = false
    )

    data class PointResidual(
        val id: Int,
        val pointName: String,
        val dN: Double,
        val dE: Double,
        val dZ: Double,
        val excluded: Boolean
    )

    data class CalibrationResult(
        val isValid: Boolean,
        val method: TransformMethod = TransformMethod.ONE_STEP,
        val distributionMode: ResidualDistributionMode = ResidualDistributionMode.NONE,
        val scaleFactor: Double = 1.0,
        val scalePpm: Double = 0.0,
        val rotationDeg: Double = 0.0,
        val dX: Double = 0.0,
        val dY: Double = 0.0,
        val originGnssN: Double = 0.0,
        val originGnssE: Double = 0.0,
        val slopeNorthPpm: Double = 0.0,
        val slopeEastPpm: Double = 0.0,
        val constantShiftZ: Double = 0.0,
        val refStd: Double = 0.0,
        val residuals: List<PointResidual> = emptyList(),
        val activePairs: List<CalibrationPair> = emptyList()
    ) {
        fun apply(gnssN: Double, gnssE: Double, gnssZ: Double): Triple<Double, Double, Double> {
            if (!isValid) return Triple(gnssN, gnssE, gnssZ)
            val rad = Math.toRadians(rotationDeg)
            val a = scaleFactor * cos(rad)
            val b = scaleFactor * sin(rad)

            var calN = dX + a * gnssN - b * gnssE
            var calE = dY + b * gnssN + a * gnssE
            var calZ = gnssZ + constantShiftZ +
                (slopeNorthPpm * 1e-6) * (gnssN - originGnssN) +
                (slopeEastPpm * 1e-6) * (gnssE - originGnssE)

            if (distributionMode != ResidualDistributionMode.NONE && activePairs.isNotEmpty() && residuals.isNotEmpty()) {
                val resMap = residuals.filter { !it.excluded }.associateBy { it.id }
                var sumW = 0.0
                var corrN = 0.0
                var corrE = 0.0
                var corrZ = 0.0
                for (pair in activePairs) {
                    val r = resMap[pair.id] ?: continue
                    val dist = hypot(gnssN - pair.gnssN, gnssE - pair.gnssE).coerceAtLeast(0.001)
                    val w = when (distributionMode) {
                        ResidualDistributionMode.INV_DIST -> 1.0 / dist
                        ResidualDistributionMode.INV_DIST_SQ -> 1.0 / (dist * dist)
                        ResidualDistributionMode.SQRT_DIST4 -> 1.0 / sqrt(dist.pow(4))
                        ResidualDistributionMode.NONE -> 0.0
                    }
                    sumW += w
                    corrN += w * r.dN
                    corrE += w * r.dE
                    corrZ += w * r.dZ
                }
                if (sumW > 1e-12) {
                    calN += corrN / sumW
                    calE += corrE / sumW
                    calZ += corrZ / sumW
                }
            }

            return Triple(calN, calE, calZ)
        }
    }

    fun solve(
        pairs: List<CalibrationPair>,
        method: TransformMethod = TransformMethod.ONE_STEP,
        distributionMode: ResidualDistributionMode = ResidualDistributionMode.NONE
    ): CalibrationResult {
        val active = pairs.filter { !it.excluded }
        if (active.isEmpty()) {
            return CalibrationResult(isValid = false, method = method, distributionMode = distributionMode)
        }

        var dX = 0.0
        var dY = 0.0
        var scale = 1.0
        var rotDeg = 0.0

        if (active.size == 1) {
            val p = active.first()
            dX = p.localN - p.gnssN
            dY = p.localE - p.gnssE
        } else {
            val meanGnssN = active.map { it.gnssN }.average()
            val meanGnssE = active.map { it.gnssE }.average()
            val meanLocalN = active.map { it.localN }.average()
            val meanLocalE = active.map { it.localE }.average()

            var sxx = 0.0
            var numA = 0.0
            var numB = 0.0
            for (p in active) {
                val u = p.gnssN - meanGnssN
                val v = p.gnssE - meanGnssE
                val x = p.localN - meanLocalN
                val y = p.localE - meanLocalE
                sxx += u * u + v * v
                numA += u * x + v * y
                numB += u * y - v * x
            }
            if (sxx > 1e-9) {
                val a = numA / sxx
                val b = numB / sxx
                scale = hypot(a, b)
                rotDeg = Math.toDegrees(atan2(b, a))
                dX = meanLocalN - (a * meanGnssN - b * meanGnssE)
                dY = meanLocalE - (b * meanGnssN + a * meanGnssE)
            }
        }

        val originN = active.map { it.gnssN }.average()
        val originE = active.map { it.gnssE }.average()
        val dZ0 = active.map { it.localZ - it.gnssZ }.average()
        var slopeN = 0.0
        var slopeE = 0.0

        if (active.size >= 4) {
            var suu = 0.0; var svv = 0.0; var suv = 0.0
            var suz = 0.0; var svz = 0.0
            for (p in active) {
                val u = p.gnssN - originN
                val v = p.gnssE - originE
                val w = (p.localZ - p.gnssZ) - dZ0
                suu += u * u
                svv += v * v
                suv += u * v
                suz += u * w
                svz += v * w
            }
            val det = suu * svv - suv * suv
            if (abs(det) > 1e-6) {
                slopeN = ((svv * suz - suv * svz) / det) * 1e6
                slopeE = ((suu * svz - suv * suz) / det) * 1e6
            }
        }

        val rad = Math.toRadians(rotDeg)
        val a = scale * cos(rad)
        val b = scale * sin(rad)

        val residuals = pairs.map { p ->
            val predN = dX + a * p.gnssN - b * p.gnssE
            val predE = dY + b * p.gnssN + a * p.gnssE
            val predZ = p.gnssZ + dZ0 + (slopeN * 1e-6) * (p.gnssN - originN) + (slopeE * 1e-6) * (p.gnssE - originE)
            PointResidual(
                id = p.id,
                pointName = p.pointName,
                dN = p.localN - predN,
                dE = p.localE - predE,
                dZ = p.localZ - predZ,
                excluded = p.excluded
            )
        }

        val activeRes = residuals.filter { !it.excluded }
        val std = if (activeRes.isNotEmpty()) {
            sqrt(activeRes.sumOf { it.dN * it.dN + it.dE * it.dE + it.dZ * it.dZ } / activeRes.size)
        } else 0.0

        return CalibrationResult(
            isValid = true,
            method = method,
            distributionMode = distributionMode,
            scaleFactor = scale,
            scalePpm = (scale - 1.0) * 1e6,
            rotationDeg = rotDeg,
            dX = dX,
            dY = dY,
            originGnssN = originN,
            originGnssE = originE,
            slopeNorthPpm = slopeN,
            slopeEastPpm = slopeE,
            constantShiftZ = dZ0,
            refStd = std,
            residuals = residuals,
            activePairs = active
        )
    }

    fun exportTrfContent(pairs: List<CalibrationPair>, result: CalibrationResult): String {
        val sb = StringBuilder()
        sb.append("CivilPro Coordinate Transformation Data File.\r\n")
        sb.append("[Control Points]\r\n")
        sb.append("${pairs.size}\r\n")
        sb.append("(GRS80 TM) N, E, Z(Geoid Height).\r\n")
        pairs.forEachIndexed { idx, p ->
            sb.append(String.format(Locale.US, "%d, %.4f, %.4f, %.4f\r\n", idx + 1, p.localN, p.localE, p.localZ))
        }
        sb.append("(WGS84) Latitude(dms), Longitude(dms), Ellipsoid Height.\r\n")
        pairs.forEachIndexed { idx, p ->
            val dmsLat = CoordinateConverter.toPackedDmsDouble(p.wgsLat)
            val dmsLon = CoordinateConverter.toPackedDmsDouble(p.wgsLon)
            sb.append(String.format(Locale.US, "%d, %.9f, %.9f, %.4f\r\n", idx + 1, dmsLat, dmsLon, p.wgsEllipH))
        }
        sb.append("[OneStep Parameter]\r\n")
        sb.append("(parameter) scale(ppm),Re(sec),Rn,kappa,tx,ty,dh, center e,n\r\n")
        sb.append(
            String.format(
                Locale.US,
                "%.4f, %.8f, %.8f, %.8f, %.4f, %.4f, %.4f, %.4f, %.4f\r\n",
                result.scalePpm,
                result.slopeEastPpm,
                result.slopeNorthPpm,
                result.rotationDeg * 3600.0,
                result.dY,
                result.dX,
                result.constantShiftZ,
                result.originGnssE,
                result.originGnssN
            )
        )
        sb.append(String.format(Locale.US, "(RefSTD)%.8f\r\n", result.refStd))
        sb.append("residuals\r\n")
        result.residuals.forEachIndexed { idx, r ->
            if (r.excluded) {
                sb.append("${idx + 1}, 제외\r\n")
            } else {
                sb.append(String.format(Locale.US, "%d, %.4f, %.4f, %.4f\r\n", idx + 1, r.dN, r.dE, r.dZ))
            }
        }
        return sb.toString()
    }
}
