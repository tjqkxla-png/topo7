package kr.koseco.gandansurvey.v90.model

/**
 * 한국 측량 좌표계 종류 (브이월드 SHP 호환 EPSG:5186, 5174, 5179 포함)
 */
enum class CoordinateSystemType(val displayName: String) {
    GRS80_TM("GRS80 평면직각 (EPSG:5186)"),
    BESSEL_TM("Bessel 평면직각 (구지적 EPSG:5174)"),
    GRS80_LATLON("WGS84/GRS80 경위도")
}

/**
 * 평면직각좌표계 투영 원점 (서부/중부/동부/동해/제주/UTM-K/구중부50만/자동)
 */
enum class KoreaTmZone(
    val displayName: String,
    val centralMeridianDeg: Double,
    val originLatDeg: Double = 38.0,
    val falseNorthing: Double = 600000.0,
    val falseEasting: Double = 200000.0,
    val scaleFactor: Double = 1.0
) {
    WEST_ZONE1("서부 (125°E, X:60만)", 125.0),
    CENTRAL_ZONE2("중부 (127°E, X:60만)", 127.0),
    EAST_ZONE3("동부 (129°E, X:60만)", 129.0),
    EAST_SEA_ZONE4("동해 (131°E, X:60만)", 131.0),
    OLD_CENTRAL_500K("구중부 (127°E, X:50만)", 127.0, 38.0, 500000.0, 200000.0, 1.0),
    JEJU_ZONE("제주 (127°E, X:55만)", 127.0, 38.0, 550000.0, 200000.0, 1.0),
    UTMK("UTM-K (브이월드 EPSG:5179)", 127.5, 38.0, 2000000.0, 1000000.0, 0.9996),
    AUTO("자동 (현위치 경도)", 127.0);

    companion object {
        fun resolveZone(longitudeDeg: Double, selected: KoreaTmZone): KoreaTmZone {
            if (selected != AUTO) return selected
            return when {
                longitudeDeg < 126.0 -> WEST_ZONE1
                longitudeDeg < 128.0 -> CENTRAL_ZONE2
                longitudeDeg < 130.0 -> EAST_ZONE3
                else -> EAST_SEA_ZONE4
            }
        }
    }
}

enum class GeoidModel(val displayName: String) {
    KNGEOID18("KNGeoid18 (권장)"),
    KNGEOID14("KNGeoid14"),
    KNGEOID13("KNGeoid13"),
    EGM2008("EGM2008"),
    EGM96("EGM96")
}

/**
 * 배경 항공사진 / 지도 모드 (사용자 요청 7번 완벽 구현)
 */
enum class BackgroundMapType(val korLabel: String, val shortLabel: String) {
    NONE("배경지도 없음 (CAD 전용)", "CAD도면"),
    GOOGLE_SATELLITE("구글 항공사진 (위성+하이브리드)", "구글항공"),
    DAUM_SKYVIEW("다음(카카오) 항공사진 (스카이뷰)", "다음항공"),
    NAVER_SATELLITE("네이버 항공사진 (위성지도)", "네이버항공"),
    DAUM_ROADMAP("다음(카카오) 일반지도 (도로/지번)", "다음지도")
}

/**
 * 도면측설 객체스냅(OSNAP) 유형 (사용자 요청 5번 완벽 구현)
 */
enum class CadSnapType(val korLabel: String) {
    ENDPOINT("끝점(변곡점)"),
    MIDPOINT("중간점"),
    INTERSECTION("교차점"),
    POINT_NODE("도면점(노드)"),
    NEAREST_LINE("선상 최근접점")
}

data class CadSnapCandidate(
    val xNorthing: Double,
    val yEasting: Double,
    val zElevation: Double,
    val snapType: CadSnapType,
    val sourceLayer: String
)

/**
 * 사용자 선택형 내보내기 항목 설정 (사용자 핵심 요청: 측번, 코드명, 메모 등을 직접 선택해서 출력!)
 */
enum class ExportDelimiter(val korLabel: String, val sepChar: String, val fileExt: String) {
    COMMA_CSV("쉼표(,) 구분 - 엑셀 CSV (.csv)", ",", "csv"),
    SPACE_NEZ("공백( ) 구분 - NEZ 포맷 (.nez)", " ", "nez"),
    COMMA_NEZ("쉼표(,) 구분 - NEZ 포맷 (.nez)", ",", "nez"),
    TAB_TXT("탭(Tab) 구분 - 텍스트 (.txt)", "\t", "txt")
}

