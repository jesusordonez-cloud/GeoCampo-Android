package mx.org.fraac.geocampo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class PrintReportData(
    val title: String,
    val projectName: String,
    val preparedBy: String,
    val description: String,
    val observations: String
)

@Composable
fun ExportProjectDialog(
    onDismiss: () -> Unit,
    onExport: (ExportFormat) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        },
        title = { Text("Exportar proyecto") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                item {
                    Text(
                        "Elige el formato. Para conservar fotografías dentro de un solo archivo, usa KMZ o Shapefile + evidencias.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                item {
                    ExportOption(
                        title = "KMZ + evidencias",
                        subtitle = "Google Earth: puntos, recorridos, descripciones y fotografías dentro del archivo.",
                        icon = Icons.Default.Map,
                        onClick = { onExport(ExportFormat.KMZ) }
                    )
                }

                item {
                    ExportOption(
                        title = "Shapefile + evidencias",
                        subtitle = "ZIP con SHP/SHX/DBF/PRJ de puntos y recorridos, más carpeta de fotografías.",
                        icon = Icons.Default.FolderZip,
                        onClick = { onExport(ExportFormat.SHAPE_ZIP) }
                    )
                }

                item {
                    ExportOption(
                        title = "KML",
                        subtitle = "Archivo KML ligero para puntos y recorridos. Las fotografías no se incrustan.",
                        icon = Icons.Default.Description,
                        onClick = { onExport(ExportFormat.KML) }
                    )
                }
            }
        }
    )
}

@Composable
private fun ExportOption(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Card(onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun PrintReportDialog(
    defaultProjectName: String,
    defaultDescription: String,
    onDismiss: () -> Unit,
    onGenerate: (PrintReportData) -> Unit
) {
    var title by remember { mutableStateOf("Reporte de georreferenciación") }
    var projectName by remember { mutableStateOf(defaultProjectName) }
    var preparedBy by remember { mutableStateOf("") }
    var description by remember { mutableStateOf(defaultDescription) }
    var observations by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    onGenerate(
                        PrintReportData(
                            title = title.ifBlank { "Reporte de georreferenciación" },
                            projectName = projectName.ifBlank { "Proyecto GeoCampo" },
                            preparedBy = preparedBy.ifBlank { "No especificado" },
                            description = description,
                            observations = observations
                        )
                    )
                }
            ) {
                Icon(Icons.Default.PictureAsPdf, null)
                Spacer(Modifier.width(6.dp))
                Text("Generar PDF")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
        title = { Text("Reporte ejecutivo PDF") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 600.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        "El mapa del PDF corresponderá al encuadre que está visible en GeoCampo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item {
                    OutlinedTextField(
                        title,
                        { title = it },
                        label = { Text("Título del documento") },
                        singleLine = true
                    )
                }
                item {
                    OutlinedTextField(
                        projectName,
                        { projectName = it },
                        label = { Text("Nombre del proyecto") },
                        singleLine = true
                    )
                }
                item {
                    OutlinedTextField(
                        preparedBy,
                        { preparedBy = it },
                        label = { Text("Elaboró") },
                        placeholder = { Text("Nombre y cargo") },
                        singleLine = true
                    )
                }
                item {
                    OutlinedTextField(
                        description,
                        { description = it },
                        label = { Text("Breve descripción") },
                        minLines = 3
                    )
                }
                item {
                    OutlinedTextField(
                        observations,
                        { observations = it },
                        label = { Text("Observaciones / notas ejecutivas") },
                        minLines = 3
                    )
                }
                item {
                    Text(
                        "La fecha de impresión y la simbología se agregarán automáticamente.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}
