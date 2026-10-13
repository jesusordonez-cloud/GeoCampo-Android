package mx.org.fraac.geocampo

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Stop
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
    val projects by vm.projects.collectAsState()
    val activeProjectId by vm.activeProjectId.collectAsState()
    val waypoints by vm.waypoints.collectAsState()
    val trackPoints by vm.trackPoints.collectAsState()
    val currentFix by vm.currentFix.collectAsState()
    val tracking by vm.trackingState.collectAsState()
    val message by vm.message.collectAsState()

    var showProjectDialog by remember { mutableStateOf(false) }
    var showLayersDialog by remember { mutableStateOf(false) }
    var pendingWaypoint by remember { mutableStateOf<Pair<GeoCoord, Float?>?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) vm.importUris(uris)
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        permissionLauncher.launch(permissions.toTypedArray())
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
                    Column {
                        Text("GeoCampo")
                        Text(
                            projects.firstOrNull { it.id == activeProjectId }?.displayName ?: "Sin proyecto",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showProjectDialog = true }) {
                        Icon(Icons.Default.Folder, contentDescription = "Proyecto")
                    }
                    IconButton(onClick = { showLayersDialog = true }) {
                        Icon(Icons.Default.Layers, contentDescription = "Capas")
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
                                    if (coord != null) pendingWaypoint = coord to accuracy
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
            SimpleFieldMap(
                layers = layers.filter { it.visible },
                waypoints = waypoints,
                trackPoints = trackPoints,
                currentFix = currentFix,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

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
            projects = projects,
            activeProjectId = activeProjectId,
            onDismiss = { showProjectDialog = false },
            onSelect = {
                vm.setActiveProject(it)
                showProjectDialog = false
            },
            onCreate = { name, ejido, municipality, notes ->
                vm.createProject(name, ejido, municipality, notes)
                showProjectDialog = false
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

    pendingWaypoint?.let { pair ->
        WaypointDialog(
            onDismiss = { pendingWaypoint = null },
            onSave = { title, note, category ->
                vm.saveWaypoint(pair.first, pair.second, title, note, category, null)
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
    projects: List<FieldProject>,
    activeProjectId: Long?,
    onDismiss: () -> Unit,
    onSelect: (Long?) -> Unit,
    onCreate: (String, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var ejido by remember { mutableStateOf("") }
    var municipality by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = { onCreate(name, ejido, municipality, notes) },
                enabled = name.isNotBlank() || ejido.isNotBlank()
            ) { Text("Crear") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        },
        title = { Text("Proyecto / ejido") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (projects.isNotEmpty()) {
                    Text("Proyectos existentes", style = MaterialTheme.typography.labelLarge)
                    projects.take(5).forEach { project ->
                        FilterChip(
                            selected = project.id == activeProjectId,
                            onClick = { onSelect(project.id) },
                            label = { Text(project.displayName) }
                        )
                    }
                    HorizontalDivider()
                }
                OutlinedTextField(name, { name = it }, label = { Text("Nombre del proyecto") }, singleLine = true)
                OutlinedTextField(ejido, { ejido = it }, label = { Text("Ejido / comunidad") }, singleLine = true)
                OutlinedTextField(municipality, { municipality = it }, label = { Text("Municipio") }, singleLine = true)
                OutlinedTextField(notes, { notes = it }, label = { Text("Notas") }, minLines = 2)
            }
        }
    )
}

@Composable
private fun WaypointDialog(
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Nombre") }, singleLine = true)
                OutlinedTextField(note, { note = it }, label = { Text("Observaciones") }, minLines = 2)
                Text("Categoría", style = MaterialTheme.typography.labelLarge)
                LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
                    items(WaypointCategory.entries) { item ->
                        FilterChip(
                            selected = category == item,
                            onClick = { category = item },
                            label = { Text("${item.glyph}  ${item.label}") }
                        )
                    }
                }
            }
        }
    )
}

private fun formatDistance(meters: Double): String =
    if (meters < 1000) "${meters.toInt()} m"
    else String.format(Locale.getDefault(), "%.2f km", meters / 1000.0)
