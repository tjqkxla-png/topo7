package kr.koseco.gandansurvey.v90.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.koseco.gandansurvey.v90.coord.CoordinateConverter
import kr.koseco.gandansurvey.v90.model.*
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun GandanSurveyMainApp(viewModel: GandanSurveyViewModel) {
    val gnssStatus by viewModel.gnssStatus.collectAsState()
    val planeCoord by viewModel.currentPlaneCoord.collectAsState()
    val coordSystem by viewModel.coordinateSystem.collectAsState()
    val points by viewModel.points.collectAsState()
    val polylines by viewModel.polylines.collectAsState()
    val cadEntities by viewModel.cadEntities.collectAsState()
    val cadSnapEnabled by viewModel.cadSnapEnabled.collectAsState()
    val lastSnappedTarget by viewModel.lastSnappedTarget.collectAsState()
    val backgroundMapType by viewModel.backgroundMapType.collectAsState()
    val backgroundMapAlpha by viewModel.backgroundMapAlpha.collectAsState()
    val tileInvalidateTick by viewModel.tileInvalidateTick.collectAsState()
    val stakeoutTarget by viewModel.stakeoutTarget.collectAsState()
    val stakeoutGuidance by viewModel.stakeoutGuidance.collectAsState()
    val stakeoutIncludeZ by viewModel.stakeoutIncludeZ.collectAsState()
    val stakeoutSoundEnabled by viewModel.stakeoutSoundEnabled.collectAsState()
    val antennaHeight by viewModel.antennaHeight.collectAsState()
    val quickCodes by viewModel.quickCodes.collectAsState()
    val isAveraging by viewModel.isAveraging.collectAsState()
    val averagingProgress by viewModel.averagingProgress.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    val openMeasureDialogEvent by viewModel.openMeasureDialogEvent.collectAsState()
    val deviceAzimuthDeg by viewModel.soundSensorManager.deviceAzimuthDeg.collectAsState()
    val lastPreset by viewModel.lastMeasurePreset.collectAsState()
    val sourceMode by viewModel.sourceMode.collectAsState()
    val requiredFixQuality by viewModel.requiredFixQuality.collectAsState()
    val projectName by viewModel.projectName.collectAsState()
    val savedProjectFiles by viewModel.savedProjectFiles.collectAsState()
    val exportConfig by viewModel.exportConfig.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var showMeasureDialog by remember { mutableStateOf(false) }
    var showAddStakeoutPointDialog by remember { mutableStateOf(false) }
    var showProjectDialog by remember { mutableStateOf(false) }
    var showExportConfigDialog by remember { mutableStateOf(false) }
    var showNmeaMonitorDialog by remember { mutableStateOf(false) }
    var lastBackPressMs by remember { mutableLongStateOf(0L) }

    // File Picker for external DXF / DWG / SHP / GSPROJ / CSV files
    val externalFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.loadExternalCadOrShpUri(uri, uri.lastPathSegment ?: "")
        }
    }

    LaunchedEffect(openMeasureDialogEvent) {
        if (openMeasureDialogEvent) {
            showMeasureDialog = true
            viewModel.consumeOpenMeasureDialogEvent()
        }
    }

    LaunchedEffect(stakeoutTarget) {
        if (stakeoutTarget != null && selectedTab != 1 && selectedTab != 0) {
            selectedTab = 1
        }
    }

    // Back-button navigation guard
    BackHandler(enabled = true) {
        when {
            showMeasureDialog -> showMeasureDialog = false
            showAddStakeoutPointDialog -> showAddStakeoutPointDialog = false
            showProjectDialog -> showProjectDialog = false
            showExportConfigDialog -> showExportConfigDialog = false
            showNmeaMonitorDialog -> showNmeaMonitorDialog = false
            selectedTab != 0 -> selectedTab = 0
            stakeoutTarget != null -> viewModel.setStakeoutTarget(null)
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPressMs < 2000L) {
                    // Let system handle exit if needed
                } else {
                    lastBackPressMs = now
                    viewModel.postToast("뒤로가기를 한 번 더 누르면 종료됩니다.")
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopGnssStatusBar(
                projectName = projectName,
                status = gnssStatus,
                plane = planeCoord,
                antennaHeight = antennaHeight,
                sourceMode = sourceMode,
                requiredFixQuality = requiredFixQuality,
                onOpenProjectDialog = {
                    viewModel.refreshSavedProjectsList()
                    showProjectDialog = true
                },
                onQuickSwitchToConnTab = { selectedTab = 3 }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF0F172A),
                tonalElevation = 8.dp
            ) {
                val items = listOf(
                    Triple(0, "현황측량", Icons.Default.Map),
                    Triple(1, "좌표/도면측설", Icons.Default.Explore),
                    Triple(2, "측점/내보내기", Icons.Default.FormatListNumbered),
                    Triple(3, "통신(V90/VRS)", Icons.Default.BluetoothConnected),
                    Triple(4, "좌표계/보정", Icons.Default.Tune)
                )
                items.forEach { (idx, title, icon) ->
                    NavigationBarItem(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        icon = { Icon(icon, contentDescription = title) },
                        label = { Text(title, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF38BDF8),
                            selectedTextColor = Color(0xFF38BDF8),
                            indicatorColor = Color(0xFF1E293B),
                            unselectedIconColor = Color(0xFF94A3B8),
                            unselectedTextColor = Color(0xFF94A3B8)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF0B0F19))
        ) {
            when (selectedTab) {
                0 -> MapSurveyScreen(
                    viewModel = viewModel,
                    gnssStatus = gnssStatus,
                    planeCoord = planeCoord,
                    coordSystem = coordSystem,
                    points = points,
                    polylines = polylines,
                    cadEntities = cadEntities,
                    cadSnapEnabled = cadSnapEnabled,
                    lastSnappedTarget = lastSnappedTarget,
                    backgroundMapType = backgroundMapType,
                    backgroundMapAlpha = backgroundMapAlpha,
                    tileInvalidateTick = tileInvalidateTick,
                    stakeoutTarget = stakeoutTarget,
                    stakeoutGuidance = stakeoutGuidance,
                    quickCodes = quickCodes,
                    isAveraging = isAveraging,
                    averagingProgress = averagingProgress,
                    lastPreset = lastPreset,
                    deviceAzimuthDeg = deviceAzimuthDeg,
                    onOpenMeasureDialog = {
                        val blockReason = viewModel.validateReadyToMeasure()
                        if (blockReason != null) {
                            viewModel.postToast(blockReason)
                        } else {
                            showMeasureDialog = true
                        }
                    },
                    onQuickRepeatMeasure = { viewModel.onVolumeDownPressed() },
                    onLoadExternalDrawing = {
                        externalFilePicker.launch(arrayOf("*/*"))
                    },
                    onOpenProjectDialog = {
                        viewModel.refreshSavedProjectsList()
                        showProjectDialog = true
                    },
                    onGoToStakeoutTab = { selectedTab = 1 }
                )
                1 -> StakeoutCompassScreen(
                    gnssStatus = gnssStatus,
                    planeCoord = planeCoord,
                    points = points,
                    cadEntitiesCount = cadEntities.size,
                    stakeoutTarget = stakeoutTarget,
                    guidance = stakeoutGuidance,
                    includeZ = stakeoutIncludeZ,
                    soundEnabled = stakeoutSoundEnabled,
                    deviceAzimuthDeg = deviceAzimuthDeg,
                    onSelectTarget = { viewModel.setStakeoutTarget(it) },
                    onToggleIncludeZ = { viewModel.setStakeoutIncludeZ(it) },
                    onToggleSound = { viewModel.setStakeoutSoundEnabled(it) },
                    onOpenAddTargetDialog = { showAddStakeoutPointDialog = true },
                    onLoadCadOrShpForStakeout = { externalFilePicker.launch(arrayOf("*/*")) },
                    onJumpToMapForCadSnap = {
                        viewModel.setCadSnapEnabled(true)
                        selectedTab = 0
                        viewModel.postToast("도면 위의 선 끝점/교차점/도면점을 터치하면 즉시 측설이 시작됩니다.")
                    },
                    onQuickMeasureStakedPoint = {
                        val blockReason = viewModel.validateReadyToMeasure()
                        if (blockReason != null) {
                            viewModel.postToast(blockReason)
                        } else {
                            val target = stakeoutTarget
                            if (target != null) {
                                viewModel.recordPoint(
                                    name = "${target.name}_검측",
                                    code = "검측",
                                    epochs = 3,
                                    memo = "원점:${target.name}"
                                )
                            }
                        }
                    }
                )
                2 -> PointManagerScreen(
                    projectName = projectName,
                    points = points,
                    exportConfig = exportConfig,
                    onOpenProjectDialog = {
                        viewModel.refreshSavedProjectsList()
                        showProjectDialog = true
                    },
                    onOpenExportConfigDialog = { showExportConfigDialog = true },
                    onExportSelectedColumns = { viewModel.exportSelectedColumnsFile() },
                    onExportDxf = { mode -> viewModel.exportDxfFile(mode) },
                    onExportKml = { viewModel.exportKmlFile() },
                    onLoadExternalFile = { externalFilePicker.launch(arrayOf("*/*")) },
                    onAddManualPoint = { showAddStakeoutPointDialog = true },
                    onSelectStakeout = { pt ->
                        viewModel.setStakeoutTarget(pt)
                        selectedTab = 1
                    },
                    onDeletePoint = { viewModel.deletePoint(it) }
                )
                3 -> ConnectionAndSkyplotScreen(
                    viewModel = viewModel,
                    gnssStatus = gnssStatus,
                    sourceMode = sourceMode,
                    requiredFixQuality = requiredFixQuality,
                    onOpenNmeaMonitor = { showNmeaMonitorDialog = true }
                )
                4 -> CoordinateAndCalibrationScreen(
                    viewModel = viewModel,
                    coordSystem = coordSystem,
                    planeCoord = planeCoord,
                    antennaHeight = antennaHeight
                )
            }

            // 1.5-second Auto-Dismissing Bottom Notification Banner
            toastMessage?.let { msg ->
                Surface(
                    color = Color(0xEE1E293B),
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 8.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(12.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.NotificationsActive,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = msg,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }

    if (showMeasureDialog) {
        MeasurePointDialog(
            suggestedSeq = viewModel.generateNextSeqNumber(),
            suggestedName = viewModel.generateNextPointName(),
            quickCodes = quickCodes,
            defaultCode = lastPreset.code,
            defaultEpochs = lastPreset.epochs,
            planeCoord = planeCoord,
            gnssStatus = gnssStatus,
            onDismiss = { showMeasureDialog = false },
            onConfirm = { name, code, epochs, memo, autoLine ->
                showMeasureDialog = false
                viewModel.recordPoint(name, code, epochs, memo, autoLine)
            }
        )
    }

    if (showAddStakeoutPointDialog) {
        AddStakeoutPointDialog(
            suggestedSeq = viewModel.generateNextSeqNumber(),
            currentPlane = planeCoord,
            onDismiss = { showAddStakeoutPointDialog = false },
            onConfirm = { name, code, n, e, z, memo, startNow ->
                showAddStakeoutPointDialog = false
                viewModel.addManualStakeoutPoint(name, code, n, e, z, memo, startNow)
            }
        )
    }

    if (showProjectDialog) {
        ProjectManagementDialog(
            currentProjectName = projectName,
            pointCount = points.size,
            cadEntityCount = cadEntities.size,
            savedFiles = savedProjectFiles,
            onDismiss = { showProjectDialog = false },
            onSaveProject = { customName ->
                viewModel.saveCurrentProjectExplicitly(customName)
                showProjectDialog = false
            },
            onCreateNewProject = { newName ->
                viewModel.createNewProject(newName)
                showProjectDialog = false
            },
            onOpenProjectFile = { file ->
                viewModel.openSavedProjectFile(file)
                showProjectDialog = false
            },
            onImportProjectFromStorage = {
                showProjectDialog = false
                externalFilePicker.launch(arrayOf("*/*"))
            }
        )
    }

    if (showExportConfigDialog) {
        SelectiveExportConfigDialog(
            config = exportConfig,
            onUpdateConfig = { viewModel.updateExportConfig(it) },
            onExportCsvOrNezNow = {
                showExportConfigDialog = false
                viewModel.exportSelectedColumnsFile()
            },
            onExportDxfNow = { mode ->
                showExportConfigDialog = false
                viewModel.exportDxfFile(mode)
            },
            onDismiss = { showExportConfigDialog = false }
        )
    }

    if (showNmeaMonitorDialog) {
        NmeaRawMonitorDialog(
            viewModel = viewModel,
            gnssStatus = gnssStatus,
            onDismiss = { showNmeaMonitorDialog = false }
        )
    }
}

// ============================================================================
// TOP GNSS STATUS BAR (간단측량 헤더 + 프로젝트 칩 + RTK/위성 상태)
// ============================================================================

@Composable
fun TopGnssStatusBar(
    projectName: String,
    status: GnssStatus,
    plane: PlaneCoordinate,
    antennaHeight: Double,
    sourceMode: PositioningSourceMode,
    requiredFixQuality: FixQuality,
    onOpenProjectDialog: () -> Unit,
    onQuickSwitchToConnTab: () -> Unit
) {
    val fixColor = Color(status.fixQuality.colorHex)
    Surface(
        color = Color(0xFF0F172A),
        tonalElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "간단측량",
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1E293B))
                            .border(1.dp, Color(0xFF334155), RoundedCornerShape(6.dp))
                            .clickable { onOpenProjectDialog() }
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = Color(0xFFFACC15),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = projectName,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(fixColor.copy(alpha = 0.2f))
                            .border(1.dp, fixColor, RoundedCornerShape(6.dp))
                            .clickable { onQuickSwitchToConnTab() }
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = status.fixQuality.koreanLabel,
                            color = fixColor,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 11.sp
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val modeColor = when (sourceMode) {
                        PositioningSourceMode.BLUETOOTH_GNSS ->
                            if (status.btConnected) Color(0xFF22C55E) else Color(0xFFEF4444)
                        PositioningSourceMode.PHONE_INTERNAL -> Color(0xFF38BDF8)
                        PositioningSourceMode.SIMULATION -> Color(0xFFF59E0B)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(modeColor.copy(alpha = 0.15f))
                            .border(1.dp, modeColor, RoundedCornerShape(6.dp))
                            .clickable { onQuickSwitchToConnTab() }
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = when (sourceMode) {
                                PositioningSourceMode.BLUETOOTH_GNSS ->
                                    if (status.btConnected) "V90 연결됨" else "V90 미연결"
                                PositioningSourceMode.PHONE_INTERNAL -> "휴대폰GPS"
                                PositioningSourceMode.SIMULATION -> "시뮬레이션"
                            },
                            color = modeColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(5.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatusMetricChip("위성", "${status.satellitesUsed}/${status.satellitesTracked}")
                StatusMetricChip(
                    "HRMS",
                    if (status.fixQuality == FixQuality.NO_FIX) "---"
                    else String.format(Locale.US, "%.3fm", status.hrms)
                )
                StatusMetricChip("허용기준", "${requiredFixQuality.koreanLabel}↑")
                StatusMetricChip(
                    "VRS",
                    if (status.ntripConnected) "${status.rtcmBytesPerSec}B/s" else "OFF"
                )
                StatusMetricChip("폴대고", String.format(Locale.US, "%.2fm", antennaHeight))
            }
        }
    }
}

@Composable
private fun StatusMetricChip(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = "$label:", color = Color(0xFF94A3B8), fontSize = 11.sp)
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = value,
            color = Color(0xFFF8FAFC),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ============================================================================
// TAB 0: MAP SURVEY SCREEN (배경 항공사진 + 외부 CAD/SHP 도면측설 스냅 + 측점 라벨)
// ============================================================================

@Composable
fun MapSurveyScreen(
    viewModel: GandanSurveyViewModel,
    gnssStatus: GnssStatus,
    planeCoord: PlaneCoordinate,
    coordSystem: KoreanCoordinateSystem,
    points: List<SurveyPoint>,
    polylines: List<CadPolyline>,
    cadEntities: List<CadLayerEntity>,
    cadSnapEnabled: Boolean,
    lastSnappedTarget: CadSnapTarget?,
    backgroundMapType: BackgroundMapType,
    backgroundMapAlpha: Float,
    tileInvalidateTick: Long,
    stakeoutTarget: SurveyPoint?,
    stakeoutGuidance: StakeoutGuidance?,
    quickCodes: List<QuickCodePreset>,
    isAveraging: Boolean,
    averagingProgress: Int,
    lastPreset: LastMeasurePreset,
    deviceAzimuthDeg: Float,
    onOpenMeasureDialog: () -> Unit,
    onQuickRepeatMeasure: () -> Unit,
    onLoadExternalDrawing: () -> Unit,
    onOpenProjectDialog: () -> Unit,
    onGoToStakeoutTab: () -> Unit
) {
    var scaleMetersPerPixel by remember { mutableFloatStateOf(0.15f) }
    var panOffsetPx by remember { mutableStateOf(Offset.Zero) }
    var showPointLabels by remember { mutableStateOf(true) }

    val blockReason = viewModel.validateReadyToMeasure()
    val canSurvey = (blockReason == null)

    Column(modifier = Modifier.fillMaxSize()) {
        // 1. Live Coordinates Readout Banner
        Surface(
            color = Color(0xFF131B2E),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    CoordReadoutBox("N (X 북방향)", planeCoord.northing, Color(0xFF38BDF8))
                    CoordReadoutBox("E (Y 동방향)", planeCoord.easting, Color(0xFF22C55E))
                    CoordReadoutBox("Z (지반고)", planeCoord.orthometricHeight, Color(0xFFFACC15))
                }
            }
        }

        // 2. Background Aerial/Road Map Selector Strip + CAD/SHP Tools
        Surface(
            color = Color(0xFF0F172A),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("배경맵:", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    BackgroundMapType.entries.forEach { mapType ->
                        val selected = (backgroundMapType == mapType)
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.setBackgroundMapType(mapType) },
                            label = { Text(mapType.koreanLabel, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF0284C7),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = onLoadExternalDrawing,
                        label = { Text("도면(DXF/SHP) 열기", fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                    )
                    FilterChip(
                        selected = cadSnapEnabled,
                        onClick = { viewModel.setCadSnapEnabled(!cadSnapEnabled) },
                        label = {
                            Text(
                                if (cadSnapEnabled) "도면측설 스냅 ON (${cadEntities.size})" else "도면스냅 OFF",
                                fontSize = 11.sp
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF16A34A),
                            selectedLabelColor = Color.White
                        )
                    )
                    if (cadEntities.isNotEmpty()) {
                        AssistChip(
                            onClick = { viewModel.clearExternalCadEntities() },
                            label = { Text("도면 지우기", fontSize = 11.sp) }
                        )
                    }
                    AssistChip(
                        onClick = onOpenProjectDialog,
                        label = { Text("프로젝트 저장/열기", fontSize = 11.sp) }
                    )
                }
            }
        }

        // 3. Interactive CAD + Aerial Map Canvas
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF080C14))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scaleMetersPerPixel = (scaleMetersPerPixel / zoom).coerceIn(0.01f, 80.0f)
                        panOffsetPx += pan
                    }
                }
                .pointerInput(cadEntities, cadSnapEnabled, scaleMetersPerPixel, panOffsetPx, planeCoord) {
                    detectTapGestures { tapOffset ->
                        if (cadSnapEnabled && cadEntities.isNotEmpty()) {
                            val centerN = if (planeCoord.northing != 0.0) planeCoord.northing
                            else points.lastOrNull()?.northing ?: cadEntities.firstOrNull()?.verticesNE?.firstOrNull()?.first ?: 0.0
                            val centerE = if (planeCoord.easting != 0.0) planeCoord.easting
                            else points.lastOrNull()?.easting ?: cadEntities.firstOrNull()?.verticesNE?.firstOrNull()?.second ?: 0.0

                            val cx = size.width / 2f + panOffsetPx.x
                            val cy = size.height / 2f + panOffsetPx.y
                            val tapE = centerE + (tapOffset.x - cx) * scaleMetersPerPixel
                            val tapN = centerN - (tapOffset.y - cy) * scaleMetersPerPixel
                            val snapRadiusMeters = (42f * scaleMetersPerPixel).toDouble().coerceAtLeast(0.5)
                            viewModel.snapAndStakeoutFromCanvas(tapN, tapE, snapRadiusMeters)
                        }
                    }
                }
        ) {
            val pointMap = remember(points) { points.associateBy { it.id } }

            val labelPaint = remember {
                Paint().apply {
                    color = android.graphics.Color.parseColor("#F8FAFC")
                    textSize = 26f
                    isAntiAlias = true
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    setShadowLayer(4f, 1f, 1f, android.graphics.Color.BLACK)
                }
            }
            val elevPaint = remember {
                Paint().apply {
                    color = android.graphics.Color.parseColor("#FACC15")
                    textSize = 23f
                    isAntiAlias = true
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                    setShadowLayer(4f, 1f, 1f, android.graphics.Color.BLACK)
                }
            }
            val cadTextPaint = remember {
                Paint().apply {
                    color = android.graphics.Color.parseColor("#93C5FD")
                    textSize = 22f
                    isAntiAlias = true
                }
            }

            // Observe tileInvalidateTick so Canvas redraws when async web tiles arrive
            val tickRead = tileInvalidateTick

            Canvas(modifier = Modifier.fillMaxSize()) {
                val _unused = tickRead
                val centerX = size.width / 2f + panOffsetPx.x
                val centerY = size.height / 2f + panOffsetPx.y

                val refN = when {
                    planeCoord.northing != 0.0 -> planeCoord.northing
                    points.isNotEmpty() -> points.last().northing
                    cadEntities.isNotEmpty() -> cadEntities.first().verticesNE.firstOrNull()?.first ?: 0.0
                    else -> 0.0
                }
                val refE = when {
                    planeCoord.easting != 0.0 -> planeCoord.easting
                    points.isNotEmpty() -> points.last().easting
                    cadEntities.isNotEmpty() -> cadEntities.first().verticesNE.firstOrNull()?.second ?: 0.0
                    else -> 0.0
                }

                fun worldToScreen(n: Double, e: Double): Offset {
                    val dxMeters = (e - refE).toFloat()
                    val dyMeters = (n - refN).toFloat()
                    val sx = centerX + (dxMeters / scaleMetersPerPixel)
                    val sy = centerY - (dyMeters / scaleMetersPerPixel)
                    return Offset(sx, sy)
                }

                // 0. Draw Background Aerial / Road Map Tiles if enabled
                if (backgroundMapType != BackgroundMapType.NONE && refN != 0.0 && refE != 0.0) {
                    val (centerLat, centerLon) = CoordinateConverter.planeToWgs84(refN, refE, coordSystem)
                    val tiles = viewModel.tileMapManager.getVisibleTiles(
                        mapType = backgroundMapType,
                        centerLat = centerLat,
                        centerLon = centerLon,
                        metersPerPixel = scaleMetersPerPixel.toDouble()
                    )
                    tiles.forEach { t ->
                        val bmp = t.bitmap
                        val nwPlane = CoordinateConverter.wgs84ToPlane(t.northLat, t.westLon, 0.0, coordSystem)
                        val sePlane = CoordinateConverter.wgs84ToPlane(t.southLat, t.eastLon, 0.0, coordSystem)
                        val topLeft = worldToScreen(nwPlane.northing, nwPlane.easting)
                        val botRight = worldToScreen(sePlane.northing, sePlane.easting)
                        val dstW = (botRight.x - topLeft.x).roundToInt().coerceAtLeast(1)
                        val dstH = (botRight.y - topLeft.y).roundToInt().coerceAtLeast(1)
                        if (bmp != null) {
                            drawImage(
                                image = bmp,
                                dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
                                dstSize = IntSize(dstW, dstH),
                                alpha = backgroundMapAlpha
                            )
                        } else {
                            drawRect(
                                color = Color(0xFF1E293B).copy(alpha = 0.35f),
                                topLeft = topLeft,
                                size = androidx.compose.ui.geometry.Size(dstW.toFloat(), dstH.toFloat()),
                                style = Stroke(width = 1f)
                            )
                        }
                    }
                }

                // 1. Draw subtle grid
                val gridStepPx = 110f
                var gx = centerX % gridStepPx
                while (gx < size.width) {
                    drawLine(
                        color = Color(0xFF1E293B).copy(alpha = 0.55f),
                        start = Offset(gx, 0f),
                        end = Offset(gx, size.height),
                        strokeWidth = 1f
                    )
                    gx += gridStepPx
                }
                var gy = centerY % gridStepPx
                while (gy < size.height) {
                    drawLine(
                        color = Color(0xFF1E293B).copy(alpha = 0.55f),
                        start = Offset(0f, gy),
                        end = Offset(size.width, gy),
                        strokeWidth = 1f
                    )
                    gy += gridStepPx
                }

                // 2. Draw External CAD (DXF/DWG) & V-World SHP Layer Entities
                cadEntities.forEach { ent ->
                    val entColor = Color(ent.colorHex)
                    val verts = ent.verticesNE
                    when (ent.type) {
                        CadEntityType.POINT -> {
                            val v = verts.firstOrNull() ?: return@forEach
                            val pos = worldToScreen(v.first, v.second)
                            if (pos.x in -50f..(size.width + 50f) && pos.y in -50f..(size.height + 50f)) {
                                drawCircle(color = entColor, radius = 6f, center = pos, style = Stroke(width = 2f))
                                if (ent.textLabel.isNotBlank() && showPointLabels) {
                                    drawContext.canvas.nativeCanvas.drawText(
                                        ent.textLabel,
                                        pos.x + 8f,
                                        pos.y - 6f,
                                        cadTextPaint
                                    )
                                }
                            }
                        }
                        CadEntityType.TEXT -> {
                            val v = verts.firstOrNull() ?: return@forEach
                            val pos = worldToScreen(v.first, v.second)
                            if (pos.x in -100f..(size.width + 100f) && pos.y in -50f..(size.height + 50f)) {
                                drawContext.canvas.nativeCanvas.drawText(
                                    ent.textLabel,
                                    pos.x,
                                    pos.y,
                                    cadTextPaint
                                )
                            }
                        }
                        else -> {
                            if (verts.size >= 2) {
                                for (i in 0 until verts.size - 1) {
                                    val p1 = worldToScreen(verts[i].first, verts[i].second)
                                    val p2 = worldToScreen(verts[i + 1].first, verts[i + 1].second)
                                    drawLine(
                                        color = entColor,
                                        start = p1,
                                        end = p2,
                                        strokeWidth = 2.6f
                                    )
                                    if (cadSnapEnabled) {
                                        // Draw subtle square snap grip at endpoints
                                        drawCircle(color = entColor, radius = 3.5f, center = p1)
                                    }
                                }
                                if (ent.isClosed && verts.size >= 3) {
                                    val pLast = worldToScreen(verts.last().first, verts.last().second)
                                    val pFirst = worldToScreen(verts.first().first, verts.first().second)
                                    drawLine(color = entColor, start = pLast, end = pFirst, strokeWidth = 2.6f)
                                }
                            }
                        }
                    }
                }

                // Highlight Last Snapped OSNAP Target if active
                lastSnappedTarget?.let { snap ->
                    val sPos = worldToScreen(snap.northing, snap.easting)
                    drawCircle(
                        color = Color(0xFFEC4899),
                        radius = 14f,
                        center = sPos,
                        style = Stroke(width = 3f)
                    )
                }

                // 3. Draw Surveyed CAD Polylines
                polylines.forEach { poly ->
                    val pts = poly.pointIds.mapNotNull { pointMap[it] }
                    if (pts.size >= 2) {
                        val lineColor = Color(poly.colorHex)
                        for (i in 0 until pts.size - 1) {
                            val p1 = worldToScreen(pts[i].northing, pts[i].easting)
                            val p2 = worldToScreen(pts[i + 1].northing, pts[i + 1].easting)
                            drawLine(color = lineColor, start = p1, end = p2, strokeWidth = 3.5f)
                        }
                    }
                }

                // 4. Draw Surveyed Points with Sequence Number + Code Name + Ground Elevation (Z)
                points.forEach { pt ->
                    val pos = worldToScreen(pt.northing, pt.easting)
                    if (pos.x in -120f..(size.width + 120f) && pos.y in -80f..(size.height + 80f)) {
                        val isTarget = stakeoutTarget?.id == pt.id
                        val ptColor = if (isTarget) Color(0xFFF43F5E) else Color(0xFF38BDF8)

                        drawCircle(color = ptColor, radius = if (isTarget) 9f else 6f, center = pos)
                        drawCircle(color = Color.White, radius = 2.5f, center = pos)

                        if (showPointLabels) {
                            val topLabel = "#${pt.seqNumber} (${pt.name}) [${pt.code}]"
                            val bottomLabel = String.format(Locale.US, "Z: %.3fm", pt.elevation)
                            drawContext.canvas.nativeCanvas.drawText(
                                topLabel,
                                pos.x + 12f,
                                pos.y - 6f,
                                labelPaint
                            )
                            drawContext.canvas.nativeCanvas.drawText(
                                bottomLabel,
                                pos.x + 12f,
                                pos.y + 20f,
                                elevPaint
                            )
                        }
                    }
                }

                // 5. Draw Stakeout Vector & Target Bullseye
                val curPos = worldToScreen(planeCoord.northing, planeCoord.easting)
                stakeoutTarget?.let { target ->
                    val targetPos = worldToScreen(target.northing, target.easting)
                    drawLine(
                        color = Color(0xFFF43F5E),
                        start = curPos,
                        end = targetPos,
                        strokeWidth = 3.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
                    )
                    drawCircle(
                        color = Color(0xFFF43F5E),
                        radius = 18f,
                        center = targetPos,
                        style = Stroke(width = 3f)
                    )
                }

                // 6. Draw Live Rover Position
                if (gnssStatus.fixQuality != FixQuality.NO_FIX) {
                    val roverColor = Color(gnssStatus.fixQuality.colorHex)
                    drawCircle(
                        color = roverColor.copy(alpha = 0.25f),
                        radius = 22f,
                        center = curPos
                    )
                    drawCircle(color = roverColor, radius = 8f, center = curPos)
                    drawCircle(color = Color.White, radius = 3f, center = curPos)
                }
            }

            // Floating Map Control Buttons (Right side)
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SmallFloatingActionButton(
                    onClick = { scaleMetersPerPixel = (scaleMetersPerPixel * 0.7f).coerceAtLeast(0.01f) },
                    containerColor = Color(0xFF1E293B),
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.ZoomIn, contentDescription = "확대")
                }
                SmallFloatingActionButton(
                    onClick = { scaleMetersPerPixel = (scaleMetersPerPixel * 1.4f).coerceAtMost(80.0f) },
                    containerColor = Color(0xFF1E293B),
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.ZoomOut, contentDescription = "축소")
                }
                SmallFloatingActionButton(
                    onClick = { panOffsetPx = Offset.Zero },
                    containerColor = Color(0xFF1E293B),
                    contentColor = Color(0xFF38BDF8)
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = "내 위치 중심")
                }
                SmallFloatingActionButton(
                    onClick = { showPointLabels = !showPointLabels },
                    containerColor = if (showPointLabels) Color(0xFF0284C7) else Color(0xFF1E293B),
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.Label, contentDescription = "라벨 토글")
                }
            }

            // Mini Stakeout Banner on Map if Stakeout is Active
            if (stakeoutTarget != null && stakeoutGuidance != null) {
                val g = stakeoutGuidance
                val arrowRotation = (g.azimuthDeg.toFloat() - deviceAzimuthDeg)
                Surface(
                    color = Color(0xEA1E293B),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .border(1.dp, Color(0xFFF43F5E), RoundedCornerShape(12.dp))
                        .clickable { onGoToStakeoutTab() }
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Canvas(modifier = Modifier.size(34.dp)) {
                            rotate(arrowRotation) {
                                val path = Path().apply {
                                    moveTo(size.width / 2f, 2f)
                                    lineTo(size.width - 4f, size.height - 4f)
                                    lineTo(size.width / 2f, size.height * 0.72f)
                                    lineTo(4f, size.height - 4f)
                                    close()
                                }
                                drawPath(path, color = Color(0xFF22C55E))
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "측설점: ${g.targetName} (터치시 상세)",
                                color = Color(0xFFF43F5E),
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "거리 %.3fm | ΔN:%+.3f ΔE:%+.3f",
                                    g.horizontalDistance,
                                    g.deltaN,
                                    g.deltaE
                                ),
                                color = Color.White,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // Disconnected Warning Banner on Canvas
            if (!canSurvey) {
                Surface(
                    color = Color(0xDD7F1D1D),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(10.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = Color(0xFFFCA5A5),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = blockReason ?: "측량 대기 중",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // 4. Bottom Quick-Code & Dual Survey Trigger Bar (Volume Up / Down)
        Surface(
            color = Color(0xFF0F172A),
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickCodes.forEach { preset ->
                        AssistChip(
                            enabled = canSurvey && !isAveraging,
                            onClick = {
                                viewModel.recordPoint(
                                    name = viewModel.generateNextPointName(),
                                    code = preset.code,
                                    epochs = 1,
                                    memo = preset.description,
                                    autoAppendLine = preset.isLinework
                                )
                            },
                            label = {
                                Text(
                                    text = "+${preset.code}",
                                    color = if (canSurvey) Color.White else Color(0xFF64748B),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = Color(0xFF1E293B)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onQuickRepeatMeasure,
                        enabled = canSurvey && !isAveraging,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(
                            Icons.Default.Replay,
                            contentDescription = null,
                            tint = if (canSurvey) Color(0xFF38BDF8) else Color(0xFF64748B),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(
                                text = "직전 재측량 (볼륨↓)",
                                color = if (canSurvey) Color.White else Color(0xFF64748B),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "코드: ${lastPreset.code} (${lastPreset.epochs}회)",
                                color = Color(0xFF94A3B8),
                                fontSize = 9.sp
                            )
                        }
                    }

                    Button(
                        onClick = onOpenMeasureDialog,
                        enabled = canSurvey && !isAveraging,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF0284C7),
                            disabledContainerColor = Color(0xFF1E293B)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1.15f)
                            .height(48.dp)
                    ) {
                        Icon(
                            Icons.Default.AddLocationAlt,
                            contentDescription = null,
                            tint = if (canSurvey) Color.White else Color(0xFF64748B)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isAveraging) "평균 측정 중 ($averagingProgress)..."
                            else "측정 저장 (볼륨↑)",
                            color = if (canSurvey) Color.White else Color(0xFF64748B),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CoordReadoutBox(label: String, value: Double, accent: Color) {
    Column {
        Text(text = label, color = Color(0xFF94A3B8), fontSize = 10.sp)
        Text(
            text = String.format(Locale.US, "%.4f", value),
            color = accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ============================================================================
// TAB 1: STAKEOUT COMPASS & CAD STAKEOUT SCREEN (좌표측설 + 도면측설)
// ============================================================================

@Composable
fun StakeoutCompassScreen(
    gnssStatus: GnssStatus,
    planeCoord: PlaneCoordinate,
    points: List<SurveyPoint>,
    cadEntitiesCount: Int,
    stakeoutTarget: SurveyPoint?,
    guidance: StakeoutGuidance?,
    includeZ: Boolean,
    soundEnabled: Boolean,
    deviceAzimuthDeg: Float,
    onSelectTarget: (SurveyPoint?) -> Unit,
    onToggleIncludeZ: (Boolean) -> Unit,
    onToggleSound: (Boolean) -> Unit,
    onOpenAddTargetDialog: () -> Unit,
    onLoadCadOrShpForStakeout: () -> Unit,
    onJumpToMapForCadSnap: () -> Unit,
    onQuickMeasureStakedPoint: () -> Unit
) {
    var showTargetPickerSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header Card with Target Controls & CAD Stakeout Entry
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "좌표측설 / 도면측설",
                            color = Color(0xFF38BDF8),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "10m 이내: 2초 간격 종소리 | 1m 이내: 삐비빅 알림음",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = onOpenAddTargetDialog,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("좌표 입력", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = { showTargetPickerSheet = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("목록 선택(${points.size})", fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // CAD / SHP Stakeout Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onLoadCadOrShpForStakeout,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("DXF/SHP 도면 열기", fontSize = 11.sp)
                    }
                    Button(
                        onClick = onJumpToMapForCadSnap,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.AdsClick, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("도면에서 끝점/점 스냅 ($cadEntitiesCount)", fontSize = 11.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !includeZ,
                        onClick = { onToggleIncludeZ(false) },
                        label = { Text("평면(X,Y) 집중 모드", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = includeZ,
                        onClick = { onToggleIncludeZ(true) },
                        label = { Text("X,Y + Z(절성토) 표시", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = soundEnabled,
                        onClick = { onToggleSound(!soundEnabled) },
                        label = { Text(if (soundEnabled) "거리 알림음 ON" else "알림음 OFF", fontSize = 11.sp) }
                    )
                }
            }
        }

        if (stakeoutTarget == null || guidance == null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Explore,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "선택된 측설 목표점이 없습니다",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "[좌표 입력], [목록 선택], 또는 [도면에서 끝점/점 스냅]을 눌러 측설할 목표점을 지정하세요.",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            val distColor = when {
                guidance.horizontalDistance <= 0.05 -> Color(0xFF22C55E)
                guidance.horizontalDistance <= 1.0 -> Color(0xFFFACC15)
                guidance.horizontalDistance <= 10.0 -> Color(0xFF38BDF8)
                else -> Color(0xFFF43F5E)
            }
            val soundStatusBadge = when {
                !soundEnabled -> "음소거"
                guidance.horizontalDistance <= 1.0 -> "1m 이내 (삐비빅 연속음)"
                guidance.horizontalDistance <= 10.0 -> "10m 이내 (2초 간격 종소리)"
                else -> "10m 밖 (대기 중)"
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "목표: ${stakeoutTarget.name} [${stakeoutTarget.code}]",
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "목표 N: %.4f | E: %.4f",
                                    stakeoutTarget.northing,
                                    stakeoutTarget.easting
                                ),
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        TextButton(onClick = { onSelectTarget(null) }) {
                            Text("측설 종료", color = Color(0xFFEF4444), fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(distColor.copy(alpha = 0.16f))
                            .border(1.dp, distColor, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "알림 상태: $soundStatusBadge",
                            color = distColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    val relativeTargetAngle = (guidance.azimuthDeg.toFloat() - deviceAzimuthDeg)

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(210.dp)
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val outerR = size.minDimension / 2f - 10f

                            drawCircle(
                                color = Color(0xFF1E293B),
                                radius = outerR,
                                center = center
                            )
                            drawCircle(
                                color = distColor,
                                radius = outerR,
                                center = center,
                                style = Stroke(width = 4f)
                            )
                            drawCircle(
                                color = Color(0xFF334155),
                                radius = outerR * 0.65f,
                                center = center,
                                style = Stroke(width = 1.5f)
                            )
                            drawCircle(
                                color = Color(0xFF334155),
                                radius = outerR * 0.30f,
                                center = center,
                                style = Stroke(width = 1.5f)
                            )

                            rotate(-deviceAzimuthDeg, pivot = center) {
                                val northPaint = Paint().apply {
                                    color = android.graphics.Color.parseColor("#EF4444")
                                    textSize = 28f
                                    typeface = Typeface.DEFAULT_BOLD
                                    textAlign = Paint.Align.CENTER
                                }
                                drawContext.canvas.nativeCanvas.drawText(
                                    "N",
                                    center.x,
                                    center.y - outerR + 28f,
                                    northPaint
                                )
                            }

                            rotate(relativeTargetAngle, pivot = center) {
                                val arrowPath = Path().apply {
                                    moveTo(center.x, center.y - outerR * 0.78f)
                                    lineTo(center.x + 24f, center.y + outerR * 0.45f)
                                    lineTo(center.x, center.y + outerR * 0.22f)
                                    lineTo(center.x - 24f, center.y + outerR * 0.45f)
                                    close()
                                }
                                drawPath(arrowPath, color = distColor)
                            }

                            drawCircle(color = Color.White, radius = 6f, center = center)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = String.format(Locale.US, "수평 거리: %.3f m", guidance.horizontalDistance),
                        color = distColor,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 22.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = String.format(
                            Locale.US,
                            "목표 방위각: %.1f° | 내 나침반 방향: %.0f°",
                            guidance.azimuthDeg,
                            deviceAzimuthDeg
                        ),
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }
            }

            // X(N), Y(E), and Optional Z Error Cards
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "실시간 이동 가이드 (X, Y 오차 거리)",
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val nDir = if (guidance.deltaN >= 0) "북(N)쪽으로 이동 ▲" else "남(S)쪽으로 이동 ▼"
                        StakeoutDeltaCard(
                            modifier = Modifier.weight(1f),
                            title = "ΔX (남-북 오차)",
                            directionText = nDir,
                            deltaMeters = guidance.deltaN,
                            accent = Color(0xFF38BDF8)
                        )

                        val eDir = if (guidance.deltaE >= 0) "동(E)쪽으로 이동 ▶" else "서(W)쪽으로 이동 ◀"
                        StakeoutDeltaCard(
                            modifier = Modifier.weight(1f),
                            title = "ΔY (동-서 오차)",
                            directionText = eDir,
                            deltaMeters = guidance.deltaE,
                            accent = Color(0xFF22C55E)
                        )
                    }

                    if (includeZ) {
                        val zDir = if (guidance.deltaZ >= 0) "성토 (높여야 함 ↑)" else "절토 (깎아야 함 ↓)"
                        StakeoutDeltaCard(
                            modifier = Modifier.fillMaxWidth(),
                            title = "ΔZ (표고 오차 - 지반고)",
                            directionText = zDir,
                            deltaMeters = guidance.deltaZ,
                            accent = Color(0xFFFACC15)
                        )
                    }

                    Button(
                        onClick = onQuickMeasureStakedPoint,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("현재 위치 검측점 저장", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showTargetPickerSheet) {
        AlertDialog(
            onDismissRequest = { showTargetPickerSheet = false },
            containerColor = Color(0xFF131B2E),
            title = { Text("측설할 목표점 선택", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                if (points.isEmpty()) {
                    Text("저장된 측점이 없습니다. 먼저 좌표를 입력하거나 측량하세요.", color = Color(0xFF94A3B8))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                        items(points) { pt ->
                            Surface(
                                color = Color(0xFF1E293B),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        showTargetPickerSheet = false
                                        onSelectTarget(pt)
                                    }
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = "#${pt.seqNumber} ${pt.name} [${pt.code}]",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = String.format(
                                            Locale.US,
                                            "N:%.3f  E:%.3f  Z:%.3f",
                                            pt.northing,
                                            pt.easting,
                                            pt.elevation
                                        ),
                                        color = Color(0xFF94A3B8),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTargetPickerSheet = false }) {
                    Text("닫기", color = Color(0xFF38BDF8))
                }
            }
        )
    }
}

@Composable
private fun StakeoutDeltaCard(
    modifier: Modifier = Modifier,
    title: String,
    directionText: String,
    deltaMeters: Double,
    accent: Color
) {
    Surface(
        color = Color(0xFF1E293B),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(text = title, color = Color(0xFF94A3B8), fontSize = 11.sp)
            Text(
                text = String.format(Locale.US, "%+.3f m", deltaMeters),
                color = accent,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(text = directionText, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ============================================================================
// TAB 2: POINT MANAGER & SELECTIVE EXPORT SCREEN (프로젝트 관리 + 선택형 내보내기)
// ============================================================================

@Composable
fun PointManagerScreen(
    projectName: String,
    points: List<SurveyPoint>,
    exportConfig: ExportColumnConfig,
    onOpenProjectDialog: () -> Unit,
    onOpenExportConfigDialog: () -> Unit,
    onExportSelectedColumns: () -> Unit,
    onExportDxf: (DxfEncodingMode) -> Unit,
    onExportKml: () -> Unit,
    onLoadExternalFile: () -> Unit,
    onAddManualPoint: () -> Unit,
    onSelectStakeout: (SurveyPoint) -> Unit,
    onDeletePoint: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filtered = remember(points, searchQuery) {
        if (searchQuery.isBlank()) points
        else points.filter {
            it.name.contains(searchQuery, true) ||
                it.code.contains(searchQuery, true) ||
                it.memo.contains(searchQuery, true) ||
                it.seqNumber.toString() == searchQuery.trim()
        }
    }

    val activeColsSummary = buildList {
        if (exportConfig.includeSeqNumber) add("순번")
        if (exportConfig.includePointName) add("측번")
        if (exportConfig.coordOrderNEZ) {
            if (exportConfig.includeNorthing) add("N(X)")
            if (exportConfig.includeEasting) add("E(Y)")
        } else {
            if (exportConfig.includeEasting) add("E(Y)")
            if (exportConfig.includeNorthing) add("N(X)")
        }
        if (exportConfig.includeElevation) add("Z(지반고)")
        if (exportConfig.includeCode) add("코드명")
        if (exportConfig.includeMemo) add("메모")
        if (exportConfig.includeLatLon) add("위경도")
        if (exportConfig.includeFixAndRms) add("정밀도")
        if (exportConfig.includeTimestamp) add("측정시각")
    }.joinToString(", ")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Project Management & Selective Export Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "프로젝트: $projectName (총 ${points.size}점)",
                            color = Color(0xFF38BDF8),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "선택 출력 항목: $activeColsSummary",
                            color = Color(0xFFFACC15),
                            fontSize = 11.sp
                        )
                    }
                    Button(
                        onClick = onOpenProjectDialog,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("프로젝트 저장/열기", fontSize = 11.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = onOpenExportConfigDialog,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Icon(Icons.Default.Checklist, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("내보내기 항목 선택/출력", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onExportSelectedColumns,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("즉시 CSV/NEZ 출력", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AssistChip(
                        onClick = { onExportDxf(DxfEncodingMode.CP949_KOREAN) },
                        label = { Text("한글 DXF(CP949) 내보내기", fontSize = 11.sp) }
                    )
                    AssistChip(
                        onClick = { onExportDxf(DxfEncodingMode.UTF8_MODERN) },
                        label = { Text("UTF-8 DXF", fontSize = 11.sp) }
                    )
                    AssistChip(
                        onClick = { onExportDxf(DxfEncodingMode.UNICODE_ESCAPE) },
                        label = { Text("유니코드 DXF", fontSize = 11.sp) }
                    )
                    AssistChip(
                        onClick = onExportKml,
                        label = { Text("KML 내보내기", fontSize = 11.sp) }
                    )
                    AssistChip(
                        onClick = onLoadExternalFile,
                        label = { Text("파일/도면 불러오기", fontSize = 11.sp) }
                    )
                    AssistChip(
                        onClick = onAddManualPoint,
                        label = { Text("+ 수동 측점 추가", fontSize = 11.sp) }
                    )
                }
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("측점순번, 측번, 코드명, 메모 검색") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filtered, key = { it.id }) { pt ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "#${pt.seqNumber} ${pt.name}",
                                    color = Color.White,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF1E293B))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = pt.code,
                                        color = Color(0xFF38BDF8),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "N: %.4f | E: %.4f | 지반고(Z): %.4fm",
                                    pt.northing,
                                    pt.easting,
                                    pt.elevation
                                ),
                                color = Color(0xFFFACC15),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            if (pt.memo.isNotBlank()) {
                                Text(
                                    text = "메모: ${pt.memo}",
                                    color = Color(0xFFCBD5E1),
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Row {
                            IconButton(onClick = { onSelectStakeout(pt) }) {
                                Icon(
                                    Icons.Default.Explore,
                                    contentDescription = "좌표측설",
                                    tint = Color(0xFF22C55E)
                                )
                            }
                            IconButton(onClick = { onDeletePoint(pt.id) }) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "삭제",
                                    tint = Color(0xFFEF4444)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// TAB 3: BLUETOOTH V90 + NTRIP VRS + NMEA WAKE-UP & SKYPLOT
// ============================================================================

@Composable
fun ConnectionAndSkyplotScreen(
    viewModel: GandanSurveyViewModel,
    gnssStatus: GnssStatus,
    sourceMode: PositioningSourceMode,
    requiredFixQuality: FixQuality,
    onOpenNmeaMonitor: () -> Unit
) {
    val ntripConfig by viewModel.ntripConfig.collectAsState()
    val mountpoints by viewModel.availableMountpoints.collectAsState()
    val discoveredDevices by viewModel.btManager.discoveredDevices.collectAsState()
    val isScanning by viewModel.btManager.isScanning.collectAsState()
    val nmeaLinesCount by viewModel.btManager.nmeaLinesReceived.collectAsState()
    val rawBytesCount by viewModel.btManager.rawBytesReceived.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.getPairedBluetoothDevices()
    }

    var host by remember(ntripConfig.host) { mutableStateOf(ntripConfig.host) }
    var portStr by remember(ntripConfig.port) { mutableStateOf(ntripConfig.port.toString()) }
    var user by remember(ntripConfig.username) { mutableStateOf(ntripConfig.username) }
    var pass by remember(ntripConfig.password) { mutableStateOf(ntripConfig.password) }
    var mount by remember(ntripConfig.mountpoint) { mutableStateOf(ntripConfig.mountpoint) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Positioning Source Mode Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "1. 측량 위치 소스 모드 (신뢰성 잠금)",
                    color = Color(0xFF38BDF8),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(6.dp))

                PositioningSourceMode.entries.forEach { mode ->
                    val isSelected = sourceMode == mode
                    Surface(
                        color = if (isSelected) Color(0xFF0284C7).copy(alpha = 0.25f) else Color(0xFF1E293B),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) Color(0xFF38BDF8) else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { viewModel.setPositioningSourceMode(mode) }
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { viewModel.setPositioningSourceMode(mode) }
                            )
                            Column {
                                Text(
                                    text = mode.koreanTitle,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = mode.description,
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "측량 허용 최소 품질 (DGPS / FLOAT / FIXED)",
                    color = Color(0xFFFACC15),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        FixQuality.RTK_FIXED,
                        FixQuality.RTK_FLOAT,
                        FixQuality.DGPS,
                        FixQuality.SINGLE
                    ).forEach { q ->
                        FilterChip(
                            selected = requiredFixQuality == q,
                            onClick = { viewModel.setRequiredFixQuality(q) },
                            label = { Text("${q.koreanLabel} 이상", fontSize = 11.sp) }
                        )
                    }
                }
            }
        }

        // 2. Hi-Target V90 Bluetooth SPP Connection Card + NMEA Wake-Up
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "2. Hi-Target V90 블루투스 연결",
                            color = Color(0xFF38BDF8),
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "상태: ${gnssStatus.connectedDeviceName}",
                            color = if (gnssStatus.btConnected) Color(0xFF22C55E) else Color(0xFFF87171),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "수신 바이트: ${rawBytesCount}B | NMEA 문장: ${nmeaLinesCount}개",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.startBluetoothScan() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(if (isScanning) "검색중..." else "주변기기 검색", fontSize = 11.sp)
                        }
                        if (gnssStatus.btConnected) {
                            OutlinedButton(
                                onClick = { viewModel.disconnectBluetoothReceiver() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("해제", color = Color(0xFFEF4444), fontSize = 11.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // V90 NMEA Output Wake-up + Live Terminal Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.requestV90NmeaOutputNow() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("V90 위성출력(NMEA) 깨우기", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onOpenNmeaMonitor,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("NMEA 수신 모니터", fontSize = 11.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (discoveredDevices.isEmpty()) {
                    Text(
                        text = "페어링된 블루투스 기기가 없습니다. 안드로이드 설정에서 V90(예: 11606199)을 페어링하거나 [주변기기 검색]을 누르세요.",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                } else {
                    discoveredDevices.forEach { dev ->
                        Surface(
                            color = Color(0xFF1E293B),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clickable { viewModel.connectBluetoothReceiver(dev) }
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = if (dev.isHiTargetV90) "★ ${dev.name} (GNSS/V90)" else dev.name,
                                        color = if (dev.isHiTargetV90) Color(0xFFFACC15) else Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = dev.address,
                                        color = Color(0xFF94A3B8),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Text("연결", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // 3. NGII VRS NTRIP Client Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "3. 국토지리정보원(NGII) VRS NTRIP 설정",
                    color = Color(0xFF38BDF8),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Text(
                    text = "상태: ${gnssStatus.ntripStatusMessage} (${gnssStatus.rtcmBytesPerSec} B/s)",
                    color = if (gnssStatus.ntripConnected) Color(0xFF22C55E) else Color(0xFFFACC15),
                    fontSize = 12.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("호스트 주소") },
                        singleLine = true,
                        modifier = Modifier.weight(2f)
                    )
                    OutlinedTextField(
                        value = portStr,
                        onValueChange = { portStr = it },
                        label = { Text("포트") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it },
                        label = { Text("VRS 아이디") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text("비밀번호 (기본 ngii)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = mount,
                    onValueChange = { mount = it },
                    label = { Text("마운트포인트 (예: VRS-RTCM32)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    mountpoints.forEach { mp ->
                        AssistChip(
                            onClick = { mount = mp.substringBefore(" ") },
                            label = { Text(mp, fontSize = 11.sp) }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            viewModel.updateNtripConfig(
                                NtripConfig(host, portStr.toIntOrNull() ?: 2101, mount, user, pass)
                            )
                            viewModel.fetchMountpoints()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("마운트 조회", fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            viewModel.updateNtripConfig(
                                NtripConfig(host, portStr.toIntOrNull() ?: 2101, mount, user, pass)
                            )
                            viewModel.toggleNtripConnection()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (gnssStatus.ntripConnected) Color(0xFFEF4444) else Color(0xFF22C55E)
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (gnssStatus.ntripConnected) "VRS 연결 중지" else "VRS 시작",
                            color = Color.Black,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // 4. Skyplot Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "4. 위성 스카이플롯 (사용 ${gnssStatus.satellitesUsed} / 추적 ${gnssStatus.satellitesTracked})",
                    color = Color(0xFF38BDF8),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(10.dp))

                Canvas(modifier = Modifier.size(190.dp)) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val maxR = size.minDimension / 2f - 12f
                    drawCircle(color = Color(0xFF1E293B), radius = maxR, center = center)
                    drawCircle(color = Color(0xFF334155), radius = maxR, center = center, style = Stroke(width = 2f))
                    drawCircle(color = Color(0xFF334155), radius = maxR * 0.66f, center = center, style = Stroke(width = 1f))
                    drawCircle(color = Color(0xFF334155), radius = maxR * 0.33f, center = center, style = Stroke(width = 1f))

                    gnssStatus.satellites.forEach { sat ->
                        val r = maxR * (1f - (sat.elevation.coerceIn(0, 90) / 90f))
                        val rad = Math.toRadians((sat.azimuth - 90).toDouble())
                        val sx = center.x + (r * cos(rad)).toFloat()
                        val sy = center.y + (r * sin(rad)).toFloat()
                        val satColor = when (sat.constellation) {
                            "GPS" -> Color(0xFF38BDF8)
                            "GLONASS" -> Color(0xFFFACC15)
                            "BeiDou" -> Color(0xFF22C55E)
                            "Galileo" -> Color(0xFFA855F7)
                            else -> Color(0xFFF43F5E)
                        }
                        drawCircle(color = satColor, radius = 6.5f, center = Offset(sx, sy))
                    }
                }
            }
        }
    }
}

// ============================================================================
// TAB 4: COORDINATE SYSTEM & SITE CALIBRATION
// ============================================================================

@Composable
fun CoordinateAndCalibrationScreen(
    viewModel: GandanSurveyViewModel,
    coordSystem: KoreanCoordinateSystem,
    planeCoord: PlaneCoordinate,
    antennaHeight: Double
) {
    val useGeoid18 by viewModel.useKnGeoid18.collectAsState()
    val imuEnabled by viewModel.imuTiltCompensationEnabled.collectAsState()
    val calPairs by viewModel.calibrationPairs.collectAsState()
    val calParams by viewModel.calibrationParams.collectAsState()

    var poleInput by remember(antennaHeight) { mutableStateOf(String.format(Locale.US, "%.3f", antennaHeight)) }
    var knownName by remember { mutableStateOf("CP-1") }
    var knownN by remember { mutableStateOf("") }
    var knownE by remember { mutableStateOf("") }
    var knownZ by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "대한민국 평면직각좌표계 선택",
                    color = Color(0xFF38BDF8),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                KoreanCoordinateSystem.entries.forEach { sys ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setCoordinateSystem(sys) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (sys == coordSystem),
                            onClick = { viewModel.setCoordinateSystem(sys) }
                        )
                        Column {
                            Text(text = sys.displayName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(text = sys.epsgCode, color = Color(0xFF94A3B8), fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "안테나 폴대고 & KNGeoid18 지오이드",
                    color = Color(0xFF38BDF8),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = poleInput,
                        onValueChange = { poleInput = it },
                        label = { Text("폴대 높이 (m)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            poleInput.toDoubleOrNull()?.let { viewModel.setAntennaHeight(it) }
                        }
                    ) {
                        Text("적용")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("국토지리정보원 KNGeoid18 표고 보정", color = Color.White, fontSize = 13.sp)
                    Switch(checked = useGeoid18, onCheckedChange = { viewModel.setUseKnGeoid18(it) })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("V90 IMU 전자기포관 기울기 보정", color = Color.White, fontSize = 13.sp)
                    Switch(checked = imuEnabled, onCheckedChange = { viewModel.setImuTiltCompensation(it) })
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "현장 로컬 캘리브레이션 (${calPairs.size}점)",
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    if (calPairs.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearCalibration() }) {
                            Text("초기화", color = Color(0xFFEF4444))
                        }
                    }
                }

                if (calParams.enabled) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "ΔN:%+.3fm ΔE:%+.3fm ΔZ:%+.3fm | 축척:%.6f | RMSE:%.3fm",
                            calParams.translateN,
                            calParams.translateE,
                            calParams.translateZ,
                            calParams.scaleFactor,
                            calParams.rmseHorizontal
                        ),
                        color = Color(0xFF22C55E),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                OutlinedTextField(
                    value = knownName,
                    onValueChange = { knownName = it },
                    label = { Text("기지점 명칭") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = knownN,
                        onValueChange = { knownN = it },
                        label = { Text("기지점 N(X)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = knownE,
                        onValueChange = { knownE = it },
                        label = { Text("기지점 E(Y)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = knownZ,
                        onValueChange = { knownZ = it },
                        label = { Text("기지점 Z") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(0.9f)
                    )
                }

                Button(
                    onClick = {
                        val n = knownN.toDoubleOrNull() ?: planeCoord.northing
                        val e = knownE.toDoubleOrNull() ?: planeCoord.easting
                        val z = knownZ.toDoubleOrNull() ?: planeCoord.orthometricHeight
                        viewModel.addCalibrationPair(knownName, n, e, z)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("현재 GNSS 위치와 기지점 매칭 추가")
                }
            }
        }
    }
}

// ============================================================================
// DIALOGS: MEASURE POINT, STAKEOUT POINT, PROJECT MANAGER, SELECTIVE EXPORT, NMEA MONITOR
// ============================================================================

@Composable
fun MeasurePointDialog(
    suggestedSeq: Int,
    suggestedName: String,
    quickCodes: List<QuickCodePreset>,
    defaultCode: String,
    defaultEpochs: Int,
    planeCoord: PlaneCoordinate,
    gnssStatus: GnssStatus,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Int, String, Boolean) -> Unit
) {
    var ptName by remember { mutableStateOf(suggestedName) }
    var code by remember { mutableStateOf(defaultCode) }
    var epochsStr by remember { mutableStateOf(defaultEpochs.toString()) }
    var memo by remember { mutableStateOf("") }
    var autoLine by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF131B2E),
        title = {
            Text(
                text = "측점 저장 (#$suggestedSeq)",
                color = Color.White,
                fontWeight = FontWeight.ExtraBold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = String.format(
                        Locale.US,
                        "N: %.4f | E: %.4f\n지반고(Z): %.4f m (%s)",
                        planeCoord.northing,
                        planeCoord.easting,
                        planeCoord.orthometricHeight,
                        gnssStatus.fixQuality.koreanLabel
                    ),
                    color = Color(0xFFFACC15),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )

                OutlinedTextField(
                    value = ptName,
                    onValueChange = { ptName = it },
                    label = { Text("측번 (측점 번호/이름)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("코드명 (지형/지물 코드)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickCodes.forEach { preset ->
                        FilterChip(
                            selected = (code == preset.code),
                            onClick = {
                                code = preset.code
                                autoLine = preset.isLinework
                            },
                            label = { Text(preset.code, fontSize = 11.sp) }
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = epochsStr,
                        onValueChange = { epochsStr = it },
                        label = { Text("에포크 평균 횟수") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = autoLine, onCheckedChange = { autoLine = it })
                        Text("결선 연결", color = Color.White, fontSize = 12.sp)
                    }
                }

                OutlinedTextField(
                    value = memo,
                    onValueChange = { memo = it },
                    label = { Text("메모 (내보내기 시 선택 출력 가능)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val ep = epochsStr.toIntOrNull()?.coerceIn(1, 30) ?: 1
                    onConfirm(ptName, code, ep, memo, autoLine)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
            ) {
                Text("저장", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소", color = Color(0xFF94A3B8))
            }
        }
    )
}

@Composable
fun AddStakeoutPointDialog(
    suggestedSeq: Int,
    currentPlane: PlaneCoordinate,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Double, Double, Double, String, Boolean) -> Unit
) {
    var name by remember { mutableStateOf("ST-$suggestedSeq") }
    var code by remember { mutableStateOf("측설점") }
    var nStr by remember { mutableStateOf(String.format(Locale.US, "%.4f", currentPlane.northing + 5.0)) }
    var eStr by remember { mutableStateOf(String.format(Locale.US, "%.4f", currentPlane.easting + 5.0)) }
    var zStr by remember { mutableStateOf(String.format(Locale.US, "%.3f", currentPlane.orthometricHeight)) }
    var memo by remember { mutableStateOf("") }
    var startImmediately by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF131B2E),
        title = {
            Text(
                text = "좌표측설 포인트 직접 입력",
                color = Color.White,
                fontWeight = FontWeight.ExtraBold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("측번") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("코드명") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = nStr,
                    onValueChange = { nStr = it },
                    label = { Text("목표 N (X 북방향 좌표)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = eStr,
                    onValueChange = { eStr = it },
                    label = { Text("목표 E (Y 동방향 좌표)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = zStr,
                    onValueChange = { zStr = it },
                    label = { Text("목표 Z (지반고, 선택사항)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = memo,
                    onValueChange = { memo = it },
                    label = { Text("메모") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = startImmediately, onCheckedChange = { startImmediately = it })
                    Text("추가 즉시 좌표측설 시작", color = Color.White, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val n = nStr.toDoubleOrNull() ?: currentPlane.northing
                    val e = eStr.toDoubleOrNull() ?: currentPlane.easting
                    val z = zStr.toDoubleOrNull() ?: 0.0
                    onConfirm(name, code, n, e, z, memo, startImmediately)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
            ) {
                Text("저장 및 선택", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소", color = Color(0xFF94A3B8))
            }
        }
    )
}

@Composable
fun ProjectManagementDialog(
    currentProjectName: String,
    pointCount: Int,
    cadEntityCount: Int,
    savedFiles: List<File>,
    onDismiss: () -> Unit,
    onSaveProject: (String) -> Unit,
    onCreateNewProject: (String) -> Unit,
    onOpenProjectFile: (File) -> Unit,
    onImportProjectFromStorage: () -> Unit
) {
    var nameInput by remember(currentProjectName) { mutableStateOf(currentProjectName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF131B2E),
        title = {
            Text("프로젝트 저장 및 불러오기 (.gsproj)", color = Color.White, fontWeight = FontWeight.ExtraBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "현재 프로젝트: $currentProjectName (측점 ${pointCount}개, 도면객체 ${cadEntityCount}개)",
                    color = Color(0xFF38BDF8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("프로젝트 이름") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onSaveProject(nameInput) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("현재 프로젝트 저장", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { onCreateNewProject(nameInput) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("새 프로젝트 시작", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("저장된 프로젝트 목록 (Documents/간단측량/Projects)", color = Color(0xFFFACC15), fontSize = 11.sp)
                    TextButton(onClick = onImportProjectFromStorage) {
                        Text("외부 파일 열기", color = Color(0xFF38BDF8), fontSize = 11.sp)
                    }
                }

                if (savedFiles.isEmpty()) {
                    Text("저장된 프로젝트 파일이 없습니다.", color = Color(0xFF94A3B8), fontSize = 12.sp)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                        items(savedFiles) { file ->
                            Surface(
                                color = Color(0xFF1E293B),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clickable { onOpenProjectFile(file) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = file.nameWithoutExtension,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = "${file.length() / 1024} KB",
                                            color = Color(0xFF94A3B8),
                                            fontSize = 11.sp
                                        )
                                    }
                                    Text("열기", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("닫기", color = Color(0xFF94A3B8))
            }
        }
    )
}

/**
 * Dialog allowing the user to select which fields (측번, 코드명, 메모, 순번, N, E, Z, etc.)
 * are exported in CSV/NEZ/TXT and CAD DXF files.
 */
@Composable
fun SelectiveExportConfigDialog(
    config: ExportColumnConfig,
    onUpdateConfig: (ExportColumnConfig) -> Unit,
    onExportCsvOrNezNow: () -> Unit,
    onExportDxfNow: (DxfEncodingMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF131B2E),
        title = {
            Text("내보내기 항목 선택 (측번/코드명/메모 등)", color = Color.White, fontWeight = FontWeight.ExtraBold)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("1. CSV / NEZ / TXT 출력 컬럼 선택", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 13.sp)

                Row(modifier = Modifier.fillMaxWidth()) {
                    ExportCheckboxItem(
                        label = "순번 (#1,2..)",
                        checked = config.includeSeqNumber,
                        onCheckedChange = { onUpdateConfig(config.copy(includeSeqNumber = it)) },
                        modifier = Modifier.weight(1f)
                    )
                    ExportCheckboxItem(
                        label = "측번 (PT-1..)",
                        checked = config.includePointName,
                        onCheckedChange = { onUpdateConfig(config.copy(includePointName = it)) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(modifier = Modifier.fillMaxWidth()) {
                    ExportCheckboxItem(
                        label = "코드명 (필수권장)",
                        checked = config.includeCode,
                        onCheckedChange = { onUpdateConfig(config.copy(includeCode = it)) },
                        modifier = Modifier.weight(1f)
                    )
                    ExportCheckboxItem(
                        label = "메모",
                        checked = config.includeMemo,
                        onCheckedChange = { onUpdateConfig(config.copy(includeMemo = it)) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(modifier = Modifier.fillMaxWidth()) {
                    ExportCheckboxItem(
                        label = "N(X 북방향)",
                        checked = config.includeNorthing,
                        onCheckedChange = { onUpdateConfig(config.copy(includeNorthing = it)) },
                        modifier = Modifier.weight(1f)
                    )
                    ExportCheckboxItem(
                        label = "E(Y 동방향)",
                        checked = config.includeEasting,
                        onCheckedChange = { onUpdateConfig(config.copy(includeEasting = it)) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(modifier = Modifier.fillMaxWidth()) {
                    ExportCheckboxItem(
                        label = "Z(지반고)",
                        checked = config.includeElevation,
                        onCheckedChange = { onUpdateConfig(config.copy(includeElevation = it)) },
                        modifier = Modifier.weight(1f)
                    )
                    ExportCheckboxItem(
                        label = "위경도(Lat/Lon)",
                        checked = config.includeLatLon,
                        onCheckedChange = { onUpdateConfig(config.copy(includeLatLon = it)) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(modifier = Modifier.fillMaxWidth()) {
                    ExportCheckboxItem(
                        label = "측위상태/정밀도",
                        checked = config.includeFixAndRms,
                        onCheckedChange = { onUpdateConfig(config.copy(includeFixAndRms = it)) },
                        modifier = Modifier.weight(1f)
                    )
                    ExportCheckboxItem(
                        label = "측정시각",
                        checked = config.includeTimestamp,
                        onCheckedChange = { onUpdateConfig(config.copy(includeTimestamp = it)) },
                        modifier = Modifier.weight(1f)
                    )
                }

                ExportCheckboxItem(
                    label = "첫 줄에 컬럼 제목(헤더) 포함",
                    checked = config.includeHeaderRow,
                    onCheckedChange = { onUpdateConfig(config.copy(includeHeaderRow = it)) },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(4.dp))
                Text("2. 좌표 순서 및 구분자", color = Color(0xFFFACC15), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = config.coordOrderNEZ,
                        onClick = { onUpdateConfig(config.copy(coordOrderNEZ = true)) },
                        label = { Text("N(X), E(Y), Z 순", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = !config.coordOrderNEZ,
                        onClick = { onUpdateConfig(config.copy(coordOrderNEZ = false)) },
                        label = { Text("E(Y), N(X), Z 순", fontSize = 11.sp) }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = config.delimiter == ",",
                        onClick = { onUpdateConfig(config.copy(delimiter = ",")) },
                        label = { Text("CSV 쉼표(,)", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = config.delimiter == " ",
                        onClick = { onUpdateConfig(config.copy(delimiter = " ")) },
                        label = { Text("NEZ 공백", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = config.delimiter == "\t",
                        onClick = { onUpdateConfig(config.copy(delimiter = "\t")) },
                        label = { Text("TXT 탭", fontSize = 11.sp) }
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text("3. DXF 캐드 도면 문자 출력 선택", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Row(modifier = Modifier.fillMaxWidth()) {
                    ExportCheckboxItem(
                        label = "DXF 측번 표시",
                        checked = config.dxfIncludePointName,
                        onCheckedChange = { onUpdateConfig(config.copy(dxfIncludePointName = it)) },
                        modifier = Modifier.weight(1f)
                    )
                    ExportCheckboxItem(
                        label = "DXF 코드명 표시",
                        checked = config.dxfIncludeCode,
                        onCheckedChange = { onUpdateConfig(config.copy(dxfIncludeCode = it)) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    ExportCheckboxItem(
                        label = "DXF 지반고(Z)",
                        checked = config.dxfIncludeElevation,
                        onCheckedChange = { onUpdateConfig(config.copy(dxfIncludeElevation = it)) },
                        modifier = Modifier.weight(1f)
                    )
                    ExportCheckboxItem(
                        label = "DXF 메모 표시",
                        checked = config.dxfIncludeMemo,
                        onCheckedChange = { onUpdateConfig(config.copy(dxfIncludeMemo = it)) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = onExportCsvOrNezNow,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A))
                ) {
                    Text("CSV/NEZ 내보내기", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { onExportDxfNow(DxfEncodingMode.CP949_KOREAN) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                ) {
                    Text("한글 DXF 출력", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("닫기", color = Color(0xFF94A3B8))
            }
        }
    )
}

@Composable
private fun ExportCheckboxItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(text = label, color = Color.White, fontSize = 11.sp)
    }
}

@Composable
fun NmeaRawMonitorDialog(
    viewModel: GandanSurveyViewModel,
    gnssStatus: GnssStatus,
    onDismiss: () -> Unit
) {
    val rawLogs by viewModel.btManager.recentRawLog.collectAsState()
    val rawBytes by viewModel.btManager.rawBytesReceived.collectAsState()
    val nmeaLines by viewModel.btManager.nmeaLinesReceived.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF131B2E),
        title = {
            Text("V90 실시간 NMEA 수신 모니터", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "수신 바이트: ${rawBytes}B | NMEA 문장: ${nmeaLines}개 | 위성: ${gnssStatus.satellitesUsed}/${gnssStatus.satellitesTracked}",
                    color = Color(0xFFFACC15),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Button(
                    onClick = { viewModel.requestV90NmeaOutputNow() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("V90 NMEA 1Hz 출력 활성화 명령 재전송", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Surface(
                    color = Color(0xFF080C14),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(230.dp)
                ) {
                    LazyColumn(modifier = Modifier.padding(8.dp)) {
                        if (rawLogs.isEmpty()) {
                            item {
                                Text(
                                    text = "수신된 NMEA 로그가 아직 없습니다. V90 블루투스를 연결하세요.",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp
                                )
                            }
                        } else {
                            items(rawLogs) { line ->
                                Text(
                                    text = line,
                                    color = Color(0xFF22C55E),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("닫기", color = Color(0xFF38BDF8))
            }
        }
    )
}