enum class CoordinateOrderMode(val korLabel: String) {
    N_E_Z("북방 N(X) → 동방 E(Y) → 표고 Z (측량 표준)"),
    E_N_Z("동방 E(X) → 북방 N(Y) → 표고 Z (CAD 순서)")
}

data class ExportColumnConfig(
    val includeHeaderRow: Boolean = true,      // 첫 줄 컬럼명 헤더 포함 여부
    val includeSeqNo: Boolean = false,         // 순번 (1, 2, 3...)
    val includePointName: Boolean = true,      // 측번 (포인트명) - 기본 ON
    val includeNorthingEasting: Boolean = true,// 평면좌표 N(X), E(Y) - 기본 ON
    val includeElevationZ: Boolean = true,     // 지반고 Z(h) - 기본 ON
    val includeCode: Boolean = true,           // 코드명 - 기본 ON (확실하게 출력!)
    val includeMemo: Boolean = true,           // 메모(비고) - 기본 ON
    val includeLatLon: Boolean = false,        // 위도 / 경도 / 타원체고
    val includeFixAndPrecision: Boolean = false,// 측위상태(FIXED 등) 및 정밀도(Hσ, Vσ)
    val includeTimestamp: Boolean = false,     // 측정시각
    val coordOrder: CoordinateOrderMode = CoordinateOrderMode.N_E_Z,
    val delimiter: ExportDelimiter = ExportDelimiter.COMMA_CSV,
    // DXF 도면 내보내기 시 문자 라벨 선택 옵션
    val dxfIncludePointName: Boolean = true,
    val dxfIncludeCode: Boolean = true,
    val dxfIncludeElevation: Boolean = true,
    val dxfIncludeMemo: Boolean = false
)

/**
 * 측량 수신기 작동 모드
 */
enum class SurveyReceiverMode(val korTitle: String, val shortBadge: String) {
    EXTERNAL_GPS("GPS 측량기 모드 (V90 외부장비)", "장비GPS"),
    PHONE_INTERNAL_GPS("휴대폰 측량 모드 (내장GPS)", "휴대폰GPS"),
    INDOOR_SIMULATION("실내 시뮬레이션 테스트 모드", "시뮬모드")
}

enum class FixStatus(val korLabel: String, val engLabel: String, val nmeaQuality: Int, val reliabilityRank: Int) {
    FIXED("고정(FIXED)", "FIXED", 4, 4),
    FLOAT("유동(FLOAT)", "FLOAT", 5, 3),
    DGPS("차분(DGPS)", "DGPS", 2, 2),
    SINGLE("단독(SINGLE)", "SINGLE", 1, 1),
    INVALID("미수신(연결필요)", "NONE", 0, 0);

    companion object {
        fun fromNmeaQuality(q: Int): FixStatus = when (q) {
            4 -> FIXED
            5 -> FLOAT
            2, 3 -> DGPS
            1, 6 -> SINGLE
            else -> INVALID
        }
    }
}

enum class MinRequiredFixLevel(val korLabel: String, val shortLabel: String, val minRank: Int) {
    FIXED_ONLY("FIXED만 측량 허용 (RTK 정밀측량)", "FIXED 전용", 4),
    FLOAT_OR_BETTER("FLOAT(유동) 이상 측량 허용", "FLOAT 이상", 3),
    DGPS_OR_BETTER("DGPS(차분) 이상 측량 허용", "DGPS 이상", 2),
    SINGLE_ALLOWED("SINGLE(단독/휴대폰) 포함 허용", "SINGLE 이상", 1)
}

enum class DxfKoreanEncoding(val korLabel: String) {
    CP949_KOREAN("한글 캐드 표준 (CP949 / ANSI_949)"),
    UTF8_MODERN("UTF-8 (AutoCAD 2007+ / 최신뷰어)"),
    UNICODE_ESCAPE("유니코드 이스케이프 (\\U+XXXX 범용)")
}

enum class GpsOperationMode(val korLabel: String) {
    VRS("VRS (네트워크 RTK)"),
    RTK_ROVER("RTK-ROVER (이동국)"),
    RTK_BASE("RTK-BASE (기준국)"),
    STATIC("STATIC (정지측량)")
}

enum class AntennaMeasureType(val displayName: String, val phaseCenterOffsetM: Double) {
    VERTICAL_POLE("수직 (GPS 폴)", 0.088),
    SLANT_TRIPOD("경사 (정준대)", 0.138),
    APC("위상중심 (APC)", 0.000)
}

enum class DiffStatus(val korLabel: String) {
    GOOD("양호"),
    BAD("불량")
}

