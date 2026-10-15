package kr.koseco.gandansurvey.v90.util

import android.content.Context
import android.os.Environment
import kr.koseco.gandansurvey.v90.model.CadEntityType
import kr.koseco.gandansurvey.v90.model.CadLayerEntity
import kr.koseco.gandansurvey.v90.model.CadPolyline
import kr.koseco.gandansurvey.v90.model.DxfEncodingMode
import kr.koseco.gandansurvey.v90.model.ExportColumnConfig
import kr.koseco.gandansurvey.v90.model.FixQuality
import kr.koseco.gandansurvey.v90.model.KoreanCoordinateSystem
import kr.koseco.gandansurvey.v90.model.SurveyProjectData
import kr.koseco.gandansurvey.v90.model.SurveyPoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * Handles:
 * 1. Project Save & Load (`.gsproj` JSON project files in `Documents/간단측량/Projects/`)
 * 2. Selective Column CSV / NEZ / TXT Export (`ExportColumnConfig`: 측번, 코드명, 메모 등 체크박스 선택 출력)
 * 3. Korean-safe AutoCAD DXF R12/2000 Export with selectable DXF labels (`측번`, `코드명`, `지반고`, `메모`)
 * 4. External CAD (DXF / ASCII DWG) Loader for CAD Stakeout (`도면측설`: LINE, LWPOLYLINE, POLYLINE, POINT, CIRCLE, TEXT)
 * 5. Google Earth KML Export
 */
object FileIoManager {

    private fun getBaseDir(context: Context): File {
        val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val dir = File(docs, "간단측량")
        if (!dir.exists()) {
            val ok = dir.mkdirs()
            if (!ok) {
                val fallback = File(context.getExternalFilesDir(null), "간단측량")
                fallback.mkdirs()
                return fallback
            }
        }
        return dir
    }

