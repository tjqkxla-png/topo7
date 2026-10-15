package kr.koseco.gandansurvey.v90.coord

import kr.koseco.gandansurvey.v90.model.GeoidModel
import kotlin.math.floor

object KnGeoid18Grid {

    private const val LAT_MIN = 33.0
    private const val LAT_MAX = 39.0
    private const val LON_MIN = 124.0
    private const val LON_MAX = 132.0

    private val KNGEOID18_GRID: Array<DoubleArray> = arrayOf(
        doubleArrayOf(22.80, 24.30, 25.85, 27.20, 28.50, 29.80, 30.80, 31.40, 31.90),
        doubleArrayOf(22.10, 23.65, 25.25, 26.85, 28.35, 29.65, 30.60, 31.20, 31.60),
        doubleArrayOf(21.50, 23.10, 24.75, 26.40, 28.05, 29.45, 30.30, 30.90, 31.30),
        doubleArrayOf(20.90, 22.50, 24.15, 25.85, 27.55, 28.95, 29.85, 30.45, 30.85),
        doubleArrayOf(20.30, 21.85, 23.35, 25.10, 26.95, 28.35, 29.25, 29.85, 30.25),
        doubleArrayOf(19.80, 21.30, 22.80, 24.50, 26.30, 27.70, 28.60, 29.20, 29.60),
        doubleArrayOf(19.30, 20.80, 22.30, 23.95, 25.65, 27.05, 27.95, 28.55, 28.95)
    )

    fun getGeoidSeparation(latDeg: Double, lonDeg: Double, model: GeoidModel = GeoidModel.KNGEOID18): Double {
        val clampedLat = latDeg.coerceIn(LAT_MIN, LAT_MAX - 1e-6)
        val clampedLon = lonDeg.coerceIn(LON_MIN, LON_MAX - 1e-6)

        val latIdx = floor(clampedLat - LAT_MIN).toInt().coerceIn(0, 5)
        val lonIdx = floor(clampedLon - LON_MIN).toInt().coerceIn(0, 7)

        val t = (clampedLat - (LAT_MIN + latIdx)).coerceIn(0.0, 1.0)
        val u = (clampedLon - (LON_MIN + lonIdx)).coerceIn(0.0, 1.0)

        val n00 = KNGEOID18_GRID[latIdx][lonIdx]
        val n10 = KNGEOID18_GRID[latIdx + 1][lonIdx]
        val n01 = KNGEOID18_GRID[latIdx][lonIdx + 1]
        val n11 = KNGEOID18_GRID[latIdx + 1][lonIdx + 1]

        val kn18 = (1.0 - t) * (1.0 - u) * n00 +
            t * (1.0 - u) * n10 +
            (1.0 - t) * u * n01 +
            t * u * n11

        return when (model) {
            GeoidModel.KNGEOID18 -> kn18
            GeoidModel.KNGEOID14 -> kn18 - 0.032
            GeoidModel.KNGEOID13 -> kn18 - 0.048
            GeoidModel.EGM2008 -> kn18 - 0.110
            GeoidModel.EGM96 -> kn18 - 0.245
        }
    }
}