data class SatelliteInfo(
    val key: String, // "$constellation-$prn" 복합키 (다중 위성군 PRN 충돌 방지!)
    val prn: Int,
    val constellation: String,
    val elevationDeg: Float,
    val azimuthDeg: Float,
    val snrDb: Float,
    val usedInFix: Boolean
)

data class GnssTelemetry(
    val receiverMode: SurveyReceiverMode = SurveyReceiverMode.EXTERNAL_GPS,
    val isConnected: Boolean = false,
    val deviceName: String = "미연결 (GPS 측량기를 연결하세요)",
    val deviceAddress: String = "",
    val gpsMode: GpsOperationMode = GpsOperationMode.VRS,
    val latitude: Double = 36.642434,
    val longitude: Double = 127.489032,
    val ellipsoidalHeight: Double = 78.420,
    val geoidSeparation: Double = 24.810,
    val orthometricHeight: Double = 53.610,
    val xNorthing: Double = 449182.345,
    val yEasting: Double = 243718.612,
    val zElevation: Double = 52.010,
    val fixStatus: FixStatus = FixStatus.INVALID,
    val diffStatus: DiffStatus = DiffStatus.BAD,
    val diffAgeSec: Double = 99.0,
    val rtcmBps: Int = 0,
    val rxNmeaCount: Long = 0L,
    val satellitesUsed: Int = 0,
    val satellitesInView: Int = 0,
    val pdop: Double = 99.9,
    val hdop: Double = 99.9,
    val vdop: Double = 99.9,
    val hPrecision: Double = 9.999,
    val vPrecision: Double = 9.999,
    val headingDeg: Float = 0f,
    val compassAzimuthDeg: Float = 0f,
    val speedKmh: Double = 0.0,
    val imuTiltDeg: Float = 0.6f,
    val imuPitchDeg: Float = 0.3f,
    val imuRollDeg: Float = -0.2f,
    val imuActive: Boolean = false,
    val receiverBattery: Int = 0,
    val controllerBattery: Int = 100,
    val satellites: List<SatelliteInfo> = emptyList(),
    val lastGgaSentence: String = ""
)

data class VrsAccount(
    val id: String,
    val alias: String,
    val host: String,
    val port: Int = 2101,
    val username: String,
    val password: String = "ngii",
    val mountpoint: String = "VRS-RTCM32"
)

data class MountpointItem(
    val name: String,
    val format: String,
    val carrier: String,
    val navSystem: String,
    val network: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
    val requiresGga: Boolean,
    val distanceKm: Double = 0.0
)

data class MapDisplayConfig(
    val showPointName: Boolean = true,
    val showCode: Boolean = true,
    val showElevation: Boolean = true,
    val showMemo: Boolean = false,
    val showSymbol: Boolean = true,
    val textSizeScale: Float = 1.0f,
    val whiteBackground: Boolean = false,
    val backgroundMapType: BackgroundMapType = BackgroundMapType.NONE,
    val mapOpacity: Float = 0.85f,
    val cadSnapEnabled: Boolean = true,      // 도면 끝점/교차점/중간점 객체스냅 활성화
    val codeLayerSeparate: Boolean = true,
    val followRoverCenter: Boolean = false,
    val dxfEncoding: DxfKoreanEncoding = DxfKoreanEncoding.CP949_KOREAN,
    val decimalPlaces: Int = 3
)

enum class AutoLineMode(val korName: String) {
    NONE("연결안함"),
    PREV_POINT("전점연결"),
    PREV_PREV_POINT("전전점연결"),
    CODE_MATCH("코드연결"),
    POLYGON("면연결")
}

data class SurveyOptionConfig(
    val minRequiredFixLevel: MinRequiredFixLevel = MinRequiredFixLevel.FIXED_ONLY,
    val hrmsLimitM: Double = 0.050,
    val vrmsLimitM: Double = 0.100,
    val pdopLimit: Double = 3.0,
    val antennaHeightM: Double = 1.800,
    val antennaMeasureType: AntennaMeasureType = AntennaMeasureType.VERTICAL_POLE,
    val epochCount: Int = 1,
    val imuTiltLimitDeg: Double = 30.0,
    val imuCompensationEnabled: Boolean = true,
    val useVolumeKeyShutter: Boolean = true,
    val continuousPresetLocked: Boolean = false
)

enum class AutoSurveyMode(val korName: String) {
    NONE("수동측정"),
    TIME_INTERVAL("시간간격"),
    STOP_AND_GO("정지측정"),
    DISTANCE_INTERVAL("거리간격")
}

