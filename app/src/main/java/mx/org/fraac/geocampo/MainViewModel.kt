package mx.org.fraac.geocampo

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val context = app.applicationContext
    private val db = TrackDatabase(context)
    private val importer = LayerImporter(context)
    private val exporter = ProjectExporter(context, db)
    private val reportGenerator = GeoReportGenerator(context, db)
    private val regionExtractor = NativePmtilesExtractor(context)
    private val satelliteManager = SatelliteOfflineManager(context)
    private val imagerySettings = ImagerySettings(context)
    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private val prefs = context.getSharedPreferences("geocampo_preferences", 0)

    private var liveLocationStarted = false
    private val liveLocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            _currentFix.value = TrackPoint(
                trackId = -1,
                lat = loc.latitude,
                lon = loc.longitude,
                altitude = if (loc.hasAltitude()) loc.altitude else null,
                accuracy = if (loc.hasAccuracy()) loc.accuracy else null,
                timestamp = System.currentTimeMillis()
            )
        }
    }

    private val _layers = MutableStateFlow<List<MapLayer>>(emptyList())
    val layers = _layers.asStateFlow()

    private val _basemap = MutableStateFlow<PmTilesBasemap?>(null)
    val basemap = _basemap.asStateFlow()

    private val _folders = MutableStateFlow<List<FieldFolder>>(emptyList())
    val folders = _folders.asStateFlow()

    private val _projects = MutableStateFlow<List<FieldProject>>(emptyList())
    val projects = _projects.asStateFlow()

    private val _activeProjectId = MutableStateFlow<Long?>(
        prefs.getLong(KEY_ACTIVE_PROJECT, -1L).takeIf { it > 0 }
    )
    val activeProjectId = _activeProjectId.asStateFlow()

    private val _waypoints = MutableStateFlow<List<Waypoint>>(emptyList())
    val waypoints = _waypoints.asStateFlow()

    private val _tracks = MutableStateFlow<List<TrackSummary>>(emptyList())
    val tracks = _tracks.asStateFlow()

    private val _trackPoints = MutableStateFlow<List<TrackPoint>>(emptyList())
    val trackPoints = _trackPoints.asStateFlow()

    private val _selectedTrackId = MutableStateFlow<Long?>(null)
    val selectedTrackId = _selectedTrackId.asStateFlow()

    private val _currentFix = MutableStateFlow<TrackPoint?>(null)
    val currentFix = _currentFix.asStateFlow()

    private val _navigationTarget = MutableStateFlow<Waypoint?>(null)
    val navigationTarget = _navigationTarget.asStateFlow()

    val trackingState = TrackingBus.state

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    private val _mapDownloadActive = MutableStateFlow(false)
    val mapDownloadActive = _mapDownloadActive.asStateFlow()

    private val _mapDownloadStatus = MutableStateFlow<String?>(null)
    val mapDownloadStatus = _mapDownloadStatus.asStateFlow()

    private val _satelliteOffline = MutableStateFlow<PmTilesBasemap?>(satelliteManager.load())
    val satelliteOffline = _satelliteOffline.asStateFlow()

    private val _satelliteDownloadActive = MutableStateFlow(false)
    val satelliteDownloadActive = _satelliteDownloadActive.asStateFlow()

    private val _satelliteDownloadProgress = MutableStateFlow(0f)
    val satelliteDownloadProgress = _satelliteDownloadProgress.asStateFlow()

    private val _satelliteDownloadStatus = MutableStateFlow<String?>(null)
    val satelliteDownloadStatus = _satelliteDownloadStatus.asStateFlow()

    private val _satelliteDownloadedBytes = MutableStateFlow(0L)
    val satelliteDownloadedBytes = _satelliteDownloadedBytes.asStateFlow()

    private val _mapTilerApiKey = MutableStateFlow(imagerySettings.mapTilerKey())
    val mapTilerApiKey = _mapTilerApiKey.asStateFlow()

    private val _mapTilerPrintAllowed =
        MutableStateFlow(imagerySettings.mapTilerPrintAllowed())
    val mapTilerPrintAllowed = _mapTilerPrintAllowed.asStateFlow()

    private val _mapTilerValidationActive = MutableStateFlow(false)
    val mapTilerValidationActive = _mapTilerValidationActive.asStateFlow()

    init {
        refreshFiles()
        refreshFolders()
        refreshProjects()
        refreshProjectData()

        viewModelScope.launch {
            TrackingBus.points.collect { point ->
                if (TrackingBus.state.value.trackId == point.trackId) {
                    _selectedTrackId.value = point.trackId
                    _trackPoints.value = _trackPoints.value + point
                    _currentFix.value = point
                }
            }
        }

        viewModelScope.launch {
            TrackingBus.state.collect { state ->
                val id = state.trackId
                if (state.isTracking && id != null && _selectedTrackId.value != id) {
                    _selectedTrackId.value = id
                    _trackPoints.value = withContext(Dispatchers.IO) { db.getTrackPoints(id) }
                }
                if (!state.isTracking) refreshTracks()
            }
        }
    }

    fun activeProject(): FieldProject? =
        _projects.value.firstOrNull { it.id == _activeProjectId.value }

    fun importUris(uris: List<Uri>) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { importer.importUris(uris) }
            _layers.value = result.layers
            _basemap.value = result.basemap
            _message.value = result.warnings.takeIf { it.isNotEmpty() }?.joinToString("\n")
                ?: if (result.basemap != null) "Mapa base offline cargado" else "Archivos importados correctamente"
        }
    }

    fun downloadPmTiles(url: String, name: String) {
        if (url.isBlank()) {
            _message.value = "Pega un enlace directo HTTPS a un archivo .pmtiles"
            return
        }
        viewModelScope.launch {
            _message.value = "Descargando mapa base…"
            runCatching {
                withContext(Dispatchers.IO) { importer.downloadPmTiles(url.trim(), name.trim()) }
            }.onSuccess { result ->
                _layers.value = result.layers
                _basemap.value = result.basemap
                _message.value = "Mapa base descargado. Ya puede utilizarse sin conexión."
            }.onFailure {
                _message.value = "No se pudo descargar el mapa: ${it.message ?: "error desconocido"}"
            }
        }
    }

    fun extractOfflineRegion(
        name: String,
        west: Double,
        south: Double,
        east: Double,
        north: Double,
        maxZoom: Int
    ) {
        if (_mapDownloadActive.value) {
            _message.value = "Ya hay una descarga de mapa en curso"
            return
        }

        val request = OfflineRegionRequest(
            name = name,
            west = west,
            south = south,
            east = east,
            north = north,
            maxZoom = maxZoom
        )

        viewModelScope.launch {
            _mapDownloadActive.value = true
            _mapDownloadStatus.value =
                "Preparando " + name + " · zoom 0–" + maxZoom + "…"
            _message.value = null

            runCatching {
                withContext(Dispatchers.IO) {
                    regionExtractor.extract(request)
                    importer.loadAll()
                }
            }.onSuccess { result ->
                _layers.value = result.layers
                _basemap.value = result.basemap
                _mapDownloadStatus.value = null
                _message.value =
                    "Mapa offline listo. Ya puedes apagar datos y Wi‑Fi."
            }.onFailure { error ->
                _mapDownloadStatus.value = null
                _message.value =
                    "No se pudo preparar el mapa offline: " +
                    (error.message ?: "error desconocido")
            }

            _mapDownloadActive.value = false
        }
    }

    fun downloadSatelliteRegion(request: SatelliteRegionRequest) {
        if (_satelliteDownloadActive.value) {
            _message.value = "Ya hay una descarga satelital en curso"
            return
        }

        viewModelScope.launch {
            _satelliteDownloadActive.value = true
            _satelliteDownloadProgress.value = 0f
            _satelliteDownloadedBytes.value = 0L
            _satelliteDownloadStatus.value =
                "Preparando satélite " + request.name + "…"

            runCatching {
                satelliteManager.download(request) { p ->
                    _satelliteDownloadProgress.value =
                        if (p.total <= 0) 0f
                        else p.completed.toFloat() / p.total.toFloat()
                    _satelliteDownloadedBytes.value = p.downloadedBytes
                    _satelliteDownloadStatus.value =
                        "Descargando teselas " + p.completed + " de " + p.total + "…"
                }
            }.onSuccess { satellite ->
                _satelliteOffline.value = satellite
                _satelliteDownloadProgress.value = 1f
                _satelliteDownloadStatus.value = null
                _message.value =
                    "Satélite offline listo. Ya puedes usarlo sin datos ni Wi‑Fi."
            }.onFailure { error ->
                _satelliteDownloadStatus.value = null
                _message.value =
                    "No se pudo descargar el satélite offline: " +
                    (error.message ?: "error desconocido")
            }

            _satelliteDownloadActive.value = false
        }
    }

    fun deleteSatelliteOffline() {
        if (_satelliteDownloadActive.value) {
            _message.value = "Espera a que termine la descarga satelital"
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { satelliteManager.deleteAll() }
            _satelliteOffline.value = null
            _satelliteDownloadProgress.value = 0f
            _satelliteDownloadedBytes.value = 0L
            _message.value = "Capa satelital offline eliminada"
        }
    }

    fun setLayerVisible(id: String, visible: Boolean) {
        _layers.value = _layers.value.map { if (it.id == id) it.copy(visible = visible) else it }
    }

    fun deleteLayer(layer: MapLayer) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { importer.deleteLayer(layer) }
            _layers.value = _layers.value.filterNot { it.id == layer.id }
        }
    }

    fun deleteBasemap() {
        val base = _basemap.value ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { importer.deleteBasemap(base) }
            _basemap.value = null
            _message.value = "Mapa base eliminado del dispositivo"
        }
    }

    fun createFolder(name: String, notes: String = "", parentId: Long? = null) {
        if (name.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.insertFolder(name.trim(), notes.trim(), parentId) }
            refreshFolders()
            _message.value = "Carpeta creada"
        }
    }

    fun deleteFolder(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.deleteFolder(id) }
            refreshFolders()
            refreshProjects()
            _message.value = "Carpeta eliminada; los proyectos se conservaron"
        }
    }

    fun createProject(
        name: String,
        ejido: String,
        municipality: String,
        notes: String,
        folderId: Long? = null
    ) {
        if (name.isBlank() && ejido.isBlank()) {
            _message.value = "Indica al menos el nombre del proyecto o del ejido"
            return
        }
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                db.insertProject(name.ifBlank { ejido }, ejido, municipality, notes, folderId)
            }
            refreshProjects()
            setActiveProject(id)
            _message.value = "Proyecto de campo creado"
        }
    }

    fun setActiveProject(id: Long?) {
        _activeProjectId.value = id
        if (id == null) prefs.edit().remove(KEY_ACTIVE_PROJECT).apply()
        else prefs.edit().putLong(KEY_ACTIVE_PROJECT, id).apply()
        _selectedTrackId.value = null
        _trackPoints.value = emptyList()
        _navigationTarget.value = null
        refreshProjectData()
    }

    fun deleteProject(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.deleteProject(id) }
            if (_activeProjectId.value == id) setActiveProject(null)
            refreshProjects()
            refreshProjectData()
            _message.value = "Proyecto eliminado; sus puntos y recorridos quedaron en 'Sin proyecto'"
        }
    }

    fun startTracking() {
        if (TrackingBus.state.value.isTracking) return
        viewModelScope.launch(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val name = "Recorrido " +
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(started))
            val id = db.startTrack(name, started, _activeProjectId.value)
            withContext(Dispatchers.Main) {
                _trackPoints.value = emptyList()
                _selectedTrackId.value = id
                TrackingBus.start(id, started)
                val intent = Intent(context, TrackRecorderService::class.java)
                    .setAction(TrackRecorderService.ACTION_START)
                    .putExtra(TrackRecorderService.EXTRA_TRACK_ID, id)
                    .putExtra(TrackRecorderService.EXTRA_STARTED_AT, started)
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }

    fun stopTracking() {
        context.startService(
            Intent(context, TrackRecorderService::class.java)
                .setAction(TrackRecorderService.ACTION_STOP)
        )
    }

    fun loadTrack(id: Long) {
        viewModelScope.launch {
            _selectedTrackId.value = id
            _trackPoints.value = withContext(Dispatchers.IO) { db.getTrackPoints(id) }
        }
    }

    fun deleteTrack(id: Long) {
        if (TrackingBus.state.value.trackId == id) {
            _message.value = "Detén el recorrido antes de eliminarlo"
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.deleteTrack(id) }
            if (_selectedTrackId.value == id) {
                _selectedTrackId.value = null
                _trackPoints.value = emptyList()
            }
            refreshTracks()
            _message.value = "Recorrido eliminado"
        }
    }

    fun saveMapTilerSettings(
        apiKey: String,
        printAllowed: Boolean,
        onValidated: (Boolean) -> Unit = {}
    ) {
        if (_mapTilerValidationActive.value) return
        viewModelScope.launch {
            _mapTilerValidationActive.value = true

            val result = imagerySettings.validateMapTilerKey(apiKey.trim())
            result.onSuccess {
                imagerySettings.setMapTilerKey(apiKey)
                imagerySettings.setMapTilerPrintAllowed(printAllowed)
                _mapTilerApiKey.value = apiKey.trim()
                _mapTilerPrintAllowed.value = printAllowed
                _message.value = "MapTiler HD configurado correctamente"
                onValidated(true)
            }.onFailure {
                _message.value =
                    "No se pudo validar MapTiler: " +
                    (it.message ?: "error desconocido")
                onValidated(false)
            }

            _mapTilerValidationActive.value = false
        }
    }

    fun clearMapTilerSettings() {
        imagerySettings.setMapTilerKey("")
        imagerySettings.setMapTilerPrintAllowed(false)
        _mapTilerApiKey.value = ""
        _mapTilerPrintAllowed.value = false
        _message.value = "Configuración de MapTiler eliminada"
    }

    fun startLiveLocation() {
        if (liveLocationStarted) return
        if (
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5_000L
        )
            .setMinUpdateIntervalMillis(2_000L)
            .setMinUpdateDistanceMeters(2f)
            .build()

        fused.requestLocationUpdates(
            request,
            liveLocationCallback,
            Looper.getMainLooper()
        )
        liveLocationStarted = true
    }

    fun stopLiveLocation() {
        if (!liveLocationStarted) return
        fused.removeLocationUpdates(liveLocationCallback)
        liveLocationStarted = false
    }

    fun requestCurrentFix(onResult: (GeoCoord?, Float?) -> Unit = { _, _ -> }) {
        if (
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            onResult(null, null)
            return
        }

        val token = CancellationTokenSource()
        fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
            .addOnSuccessListener { loc: Location? ->
                if (loc == null) {
                    _message.value =
                        "No fue posible obtener una posición GPS. Verifica que la ubicación esté encendida."
                    onResult(null, null)
                } else {
                    val point = TrackPoint(
                        trackId = -1,
                        lat = loc.latitude,
                        lon = loc.longitude,
                        altitude = if (loc.hasAltitude()) loc.altitude else null,
                        accuracy = if (loc.hasAccuracy()) loc.accuracy else null,
                        timestamp = System.currentTimeMillis()
                    )
                    _currentFix.value = point
                    onResult(GeoCoord(point.lat, point.lon, point.altitude), point.accuracy)
                }
            }
            .addOnFailureListener {
                _message.value = "Error de GPS: ${it.message ?: "no disponible"}"
                onResult(null, null)
            }
    }

    fun saveWaypoint(
        coord: GeoCoord,
        accuracy: Float?,
        title: String,
        note: String,
        category: WaypointCategory,
        photoPaths: List<String>
    ) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.insertWaypoint(
                    _activeProjectId.value,
                    title.ifBlank { category.label },
                    note,
                    category,
                    coord,
                    accuracy,
                    photoPaths
                )
            }
            refreshWaypoints()
            _message.value =
                if (photoPaths.isNotEmpty()) {
                    "Punto y ${photoPaths.size} evidencia(s) fotográfica(s) guardados"
                } else {
                    "Ubicación georreferenciada"
                }
        }
    }

    fun deleteWaypoint(id: Long) {
        viewModelScope.launch {
            val photos = _waypoints.value
                .firstOrNull { it.id == id }
                ?.photoPaths
                .orEmpty()
            withContext(Dispatchers.IO) {
                db.deleteWaypoint(id)
                photos.forEach { path -> runCatching { File(path).delete() } }
            }
            if (_navigationTarget.value?.id == id) _navigationTarget.value = null
            refreshWaypoints()
        }
    }

    fun createEvidencePhoto(): Pair<Uri, String> {
        val dir = File(context.filesDir, "evidence").apply { mkdirs() }
        val file = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return uri to file.absolutePath
    }

    fun discardEvidence(path: String?) {
        if (path == null) return
        viewModelScope.launch(Dispatchers.IO) { runCatching { File(path).delete() } }
    }

    fun setNavigationTarget(waypoint: Waypoint?) {
        _navigationTarget.value = waypoint
    }

    fun exportProject(uri: Uri, format: ExportFormat) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    exporter.export(uri, format, _activeProjectId.value)
                }
            }.onSuccess { count ->
                _message.value = "Exportación ${format.label} creada ($count elementos)"
            }.onFailure {
                _message.value = "No se pudo exportar: ${it.message ?: "error desconocido"}"
            }
        }
    }

    fun generateReport(
        uri: Uri,
        data: PrintReportData,
        mapBitmap: Bitmap,
        baseMapMode: BaseMapMode,
        currentFix: TrackPoint?,
        visibleLayerNames: List<String>
    ) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    reportGenerator.generate(
                        uri = uri,
                        data = data,
                        mapBitmap = mapBitmap,
                        projectId = _activeProjectId.value,
                        baseMapMode = baseMapMode,
                        currentFix = currentFix,
                        visibleLayerNames = visibleLayerNames
                    )
                }
            }.onSuccess {
                _message.value = "Reporte PDF ejecutivo generado correctamente"
            }.onFailure {
                _message.value =
                    "No se pudo generar el PDF: " +
                    (it.message ?: "error desconocido")
            }
        }
    }

    fun reportSuggestedName(): String {
        val base = activeProject()?.displayName?.ifBlank { "proyecto" } ?: "proyecto"
        val safe = base.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9áéíóúñ_-]+"), "_")
            .trim('_')
        return "geocampo_reporte_" +
            safe.ifBlank { "proyecto" } + "_" +
            SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) +
            ".pdf"
    }

    fun exportSuggestedName(format: ExportFormat): String {
        val base = activeProject()?.displayName?.ifBlank { "proyecto" } ?: "sin_proyecto"
        val safe = base.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9áéíóúñ_-]+"), "_")
            .trim('_')
        return "geocampo_${safe.ifBlank { "export" }}_" +
            "${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.${format.extension}"
    }

    fun notifyUser(text: String) {
        _message.value = text
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun refreshFiles() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { importer.loadAll() }
            _layers.value = result.layers
            _basemap.value = result.basemap
            if (result.warnings.isNotEmpty()) {
                _message.value = result.warnings.joinToString("\n")
            }
        }
    }

    private fun refreshFolders() {
        viewModelScope.launch {
            _folders.value = withContext(Dispatchers.IO) { db.getFolders() }
        }
    }

    private fun refreshProjects() {
        viewModelScope.launch {
            _projects.value = withContext(Dispatchers.IO) { db.getProjects() }
            if (
                _activeProjectId.value != null &&
                _projects.value.none { it.id == _activeProjectId.value }
            ) {
                setActiveProject(null)
            }
        }
    }

    private fun refreshProjectData() {
        refreshWaypoints()
        refreshTracks()
    }

    private fun refreshWaypoints() {
        viewModelScope.launch {
            _waypoints.value = withContext(Dispatchers.IO) {
                db.getWaypoints(_activeProjectId.value, filterByProject = true)
            }
        }
    }

    private fun refreshTracks() {
        viewModelScope.launch {
            _tracks.value = withContext(Dispatchers.IO) {
                db.getTracks(_activeProjectId.value, filterByProject = true)
            }
        }
    }

    override fun onCleared() {
        stopLiveLocation()
        super.onCleared()
    }

    companion object {
        private const val KEY_ACTIVE_PROJECT = "active_project_id"
    }
}
