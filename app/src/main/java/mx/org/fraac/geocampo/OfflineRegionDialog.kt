package mx.org.fraac.geocampo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.cos

@Composable
fun OfflineRegionDialog(
    basemap: PmTilesBasemap?,
    currentFix: TrackPoint?,
    busy: Boolean,
    status: String?,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
    onDelete: () -> Unit,
    onRequestFix: () -> Unit,
    onExtract: (
        name: String,
        west: Double,
        south: Double,
        east: Double,
        north: Double,
        maxZoom: Int
    ) -> Unit
) {
    var maxZoom by remember { mutableIntStateOf(13) }
    var radiusKm by remember { mutableIntStateOf(10) }

    var customName by remember { mutableStateOf("Área de campo") }
    var west by remember { mutableStateOf("") }
    var south by remember { mutableStateOf("") }
    var east by remember { mutableStateOf("") }
    var north by remember { mutableStateOf("") }

    val parsedWest = west.toDoubleOrNull()
    val parsedSouth = south.toDoubleOrNull()
    val parsedEast = east.toDoubleOrNull()
    val parsedNorth = north.toDoubleOrNull()
    val customValid =
        parsedWest != null &&
        parsedSouth != null &&
        parsedEast != null &&
        parsedNorth != null &&
        parsedWest < parsedEast &&
        parsedSouth < parsedNorth

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !busy
            ) {
                Text("Cerrar")
            }
        },
        title = { Text("Mapas offline") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 620.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    if (basemap == null) {
                        Text(
                            "Aún no hay un mapa base guardado.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Card {
                            Column(Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            basemap.name,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            (if (basemap.isRaster) "Raster" else "Vectorial") +
                                                " · zoom " + basemap.minZoom + "–" + basemap.maxZoom,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        Icons.Default.OfflinePin,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }

                                TextButton(
                                    onClick = onDelete,
                                    enabled = !busy
                                ) {
                                    Icon(Icons.Default.DeleteOutline, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Eliminar mapa actual")
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
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    status ?: "Descargando y preparando mapa…",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "Mantén GeoCampo abierto hasta que termine.",
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
                        "Para todo Campeche recomiendo 13. Para una zona de trabajo pequeña puedes usar 15.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(12, 13, 14, 15).forEach { zoom ->
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
                        "Descarga un recorte vectorial del estado para usarlo posteriormente sin conexión.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = {
                            onExtract(
                                "Campeche",
                                CAMPECHE_WEST,
                                CAMPECHE_SOUTH,
                                CAMPECHE_EAST,
                                CAMPECHE_NORTH,
                                maxZoom
                            )
                        },
                        enabled = !busy
                    ) {
                        Icon(Icons.Default.DownloadForOffline, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Descargar Campeche")
                    }
                }

                item {
                    HorizontalDivider()
                    Text(
                        "Área alrededor de mi GPS",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "Útil para descargar sólo la zona del ejido o recorrido en el que estás trabajando.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Spacer(Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        listOf(5, 10, 25, 50).forEach { km ->
                            FilterChip(
                                selected = radiusKm == km,
                                onClick = { radiusKm = km },
                                enabled = !busy,
                                label = { Text(km.toString() + " km") }
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
                        currentFix.accuracy?.let {
                            Text(
                                "Precisión aproximada: ±" + it.toInt() + " m",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }

                        Spacer(Modifier.height(6.dp))

                        Button(
                            onClick = {
                                val box = bboxAround(
                                    currentFix.lat,
                                    currentFix.lon,
                                    radiusKm.toDouble()
                                )
                                onExtract(
                                    "Zona_GPS_" + radiusKm + "km",
                                    box.west,
                                    box.south,
                                    box.east,
                                    box.north,
                                    maxZoom
                                )
                            },
                            enabled = !busy
                        ) {
                            Icon(Icons.Default.Download, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Descargar zona de " + radiusKm + " km")
                        }
                    }
                }

                item {
                    HorizontalDivider()
                    Text(
                        "Área personalizada",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "Puedes definir un rectángulo por coordenadas WGS84. " +
                            "Formato: oeste, sur, este y norte.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                item {
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("Nombre del mapa") },
                        singleLine = true,
                        enabled = !busy
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = west,
                            onValueChange = { west = it },
                            label = { Text("Oeste") },
                            placeholder = { Text("-90.8") },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = east,
                            onValueChange = { east = it },
                            label = { Text("Este") },
                            placeholder = { Text("-89.8") },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = south,
                            onValueChange = { south = it },
                            label = { Text("Sur") },
                            placeholder = { Text("18.0") },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = north,
                            onValueChange = { north = it },
                            label = { Text("Norte") },
                            placeholder = { Text("19.0") },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item {
                    Button(
                        onClick = {
                            onExtract(
                                customName.ifBlank { "Área personalizada" },
                                parsedWest!!,
                                parsedSouth!!,
                                parsedEast!!,
                                parsedNorth!!,
                                maxZoom
                            )
                        },
                        enabled = customValid && !busy
                    ) {
                        Icon(Icons.Default.CropFree, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Descargar esta área")
                    }
                }

                item {
                    HorizontalDivider()
                    FilledTonalButton(
                        onClick = onImport,
                        enabled = !busy
                    ) {
                        Icon(Icons.Default.SdStorage, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Importar PMTiles existente")
                    }
                }

                item {
                    Text(
                        "Fuente cartográfica",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "GeoCampo utiliza un recorte de Protomaps/OpenStreetMap. " +
                            "El archivo descargado queda dentro del teléfono. " +
                            "No extrae ni copia las descargas offline de Google Maps o Google Earth.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}

private data class RegionBox(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double
)

private fun bboxAround(lat: Double, lon: Double, radiusKm: Double): RegionBox {
    val latDelta = radiusKm / 111.32
    val lonFactor = 111.32 * cos(Math.toRadians(lat)).coerceAtLeast(0.2)
    val lonDelta = radiusKm / lonFactor

    return RegionBox(
        west = (lon - lonDelta).coerceAtLeast(-180.0),
        south = (lat - latDelta).coerceAtLeast(-85.0),
        east = (lon + lonDelta).coerceAtMost(180.0),
        north = (lat + latDelta).coerceAtMost(85.0)
    )
}

private const val CAMPECHE_WEST = -92.46879
private const val CAMPECHE_SOUTH = 17.812871
private const val CAMPECHE_EAST = -89.121229
private const val CAMPECHE_NORTH = 20.848327