data class AutoSurveyConfig(
    val mode: AutoSurveyMode = AutoSurveyMode.NONE,
    val timeIntervalSec: Int = 5,
    val stopDurationSec: Int = 5,
    val stopToleranceM: Double = 0.050,
    val distanceIntervalM: Double = 5.0
)

enum class StakeoutOrientationMode(val korName: String) {
    COMPASS("나침반(컨트롤러 방향)"),
    NORTH("북쪽 고정"),
    MOVING("이동 진행방향")
}

enum class StakeoutSnapMode(val korName: String) {
    OBJECT_SNAP("도면 객체스냅(끝점·교차점·중간점)"),
    AUTO_NEAREST("근접점 자동선택"),
    LINE_SNAP("선상 근처점 스냅")
}

data class StakeoutConfig(
    val showZGuidance: Boolean = false,
    val soundAlertEnabled: Boolean = true,
    val farBellDistanceM: Double = 10.0,
    val nearBeepDistanceM: Double = 1.0,
    val orientationMode: StakeoutOrientationMode = StakeoutOrientationMode.COMPASS,
    val snapMode: StakeoutSnapMode = StakeoutSnapMode.OBJECT_SNAP
)

data class LastSurveyPreset(
    val code: String = "경계점",
    val symbol: String = "CIRCLE",
    val memo: String = "",
    val autoLineMode: AutoLineMode = AutoLineMode.NONE,
    val antennaHeightM: Double = 1.800
)

data class SurveyPoint(
    val seqNo: Int,
    val name: String,
    val xNorthing: Double,
    val yEasting: Double,
    val zElevation: Double,
    val latitude: Double,
    val longitude: Double,
    val ellipsoidalHeight: Double,
    val geoidSeparation: Double = 24.810,
    val code: String = "",
    val symbol: String = "CIRCLE",
    val memo: String = "",
    val antennaHeight: Double = 1.800,
    val antennaType: AntennaMeasureType = AntennaMeasureType.VERTICAL_POLE,
    val hPrecision: Double = 0.012,
    val vPrecision: Double = 0.020,
    val pdop: Double = 1.3,
    val hdop: Double = 0.7,
    val vdop: Double = 1.1,
    val satellites: Int = 22,
    val epochCount: Int = 1,
    val fixStatus: FixStatus = FixStatus.FIXED,
    val receiverModeLabel: String = "장비GPS",
    val timestamp: String = "",
    val startTime: String = "",
    val endTime: String = ""
)

enum class FigureType { POLYLINE, POLYGON }

data class SurveyFigure(
    val id: String,
    val name: String,
    val layer: String,
    val type: FigureType,
    val pointNames: List<String>,
    val colorHex: Long = 0xFF10B981,
    val strokeWidth: Float = 3.5f
)

data class DxfCadEntity(
    val layer: String,
    val type: String, // LINE, LWPOLYLINE, POINT, TEXT, SHP_POLYGON, SHP_POINT
    val vertices: List<Pair<Double, Double>>, // Pair(X_Northing, Y_Easting)
    val elevations: List<Double> = emptyList(),
    val isClosed: Boolean = false,
    val text: String = "",
    val colorIndex: Int = 7
)

data class LayerConfig(
    val name: String,
    val visible: Boolean = true,
    val colorHex: Long = 0xFF38BDF8
)

data class SurveyJob(
    val name: String,
    val createdAt: String,
    val coordSystem: CoordinateSystemType = CoordinateSystemType.GRS80_TM,
    val zone: KoreaTmZone = KoreaTmZone.CENTRAL_ZONE2,
    val geoidModel: GeoidModel = GeoidModel.KNGEOID18,
    val points: List<SurveyPoint> = emptyList(),
    val figures: List<SurveyFigure> = emptyList(),
    val stakeoutPoints: List<SurveyPoint> = emptyList(),
    val layers: List<LayerConfig> = listOf(
        LayerConfig("0", true, 0xFFFFFFFF),
        LayerConfig("지적도(SHP/DXF)", true, 0xFFF59E0B),
        LayerConfig("PTLAYER", true, 0xFFEF4444),
        LayerConfig("PTANNOTPNO", true, 0xFF60A5FA),
        LayerConfig("PTANNOTELE", true, 0xFF34D399),
        LayerConfig("PTANNOTCODE", true, 0xFFFBBF24)
    ),
    val backgroundDxfName: String = "도면 없음",
    val dxfEntities: List<DxfCadEntity> = emptyList(),
    val activeTrfName: String = "기본좌표계 사용중"
)
