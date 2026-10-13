package mx.org.fraac.geocampo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

@Composable
fun SatelliteOfflineDialog(
    satellite: PmTilesBasemap?,
    currentFix: TrackPoint?,
    busy: Boolean,
    progress: Float,
    status: String?,
    downloadedBytes: Long,
    onDismiss: () -> Unit,
    onRequestFix: () -> Unit,
    onDownload: (SatelliteRegionRequest) -> Unit,
    onDelete: () -> Unit,
    onUseOffline: () -> Unit
) {
    var maxZoom by remember { mutableIntStateOf(13) }
    var radiusKm by remember { mutableIntStateOf(10) }

    val campecheRequest = remember(maxZoom) {
        SatelliteRegionRequest(
            name = "Campeche_Sentinel2_2025",
            west = CAMPECHE_WEST,
            south = CAMPECHE_SOUTH,
            east = CAMPECHE_EAST,
            north = CAMPECHE_NORTH,
            maxZoom = min(maxZoom, 13)
        )
    }

    val gpsRequest = remember(currentFix, radiusKm, maxZoom) {
        currentFix?.let {
            val box = bboxAround(it.lat, it.lon, radiusKm.toDouble())
            SatelliteRegionRequest(
                name = "Satelite_GPS_${radiusKm}km",
                west = box.west,
                south = box.south,
                east = box.east,
                north = box.north,
                maxZoom = maxZoom
            )
        }
    }

    val campecheTiles = remember(campecheRequest) { estimateTiles(campecheRequest) }
    val gpsTiles = remember(gpsRequest) { gpsRequest?.let(::estimateTiles) ?: 0 }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("Cerrar")
            }
        },
        title = { Text("Satélite offline") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 620.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        "Descarga Sentinel-2 Cloudless 2025 para usarlo después sin datos ni Wi‑Fi.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        SatelliteOfflineManager.ATTRIBUTION,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (satellite != null) {
                    item {
                        Card {
                            Column(Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Satélite descargado",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            satellite.name,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            "Zoom 0–${satellite.maxZoom}",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                    Icon(
                                        Icons.Default.OfflinePin,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Spacer(Modifier.height(8.dp))

                                Button(
                                    onClick = onUseOffline,
                                    enabled = !busy
                                ) {
                                    Icon(Icons.Default.SatelliteAlt, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Usar satélite offline")
                                }

                                TextButton(
                                    onClick = onDelete,
                                    enabled = !busy
                                ) {
                                    Icon(Icons.Default.DeleteOutline, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Eliminar descarga")
                                }
                            }
                        }
                    }
                }

                if (busy) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp)
                            ) {
                                LinearProgressIndicator(
                                    progress = { progress.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    status ?: "Descargando imagen satelital…",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "Descargado: " + formatBytes(downloadedBytes),
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text(
                                    "Mantén GeoCampo abierto hasta terminar.",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }

                item {
                    Text(
                        "Nivel de detalle",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "Z13 es adecuado para todo Campeche. Z14 ofrece más detalle y se recomienda para áreas pequeñas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(12, 13, 14).forEach { zoom ->
                            FilterChip(
                                selected = maxZoom == zoom,
                                onClick = { maxZoom = zoom },
                                enabled = !busy,
                                label = { Text("Z$zoom") }
                            )
                        }
                    }
                }

                item {
                    HorizontalDivider()
                    Text(
                        "Campeche completo",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        if (maxZoom > 13) {
                            "Para todo Campeche se limitará automáticamente a Z13."
                        } else {
                            "Descarga aproximada: ${formatCount(campecheTiles)} teselas."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = { onDownload(campecheRequest) },
                        enabled = !busy
                    ) {
                        Icon(Icons.Default.DownloadForOffline, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Descargar Campeche satelital")
                    }
                }

                item {
                    HorizontalDivider()
                    Text(
                        "Alrededor de mi ubicación",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "La mejor opción para trabajo en ejidos: descargas menos datos y puedes usar Z14.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        listOf(5, 10, 25, 50).forEach { km ->
                            FilterChip(
                                selected = radiusKm == km,
                                onClick = { radiusKm = km },
                                enabled = !busy,
                                label = { Text("${km} km") }
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    if (currentFix == null) {
                        FilledTonalButton(
                            onClick = onRequestFix,
                            enabled = !busy
                        ) {
                            Icon(Icons.Default.MyLocation, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Obtener mi ubicación")
                        }
                    } else {
                        Text(
                            String.format(
                                Locale.US,
                                "Centro: %.5f, %.5f",
                                currentFix.lat,
                                currentFix.lon
                            ),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            "Estimado: ${formatCount(gpsTiles)} teselas",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = {
                                gpsRequest?.let(onDownload)
                            },
                            enabled = gpsRequest != null && !busy
                        ) {
                            Icon(Icons.Default.DownloadForOffline, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Descargar zona satelital")
                        }
                    }
                }

                item {
                    HorizontalDivider()
                    Text(
                        "Uso de datos y almacenamiento",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "La descarga se realiza una sola vez con internet. GeoCampo empaqueta las teselas JPG en un PMTiles raster local. Después puedes activar “Satélite offline” sin conexión.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "La cantidad final de MB depende del área y del nivel de zoom.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}

private data class SatelliteBox(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double
)

private fun bboxAround(lat: Double, lon: Double, radiusKm: Double): SatelliteBox {
    val latDelta = radiusKm / 111.32
    val lonFactor = 111.32 * cos(Math.toRadians(lat)).coerceAtLeast(0.2)
    val lonDelta = radiusKm / lonFactor

    return SatelliteBox(
        west = (lon - lonDelta).coerceAtLeast(-180.0),
        south = (lat - latDelta).coerceAtLeast(-85.0),
        east = (lon + lonDelta).coerceAtMost(180.0),
        north = (lat + latDelta).coerceAtMost(85.0)
    )
}

private fun estimateTiles(request: SatelliteRegionRequest): Int {
    var total = 0L
    for (z in 0..request.maxZoom) {
        val n = 1 shl z
        fun x(lon: Double): Int =
            floor((lon + 180.0) / 360.0 * n)
                .toInt()
                .coerceIn(0, n - 1)

        fun y(lat: Double): Int {
            val rad = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
            return floor((1.0 - asinh(tan(rad)) / PI) / 2.0 * n)
                .toInt()
                .coerceIn(0, n - 1)
        }

        val x1 = x(request.west)
        val x2 = x(request.east)
        val y1 = y(request.north)
        val y2 = y(request.south)

        total +=
            (max(x1, x2) - min(x1, x2) + 1L) *
                (max(y1, y2) - min(y1, y2) + 1L)
    }
    return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

private fun formatCount(value: Int): String =
    String.format(Locale.US, "%,d", value)

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb < 1024.0) {
        String.format(Locale.US, "%.1f MB", mb)
    } else {
        String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }
}

private const val CAMPECHE_WEST = -92.46879
private const val CAMPECHE_SOUTH = 17.812871
private const val CAMPECHE_EAST = -89.121229
private const val CAMPECHE_NORTH = 20.848327
