package mx.org.fraac.geocampo

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GeoCampoTheme {
                GeoCampoScreen(vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeoCampoScreen(vm: MainViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val layers by vm.layers.collectAsState()
    val basemap by vm.basemap.collectAsState()
    val folders by vm.folders.collectAsState()
    val projects by vm.projects.collectAsState()
    val activeProjectId by vm.activeProjectId.collectAsState()
    val waypoints by vm.waypoints.collectAsState()
    val trackPoints by vm.trackPoints.collectAsState()
    val currentFix by vm.currentFix.collectAsState()
    val tracking by vm.trackingState.collectAsState()
    val message by vm.message.collectAsState()
    val mapDownloadActive by vm.mapDownloadActive.collectAsState()
    val mapDownloadStatus by vm.mapDownloadStatus.collectAsState()
    val satelliteOffline by vm.satelliteOffline.collectAsState()
    val satelliteDownloadActive by vm.satelliteDownloadActive.collectAsState()
    val satelliteDownloadProgress by vm.satelliteDownloadProgress.collectAsState()
    val satelliteDownloadStatus by vm.satelliteDownloadStatus.collectAsState()
    val satelliteDownloadedBytes by vm.satelliteDownloadedBytes.collectAsState()
    val mapTilerApiKey by vm.mapTilerApiKey.collectAsState()
    val mapTilerPrintAllowed by vm.mapTilerPrintAllowed.collectAsState()
    val mapTilerValidationActive by vm.mapTilerValidationActive.collectAsState()

    var showProjectDialog by remember { mutableStateOf(false) }
    var showBasemapDialog by remember { mutableStateOf(false) }
    var showLayersDialog by remember { mutableStateOf(false) }
    var showSatelliteOfflineDialog by remember { mutableStateOf(false) }
    var showImageryDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showPrintDialog by remember { mutableStateOf(false) }
    var selectedWaypoint by remember { mutableStateOf<Waypoint?>(null) }

    var baseMapMode by remember { mutableStateOf(BaseMapMode.MAP) }
    var centerOnLocationToken by remember { mutableIntStateOf(0) }
    val snapshotBridge = remember { MapSnapshotBridge() }

    var pendingWaypoint by remember { mutableStateOf<Pair<GeoCoord, Float?>?>(null) }
    val pendingPhotoPaths = remember { mutableStateListOf<String>() }
    var capturingPhotoPath by remember { mutableStateOf<String?>(null) }

    var pendingExportFormat by remember { mutableStateOf<ExportFormat?>(null) }
    var pendingReportData by remember { mutableStateOf<PrintReportData?>(null) }
    var pendingReportBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted =
            grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            vm.startLiveLocation()
            vm.requestCurrentFix()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) vm.importUris(uris)
    }

    val basemapImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importUris(listOf(uri))
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val path = capturingPhotoPath
        if (success && path != null) {
            if (!pendingPhotoPaths.contains(path)) {
                pendingPhotoPaths.add(path)
            }
        } else {
            vm.discardEvidence(path)
        }
        capturingPhotoPath = null
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val format = pendingExportFormat
        if (uri != null && format != null) {
            vm.exportProject(uri, format)
        }
        pendingExportFormat = null
    }

    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val data = pendingReportData
        val bitmap = pendingReportBitmap
        if (uri != null && data != null && bitmap != null) {
            vm.generateReport(
                uri = uri,
                data = data,
                mapBitmap = bitmap,
                baseMapMode = baseMapMode,
                currentFix = currentFix,
                visibleLayerNames = layers.filter { it.visible }.map { it.name }
            )
        }
        pendingReportData = null
        pendingReportBitmap = null
    }

    LaunchedEffect(Unit) {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (
            fine == PackageManager.PERMISSION_GRANTED ||
            coarse == PackageManager.PERMISSION_GRANTED
        ) {
            vm.startLiveLocation()
            vm.requestCurrentFix()
        } else {
            val permissions = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= 33) {
                permissions += Manifest.permission.POST_NOTIFICATIONS
            }
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    DisposableEffect(Unit) {
        onDispose { vm.stopLiveLocation() }
    }

    fun withLocationPermission(block: () -> Unit) {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            block()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ForestMark()
                        Column {
                            Text(
                                "GeoCampo",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                "Desarrollada por Reynaldo Ordoñez",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                projects.firstOrNull { it.id == activeProjectId }?.displayName ?: "Sin proyecto",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showProjectDialog = true }) {
                        Icon(Icons.Default.Folder, contentDescription = "Proyecto")
                    }
                    IconButton(onClick = { showBasemapDialog = true }) {
                        Icon(Icons.Default.Map, contentDescription = "Mapa base")
                    }
                    IconButton(onClick = { showLayersDialog = true }) {
                        Icon(Icons.Default.Layers, contentDescription = "Capas")
                    }
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Herramientas")
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Exportar proyecto") },
                                leadingIcon = { Icon(Icons.Default.IosShare, null) },
                                onClick = {
                                    showMoreMenu = false
                                    showExportDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Reporte ejecutivo PDF") },
                                leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) },
                                onClick = {
                                    showMoreMenu = false
                                    showPrintDialog = true
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FileOpen, null)
                        Spacer(Modifier.width(5.dp))
                        Text("Importar")
                    }

                    FilledTonalButton(
                        onClick = {
                            withLocationPermission {
                                vm.requestCurrentFix { coord, accuracy ->
                                    if (coord != null) {
                                        pendingPhotoPaths.forEach(vm::discardEvidence)
                                        pendingPhotoPaths.clear()
                                        capturingPhotoPath = null
                                        pendingWaypoint = coord to accuracy
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.AddLocationAlt, null)
                        Spacer(Modifier.width(5.dp))
                        Text("Punto")
                    }

                    Button(
                        onClick = {
                            withLocationPermission {
                                if (tracking.isTracking) vm.stopTracking() else vm.startTracking()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = if (tracking.isTracking) {
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        } else ButtonDefaults.buttonColors()
                    ) {
                        Icon(if (tracking.isTracking) Icons.Default.Stop else Icons.Default.Route, null)
                        Spacer(Modifier.width(5.dp))
                        Text(if (tracking.isTracking) "Detener" else "Ruta")
                    }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                OfflineMapView(
                    basemap = basemap,
                    layers = layers.filter { it.visible },
                    waypoints = waypoints,
                    trackPoints = trackPoints,
                    currentFix = currentFix,
                    satelliteOffline = satelliteOffline,
                    baseMapMode = baseMapMode,
                    mapTilerApiKey = mapTilerApiKey,
                    centerOnLocationToken = centerOnLocationToken,
                    snapshotBridge = snapshotBridge,
                    modifier = Modifier.fillMaxSize(),
                    onLongPress = { coord ->
                        pendingPhotoPaths.forEach(vm::discardEvidence)
                        pendingPhotoPaths.clear()
                        capturingPhotoPath = null
                        pendingWaypoint = coord to null
                    },
                    onWaypointSelected = { waypoint ->
                        selectedWaypoint = waypoint
                    }
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FloatingActionButton(
                        onClick = {
                            withLocationPermission {
                                vm.requestCurrentFix { coord, _ ->
                                    if (coord != null) {
                                        centerOnLocationToken++
                                    }
                                }
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        Icon(
                            Icons.Default.MyLocation,
                            contentDescription = "Centrar en mi ubicación",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    SmallFloatingActionButton(
                        onClick = { showImageryDialog = true },
                        containerColor =
                            if (
                                baseMapMode == BaseMapMode.SATELLITE_ONLINE ||
                                baseMapMode == BaseMapMode.MAPTILER_HD
                            ) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                    ) {
                        Icon(
                            if (baseMapMode == BaseMapMode.MAPTILER_HD) {
                                Icons.Default.Hd
                            } else {
                                Icons.Default.SatelliteAlt
                            },
                            contentDescription = "Proveedor satelital",
                            tint =
                                if (
                                    baseMapMode == BaseMapMode.SATELLITE_ONLINE ||
                                    baseMapMode == BaseMapMode.MAPTILER_HD
                                ) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                        )
                    }

                    SmallFloatingActionButton(
                        onClick = { showSatelliteOfflineDialog = true },
                        containerColor = if (baseMapMode == BaseMapMode.SATELLITE_OFFLINE) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    ) {
                        Icon(
                            Icons.Default.DownloadForOffline,
                            contentDescription = "Descargar o usar satélite offline",
                            tint = if (satelliteOffline != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }

                if (baseMapMode != BaseMapMode.MAP) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
                    ) {
                        Text(
                            when (baseMapMode) {
                                BaseMapMode.SATELLITE_OFFLINE ->
                                    "Sentinel-2 Cloudless 2025 · OFFLINE · EOX / Copernicus"
                                BaseMapMode.SATELLITE_ONLINE ->
                                    "Sentinel-2 Cloudless 2025 · ONLINE · EOX / Copernicus"
                                BaseMapMode.MAPTILER_HD ->
                                    "MapTiler Satellite Hybrid · HD · ONLINE"
                                BaseMapMode.MAP -> ""
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Surface(tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Metric("Capas", layers.size.toString())
                    Metric("Puntos", waypoints.size.toString())
                    Metric("GPS", currentFix?.accuracy?.let { "±${it.toInt()} m" } ?: "—")
                    Metric("Ruta", if (tracking.isTracking) formatDistance(tracking.distanceMeters) else "Lista")
                }
            }
        }
    }

    if (showProjectDialog) {
        ProjectDialog(
            folders = folders,
            projects = projects,
            activeProjectId = activeProjectId,
            onDismiss = { showProjectDialog = false },
            onSelect = {
                vm.setActiveProject(it)
                showProjectDialog = false
            },
            onCreateFolder = { name -> vm.createFolder(name) },
            onCreate = { name, ejido, municipality, notes, folderId ->
                vm.createProject(name, ejido, municipality, notes, folderId)
                showProjectDialog = false
            }
        )
    }

    if (showBasemapDialog) {
        OfflineRegionDialog(
            basemap = basemap,
            currentFix = currentFix,
            busy = mapDownloadActive,
            status = mapDownloadStatus,
            onDismiss = { showBasemapDialog = false },
            onImport = { basemapImportLauncher.launch(arrayOf("*/*")) },
            onDelete = vm::deleteBasemap,
            onRequestFix = {
                withLocationPermission {
                    vm.requestCurrentFix()
                }
            },
            onExtract = vm::extractOfflineRegion
        )
    }

    if (showImageryDialog) {
        ImageryProviderDialog(
            currentMode = baseMapMode,
            mapTilerApiKey = mapTilerApiKey,
            mapTilerPrintAllowed = mapTilerPrintAllowed,
            validating = mapTilerValidationActive,
            onDismiss = { showImageryDialog = false },
            onUseSentinel = {
                baseMapMode = BaseMapMode.SATELLITE_ONLINE
                showImageryDialog = false
            },
            onUseMapTiler = {
                if (mapTilerApiKey.isNotBlank()) {
                    baseMapMode = BaseMapMode.MAPTILER_HD
                    showImageryDialog = false
                } else {
                    vm.notifyUser("Configura primero una API key válida de MapTiler.")
                }
            },
            onSaveMapTiler = { key, printAllowed ->
                vm.saveMapTilerSettings(key, printAllowed) { ok ->
                    if (ok) {
                        baseMapMode = BaseMapMode.MAPTILER_HD
                        showImageryDialog = false
                    }
                }
            },
            onClearMapTiler = {
                vm.clearMapTilerSettings()
                if (baseMapMode == BaseMapMode.MAPTILER_HD) {
                    baseMapMode = BaseMapMode.MAP
                }
            }
        )
    }

    if (showSatelliteOfflineDialog) {
        SatelliteOfflineDialog(
            satellite = satelliteOffline,
            currentFix = currentFix,
            busy = satelliteDownloadActive,
            progress = satelliteDownloadProgress,
            status = satelliteDownloadStatus,
            downloadedBytes = satelliteDownloadedBytes,
            onDismiss = { showSatelliteOfflineDialog = false },
            onRequestFix = {
                withLocationPermission {
                    vm.requestCurrentFix()
                }
            },
            onDownload = vm::downloadSatelliteRegion,
            onDelete = {
                vm.deleteSatelliteOffline()
                if (baseMapMode == BaseMapMode.SATELLITE_OFFLINE) {
                    baseMapMode = BaseMapMode.MAP
                }
            },
            onUseOffline = {
                if (satelliteOffline != null) {
                    baseMapMode = BaseMapMode.SATELLITE_OFFLINE
                    showSatelliteOfflineDialog = false
                }
            }
        )
    }

    if (showLayersDialog) {
        AlertDialog(
            onDismissRequest = { showLayersDialog = false },
            confirmButton = {
                TextButton(onClick = { showLayersDialog = false }) { Text("Cerrar") }
            },
            title = { Text("Capas") },
            text = {
                if (layers.isEmpty()) {
                    Text("Aún no hay capas importadas.")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(layers, key = { it.id }) { layer ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = layer.visible,
                                    onCheckedChange = { vm.setLayerVisible(layer.id, it) }
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(layer.name)
                                    Text(
                                        "${layer.format} · ${layer.features.size} elementos",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    if (showExportDialog) {
        ExportProjectDialog(
            onDismiss = { showExportDialog = false },
            onExport = { format ->
                pendingExportFormat = format
                showExportDialog = false
                exportLauncher.launch(vm.exportSuggestedName(format))
            }
        )
    }

    if (showPrintDialog) {
        val activeProject = projects.firstOrNull { it.id == activeProjectId }
        PrintReportDialog(
            defaultProjectName = activeProject?.displayName ?: "Proyecto GeoCampo",
            defaultDescription = activeProject?.notes.orEmpty(),
            onDismiss = { showPrintDialog = false },
            onGenerate = { data ->
                if (
                    baseMapMode == BaseMapMode.MAPTILER_HD &&
                    !mapTilerPrintAllowed
                ) {
                    vm.notifyUser(
                        "Tu configuración MapTiler no tiene habilitada la impresión/exportación. " +
                            "Cambia a Sentinel/PMTiles para generar el PDF o activa esta autorización " +
                            "solo si tu plan MapTiler la incluye."
                    )
                } else {
                    showPrintDialog = false
                    snapshotBridge.request { bitmap ->
                        if (bitmap == null) {
                            vm.notifyUser(
                                "No fue posible capturar el mapa. Espera a que termine de cargar e inténtalo de nuevo."
                            )
                        } else {
                            pendingReportData = data
                            pendingReportBitmap = bitmap
                            pdfLauncher.launch(vm.reportSuggestedName())
                        }
                    }
                }
            }
        )
    }

    selectedWaypoint?.let { waypoint ->
        WaypointDetailsDialog(
            waypoint = waypoint,
            onDismiss = { selectedWaypoint = null }
        )
    }

    pendingWaypoint?.let { pair ->
        WaypointEvidenceDialog(
            coord = pair.first,
            accuracy = pair.second,
            photoPaths = pendingPhotoPaths,
            onTakePhoto = {
                val evidence = vm.createEvidencePhoto()
                capturingPhotoPath = evidence.second
                cameraLauncher.launch(evidence.first)
            },
            onRemovePhoto = { path ->
                pendingPhotoPaths.remove(path)
                vm.discardEvidence(path)
            },
            onDismiss = {
                capturingPhotoPath?.let(vm::discardEvidence)
                capturingPhotoPath = null
                pendingPhotoPaths.forEach(vm::discardEvidence)
                pendingPhotoPaths.clear()
                pendingWaypoint = null
            },
            onSave = { title, note, category ->
                vm.saveWaypoint(
                    pair.first,
                    pair.second,
                    title,
                    note,
                    category,
                    pendingPhotoPaths.toList()
                )
                pendingPhotoPaths.clear()
                capturingPhotoPath = null
                pendingWaypoint = null
            }
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = vm::clearMessage,
            confirmButton = {
                TextButton(onClick = vm::clearMessage) { Text("Aceptar") }
            },
            title = { Text("GeoCampo") },
            text = { Text(text) }
        )
    }
}

@Composable
private fun ForestMark(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(42.dp),
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 1.dp
    ) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val dark = Color(0xFF164A38)
            val mid = Color(0xFF2F7D55)
            val light = Color(0xFF74B98B)

            fun tree(cx: Float, top: Float, width: Float, height: Float, color: Color) {
                val crown = Path().apply {
                    moveTo(cx, top)
                    lineTo(cx - width / 2f, top + height * 0.72f)
                    lineTo(cx - width * 0.18f, top + height * 0.72f)
                    lineTo(cx - width * 0.42f, top + height)
                    lineTo(cx + width * 0.42f, top + height)
                    lineTo(cx + width * 0.18f, top + height * 0.72f)
                    lineTo(cx + width / 2f, top + height * 0.72f)
                    close()
                }
                drawPath(crown, color)
                drawRect(
                    color = dark,
                    topLeft = Offset(cx - width * 0.08f, top + height),
                    size = androidx.compose.ui.geometry.Size(width * 0.16f, height * 0.24f)
                )
            }

            tree(size.width * 0.50f, size.height * 0.06f, size.width * 0.46f, size.height * 0.60f, dark)
            tree(size.width * 0.25f, size.height * 0.30f, size.width * 0.34f, size.height * 0.46f, mid)
            tree(size.width * 0.76f, size.height * 0.34f, size.width * 0.32f, size.height * 0.42f, light)

            drawOval(
                color = Color(0xFF1D6E57).copy(alpha = 0.25f),
                topLeft = Offset(size.width * 0.08f, size.height * 0.82f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.84f, size.height * 0.12f)
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private data class Bounds(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
)

@Composable
private fun SimpleFieldMap(
    layers: List<MapLayer>,
    waypoints: List<Waypoint>,
    trackPoints: List<TrackPoint>,
    currentFix: TrackPoint?,
    modifier: Modifier = Modifier
) {
    val coords = remember(layers, waypoints, trackPoints, currentFix) {
        buildList {
            layers.forEach { layer ->
                layer.features.forEach { feature ->
                    when (val g = feature.geometry) {
                        is GeoGeometry.Point -> add(g.p)
                        is GeoGeometry.LineString -> addAll(g.points.filter { it.lat.isFinite() && it.lon.isFinite() })
                        is GeoGeometry.Polygon -> g.rings.forEach { addAll(it.filter { p -> p.lat.isFinite() && p.lon.isFinite() }) }
                    }
                }
            }
            waypoints.forEach { add(GeoCoord(it.lat, it.lon)) }
            trackPoints.forEach { add(GeoCoord(it.lat, it.lon)) }
            currentFix?.let { add(GeoCoord(it.lat, it.lon)) }
        }
    }

    val bounds = remember(coords) {
        if (coords.isEmpty()) null else Bounds(
            coords.minOf { it.lat },
            coords.maxOf { it.lat },
            coords.minOf { it.lon },
            coords.maxOf { it.lon }
        )
    }

    Surface(modifier = modifier, color = Color(0xFFF1F5F1)) {
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                val pad = 28f

                fun project(c: GeoCoord): Offset {
                    val b = bounds ?: return Offset(size.width / 2f, size.height / 2f)
                    val lonSpan = (b.maxLon - b.minLon).takeIf { it > 0.000001 } ?: 0.01
                    val latSpan = (b.maxLat - b.minLat).takeIf { it > 0.000001 } ?: 0.01
                    val x = pad + ((c.lon - b.minLon) / lonSpan).toFloat() * (size.width - 2 * pad)
                    val y = size.height - pad - ((c.lat - b.minLat) / latSpan).toFloat() * (size.height - 2 * pad)
                    return Offset(x, y)
                }

                val palette = listOf(
                    Color(0xFF176B57),
                    Color(0xFF4567A9),
                    Color(0xFF995A20),
                    Color(0xFF765091),
                    Color(0xFF3D7B36)
                )

                layers.forEachIndexed { index, layer ->
                    val color = palette[index % palette.size]
                    layer.features.forEach { feature ->
                        when (val g = feature.geometry) {
                            is GeoGeometry.Point -> drawCircle(color, 6f, project(g.p))
                            is GeoGeometry.LineString -> {
                                val pts = g.points.filter { it.lat.isFinite() && it.lon.isFinite() }
                                if (pts.size > 1) {
                                    val p = Path()
                                    project(pts.first()).let { p.moveTo(it.x, it.y) }
                                    pts.drop(1).forEach { c -> project(c).let { p.lineTo(it.x, it.y) } }
                                    drawPath(p, color, style = Stroke(width = 3f))
                                }
                            }
                            is GeoGeometry.Polygon -> {
                                g.rings.forEach { ring ->
                                    val pts = ring.filter { it.lat.isFinite() && it.lon.isFinite() }
                                    if (pts.size > 2) {
                                        val p = Path()
                                        project(pts.first()).let { p.moveTo(it.x, it.y) }
                                        pts.drop(1).forEach { c -> project(c).let { p.lineTo(it.x, it.y) } }
                                        p.close()
                                        drawPath(p, color.copy(alpha = 0.14f))
                                        drawPath(p, color, style = Stroke(width = 2.5f))
                                    }
                                }
                            }
                        }
                    }
                }

                if (trackPoints.size > 1) {
                    val p = Path()
                    project(GeoCoord(trackPoints.first().lat, trackPoints.first().lon)).let { p.moveTo(it.x, it.y) }
                    trackPoints.drop(1).forEach {
                        project(GeoCoord(it.lat, it.lon)).let { o -> p.lineTo(o.x, o.y) }
                    }
                    drawPath(p, Color(0xFF0B84F3), style = Stroke(width = 5f))
                }

                waypoints.forEach {
                    val o = project(GeoCoord(it.lat, it.lon))
                    drawCircle(Color(0xFFE08A20), 9f, o)
                    drawCircle(Color.White, 3f, o)
                }

                currentFix?.let {
                    val o = project(GeoCoord(it.lat, it.lon))
                    drawCircle(Color(0xFF1677FF).copy(alpha = 0.20f), 22f, o)
                    drawCircle(Color(0xFF1677FF), 8f, o)
                    drawCircle(Color.White, 3f, o)
                }
            }

            if (coords.isEmpty()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Mapa offline", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Importa KML, GPX, GeoJSON o SHP/ZIP, o registra tu primera ubicación.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 4.dp
            ) {
                Text(
                    "Modo campo · sin conexión",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun ProjectDialog(
    folders: List<FieldFolder>,
    projects: List<FieldProject>,
    activeProjectId: Long?,
    onDismiss: () -> Unit,
    onSelect: (Long?) -> Unit,
    onCreateFolder: (String) -> Unit,
    onCreate: (String, String, String, String, Long?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var ejido by remember { mutableStateOf("") }
    var municipality by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var folderName by remember { mutableStateOf("") }
    var selectedFolder by remember { mutableStateOf<Long?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = { onCreate(name, ejido, municipality, notes, selectedFolder) },
                enabled = name.isNotBlank() || ejido.isNotBlank()
            ) { Text("Crear proyecto") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        },
        title = { Text("Carpetas y proyectos") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text("Organiza tus proyectos como carpetas de Google Earth.", style = MaterialTheme.typography.bodySmall)
                }
                item {
                    OutlinedTextField(
                        folderName,
                        { folderName = it },
                        label = { Text("Nueva carpeta") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = {
                                if (folderName.isNotBlank()) {
                                    onCreateFolder(folderName)
                                    folderName = ""
                                }
                            }) {
                                Icon(Icons.Default.CreateNewFolder, "Crear carpeta")
                            }
                        }
                    )
                }
                if (folders.isNotEmpty()) {
                    item { Text("Carpeta para el nuevo proyecto", style = MaterialTheme.typography.labelLarge) }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(
                                selected = selectedFolder == null,
                                onClick = { selectedFolder = null },
                                label = { Text("Sin carpeta") }
                            )
                            folders.forEach { folder ->
                                FilterChip(
                                    selected = selectedFolder == folder.id,
                                    onClick = { selectedFolder = folder.id },
                                    label = { Text("📁 " + folder.name) }
                                )
                            }
                        }
                    }
                }
                if (projects.isNotEmpty()) {
                    item { HorizontalDivider() }
                    item { Text("Proyectos existentes", style = MaterialTheme.typography.labelLarge) }
                    items(projects, key = { it.id }) { project ->
                        val folderNameText = folders.firstOrNull { it.id == project.folderId }?.name
                        FilterChip(
                            selected = project.id == activeProjectId,
                            onClick = { onSelect(project.id) },
                            label = {
                                Column {
                                    Text(project.displayName)
                                    if (folderNameText != null) {
                                        Text("📁 " + folderNameText, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        )
                    }
                    item { HorizontalDivider() }
                }
                item {
                    OutlinedTextField(name, { name = it }, label = { Text("Nombre del proyecto") }, singleLine = true)
                }
                item {
                    OutlinedTextField(ejido, { ejido = it }, label = { Text("Ejido / comunidad") }, singleLine = true)
                }
                item {
                    OutlinedTextField(municipality, { municipality = it }, label = { Text("Municipio") }, singleLine = true)
                }
                item {
                    OutlinedTextField(notes, { notes = it }, label = { Text("Notas") }, minLines = 2)
                }
            }
        }
    )
}

@Composable
private fun WaypointDialog(
    coord: GeoCoord,
    accuracy: Float?,
    photoPath: String?,
    onTakePhoto: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String, WaypointCategory) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(WaypointCategory.OBSERVATION) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = { onSave(title, note, category) }) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
        title = { Text("Georreferenciar punto") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        String.format(Locale.US, "%.6f, %.6f", coord.lat, coord.lon),
                        style = MaterialTheme.typography.labelMedium
                    )
                    if (accuracy != null) {
                        Text("Precisión GPS: ±" + accuracy.toInt() + " m", style = MaterialTheme.typography.labelSmall)
                    }
                }
                item {
                    OutlinedTextField(title, { title = it }, label = { Text("Nombre") }, singleLine = true)
                }
                item {
                    OutlinedTextField(note, { note = it }, label = { Text("Detalles y observaciones") }, minLines = 3)
                }
                item { Text("Icono / categoría", style = MaterialTheme.typography.labelLarge) }
                items(WaypointCategory.entries) { item ->
                    FilterChip(
                        selected = category == item,
                        onClick = { category = item },
                        label = { Text(item.glyph + "  " + item.label) }
                    )
                }
                item {
                    FilledTonalButton(onClick = onTakePhoto) {
                        Icon(Icons.Default.PhotoCamera, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (photoPath == null) "Tomar foto" else "Reemplazar foto")
                    }
                    if (photoPath != null) {
                        Text("✓ Foto lista para guardar", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    )
}

@Composable
private fun BasemapDialog(
    basemap: PmTilesBasemap?,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
    onDownload: (String, String) -> Unit,
    onDelete: () -> Unit
) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        },
        title = { Text("Mapa base offline") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    if (basemap == null) {
                        Text("No hay un mapa base guardado.")
                    } else {
                        Card {
                            Column(Modifier.padding(12.dp)) {
                                Text(basemap.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    (if (basemap.isRaster) "Raster" else "Vectorial") +
                                        " · zoom " + basemap.minZoom + "–" + basemap.maxZoom,
                                    style = MaterialTheme.typography.labelMedium
                                )
                                TextButton(onClick = onDelete) {
                                    Icon(Icons.Default.DeleteOutline, null)
                                    Spacer(Modifier.width(5.dp))
                                    Text("Eliminar mapa")
                                }
                            }
                        }
                    }
                }
                item {
                    Button(onClick = onImport) {
                        Icon(Icons.Default.SdStorage, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Importar archivo PMTiles")
                    }
                }
                item {
                    HorizontalDivider()
                    Text("Descargar PMTiles autorizado", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Pega un enlace HTTPS directo a un archivo .pmtiles. Al terminar, el mapa queda local y puede usarse sin señal.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                item {
                    OutlinedTextField(
                        name,
                        { name = it },
                        label = { Text("Nombre del mapa") },
                        placeholder = { Text("Ej. Xbonil offline") },
                        singleLine = true
                    )
                }
                item {
                    OutlinedTextField(
                        url,
                        { url = it },
                        label = { Text("Enlace HTTPS .pmtiles") },
                        minLines = 2
                    )
                }
                item {
                    Button(
                        onClick = { onDownload(url, if (name.isBlank()) "mapa_offline" else name) },
                        enabled = url.startsWith("https://")
                    ) {
                        Icon(Icons.Default.Download, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Descargar para offline")
                    }
                }
                item {
                    HorizontalDivider()
                    Text("Google Maps / Google Earth", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Las descargas offline de Google permanecen dentro de la app de Google. GeoCampo no copia ni extrae esas teselas; sí puede usar PMTiles y KML/KMZ que tengas autorización para utilizar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}

private fun formatDistance(meters: Double): String =
    if (meters < 1000) "${meters.toInt()} m"
    else String.format(Locale.getDefault(), "%.2f km", meters / 1000.0)