    fun getProjectsDir(context: Context): File {
        val dir = File(getBaseDir(context), "Projects")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getExportsDir(context: Context): File {
        val dir = File(getBaseDir(context), "Exports")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // =========================================================================
    // 1. PROJECT SAVE & LOAD (.gsproj)
    // =========================================================================

    fun saveProjectFile(context: Context, project: SurveyProjectData): File {
        val dir = getProjectsDir(context)
        val safeName = project.projectName.replace(Regex("[^가-힣a-zA-Z0-9_\\-]"), "_").ifBlank { "기본프로젝트" }
        val file = File(dir, "$safeName.gsproj")

        val root = JSONObject()
        root.put("projectName", project.projectName)
        root.put("createdAt", project.createdAt)
        root.put("updatedAt", project.updatedAt)
        root.put("coordinateSystem", project.coordinateSystem.name)
        root.put("useKnGeoid18", project.useKnGeoid18)
        root.put("antennaHeight", project.antennaHeight)

        val ptsArr = JSONArray()
        project.points.forEach { pt ->
            val obj = JSONObject()
            obj.put("id", pt.id)
            obj.put("seqNumber", pt.seqNumber)
            obj.put("name", pt.name)
            obj.put("code", pt.code)
            obj.put("northing", pt.northing)
            obj.put("easting", pt.easting)
            obj.put("elevation", pt.elevation)
            obj.put("latitude", pt.latitude)
            obj.put("longitude", pt.longitude)
            obj.put("ellipsoidalHeight", pt.ellipsoidalHeight)
            obj.put("geoidSeparation", pt.geoidSeparation)
            obj.put("antennaHeight", pt.antennaHeight)
            obj.put("fixQuality", pt.fixQuality.name)
            obj.put("hrms", pt.hrms)
            obj.put("vrms", pt.vrms)
            obj.put("epochCount", pt.epochCount)
            obj.put("timestamp", pt.timestamp)
            obj.put("memo", pt.memo)
            ptsArr.put(obj)
        }
        root.put("points", ptsArr)

        val figArr = JSONArray()
        project.figures.forEach { fig ->
            val obj = JSONObject()
            obj.put("id", fig.id)
            obj.put("code", fig.code)
            obj.put("isClosed", fig.isClosed)
            obj.put("colorHex", fig.colorHex)
            val ids = JSONArray()
            fig.pointIds.forEach { ids.put(it) }
            obj.put("pointIds", ids)
            figArr.put(obj)
        }
        root.put("figures", figArr)

        val cadArr = JSONArray()
        project.cadEntities.take(2500).forEach { ent ->
            val obj = JSONObject()
            obj.put("id", ent.id)
            obj.put("type", ent.type.name)
            obj.put("layerName", ent.layerName)
            obj.put("elevationZ", ent.elevationZ)
            obj.put("textLabel", ent.textLabel)
            obj.put("radius", ent.radius)
            obj.put("isClosed", ent.isClosed)
            obj.put("colorHex", ent.colorHex)
            obj.put("sourceType", ent.sourceType)
            val verts = JSONArray()
            ent.verticesNE.forEach { (n, e) ->
                val v = JSONArray()
                v.put(n)
                v.put(e)
                verts.put(v)
            }
            obj.put("verticesNE", verts)
            cadArr.put(obj)
        }
        root.put("cadEntities", cadArr)

        file.writeText(root.toString(2), Charsets.UTF_8)
        return file
    }

    fun listSavedProjects(context: Context): List<File> {
        val dir = getProjectsDir(context)
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".gsproj", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun loadProjectFromFile(file: File): SurveyProjectData? {
        return try {
            val content = file.readText(Charsets.UTF_8)
            parseProjectJson(content)
        } catch (_: Exception) {
            null
        }
    }

    fun parseProjectJson(jsonStr: String): SurveyProjectData? {
        return try {
            val root = JSONObject(jsonStr)
            val name = root.optString("projectName", "기본프로젝트")
            val createdAt = root.optString("createdAt", "")
            val updatedAt = root.optString("updatedAt", "")
            val coordSysName = root.optString("coordinateSystem", KoreanCoordinateSystem.CENTRAL_GRS80.name)
            val coordSys = try {
                KoreanCoordinateSystem.valueOf(coordSysName)
            } catch (_: Exception) {
                KoreanCoordinateSystem.CENTRAL_GRS80
            }
            val useGeoid = root.optBoolean("useKnGeoid18", true)
            val antH = root.optDouble("antennaHeight", 1.800)

            val points = mutableListOf<SurveyPoint>()
            val ptsArr = root.optJSONArray("points") ?: JSONArray()
            for (i in 0 until ptsArr.length()) {
                val o = ptsArr.getJSONObject(i)
                val fq = try {
                    FixQuality.valueOf(o.optString("fixQuality", FixQuality.RTK_FIXED.name))
                } catch (_: Exception) {
                    FixQuality.RTK_FIXED
                }
                points.add(
                    SurveyPoint(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        seqNumber = o.optInt("seqNumber", i + 1),
                        name = o.optString("name", "PT-${i + 1}"),
                        code = o.optString("code", "GN"),
                        northing = o.optDouble("northing", 0.0),
                        easting = o.optDouble("easting", 0.0),
                        elevation = o.optDouble("elevation", 0.0),
                        latitude = o.optDouble("latitude", 0.0),
                        longitude = o.optDouble("longitude", 0.0),
                        ellipsoidalHeight = o.optDouble("ellipsoidalHeight", 0.0),
                        geoidSeparation = o.optDouble("geoidSeparation", 0.0),
                        antennaHeight = o.optDouble("antennaHeight", 1.8),
                        fixQuality = fq,
                        hrms = o.optDouble("hrms", 0.01),
                        vrms = o.optDouble("vrms", 0.02),
                        epochCount = o.optInt("epochCount", 1),
                        timestamp = o.optString("timestamp", ""),
                        memo = o.optString("memo", "")
                    )
                )
            }

            val figures = mutableListOf<CadPolyline>()
            val figArr = root.optJSONArray("figures") ?: JSONArray()
            for (i in 0 until figArr.length()) {
                val o = figArr.getJSONObject(i)
                val idsJson = o.optJSONArray("pointIds") ?: JSONArray()
                val ids = mutableListOf<String>()
                for (j in 0 until idsJson.length()) {
                    ids.add(idsJson.getString(j))
                }
                figures.add(
                    CadPolyline(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        code = o.optString("code", "LINE"),
                        pointIds = ids,
                        isClosed = o.optBoolean("isClosed", false),
                        colorHex = o.optLong("colorHex", 0xFF38BDF8)
                    )
                )
            }

            val cadEntities = mutableListOf<CadLayerEntity>()
            val cadArr = root.optJSONArray("cadEntities") ?: JSONArray()
            for (i in 0 until cadArr.length()) {
                val o = cadArr.getJSONObject(i)
                val type = try {
                    CadEntityType.valueOf(o.optString("type", CadEntityType.LINE.name))
                } catch (_: Exception) {
                    CadEntityType.LINE
                }
                val vertsJson = o.optJSONArray("verticesNE") ?: JSONArray()
                val verts = mutableListOf<Pair<Double, Double>>()
                for (j in 0 until vertsJson.length()) {
                    val pairArr = vertsJson.optJSONArray(j)
                    if (pairArr != null && pairArr.length() >= 2) {
                        verts.add(Pair(pairArr.optDouble(0), pairArr.optDouble(1)))
                    }
                }
                if (verts.isNotEmpty()) {
                    cadEntities.add(
                        CadLayerEntity(
                            id = o.optString("id", UUID.randomUUID().toString()),
                            type = type,
                            layerName = o.optString("layerName", "0"),
                            verticesNE = verts,
                            elevationZ = o.optDouble("elevationZ", 0.0),
                            textLabel = o.optString("textLabel", ""),
                            radius = o.optDouble("radius", 0.0),
                            isClosed = o.optBoolean("isClosed", false),
                            colorHex = o.optLong("colorHex", 0xFFFACC15),
                            sourceType = o.optString("sourceType", "DXF")
                        )
                    )
                }
            }

            SurveyProjectData(
                projectName = name,
                createdAt = createdAt,
                updatedAt = updatedAt,
                coordinateSystem = coordSys,
                useKnGeoid18 = useGeoid,
                antennaHeight = antH,
                points = points,
                figures = figures,
                cadEntities = cadEntities
            )
        } catch (_: Exception) {
            null
        }
    }

    // =========================================================================
    // 2. SELECTIVE COLUMN EXPORT (측번, 코드명, 메모 등 선택 내보내기)
    // =========================================================================

    fun exportCustomTextOrCsv(
        context: Context,
        projectName: String,
        points: List<SurveyPoint>,
        config: ExportColumnConfig
    ): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date())
        val ext = when (config.delimiter) {
            " " -> "nez"
            "\t" -> "txt"
            else -> "csv"
        }
        val file = File(getExportsDir(context), "${projectName}_선택출력_$stamp.$ext")
        val sep = config.delimiter

        val sb = StringBuilder()
        // Write UTF-8 BOM for CSV so Excel opens Korean columns cleanly
        if (ext == "csv") {
            sb.append("\uFEFF")
        }

        if (config.includeHeaderRow) {
            val headers = mutableListOf<String>()
            if (config.includeSeqNumber) headers.add("순번")
            if (config.includePointName) headers.add("측번")
            if (config.coordOrderNEZ) {
                if (config.includeNorthing) headers.add("N(X_북방향)")
                if (config.includeEasting) headers.add("E(Y_동방향)")
            } else {
                if (config.includeEasting) headers.add("E(Y_동방향)")
                if (config.includeNorthing) headers.add("N(X_북방향)")
            }
            if (config.includeElevation) headers.add("Z(지반고)")
            if (config.includeCode) headers.add("코드명")
            if (config.includeMemo) headers.add("메모")
            if (config.includeLatLon) {
                headers.add("위도(Lat)")
                headers.add("경도(Lon)")
            }
            if (config.includeFixAndRms) {
                headers.add("측위상태")
                headers.add("HRMS(m)")
                headers.add("VRMS(m)")
            }
            if (config.includeTimestamp) headers.add("측정시각")
            sb.append(headers.joinToString(sep)).append("\r\n")
        }

        points.forEach { p ->
            val cols = mutableListOf<String>()
            if (config.includeSeqNumber) cols.add(p.seqNumber.toString())
            if (config.includePointName) cols.add(sanitizeField(p.name, sep))
            if (config.coordOrderNEZ) {
                if (config.includeNorthing) cols.add(String.format(Locale.US, "%.4f", p.northing))
                if (config.includeEasting) cols.add(String.format(Locale.US, "%.4f", p.easting))
            } else {
                if (config.includeEasting) cols.add(String.format(Locale.US, "%.4f", p.easting))
                if (config.includeNorthing) cols.add(String.format(Locale.US, "%.4f", p.northing))
            }
            if (config.includeElevation) cols.add(String.format(Locale.US, "%.4f", p.elevation))
            if (config.includeCode) cols.add(sanitizeField(p.code.ifBlank { "GN" }, sep))
            if (config.includeMemo) cols.add(sanitizeField(p.memo, sep))
            if (config.includeLatLon) {
                cols.add(String.format(Locale.US, "%.8f", p.latitude))
                cols.add(String.format(Locale.US, "%.8f", p.longitude))
            }
            if (config.includeFixAndRms) {
                cols.add(p.fixQuality.koreanLabel)
                cols.add(String.format(Locale.US, "%.3f", p.hrms))
                cols.add(String.format(Locale.US, "%.3f", p.vrms))
            }
            if (config.includeTimestamp) cols.add(sanitizeField(p.timestamp, sep))
            sb.append(cols.joinToString(sep)).append("\r\n")
        }

        file.writeText(sb.toString(), Charsets.UTF_8)
        return file
    }

    private fun sanitizeField(value: String, sep: String): String {
        val clean = value.replace("\r", " ").replace("\n", " ").trim()
        return if (sep == "," && clean.contains(",")) {
            "\"$clean\""
        } else if (sep == " ") {
            clean.replace(" ", "_").ifEmpty { "-" }
        } else {
            clean
        }
    }

    // =========================================================================
    // 3. KOREAN-SAFE AUTOCAD DXF EXPORT (코드명, 측번, 지반고, 메모 선택 출력 지원)
    // =========================================================================

    fun exportDxf(
        context: Context,
        projectName: String,
        points: List<SurveyPoint>,
        polylines: List<CadPolyline>,
        encodingMode: DxfEncodingMode = DxfEncodingMode.CP949_KOREAN,
        exportConfig: ExportColumnConfig = ExportColumnConfig()
    ): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date())
        val file = File(getExportsDir(context), "${projectName}_CAD도면_$stamp.dxf")

