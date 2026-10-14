package mx.org.fraac.geocampo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WaypointEvidenceDialog(
    coord: GeoCoord,
    accuracy: Float?,
    photoPaths: List<String>,
    onTakePhoto: () -> Unit,
    onRemovePhoto: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String, WaypointCategory) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(WaypointCategory.OBSERVATION) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = { onSave(title, note, category) }) {
                Text("Guardar punto")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
        title = { Text("Georreferenciar punto") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 590.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                item {
                    Text(
                        String.format(Locale.US, "%.6f, %.6f", coord.lat, coord.lon),
                        style = MaterialTheme.typography.labelMedium
                    )
                    accuracy?.let {
                        Text(
                            "Precisión GPS: ±${it.toInt()} m",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                item {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Nombre / identificación") },
                        singleLine = true
                    )
                }

                item {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Descripción y observaciones") },
                        minLines = 3
                    )
                }

                item {
                    Text("Icono / categoría", style = MaterialTheme.typography.labelLarge)
                }

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
                        Text(
                            if (photoPaths.isEmpty()) "Agregar fotografía"
                            else "Agregar otra fotografía"
                        )
                    }
                    if (photoPaths.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Evidencias: " + photoPaths.size,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (photoPaths.isNotEmpty()) {
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(photoPaths, key = { it }) { path ->
                                Card(modifier = Modifier.width(150.dp)) {
                                    Column {
                                        LocalPhoto(
                                            path = path,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(105.dp)
                                        )
                                        TextButton(
                                            onClick = { onRemovePhoto(path) },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Default.DeleteOutline, null)
                                            Spacer(Modifier.width(4.dp))
                                            Text("Quitar")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
fun WaypointDetailsDialog(
    waypoint: Waypoint,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        },
        title = {
            Column {
                Text(waypoint.title)
                Text(
                    waypoint.category.glyph + "  " + waypoint.category.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 620.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    InformationCard("Descripción", waypoint.note.ifBlank { "Sin descripción" })
                }

                item {
                    InformationCard(
                        "Coordenadas",
                        String.format(Locale.US, "%.7f, %.7f", waypoint.lat, waypoint.lon)
                    )
                }

                item {
                    val details = buildList {
                        waypoint.altitude?.let {
                            add(String.format(Locale.US, "Altitud: %.1f m", it))
                        }
                        waypoint.accuracy?.let {
                            add("Precisión GPS: ±" + it.toInt() + " m")
                        }
                        add("Fecha: " + DETAIL_DATE.format(Date(waypoint.timestamp)))
                    }.joinToString("\n")
                    InformationCard("Información de campo", details)
                }

                if (waypoint.photoPaths.isNotEmpty()) {
                    item {
                        Text(
                            "Evidencias fotográficas (" + waypoint.photoPaths.size + ")",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }

                    items(waypoint.photoPaths, key = { it }) { path ->
                        Card {
                            LocalPhoto(
                                path = path,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 180.dp, max = 360.dp)
                            )
                        }
                    }
                } else {
                    item {
                        Text(
                            "Este punto no tiene fotografías asociadas.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun InformationCard(title: String, value: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(Modifier.padding(11.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(3.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LocalPhoto(
    path: String,
    modifier: Modifier = Modifier
) {
    val bitmap = remember(path) { decodeScaledBitmap(path, 1600) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Evidencia fotográfica",
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Surface(modifier = modifier) {
            Box(Modifier.fillMaxSize().padding(12.dp)) {
                Text(
                    "Fotografía no disponible",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun decodeScaledBitmap(path: String, maxSide: Int): Bitmap? {
    val file = File(path)
    if (!file.exists()) return null

    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (
        bounds.outWidth / sample > maxSide * 2 ||
        bounds.outHeight / sample > maxSide * 2
    ) {
        sample *= 2
    }

    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeFile(path, options)
}

private val DETAIL_DATE =
    SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
