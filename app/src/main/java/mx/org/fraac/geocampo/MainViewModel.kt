package mx.org.fraac.geocampo

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    private val regionExtractor = NativePmtilesExtractor(context)
    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private val prefs = context.getSharedPreferences("geocampo_preferences", 0)

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
        photoPath: String?
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
                    photoPath
                )
            }
            refreshWaypoints()
            _message.value =
                if (photoPath != null) "Punto y evidencia fotográfica guardados"
                else "Ubicación georreferenciada"
        }
    }

    fun deleteWaypoint(id: Long) {
        viewModelScope.launch {
            val photo = _waypoints.value.firstOrNull { it.id == id }?.photoPath
            withContext(Dispatchers.IO) {
                db.deleteWaypoint(id)
                photo?.let { runCatching { File(it).delete() } }
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

    fun exportSuggestedName(format: ExportFormat): String {
        val base = activeProject()?.displayName?.ifBlank { "proyecto" } ?: "sin_proyecto"
        val safe = base.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9áéíóúñ_-]+"), "_")
            .trim('_')
        return "geocampo_${safe.ifBlank { "export" }}_" +
            "${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.${format.extension}"
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

    companion object {
        private const val KEY_ACTIVE_PROJECT = "active_project_id"
    }
}