        val pointMap = points.associateBy { it.id }
        val sb = StringBuilder()

        fun dxfText(raw: String): String {
            return if (encodingMode == DxfEncodingMode.UNICODE_ESCAPE) {
                toAutoCadUnicodeEscaped(raw)
            } else {
                raw
            }
        }

        // 1. HEADER SECTION
        sb.append("0\r\nSECTION\r\n2\r\nHEADER\r\n")
        sb.append("9\r\n\$ACADVER\r\n1\r\nAC1009\r\n")
        sb.append("9\r\n\$DWGCODEPAGE\r\n3\r\nANSI_949\r\n")
        sb.append("9\r\n\$PDMODE\r\n70\r\n34\r\n")
        sb.append("9\r\n\$PDSIZE\r\n40\r\n0.45\r\n")
        sb.append("0\r\nENDSEC\r\n")

        // 2. TABLES SECTION (Korean TrueType Font STYLE + Layer definitions)
        sb.append("0\r\nSECTION\r\n2\r\nTABLES\r\n")
        sb.append("0\r\nTABLE\r\n2\r\nSTYLE\r\n70\r\n2\r\n")
        sb.append("0\r\nSTYLE\r\n2\r\nSTANDARD\r\n70\r\n0\r\n40\r\n0.0\r\n41\r\n1.0\r\n50\r\n0.0\r\n71\r\n0\r\n42\r\n1.0\r\n3\r\ngulim.ttc\r\n4\r\n\r\n")
        sb.append("0\r\nSTYLE\r\n2\r\nKOREAN\r\n70\r\n0\r\n40\r\n0.0\r\n41\r\n1.0\r\n50\r\n0.0\r\n71\r\n0\r\n42\r\n1.0\r\n3\r\nmalgun.ttf\r\n4\r\n\r\n")
        sb.append("0\r\nENDTAB\r\n")

