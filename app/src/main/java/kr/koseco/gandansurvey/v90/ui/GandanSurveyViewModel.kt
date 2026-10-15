package kr.koseco.gandansurvey.v90.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kr.koseco.gandansurvey.v90.coord.CalibrationSolver
import kr.koseco.gandansurvey.v90.coord.CoordinateConverter
import kr.koseco.gandansurvey.v90.gnss.BtDeviceItem
import kr.koseco.gandansurvey.v90.gnss.GnssBluetoothManager
import kr.koseco.gandansurvey.v90.gnss.NmeaParser
import kr.koseco.gandansurvey.v90.gnss.NtripClient
import kr.koseco.gandansurvey.v90.model.*
import kr.koseco.gandansurvey.v90.util.FileIoManager
import kr.koseco.gandansurvey.v90.util.PhoneGpsLocationManager
import kr.koseco.gandansurvey.v90.util.ShpFileParser
import kr.koseco.gandansurvey.v90.util.SoundAndSensorManager
import kr.koseco.gandansurvey.v90.util.TileMapManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

class GandanSurveyViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("gandansurvey_v90_prefs", Context.MODE_PRIVATE)
    private val nmeaParser = NmeaParser()
    val soundSensorManager = SoundAndSensorManager(application, viewModelScope)

    private val _gnssStatus = MutableStateFlow(GnssStatus())
    val gnssStatus: StateFlow<GnssStatus> = _gnssStatus.asStateFlow()

    private val _coordinateSystem = MutableStateFlow(KoreanCoordinateSystem.CENTRAL_GRS80)
    val coordinateSystem: StateFlow<KoreanCoordinateSystem> = _coordinateSystem.asStateFlow()

    private val _useKnGeoid18 = MutableStateFlow(true)
    val useKnGeoid18: StateFlow<Boolean> = _useKnGeoid18.asStateFlow()

    private val _antennaHeight = MutableStateFlow(1.800)
    val antennaHeight: StateFlow<Double> = _antennaHeight.asStateFlow()

    private val _imuTiltCompensationEnabled = MutableStateFlow(false)
    val imuTiltCompensationEnabled: StateFlow<Boolean> = _imuTiltCompensationEnabled.asStateFlow()

    // Strict Positioning Source Mode (Defaults to V90 Bluetooth Receiver Mode)
    private val _sourceMode = MutableStateFlow(PositioningSourceMode.BLUETOOTH_GNSS)
    val sourceMode: StateFlow<PositioningSourceMode> = _sourceMode.asStateFlow()

    // Minimum required Fix Quality filter for surveying
    private val _requiredFixQuality = MutableStateFlow(FixQuality.RTK_FIXED)
    val requiredFixQuality: StateFlow<FixQuality> = _requiredFixQuality.asStateFlow()

    private val _currentPlaneCoord = MutableStateFlow(
        PlaneCoordinate(0.0, 0.0, 0.0, 0.0, KoreanCoordinateSystem.CENTRAL_GRS80)
    )
    val currentPlaneCoord: StateFlow<PlaneCoordinate> = _currentPlaneCoord.asStateFlow()

    // Project Management State
    private val _projectName = MutableStateFlow("간단측량_현장01")
    val projectName: StateFlow<String> = _projectName.asStateFlow()

    private val _savedProjectFiles = MutableStateFlow<List<File>>(emptyList())
    val savedProjectFiles: StateFlow<List<File>> = _savedProjectFiles.asStateFlow()

    private val _points = MutableStateFlow<List<SurveyPoint>>(emptyList())
    val points: StateFlow<List<SurveyPoint>> = _points.asStateFlow()

    private val _polylines = MutableStateFlow<List<CadPolyline>>(emptyList())
    val polylines: StateFlow<List<CadPolyline>> = _polylines.asStateFlow()

    // External CAD (DXF/DWG) & V-World SHP Layer Entities for CAD Stakeout (도면측설)
    private val _cadEntities = MutableStateFlow<List<CadLayerEntity>>(emptyList())
    val cadEntities: StateFlow<List<CadLayerEntity>> = _cadEntities.asStateFlow()

    private val _cadSnapEnabled = MutableStateFlow(true)
    val cadSnapEnabled: StateFlow<Boolean> = _cadSnapEnabled.asStateFlow()

    private val _lastSnappedTarget = MutableStateFlow<CadSnapTarget?>(null)
    val lastSnappedTarget: StateFlow<CadSnapTarget?> = _lastSnappedTarget.asStateFlow()

    // Background Aerial / Road Map State
    private val _backgroundMapType = MutableStateFlow(BackgroundMapType.NONE)
    val backgroundMapType: StateFlow<BackgroundMapType> = _backgroundMapType.asStateFlow()

    private val _backgroundMapAlpha = MutableStateFlow(0.82f)
    val backgroundMapAlpha: StateFlow<Float> = _backgroundMapAlpha.asStateFlow()

    private val _tileInvalidateTick = MutableStateFlow(0L)
    val tileInvalidateTick: StateFlow<Long> = _tileInvalidateTick.asStateFlow()

    val tileMapManager = TileMapManager(viewModelScope) {
        _tileInvalidateTick.value = System.currentTimeMillis()
    }

    // Selective Export Column Configuration (측번, 코드명, 메모 등 선택 내보내기)
    private val _exportConfig = MutableStateFlow(ExportColumnConfig())
    val exportConfig: StateFlow<ExportColumnConfig> = _exportConfig.asStateFlow()

    private val _activePolylineCode = MutableStateFlow("경계")
    val activePolylineCode: StateFlow<String> = _activePolylineCode.asStateFlow()

    private val _stakeoutTarget = MutableStateFlow<SurveyPoint?>(null)
    val stakeoutTarget: StateFlow<SurveyPoint?> = _stakeoutTarget.asStateFlow()

    private val _stakeoutIncludeZ = MutableStateFlow(false)
    val stakeoutIncludeZ: StateFlow<Boolean> = _stakeoutIncludeZ.asStateFlow()

    private val _stakeoutSoundEnabled = MutableStateFlow(true)
    val stakeoutSoundEnabled: StateFlow<Boolean> = _stakeoutSoundEnabled.asStateFlow()

    private val _stakeoutGuidance = MutableStateFlow<StakeoutGuidance?>(null)
    val stakeoutGuidance: StateFlow<StakeoutGuidance?> = _stakeoutGuidance.asStateFlow()

    private val _calibrationPairs = MutableStateFlow<List<CalibrationPair>>(emptyList())
    val calibrationPairs: StateFlow<List<CalibrationPair>> = _calibrationPairs.asStateFlow()

    private val _calibrationParams = MutableStateFlow(SiteCalibrationParams())
    val calibrationParams: StateFlow<SiteCalibrationParams> = _calibrationParams.asStateFlow()

    private val _ntripConfig = MutableStateFlow(NtripConfig())
    val ntripConfig: StateFlow<NtripConfig> = _ntripConfig.asStateFlow()

    private val _availableMountpoints = MutableStateFlow(
        listOf(
            "VRS-RTCM32 (RTCM 3.2 MSM4)",
            "VRS-RTCM31 (RTCM 3.1)",
            "FKP-RTCM32 (RTCM 3.2)",
            "SSR-RTCM32 (RTCM 3.2)"
        )
    )
    val availableMountpoints: StateFlow<List<String>> = _availableMountpoints.asStateFlow()

    private val _quickCodes = MutableStateFlow(defaultQuickCodes())
    val quickCodes: StateFlow<List<QuickCodePreset>> = _quickCodes.asStateFlow()

    private val _lastMeasurePreset = MutableStateFlow(LastMeasurePreset())
    val lastMeasurePreset: StateFlow<LastMeasurePreset> = _lastMeasurePreset.asStateFlow()

    private val _isAveraging = MutableStateFlow(false)
    val isAveraging: StateFlow<Boolean> = _isAveraging.asStateFlow()

    private val _averagingProgress = MutableStateFlow(0)
    val averagingProgress: StateFlow<Int> = _averagingProgress.asStateFlow()

    // Auto-dismissing 1.5-second notification toast state
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()
    private var toastAutoDismissJob: Job? = null

    private var simJob: Job? = null
    private var lastAnnouncedFix: FixQuality = FixQuality.NO_FIX

    // Triggers Measure Dialog from Hardware Volume UP key
    private val _openMeasureDialogEvent = MutableStateFlow(false)
    val openMeasureDialogEvent: StateFlow<Boolean> = _openMeasureDialogEvent.asStateFlow()

    val btManager = GnssBluetoothManager(
        context = application,
        scope = viewModelScope,
        onNmeaLine = { line ->
            val parsed = nmeaParser.parseLine(line)
            if (parsed.lastGgaSentence.isNotBlank()) {
                ntripClient.updateLatestGga(parsed.lastGgaSentence)
            }
            if (_sourceMode.value == PositioningSourceMode.BLUETOOTH_GNSS) {
                updateGnssAndCoordinates(
                    parsed.copy(
                        sourceMode = PositioningSourceMode.BLUETOOTH_GNSS,
                        btConnected = true,
                        ntripConnected = _gnssStatus.value.ntripConnected,
                        ntripStatusMessage = _gnssStatus.value.ntripStatusMessage,
                        rtcmBytesPerSec = _gnssStatus.value.rtcmBytesPerSec,
                        connectedDeviceName = _gnssStatus.value.connectedDeviceName
                    )
                )
            }
        },
        onConnectionStateChanged = { connected, msg ->
            if (!connected && _sourceMode.value == PositioningSourceMode.BLUETOOTH_GNSS) {
                nmeaParser.reset()
                _gnssStatus.value = GnssStatus(
                    sourceMode = PositioningSourceMode.BLUETOOTH_GNSS,
                    btConnected = false,
                    fixQuality = FixQuality.NO_FIX,
                    connectedDeviceName = msg,
                    ntripConnected = _gnssStatus.value.ntripConnected,
                    ntripStatusMessage = _gnssStatus.value.ntripStatusMessage
                )
            } else {
                _gnssStatus.value = _gnssStatus.value.copy(
                    btConnected = connected,
                    connectedDeviceName = msg
                )
            }
            postToast(msg)
        }
    )

    private val phoneGpsManager = PhoneGpsLocationManager(application) { phoneStatus ->
        if (_sourceMode.value == PositioningSourceMode.PHONE_INTERNAL) {
            updateGnssAndCoordinates(
                phoneStatus.copy(
                    sourceMode = PositioningSourceMode.PHONE_INTERNAL,
                    btConnected = false,
                    connectedDeviceName = "휴대폰 내장 GPS 수신 중"
                )
            )
        }
    }

    val ntripClient = NtripClient(
        scope = viewModelScope,
        onRtcmReceived = { buffer, len, bps ->
            btManager.writeRtcmBytes(buffer, len)
            _gnssStatus.value = _gnssStatus.value.copy(
                ntripConnected = true,
                rtcmBytesPerSec = bps
            )
        },
        onStatusChanged = { connected, statusMsg ->
            _gnssStatus.value = _gnssStatus.value.copy(
                ntripConnected = connected,
                ntripStatusMessage = statusMsg,
                rtcmBytesPerSec = if (connected) _gnssStatus.value.rtcmBytesPerSec else 0
            )
            postToast(statusMsg)
        }
    )

    init {
        loadSavedSettingsAndLastProject()
        refreshSavedProjectsList()
        applyPositioningSourceMode(PositioningSourceMode.BLUETOOTH_GNSS, announce = false)
    }

    /**
     * Shows a bottom notification banner that automatically closes after 1.5 seconds (1500ms).
     */
    fun postToast(msg: String) {
        toastAutoDismissJob?.cancel()
        _toastMessage.value = msg
        toastAutoDismissJob = viewModelScope.launch {
            delay(1500L)
            _toastMessage.value = null
        }
    }

    fun clearToast() {
        toastAutoDismissJob?.cancel()
        _toastMessage.value = null
    }

    fun consumeOpenMeasureDialogEvent() {
        _openMeasureDialogEvent.value = false
    }

    /**
     * Switches strictly between:
     * 1. BLUETOOTH_GNSS (GPS 측량기 모드 - V90)
     * 2. PHONE_INTERNAL (휴대폰 내장 GPS 모드)
     * 3. SIMULATION (실내 시뮬레이션 모드)
     */
    fun setPositioningSourceMode(mode: PositioningSourceMode) {
        applyPositioningSourceMode(mode, announce = true)
    }

    private fun applyPositioningSourceMode(mode: PositioningSourceMode, announce: Boolean) {
        simJob?.cancel()
        simJob = null
        phoneGpsManager.stop()
        _sourceMode.value = mode

        // Auto-adjust minimum required fix quality to match mode realistically
        when (mode) {
            PositioningSourceMode.BLUETOOTH_GNSS -> {
                _requiredFixQuality.value = FixQuality.RTK_FIXED
                val isBtConnected = _gnssStatus.value.btConnected
                if (!isBtConnected) {
                    nmeaParser.reset()
                    _gnssStatus.value = GnssStatus(
                        sourceMode = PositioningSourceMode.BLUETOOTH_GNSS,
                        btConnected = false,
                        fixQuality = FixQuality.NO_FIX,
                        satellitesTracked = 0,
                        satellitesUsed = 0,
                        hrms = 99.9,
                        vrms = 99.9,
                        connectedDeviceName = "미연결 (V90 수신기를 연결하세요)",
                        ntripConnected = _gnssStatus.value.ntripConnected,
                        ntripStatusMessage = _gnssStatus.value.ntripStatusMessage
                    )
                }
                if (announce) postToast("GPS 측량기(V90) 모드로 전환되었습니다.")
            }
            PositioningSourceMode.PHONE_INTERNAL -> {
                btManager.disconnect()
                _requiredFixQuality.value = FixQuality.SINGLE
                _gnssStatus.value = GnssStatus(
                    sourceMode = PositioningSourceMode.PHONE_INTERNAL,
                    btConnected = false,
                    fixQuality = FixQuality.NO_FIX,
                    satellitesTracked = 0,
                    satellitesUsed = 0,
                    hrms = 99.9,
                    vrms = 99.9,
                    connectedDeviceName = "휴대폰 GPS 신호 대기 중..."
                )
                val started = phoneGpsManager.start()
                if (announce) {
                    if (started) {
                        postToast("휴대폰 내장 GPS 측량 모드로 전환되었습니다.")
                    } else {
                        postToast("휴대폰 위치(GPS) 권한 또는 위치 서비스가 꺼져 있습니다.")
                    }
                }
            }
            PositioningSourceMode.SIMULATION -> {
                btManager.disconnect()
                _requiredFixQuality.value = FixQuality.RTK_FIXED
                startIndoorSimulationLoop()
                if (announce) postToast("실내 테스트 시뮬레이션 모드가 켜졌습니다.")
            }
        }
    }

    fun setRequiredFixQuality(quality: FixQuality) {
        _requiredFixQuality.value = quality
        postToast("측량 허용 기준: ${quality.koreanLabel} 이상")
    }

    /**
     * Re-sends V90 NMEA 1Hz activation commands on demand if the user sees 0 satellites.
     */
    fun requestV90NmeaOutputNow() {
        btManager.sendV90NmeaActivationCommands()
        postToast("V90 수신기에 NMEA 위성출력 명령을 재전송했습니다.")
    }

    /**
     * Checks if the current GNSS signal is valid and meets the user's selected quality threshold.
     */
    fun validateReadyToMeasure(): String? {
        val status = _gnssStatus.value
        val mode = _sourceMode.value

        when (mode) {
            PositioningSourceMode.BLUETOOTH_GNSS -> {
                if (!status.btConnected) {
                    return "측량기(GPS)가 블루투스로 연결되어 있지 않아 측량할 수 없습니다."
                }
                if (status.fixQuality == FixQuality.NO_FIX || (status.latitude == 0.0 && status.longitude == 0.0)) {
                    return "GPS 측량기 위성 수신 대기 중입니다 (NO FIX)."
                }
            }
            PositioningSourceMode.PHONE_INTERNAL -> {
                if (status.fixQuality == FixQuality.NO_FIX || (status.latitude == 0.0 && status.longitude == 0.0)) {
                    return "휴대폰 GPS 위치 신호를 아직 수신하지 못했습니다."
                }
            }
            PositioningSourceMode.SIMULATION -> {
                // Simulation mode active
            }
        }

        if (!status.fixQuality.meetsRequirement(_requiredFixQuality.value)) {
            return "현재 상태(${status.fixQuality.koreanLabel})가 설정된 기준(${_requiredFixQuality.value.koreanLabel})보다 낮아 측량이 제한됩니다."
        }
        return null
    }

    private fun startIndoorSimulationLoop() {
        simJob?.cancel()
        simJob = viewModelScope.launch(Dispatchers.Default) {
            var baseLat = 36.642435
            var baseLon = 127.489031
            var baseEllipH = 82.450
            var tick = 0
            while (isActive && _sourceMode.value == PositioningSourceMode.SIMULATION) {
                val jitterLat = (Random.nextDouble() - 0.5) * 0.00000010
                val jitterLon = (Random.nextDouble() - 0.5) * 0.00000010
                val jitterH = (Random.nextDouble() - 0.5) * 0.006

                val target = _stakeoutTarget.value
                if (target != null && target.latitude != 0.0) {
                    baseLat += (target.latitude - baseLat) * 0.05
                    baseLon += (target.longitude - baseLon) * 0.05
                }

                val lat = baseLat + jitterLat
                val lon = baseLon + jitterLon
                val ellipH = baseEllipH + jitterH
                val geoidN = 25.120
                val orthoH = ellipH - geoidN

                val fakeSats = listOf(
                    SatelliteInfo(1, "GPS", 68, 45, 46, true),
                    SatelliteInfo(3, "GPS", 52, 120, 44, true),
                    SatelliteInfo(8, "GPS", 39, 210, 41, true),
                    SatelliteInfo(14, "GPS", 74, 310, 48, true),
                    SatelliteInfo(17, "GPS", 28, 88, 38, true),
                    SatelliteInfo(65, "GLONASS", 61, 30, 43, true),
                    SatelliteInfo(66, "GLONASS", 48, 155, 40, true),
                    SatelliteInfo(72, "GLONASS", 33, 275, 39, true),
                    SatelliteInfo(201, "BeiDou", 77, 15, 47, true),
                    SatelliteInfo(205, "BeiDou", 64, 140, 45, true),
                    SatelliteInfo(209, "BeiDou", 55, 225, 44, true),
                    SatelliteInfo(214, "BeiDou", 42, 330, 42, true),
                    SatelliteInfo(302, "Galileo", 59, 95, 43, true),
                    SatelliteInfo(307, "Galileo", 46, 260, 41, true)
                )

                val simStatus = GnssStatus(
                    latitude = lat,
                    longitude = lon,
                    ellipsoidalHeight = ellipH,
                    orthometricHeight = orthoH,
                    nmeaGeoidSeparation = geoidN,
                    fixQuality = FixQuality.RTK_FIXED,
                    satellitesTracked = 18,
                    satellitesUsed = fakeSats.size,
                    hdop = 0.72,
                    vdop = 1.15,
                    pdop = 1.36,
                    hrms = 0.008,
                    vrms = 0.014,
                    diffAge = 1.0,
                    baseStationId = "VRS-2101",
                    speedKmh = 0.2,
                    headingDeg = (tick * 5 % 360).toDouble(),
                    utcTime = SimpleDateFormat("HH:mm:ss 'KST'", Locale.KOREA).format(Date()),
                    sourceMode = PositioningSourceMode.SIMULATION,
                    btConnected = false,
                    ntripConnected = _gnssStatus.value.ntripConnected,
                    ntripStatusMessage = _gnssStatus.value.ntripStatusMessage,
                    rtcmBytesPerSec = _gnssStatus.value.rtcmBytesPerSec,
                    connectedDeviceName = "실내 시뮬레이션 (가상 RTK)",
                    receiverBatteryPercent = 94,
                    imuTiltAngleDeg = 2.4,
                    imuTiltAzimuthDeg = 135.0,
                    imuTiltActive = _imuTiltCompensationEnabled.value,
                    satellites = fakeSats
                )

                updateGnssAndCoordinates(simStatus)
                tick++
                delay(1000L)
            }
        }
    }

    private fun updateGnssAndCoordinates(status: GnssStatus) {
        _gnssStatus.value = status

        if (status.fixQuality != lastAnnouncedFix) {
            if (status.fixQuality == FixQuality.RTK_FIXED) {
                soundSensorManager.speak("픽스됨")
            } else if (lastAnnouncedFix == FixQuality.RTK_FIXED && status.fixQuality == FixQuality.RTK_FLOAT) {
                soundSensorManager.speak("플로트 상태입니다")
            }
            lastAnnouncedFix = status.fixQuality
        }

        if (status.latitude == 0.0 && status.longitude == 0.0) return

        val rawPlane = CoordinateConverter.wgs84ToPlane(
            latDeg = status.latitude,
            lonDeg = status.longitude,
            ellipsoidalHeight = status.ellipsoidalHeight,
            system = _coordinateSystem.value,
            useKnGeoid18 = _useKnGeoid18.value,
            nmeaGeoidFallback = status.nmeaGeoidSeparation
        )

        val cal = _calibrationParams.value
        val (calN, calE, calZ) = if (cal.enabled) {
            val theta = Math.toRadians(cal.rotationArcSec / 3600.0)
            val n2 = cal.translateN + cal.scaleFactor * (rawPlane.northing * cos(theta) - rawPlane.easting * sin(theta))
            val e2 = cal.translateE + cal.scaleFactor * (rawPlane.northing * sin(theta) + rawPlane.easting * cos(theta))
            val z2 = rawPlane.orthometricHeight + cal.translateZ
            Triple(n2, e2, z2)
        } else {
            Triple(rawPlane.northing, rawPlane.easting, rawPlane.orthometricHeight)
        }

        val poleH = _antennaHeight.value
        val (tiltDn, tiltDe, effectivePoleH) = if (_imuTiltCompensationEnabled.value) {
            nmeaParser.computeTiltCorrection(status.imuTiltAngleDeg, status.imuTiltAzimuthDeg, poleH)
        } else {
            Triple(0.0, 0.0, poleH)
        }

        val groundElevation = calZ - effectivePoleH
        val finalPlane = rawPlane.copy(
            northing = calN + tiltDn,
            easting = calE + tiltDe,
            orthometricHeight = groundElevation
        )
        _currentPlaneCoord.value = finalPlane

        val target = _stakeoutTarget.value
        if (target != null) {
            val guidance = CoordinateConverter.computeStakeout(
                currentN = finalPlane.northing,
                currentE = finalPlane.easting,
                currentZ = finalPlane.orthometricHeight,
                target = target
            )
            _stakeoutGuidance.value = guidance
            soundSensorManager.updateStakeoutDistance(
                distanceMeters = guidance.horizontalDistance,
                enabled = _stakeoutSoundEnabled.value
            )
        } else {
            _stakeoutGuidance.value = null
            soundSensorManager.stopStakeoutSound()
        }
    }

    // =========================================================================
    // HARDWARE VOLUME KEYS (Volume Up = Normal Survey, Volume Down = Quick Repeat)
    // =========================================================================

    fun onVolumeUpPressed() {
        val blockReason = validateReadyToMeasure()
        if (blockReason != null) {
            postToast(blockReason)
            soundSensorManager.speak("측량 불가 상태입니다")
            return
        }
        _openMeasureDialogEvent.value = true
    }

    fun onVolumeDownPressed() {
        val blockReason = validateReadyToMeasure()
        if (blockReason != null) {
            postToast(blockReason)
            soundSensorManager.speak("측량 불가 상태입니다")
            return
        }
        val preset = _lastMeasurePreset.value
        val nextName = generateNextPointName()
        recordPoint(
            name = nextName,
            code = preset.code,
            epochs = preset.epochs,
            memo = preset.memo,
            autoAppendLine = preset.autoAppendLine
        )
    }

    fun generateNextPointName(): String {
        val list = _points.value
        if (list.isEmpty()) return "PT-1"
        val last = list.last().name
        val regex = Regex("^(.*?)(\\d+)$")
        val match = regex.find(last)
        return if (match != null) {
            val prefix = match.groupValues[1]
            val num = match.groupValues[2].toIntOrNull() ?: list.size
            "$prefix${num + 1}"
        } else {
            "PT-${list.size + 1}"
        }
    }

    fun generateNextSeqNumber(): Int {
        val maxSeq = _points.value.maxOfOrNull { it.seqNumber } ?: 0
        return maxSeq + 1
    }

    // =========================================================================
    // SURVEY POINT RECORDING
    // =========================================================================

    fun recordPoint(
        name: String,
        code: String,
        epochs: Int = 1,
        memo: String = "",
        autoAppendLine: Boolean = false
    ) {
        if (_isAveraging.value) return

        val blockReason = validateReadyToMeasure()
        if (blockReason != null) {
            postToast(blockReason)
            return
        }

        _lastMeasurePreset.value = LastMeasurePreset(
            code = code.ifBlank { "GN" },
            epochs = epochs,
            memo = memo,
            autoAppendLine = autoAppendLine
        )

        viewModelScope.launch {
            _isAveraging.value = true
            _averagingProgress.value = 0

            var sumN = 0.0
            var sumE = 0.0
            var sumZ = 0.0
            var sumLat = 0.0
            var sumLon = 0.0
            var sumEllip = 0.0
            val totalEpochs = epochs.coerceIn(1, 30)

            for (i in 1..totalEpochs) {
                val p = _currentPlaneCoord.value
                val g = _gnssStatus.value
                sumN += p.northing
                sumE += p.easting
                sumZ += p.orthometricHeight
                sumLat += g.latitude
                sumLon += g.longitude
                sumEllip += g.ellipsoidalHeight
                _averagingProgress.value = i
                if (totalEpochs > 1) delay(220L)
            }

            val g = _gnssStatus.value
            val p = _currentPlaneCoord.value
            val seq = generateNextSeqNumber()
            val cleanName = name.ifBlank { "PT-$seq" }
            val cleanCode = code.ifBlank { "GN" }

            val newPoint = SurveyPoint(
                seqNumber = seq,
                name = cleanName,
                code = cleanCode,
                northing = sumN / totalEpochs,
                easting = sumE / totalEpochs,
                elevation = sumZ / totalEpochs,
                latitude = sumLat / totalEpochs,
                longitude = sumLon / totalEpochs,
                ellipsoidalHeight = sumEllip / totalEpochs,
                geoidSeparation = p.geoidSeparation,
                antennaHeight = _antennaHeight.value,
                fixQuality = g.fixQuality,
                hrms = g.hrms,
                vrms = g.vrms,
                epochCount = totalEpochs,
                timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date()),
                memo = memo
            )

            val updated = _points.value + newPoint
            _points.value = updated

            if (autoAppendLine) {
                appendPointToActivePolyline(newPoint.id, cleanCode)
            }

            _isAveraging.value = false
            soundSensorManager.playPointSavedBeep()
            soundSensorManager.speak("${seq}번 ${cleanCode} 저장")
            postToast("#${seq} ${cleanName} [${cleanCode}] Z:${String.format(Locale.US, "%.3f", newPoint.elevation)} 저장 완료")
            saveCurrentStateToPrefsAndAutoFile()
        }
    }

    fun addManualStakeoutPoint(
        name: String,
        code: String,
        northing: Double,
        easting: Double,
        elevation: Double,
        memo: String,
        setAsImmediateTarget: Boolean
    ) {
        val seq = generateNextSeqNumber()
        val (lat, lon) = CoordinateConverter.planeToWgs84(
            northing = northing,
            easting = easting,
            system = _coordinateSystem.value
        )
        val pt = SurveyPoint(
            seqNumber = seq,
            name = name.ifBlank { "ST-$seq" },
            code = code.ifBlank { "측설점" },
            northing = northing,
            easting = easting,
            elevation = elevation,
            latitude = lat,
            longitude = lon,
            ellipsoidalHeight = elevation + 25.0,
            geoidSeparation = 25.0,
            antennaHeight = 0.0,
            fixQuality = FixQuality.RTK_FIXED,
            hrms = 0.0,
            vrms = 0.0,
            epochCount = 1,
            timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date()),
            memo = memo
        )
        _points.value = _points.value + pt
        saveCurrentStateToPrefsAndAutoFile()

        if (setAsImmediateTarget) {
            setStakeoutTarget(pt)
        } else {
            postToast("측설 좌표 #${pt.seqNumber} (${pt.name}) 추가됨")
        }
    }

    private fun appendPointToActivePolyline(pointId: String, code: String) {
        val list = _polylines.value.toMutableList()
        val idx = list.indexOfLast { it.code == code && !it.isClosed }
        if (idx >= 0) {
            val existing = list[idx]
            list[idx] = existing.copy(pointIds = existing.pointIds + pointId)
        } else {
            val presetColor = _quickCodes.value.find { it.code == code }?.colorHex ?: 0xFF38BDF8
            list.add(
                CadPolyline(
                    code = code,
                    pointIds = listOf(pointId),
                    isClosed = false,
                    colorHex = presetColor
                )
            )
        }
        _polylines.value = list
    }

    fun deletePoint(pointId: String) {
        _points.value = _points.value.filterNot { it.id == pointId }
        if (_stakeoutTarget.value?.id == pointId) {
            setStakeoutTarget(null)
        }
        saveCurrentStateToPrefsAndAutoFile()
        postToast("측점이 삭제되었습니다.")
    }

    fun setStakeoutTarget(point: SurveyPoint?) {
        _stakeoutTarget.value = point
        if (point == null) {
            _stakeoutGuidance.value = null
            soundSensorManager.stopStakeoutSound()
            postToast("좌표측설이 종료되었습니다.")
        } else {
            val p = _currentPlaneCoord.value
            val guidance = CoordinateConverter.computeStakeout(
                currentN = p.northing,
                currentE = p.easting,
                currentZ = p.orthometricHeight,
                target = point
            )
            _stakeoutGuidance.value = guidance
            soundSensorManager.updateStakeoutDistance(
                distanceMeters = guidance.horizontalDistance,
                enabled = _stakeoutSoundEnabled.value
            )
            soundSensorManager.speak("${point.name} 좌표측설을 시작합니다")
            postToast("[좌표측설 시작] #${point.seqNumber} ${point.name} (${point.code})")
        }
    }

    fun setStakeoutIncludeZ(includeZ: Boolean) {
        _stakeoutIncludeZ.value = includeZ
    }

    fun setStakeoutSoundEnabled(enabled: Boolean) {
        _stakeoutSoundEnabled.value = enabled
        val g = _stakeoutGuidance.value
        if (g != null) {
            soundSensorManager.updateStakeoutDistance(g.horizontalDistance, enabled)
        } else {
            soundSensorManager.stopStakeoutSound()
        }
    }

    // =========================================================================
    // CAD STAKEOUT (도면측설 - DXF/DWG & SHP OSNAP)
    // =========================================================================

    fun setCadSnapEnabled(enabled: Boolean) {
        _cadSnapEnabled.value = enabled
        postToast(if (enabled) "도면측설 객체스냅(끝점/교차점/중간점) 켜짐" else "도면측설 객체스냅 꺼짐")
    }

    /**
     * Called when the surveyor taps on the CAD/Map canvas while external DXF/SHP entities are loaded.
     * Finds the nearest Endpoint, Intersection, Midpoint, or Node within `toleranceMeters`,
     * and sets it as the active Stakeout target!
     */
    fun snapAndStakeoutFromCanvas(
        tapNorthing: Double,
        tapEasting: Double,
        toleranceMeters: Double
    ): Boolean {
        if (!_cadSnapEnabled.value || _cadEntities.value.isEmpty()) return false
        val snap = CoordinateConverter.findNearestCadSnap(
            tapN = tapNorthing,
            tapE = tapEasting,
            entities = _cadEntities.value,
            maxSnapRadiusMeters = toleranceMeters
        ) ?: return false

        _lastSnappedTarget.value = snap
        val (lat, lon) = CoordinateConverter.planeToWgs84(
            northing = snap.northing,
            easting = snap.easting,
            system = _coordinateSystem.value
        )
        val stakePt = SurveyPoint(
            seqNumber = 0,
            name = "도면-${snap.snapType.koreanLabel}",
            code = snap.layerName,
            northing = snap.northing,
            easting = snap.easting,
            elevation = snap.elevationZ,
            latitude = lat,
            longitude = lon,
            ellipsoidalHeight = snap.elevationZ + 25.0,
            geoidSeparation = 25.0,
            antennaHeight = 0.0,
            fixQuality = FixQuality.RTK_FIXED,
            hrms = 0.0,
            vrms = 0.0,
            epochCount = 1,
            timestamp = "도면측설 스냅",
            memo = snap.label
        )
        setStakeoutTarget(stakePt)
        postToast("[도면측설 스냅] ${snap.label} (N:${String.format(Locale.US, "%.3f", snap.northing)}, E:${String.format(Locale.US, "%.3f", snap.easting)})")
        return true
    }

    fun loadExternalCadOrShpUri(uri: Uri, fileNameHint: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val rawBytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("파일을 읽을 수 없습니다.")

                val lowerName = (fileNameHint.ifBlank { uri.lastPathSegment ?: "" }).lowercase(Locale.US)

                // Check if SHP file (either by extension or by magic number 0x0000270A = 9994)
                val isShpMagic = rawBytes.size >= 100 &&
                    rawBytes[0] == 0.toByte() &&
                    rawBytes[1] == 0.toByte() &&
                    rawBytes[2] == 0x27.toByte() &&
                    rawBytes[3] == 0x0A.toByte()

                if (lowerName.endsWith(".shp") || isShpMagic) {
                    val shpRes = ShpFileParser.parseShpBytes(
                        bytes = rawBytes,
                        targetSystem = _coordinateSystem.value,
                        layerLabel = "브이월드_SHP"
                    )
                    _cadEntities.value = _cadEntities.value + shpRes.entities
                    saveCurrentStateToPrefsAndAutoFile()
                    postToast(shpRes.summaryMessage)
                } else if (lowerName.endsWith(".gsproj")) {
                    val jsonStr = String(rawBytes, Charsets.UTF_8)
                    val loadedProj = FileIoManager.parseProjectJson(jsonStr)
                    if (loadedProj != null) {
                        applyLoadedProject(loadedProj)
                        postToast("프로젝트 '${loadedProj.projectName}' 불러오기 완료")
                    } else {
                        postToast("프로젝트 파일 형식이 올바르지 않습니다.")
                    }
                } else if (lowerName.endsWith(".csv") || lowerName.endsWith(".txt") || lowerName.endsWith(".nez")) {
                    val text = String(rawBytes, Charsets.UTF_8)
                    val imported = FileIoManager.importCsvText(text, generateNextSeqNumber())
                    if (imported.isNotEmpty()) {
                        _points.value = _points.value + imported
                        saveCurrentStateToPrefsAndAutoFile()
                        postToast("좌표점 ${imported.size}개를 불러왔습니다.")
                    } else {
                        postToast("불러올 수 있는 좌표 데이터가 없습니다.")
                    }
                } else {
                    // Default to DXF / DWG external CAD drawing parser
                    val cadRes = FileIoManager.parseExternalCadBytes(
                        bytes = rawBytes,
                        fileName = fileNameHint.ifBlank { "외부도면.dxf" },
                        startSeq = generateNextSeqNumber()
                    )
                    if (cadRes.entities.isNotEmpty()) {
                        _cadEntities.value = _cadEntities.value + cadRes.entities
                        saveCurrentStateToPrefsAndAutoFile()
                    }
                    postToast(cadRes.message)
                }
            } catch (e: Exception) {
                postToast("파일 불러오기 실패: ${e.message}")
            }
        }
    }

    fun clearExternalCadEntities() {
        _cadEntities.value = emptyList()
        _lastSnappedTarget.value = null
        saveCurrentStateToPrefsAndAutoFile()
        postToast("불러온 외부 도면(DXF/SHP) 레이어를 초기화했습니다.")
    }

    // =========================================================================
    // BACKGROUND MAP & COORDINATE SETTINGS
    // =========================================================================

    fun setBackgroundMapType(type: BackgroundMapType) {
        _backgroundMapType.value = type
        postToast("배경맵: ${type.koreanLabel}")
    }

    fun setBackgroundMapAlpha(alpha: Float) {
        _backgroundMapAlpha.value = alpha.coerceIn(0.2f, 1.0f)
    }

    fun setCoordinateSystem(sys: KoreanCoordinateSystem) {
        _coordinateSystem.value = sys
        updateGnssAndCoordinates(_gnssStatus.value)
        saveCurrentStateToPrefsAndAutoFile()
        postToast("좌표계 변경: ${sys.displayName}")
    }

    fun setUseKnGeoid18(enabled: Boolean) {
        _useKnGeoid18.value = enabled
        updateGnssAndCoordinates(_gnssStatus.value)
        saveCurrentStateToPrefsAndAutoFile()
    }

    fun setAntennaHeight(heightMeters: Double) {
        _antennaHeight.value = heightMeters.coerceIn(0.0, 10.0)
        updateGnssAndCoordinates(_gnssStatus.value)
        saveCurrentStateToPrefsAndAutoFile()
    }

    fun setImuTiltCompensation(enabled: Boolean) {
        _imuTiltCompensationEnabled.value = enabled
        updateGnssAndCoordinates(_gnssStatus.value.copy(imuTiltActive = enabled))
    }

    fun setProjectName(name: String) {
        if (name.isNotBlank()) {
            _projectName.value = name.trim()
            saveCurrentStateToPrefsAndAutoFile()
        }
    }

    // =========================================================================
    // PROJECT MANAGEMENT (.gsproj SAVE / OPEN / NEW)
    // =========================================================================

    fun refreshSavedProjectsList() {
        _savedProjectFiles.value = FileIoManager.listSavedProjects(getApplication())
    }

    fun saveCurrentProjectExplicitly(customName: String? = null) {
        if (!customName.isNullOrBlank()) {
            _projectName.value = customName.trim()
        }
        val proj = buildCurrentProjectData()
        val file = FileIoManager.saveProjectFile(getApplication(), proj)
        saveCurrentStateToPrefsOnly()
        refreshSavedProjectsList()
        postToast("프로젝트 저장 완료: ${file.name} (측점 ${_points.value.size}개)")
    }

    fun openSavedProjectFile(file: File) {
        val loaded = FileIoManager.loadProjectFromFile(file)
        if (loaded != null) {
            applyLoadedProject(loaded)
            postToast("프로젝트 '${loaded.projectName}' 열기 완료 (측점 ${loaded.points.size}개)")
        } else {
            postToast("프로젝트 파일을 열 수 없습니다.")
        }
    }

    fun createNewProject(newProjectName: String) {
        // Save existing project first before starting new one
        if (_points.value.isNotEmpty() || _cadEntities.value.isNotEmpty()) {
            FileIoManager.saveProjectFile(getApplication(), buildCurrentProjectData())
        }
        _projectName.value = newProjectName.trim().ifBlank {
            "간단측량_" + SimpleDateFormat("MMdd_HHmm", Locale.KOREA).format(Date())
        }
        _points.value = emptyList()
        _polylines.value = emptyList()
        _cadEntities.value = emptyList()
        _stakeoutTarget.value = null
        _stakeoutGuidance.value = null
        saveCurrentStateToPrefsAndAutoFile()
        refreshSavedProjectsList()
        postToast("새 프로젝트 '${_projectName.value}' 생성 완료")
    }

    private fun buildCurrentProjectData(): SurveyProjectData {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date())
        return SurveyProjectData(
            projectName = _projectName.value,
            createdAt = now,
            updatedAt = now,
            coordinateSystem = _coordinateSystem.value,
            useKnGeoid18 = _useKnGeoid18.value,
            antennaHeight = _antennaHeight.value,
            points = _points.value,
            figures = _polylines.value,
            cadEntities = _cadEntities.value
        )
    }

    private fun applyLoadedProject(proj: SurveyProjectData) {
        _projectName.value = proj.projectName
        _coordinateSystem.value = proj.coordinateSystem
        _useKnGeoid18.value = proj.useKnGeoid18
        _antennaHeight.value = proj.antennaHeight
        _points.value = proj.points
        _polylines.value = proj.figures
        _cadEntities.value = proj.cadEntities
        _stakeoutTarget.value = null
        _stakeoutGuidance.value = null
        saveCurrentStateToPrefsOnly()
        refreshSavedProjectsList()
        updateGnssAndCoordinates(_gnssStatus.value)
    }

    // =========================================================================
    // SELECTIVE EXPORT (측번, 코드명, 메모 등 선택 출력)
    // =========================================================================

    fun updateExportConfig(updated: ExportColumnConfig) {
        _exportConfig.value = updated
    }

    fun exportSelectedColumnsFile() {
        val list = _points.value
        if (list.isEmpty()) {
            postToast("내보낼 측점 데이터가 없습니다.")
            return
        }
        val file = FileIoManager.exportCustomTextOrCsv(
            context = getApplication(),
            projectName = _projectName.value,
            points = list,
            config = _exportConfig.value
        )
        postToast("선택 항목 내보내기 완료: ${file.absolutePath}")
    }

    fun exportDxfFile(encodingMode: DxfEncodingMode = DxfEncodingMode.CP949_KOREAN) {
        val list = _points.value
        if (list.isEmpty()) {
            postToast("내보낼 측점 데이터가 없습니다.")
            return
        }
        val file = FileIoManager.exportDxf(
            context = getApplication(),
            projectName = _projectName.value,
            points = list,
            polylines = _polylines.value,
            encodingMode = encodingMode,
            exportConfig = _exportConfig.value
        )
        postToast("한글 CAD DXF 저장 완료: ${file.absolutePath}")
    }

    fun exportKmlFile() {
        val list = _points.value
        if (list.isEmpty()) {
            postToast("내보낼 측점 데이터가 없습니다.")
            return
        }
        val file = FileIoManager.exportKml(getApplication(), _projectName.value, list)
        postToast("KML 저장 완료: ${file.absolutePath}")
    }

    // =========================================================================
    // SITE CALIBRATION & NTRIP VRS
    // =========================================================================

    fun addCalibrationPair(
        pointName: String,
        knownN: Double,
        knownE: Double,
        knownZ: Double
    ) {
        val cur = _currentPlaneCoord.value
        val pair = CalibrationPair(
            pointName = pointName,
            knownN = knownN,
            knownE = knownE,
            knownZ = knownZ,
            measuredN = cur.northing,
            measuredE = cur.easting,
            measuredZ = cur.orthometricHeight
        )
        val updated = _calibrationPairs.value + pair
        _calibrationPairs.value = updated
        _calibrationParams.value = CalibrationSolver.solveHelmert2DAndVertical(updated)
        postToast("로컬 캘리브레이션 제어점 추가됨 (RMSE: ${String.format(Locale.US, "%.3f", _calibrationParams.value.rmseHorizontal)}m)")
    }

    fun clearCalibration() {
        _calibrationPairs.value = emptyList()
        _calibrationParams.value = SiteCalibrationParams(enabled = false)
        postToast("로컬 캘리브레이션이 초기화되었습니다.")
    }

    fun updateNtripConfig(config: NtripConfig) {
        _ntripConfig.value = config
        saveCurrentStateToPrefsOnly()
    }

    fun fetchMountpoints() {
        val cfg = _ntripConfig.value
        viewModelScope.launch {
            postToast("마운트포인트 목록 조회 중...")
            val res = ntripClient.fetchMountpoints(cfg.host, cfg.port)
            res.onSuccess { list ->
                _availableMountpoints.value = list
                postToast("마운트포인트 ${list.size}개 수신 완료")
            }.onFailure { err ->
                postToast("마운트포인트 조회 실패: ${err.message}")
            }
        }
    }

    fun toggleNtripConnection() {
        if (_gnssStatus.value.ntripConnected) {
            ntripClient.stopStreaming()
            _gnssStatus.value = _gnssStatus.value.copy(
                ntripConnected = false,
                ntripStatusMessage = "연결 해제됨",
                rtcmBytesPerSec = 0
            )
        } else {
            val g = _gnssStatus.value
            val lat = if (g.latitude != 0.0) g.latitude else 36.642435
            val lon = if (g.longitude != 0.0) g.longitude else 127.489031
            val h = if (g.ellipsoidalHeight != 0.0) g.ellipsoidalHeight else 80.0
            ntripClient.startStreaming(_ntripConfig.value, lat, lon, h)
        }
    }

    fun getPairedBluetoothDevices(): List<BtDeviceItem> = btManager.getPairedDevices()

    fun startBluetoothScan() = btManager.startDiscoveryScan()

    fun connectBluetoothReceiver(item: BtDeviceItem) {
        if (_sourceMode.value != PositioningSourceMode.BLUETOOTH_GNSS) {
            _sourceMode.value = PositioningSourceMode.BLUETOOTH_GNSS
            simJob?.cancel()
            phoneGpsManager.stop()
        }
        btManager.connect(item.address, item.name)
    }

    fun disconnectBluetoothReceiver() {
        btManager.disconnect()
        nmeaParser.reset()
        _gnssStatus.value = _gnssStatus.value.copy(
            btConnected = false,
            fixQuality = FixQuality.NO_FIX,
            satellitesTracked = 0,
            satellitesUsed = 0,
            connectedDeviceName = "미연결"
        )
    }

    private fun saveCurrentStateToPrefsAndAutoFile() {
        saveCurrentStateToPrefsOnly()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                FileIoManager.saveProjectFile(getApplication(), buildCurrentProjectData())
                refreshSavedProjectsList()
            } catch (_: Exception) {}
        }
    }

    private fun saveCurrentStateToPrefsOnly() {
        prefs.edit().apply {
            putString("project_name", _projectName.value)
            putString("coord_sys", _coordinateSystem.value.name)
            putBoolean("use_geoid18", _useKnGeoid18.value)
            putString("antenna_h", _antennaHeight.value.toString())
            putString("ntrip_host", _ntripConfig.value.host)
            putInt("ntrip_port", _ntripConfig.value.port)
            putString("ntrip_user", _ntripConfig.value.username)
            putString("ntrip_pass", _ntripConfig.value.password)
            putString("ntrip_mount", _ntripConfig.value.mountpoint)
            apply()
        }
    }

    private fun loadSavedSettingsAndLastProject() {
        _projectName.value = prefs.getString("project_name", "간단측량_현장01") ?: "간단측량_현장01"
        val sysName = prefs.getString("coord_sys", KoreanCoordinateSystem.CENTRAL_GRS80.name)
        _coordinateSystem.value = try {
            KoreanCoordinateSystem.valueOf(sysName ?: KoreanCoordinateSystem.CENTRAL_GRS80.name)
        } catch (_: Exception) {
            KoreanCoordinateSystem.CENTRAL_GRS80
        }
        _useKnGeoid18.value = prefs.getBoolean("use_geoid18", true)
        _antennaHeight.value = prefs.getString("antenna_h", "1.800")?.toDoubleOrNull() ?: 1.800

        _ntripConfig.value = NtripConfig(
            host = prefs.getString("ntrip_host", "RTS2.ngii.go.kr") ?: "RTS2.ngii.go.kr",
            port = prefs.getInt("ntrip_port", 2101),
            mountpoint = prefs.getString("ntrip_mount", "VRS-RTCM32") ?: "VRS-RTCM32",
            username = prefs.getString("ntrip_user", "qwsaweds") ?: "qwsaweds",
            password = prefs.getString("ntrip_pass", "ngii") ?: "ngii"
        )

        // Load most recent project file if available
        val existingFiles = FileIoManager.listSavedProjects(getApplication())
        val matchingFile = existingFiles.find {
            it.nameWithoutExtension == _projectName.value
        } ?: existingFiles.firstOrNull()

        if (matchingFile != null) {
            val loaded = FileIoManager.loadProjectFromFile(matchingFile)
            if (loaded != null) {
                _points.value = loaded.points
                _polylines.value = loaded.figures
                _cadEntities.value = loaded.cadEntities
            }
        }
    }

    private fun defaultQuickCodes(): List<QuickCodePreset> = listOf(
        QuickCodePreset("지반", "지반고(현황)", 0xFF38BDF8, false),
        QuickCodePreset("경계", "필지/부지경계", 0xFFF43F5E, true),
        QuickCodePreset("도로", "도로가장자리", 0xFFFACC15, true),
        QuickCodePreset("연석", "보도연석", 0xFFA855F7, true),
        QuickCodePreset("건물", "건물모서리", 0xFF22C55E, true),
        QuickCodePreset("맨홀", "상하수도맨홀", 0xFFFB923C, false),
        QuickCodePreset("수목", "조경수목", 0xFF10B981, false),
        QuickCodePreset("옹벽", "옹벽/법면상단", 0xFFEC4899, true),
        QuickCodePreset("기준점", "도근/수준기준점", 0xFFEF4444, false)
    )

    override fun onCleared() {
        super.onCleared()
        btManager.disconnect()
        phoneGpsManager.stop()
        ntripClient.stopStreaming()
        soundSensorManager.release()
    }
}