        val codes = points.map { it.code.ifBlank { "DEFAULT" } }.distinct()
        sb.append("0\r\nTABLE\r\n2\r\nLAYER\r\n70\r\n${codes.size + 5}\r\n")
        sb.append("0\r\nLAYER\r\n2\r\n0\r\n70\r\n0\r\n62\r\n7\r\n6\r\nCONTINUOUS\r\n")
        sb.append("0\r\nLAYER\r\n2\r\n${dxfText("측점_기호")}\r\n70\r\n0\r\n62\r\n2\r\n6\r\nCONTINUOUS\r\n")
        sb.append("0\r\nLAYER\r\n2\r\n${dxfText("측번_코드명")}\r\n70\r\n0\r\n62\r\n3\r\n6\r\nCONTINUOUS\r\n")
        sb.append("0\r\nLAYER\r\n2\r\n${dxfText("지반고_Z")}\r\n70\r\n0\r\n62\r\n4\r\n6\r\nCONTINUOUS\r\n")
        sb.append("0\r\nLAYER\r\n2\r\n${dxfText("메모_주석")}\r\n70\r\n0\r\n62\r\n6\r\n6\r\nCONTINUOUS\r\n")
        codes.forEachIndexed { idx, code ->
            val aciColor = (idx % 6) + 1
            sb.append("0\r\nLAYER\r\n2\r\n${dxfText(code)}\r\n70\r\n0\r\n62\r\n$aciColor\r\n6\r\nCONTINUOUS\r\n")
        }
        sb.append("0\r\nENDTAB\r\n")
        sb.append("0\r\nENDSEC\r\n")

        // 3. ENTITIES SECTION (CAD X = Easting, CAD Y = Northing, CAD Z = Elevation)
        sb.append("0\r\nSECTION\r\n2\r\nENTITIES\r\n")

        points.forEach { p ->
            val layerName = dxfText(p.code.ifBlank { "측점_기호" })
            val xStr = String.format(Locale.US, "%.4f", p.easting)
            val yStr = String.format(Locale.US, "%.4f", p.northing)
            val zStr = String.format(Locale.US, "%.4f", p.elevation)

            // 3D POINT entity
            sb.append("0\r\nPOINT\r\n8\r\n$layerName\r\n")
            sb.append("10\r\n$xStr\r\n20\r\n$yStr\r\n30\r\n$zStr\r\n")

            // Crosshair symbol
            val half = 0.25
            sb.append("0\r\nLINE\r\n8\r\n${dxfText("측점_기호")}\r\n62\r\n2\r\n")
            sb.append("10\r\n${String.format(Locale.US, "%.4f", p.easting - half)}\r\n20\r\n$yStr\r\n30\r\n$zStr\r\n")
            sb.append("11\r\n${String.format(Locale.US, "%.4f", p.easting + half)}\r\n21\r\n$yStr\r\n31\r\n$zStr\r\n")
            sb.append("0\r\nLINE\r\n8\r\n${dxfText("측점_기호")}\r\n62\r\n2\r\n")
            sb.append("10\r\n$xStr\r\n20\r\n${String.format(Locale.US, "%.4f", p.northing - half)}\r\n30\r\n$zStr\r\n")
            sb.append("11\r\n$xStr\r\n21\r\n${String.format(Locale.US, "%.4f", p.northing + half)}\r\n31\r\n$zStr\r\n")

            // Top Label: 측번 + 코드명 (based on user selection)
            val topLabelParts = mutableListOf<String>()
            if (exportConfig.dxfIncludePointName) {
                topLabelParts.add("#${p.seqNumber} ${p.name}")
            }
            if (exportConfig.dxfIncludeCode && p.code.isNotBlank()) {
                topLabelParts.add("[${p.code}]")
            }
            if (topLabelParts.isNotEmpty()) {
                val topLabel = dxfText(topLabelParts.joinToString(" "))
                sb.append("0\r\nTEXT\r\n8\r\n${dxfText("측번_코드명")}\r\n7\r\nKOREAN\r\n62\r\n3\r\n")
                sb.append("10\r\n${String.format(Locale.US, "%.4f", p.easting + 0.35)}\r\n")
                sb.append("20\r\n${String.format(Locale.US, "%.4f", p.northing + 0.25)}\r\n")
                sb.append("30\r\n$zStr\r\n40\r\n0.50\r\n1\r\n$topLabel\r\n")
            }

            // Middle Label: Z 지반고
            if (exportConfig.dxfIncludeElevation) {
                val elevLabel = dxfText("Z=${String.format(Locale.US, "%.3f", p.elevation)}")
                sb.append("0\r\nTEXT\r\n8\r\n${dxfText("지반고_Z")}\r\n7\r\nKOREAN\r\n62\r\n4\r\n")
                sb.append("10\r\n${String.format(Locale.US, "%.4f", p.easting + 0.35)}\r\n")
                sb.append("20\r\n${String.format(Locale.US, "%.4f", p.northing - 0.35)}\r\n")
                sb.append("30\r\n$zStr\r\n40\r\n0.42\r\n1\r\n$elevLabel\r\n")
            }

            // Bottom Label: 메모
            if (exportConfig.dxfIncludeMemo && p.memo.isNotBlank()) {
                val memoLabel = dxfText(p.memo)
                sb.append("0\r\nTEXT\r\n8\r\n${dxfText("메모_주석")}\r\n7\r\nKOREAN\r\n62\r\n6\r\n")
                sb.append("10\r\n${String.format(Locale.US, "%.4f", p.easting + 0.35)}\r\n")
                sb.append("20\r\n${String.format(Locale.US, "%.4f", p.northing - 0.90)}\r\n")
                sb.append("30\r\n$zStr\r\n40\r\n0.38\r\n1\r\n$memoLabel\r\n")
            }
        }

        // Polylines
        polylines.forEach { poly ->
            val pts = poly.pointIds.mapNotNull { pointMap[it] }
            if (pts.size >= 2) {
                val layer = dxfText(poly.code.ifBlank { "0" })
                for (i in 0 until pts.size - 1) {
                    val a = pts[i]
                    val b = pts[i + 1]
                    sb.append("0\r\nLINE\r\n8\r\n$layer\r\n")
                    sb.append("10\r\n${String.format(Locale.US, "%.4f", a.easting)}\r\n")
                    sb.append("20\r\n${String.format(Locale.US, "%.4f", a.northing)}\r\n")
                    sb.append("30\r\n${String.format(Locale.US, "%.4f", a.elevation)}\r\n")
                    sb.append("11\r\n${String.format(Locale.US, "%.4f", b.easting)}\r\n")
                    sb.append("21\r\n${String.format(Locale.US, "%.4f", b.northing)}\r\n")
                    sb.append("31\r\n${String.format(Locale.US, "%.4f", b.elevation)}\r\n")
                }
                if (poly.isClosed && pts.size >= 3) {
                    val a = pts.last()
                    val b = pts.first()
                    sb.append("0\r\nLINE\r\n8\r\n$layer\r\n")
                    sb.append("10\r\n${String.format(Locale.US, "%.4f", a.easting)}\r\n")
                    sb.append("20\r\n${String.format(Locale.US, "%.4f", a.northing)}\r\n")
                    sb.append("30\r\n${String.format(Locale.US, "%.4f", a.elevation)}\r\n")
                    sb.append("11\r\n${String.format(Locale.US, "%.4f", b.easting)}\r\n")
                    sb.append("21\r\n${String.format(Locale.US, "%.4f", b.northing)}\r\n")
                    sb.append("31\r\n${String.format(Locale.US, "%.4f", b.elevation)}\r\n")
                }
            }
        }

        sb.append("0\r\nENDSEC\r\n0\r\nEOF\r\n")

        val targetCharset: Charset = when (encodingMode) {
            DxfEncodingMode.CP949_KOREAN -> try {
                Charset.forName("MS949")
            } catch (_: Exception) {
                try {
                    Charset.forName("EUC-KR")
                } catch (_: Exception) {
                    Charsets.UTF_8
                }
            }
            DxfEncodingMode.UTF8_MODERN -> Charsets.UTF_8
            DxfEncodingMode.UNICODE_ESCAPE -> Charsets.US_ASCII
        }

        file.writeBytes(sb.toString().toByteArray(targetCharset))
        return file
    }

    private fun toAutoCadUnicodeEscaped(input: String): String {
        val out = StringBuilder(input.length * 2)
        for (ch in input) {
            if (ch.code in 32..126) {
                out.append(ch)
            } else {
                out.append(String.format(Locale.US, "\\U+%04X", ch.code))
            }
        }
        return out.toString()
    }

    // =========================================================================
    // 4. EXTERNAL CAD (DXF / DWG) PARSER FOR CAD STAKEOUT (도면측설)
    // =========================================================================

    data class CadLoadResult(
        val entities: List<CadLayerEntity>,
        val extractedPoints: List<SurveyPoint>,
        val message: String
    )

    /**
     * Parses external DXF (or ASCII-exported DWG) bytes into `CadLayerEntity` objects
     * (LINE, LWPOLYLINE, POLYLINE, POINT, CIRCLE, TEXT) for interactive CAD Stakeout (`도면측설`).
     * Supports both UTF-8 and Korean CP949/EUC-KR encoded DXF files.
     */
    fun parseExternalCadBytes(
        bytes: ByteArray,
        fileName: String,
        startSeq: Int
    ): CadLoadResult {
        if (bytes.size >= 6) {
            val prefix = String(bytes, 0, 6, Charsets.US_ASCII)
            if (prefix.startsWith("AC10")) {
                // Binary DWG header detected
                return CadLoadResult(
                    entities = emptyList(),
                    extractedPoints = emptyList(),
                    message = "바이너리 DWG($prefix) 파일입니다. AutoCAD/캐드 프로그램에서 '다른 이름으로 저장 -> DXF'로 저장 후 불러오시면 모든 선형/끝점 도면측설이 가능합니다."
                )
            }
        }

        // Decode with UTF-8 if valid, otherwise MS949 (Korean AutoCAD default)
        val content = decodeKoreanOrUtf8(bytes)
        val lines = content.lines().map { it.trim() }

        val entities = mutableListOf<CadLayerEntity>()
        val extractedPoints = mutableListOf<SurveyPoint>()
        var seq = startSeq

        var i = 0
        val n = lines.size

        while (i + 1 < n) {
            val code = lines[i].toIntOrNull()
            val value = lines[i + 1]
            if (code == 0) {
                when (value.uppercase(Locale.US)) {
                    "LINE" -> {
                        var layer = "0"
                        var x1 = 0.0; var y1 = 0.0; var z1 = 0.0
                        var x2 = 0.0; var y2 = 0.0
                        var has1 = false; var has2 = false
                        i += 2
                        while (i + 1 < n) {
                            val c = lines[i].toIntOrNull() ?: break
                            if (c == 0) break
                            val v = lines[i + 1]
                            when (c) {
                                8 -> layer = v
                                10 -> { x1 = v.toDoubleOrNull() ?: 0.0; has1 = true }
                                20 -> y1 = v.toDoubleOrNull() ?: 0.0
                                30 -> z1 = v.toDoubleOrNull() ?: 0.0
                                11 -> { x2 = v.toDoubleOrNull() ?: 0.0; has2 = true }
                                21 -> y2 = v.toDoubleOrNull() ?: 0.0
                            }
                            i += 2
                        }
                        if (has1 && has2) {
                            // Note: CAD X = Easting, CAD Y = Northing -> store as Pair(Northing, Easting)
                            entities.add(
                                CadLayerEntity(
                                    type = CadEntityType.LINE,
                                    layerName = layer,
                                    verticesNE = listOf(Pair(y1, x1), Pair(y2, x2)),
                                    elevationZ = z1,
                                    colorHex = 0xFFFACC15,
                                    sourceType = "DXF"
                                )
                            )
                        }
                        continue
                    }
                    "LWPOLYLINE" -> {
                        var layer = "0"
                        var isClosed = false
                        var elev = 0.0
                        val verts = mutableListOf<Pair<Double, Double>>()
                        var pendingX: Double? = null
                        i += 2
                        while (i + 1 < n) {
                            val c = lines[i].toIntOrNull() ?: break
                            if (c == 0) break
                            val v = lines[i + 1]
                            when (c) {
                                8 -> layer = v
                                38 -> elev = v.toDoubleOrNull() ?: 0.0
                                70 -> {
                                    val flag = v.toIntOrNull() ?: 0
                                    isClosed = (flag and 1) != 0
                                }
                                10 -> pendingX = v.toDoubleOrNull()
                                20 -> {
                                    val y = v.toDoubleOrNull()
                                    val x = pendingX
                                    if (x != null && y != null) {
                                        verts.add(Pair(y, x))
                                        pendingX = null
                                    }
                                }
                            }
                            i += 2
                        }
                        if (verts.size >= 2) {
                            entities.add(
                                CadLayerEntity(
                                    type = if (isClosed) CadEntityType.POLYGON else CadEntityType.POLYLINE,
                                    layerName = layer,
                                    verticesNE = verts,
                                    elevationZ = elev,
                                    isClosed = isClosed,
                                    colorHex = 0xFF34D399,
                                    sourceType = "DXF"
                                )
                            )
                        }
                        continue
                    }
                    "POLYLINE" -> {
                        var layer = "0"
                        var isClosed = false
                        val verts = mutableListOf<Pair<Double, Double>>()
                        i += 2
                        while (i + 1 < n) {
                            val c = lines[i].toIntOrNull()
                            val v = lines[i + 1]
                            if (c == 0 && v.equals("SEQEND", ignoreCase = true)) {
                                i += 2
                                break
                            }
                            if (c == 8 && verts.isEmpty()) layer = v
                            if (c == 70 && verts.isEmpty()) {
                                val flag = v.toIntOrNull() ?: 0
                                isClosed = (flag and 1) != 0
                            }
                            if (c == 0 && v.equals("VERTEX", ignoreCase = true)) {
                                var vx = 0.0; var vy = 0.0; var hasV = false
                                i += 2
                                while (i + 1 < n) {
                                    val vc = lines[i].toIntOrNull() ?: break
                                    if (vc == 0) break
                                    val vv = lines[i + 1]
                                    when (vc) {
                                        10 -> { vx = vv.toDoubleOrNull() ?: 0.0; hasV = true }
                                        20 -> vy = vv.toDoubleOrNull() ?: 0.0
                                    }
                                    i += 2
                                }
                                if (hasV) verts.add(Pair(vy, vx))
                                continue
                            }
                            i += 2
                        }
                        if (verts.size >= 2) {
                            entities.add(
                                CadLayerEntity(
                                    type = if (isClosed) CadEntityType.POLYGON else CadEntityType.POLYLINE,
                                    layerName = layer,
                                    verticesNE = verts,
                                    isClosed = isClosed,
                                    colorHex = 0xFF34D399,
                                    sourceType = "DXF"
                                )
                            )
                        }
                        continue
                    }
                    "POINT", "INSERT" -> {
                        var layer = "0"
                        var px = 0.0; var py = 0.0; var pz = 0.0; var hasP = false
                        i += 2
                        while (i + 1 < n) {
                            val c = lines[i].toIntOrNull() ?: break
                            if (c == 0) break
                            val v = lines[i + 1]
                            when (c) {
                                8 -> layer = v
                                10 -> { px = v.toDoubleOrNull() ?: 0.0; hasP = true }
                                20 -> py = v.toDoubleOrNull() ?: 0.0
                                30 -> pz = v.toDoubleOrNull() ?: 0.0
                            }
                            i += 2
                        }
                        if (hasP) {
                            entities.add(
                                CadLayerEntity(
                                    type = CadEntityType.POINT,
                                    layerName = layer,
                                    verticesNE = listOf(Pair(py, px)),
                                    elevationZ = pz,
                                    textLabel = "CAD-$seq",
                                    colorHex = 0xFFF472B6,
                                    sourceType = "DXF"
                                )
                            )
                            extractedPoints.add(
                                SurveyPoint(
                                    seqNumber = seq,
                                    name = "CAD-$seq",
                                    code = layer,
                                    northing = py,
                                    easting = px,
                                    elevation = pz,
                                    latitude = 0.0,
                                    longitude = 0.0,
                                    ellipsoidalHeight = pz,
                                    geoidSeparation = 0.0,
                                    antennaHeight = 0.0,
                                    fixQuality = FixQuality.RTK_FIXED,
                                    hrms = 0.0,
                                    vrms = 0.0,
                                    epochCount = 1,
                                    timestamp = "DXF 도면",
                                    memo = fileName
                                )
                            )
                            seq++
                        }
                        continue
                    }
                    "CIRCLE" -> {
                        var layer = "0"
                        var cx = 0.0; var cy = 0.0; var cz = 0.0; var r = 0.5; var hasC = false
                        i += 2
                        while (i + 1 < n) {
                            val c = lines[i].toIntOrNull() ?: break
                            if (c == 0) break
                            val v = lines[i + 1]
                            when (c) {
                                8 -> layer = v
                                10 -> { cx = v.toDoubleOrNull() ?: 0.0; hasC = true }
                                20 -> cy = v.toDoubleOrNull() ?: 0.0
                                30 -> cz = v.toDoubleOrNull() ?: 0.0
                                40 -> r = v.toDoubleOrNull() ?: 0.5
                            }
                            i += 2
                        }
                        if (hasC) {
                            val circlePts = (0..16).map { step ->
                                val ang = 2.0 * Math.PI * step / 16.0
                                Pair(cy + r * sin(ang), cx + r * cos(ang))
                            }
                            entities.add(
                                CadLayerEntity(
                                    type = CadEntityType.CIRCLE,
                                    layerName = layer,
                                    verticesNE = listOf(Pair(cy, cx)) + circlePts,
                                    elevationZ = cz,
                                    radius = r,
                                    isClosed = true,
                                    colorHex = 0xFFA78BFA,
                                    sourceType = "DXF"
                                )
                            )
                        }
                        continue
                    }
                    "TEXT", "MTEXT" -> {
                        var layer = "0"
                        var tx = 0.0; var ty = 0.0; var tz = 0.0; var txt = ""; var hasT = false
                        i += 2
                        while (i + 1 < n) {
                            val c = lines[i].toIntOrNull() ?: break
                            if (c == 0) break
                            val v = lines[i + 1]
                            when (c) {
                                8 -> layer = v
                                10 -> { tx = v.toDoubleOrNull() ?: 0.0; hasT = true }
                                20 -> ty = v.toDoubleOrNull() ?: 0.0
                                30 -> tz = v.toDoubleOrNull() ?: 0.0
                                1 -> txt = v
                            }
                            i += 2
                        }
                        if (hasT && txt.isNotBlank()) {
                            entities.add(
                                CadLayerEntity(
                                    type = CadEntityType.TEXT,
                                    layerName = layer,
                                    verticesNE = listOf(Pair(ty, tx)),
                                    elevationZ = tz,
                                    textLabel = txt,
                                    colorHex = 0xFFE2E8F0,
                                    sourceType = "DXF"
                                )
                            )
                        }
                        continue
                    }
                }
            }
            i += 2
        }

        val msg = "도면 로드 완료: 선형/폴리라인/점 객체 ${entities.size}개 (스냅 가능 정점 ${entities.sumOf { it.verticesNE.size }}개)"
        return CadLoadResult(entities, extractedPoints, msg)
    }

    private fun decodeKoreanOrUtf8(bytes: ByteArray): String {
        val utf8Str = String(bytes, Charsets.UTF_8)
        return if (!utf8Str.contains('\uFFFD')) {
            utf8Str
        } else {
            try {
                String(bytes, Charset.forName("MS949"))
            } catch (_: Exception) {
                utf8Str
            }
        }
    }

    // =========================================================================
    // 5. CSV / TXT POINT IMPORTER & KML EXPORTER
    // =========================================================================

    fun exportKml(context: Context, projectName: String, points: List<SurveyPoint>): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date())
        val file = File(getExportsDir(context), "${projectName}_구글어스_$stamp.kml")

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n")
        sb.append("  <Document>\n")
        sb.append("    <name>$projectName</name>\n")
        points.forEach { p ->
            sb.append("    <Placemark>\n")
            sb.append("      <name>#${p.seqNumber} ${p.name} [${p.code}]</name>\n")
            sb.append("      <description>N: ${p.northing}, E: ${p.easting}, Z: ${p.elevation}, 코드: ${p.code}, 메모: ${p.memo}</description>\n")
            sb.append("      <Point>\n")
            sb.append("        <coordinates>${p.longitude},${p.latitude},${p.elevation}</coordinates>\n")
            sb.append("      </Point>\n")
            sb.append("    </Placemark>\n")
        }
        sb.append("  </Document>\n")
        sb.append("</kml>\n")

        file.writeText(sb.toString(), Charsets.UTF_8)
        return file
    }

    fun importCsvText(content: String, startSeq: Int): List<SurveyPoint> {
        val result = mutableListOf<SurveyPoint>()
        var seq = startSeq
        content.lineSequence().forEachIndexed { idx, rawLine ->
            val line = rawLine.replace("\uFEFF", "").trim()
            if (line.isEmpty()) return@forEachIndexed
            if (idx == 0 && (line.contains("Northing", true) || line.contains("측점") || line.contains("순번") || line.contains("측번"))) {
                return@forEachIndexed
            }
            val tokens = line.split(Regex("[,\\t; ]+")).map { it.trim() }.filter { it.isNotEmpty() }
            if (tokens.size >= 4) {
                // Detect if column 0 is seqNumber and column 1 is pointName
                val firstIsInt = tokens[0].toIntOrNull() != null
                val secondIsNumber = tokens[1].toDoubleOrNull() != null

                if (firstIsInt && !secondIsNumber && tokens.size >= 5) {
                    val name = tokens[1]
                    val n = tokens[2].toDoubleOrNull() ?: return@forEachIndexed
                    val e = tokens[3].toDoubleOrNull() ?: return@forEachIndexed
                    val z = tokens[4].toDoubleOrNull() ?: 0.0
                    val code = tokens.getOrNull(5) ?: "IMPORT"
                    val memo = tokens.getOrNull(6) ?: ""
                    result.add(createImportedPoint(seq++, name, code, n, e, z, memo))
                } else {
                    val name = tokens[0]
                    val n = tokens[1].toDoubleOrNull() ?: return@forEachIndexed
                    val e = tokens[2].toDoubleOrNull() ?: return@forEachIndexed
                    val z = tokens[3].toDoubleOrNull() ?: 0.0
                    val code = tokens.getOrNull(4) ?: "IMPORT"
                    val memo = tokens.getOrNull(5) ?: ""
                    result.add(createImportedPoint(seq++, name, code, n, e, z, memo))
                }
            }
        }
        return result
    }

    private fun createImportedPoint(
        seq: Int,
        name: String,
        code: String,
        n: Double,
        e: Double,
        z: Double,
        memo: String
    ): SurveyPoint {
        return SurveyPoint(
            seqNumber = seq,
            name = name,
            code = code,
            northing = n,
            easting = e,
            elevation = z,
            latitude = 0.0,
            longitude = 0.0,
            ellipsoidalHeight = z,
            geoidSeparation = 0.0,
            antennaHeight = 0.0,
            fixQuality = FixQuality.RTK_FIXED,
            hrms = 0.0,
            vrms = 0.0,
            epochCount = 1,
            timestamp = "불러오기",
            memo = memo
        )
    }
}
