package mx.org.fraac.geocampo

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun ImageryProviderDialog(
    currentMode: BaseMapMode,
    mapTilerApiKey: String,
    mapTilerPrintAllowed: Boolean,
    validating: Boolean,
    onDismiss: () -> Unit,
    onUseSentinel: () -> Unit,
    onUseMapTiler: () -> Unit,
    onSaveMapTiler: (String, Boolean) -> Unit,
    onClearMapTiler: () -> Unit
) {
    var apiKey by remember(mapTilerApiKey) { mutableStateOf(mapTilerApiKey) }
    var printAllowed by remember(mapTilerPrintAllowed) {
        mutableStateOf(mapTilerPrintAllowed)
    }
    var showKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        },
        title = { Text("Proveedor de imagen satelital") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 620.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor =
                            if (currentMode == BaseMapMode.SATELLITE_ONLINE) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.SatelliteAlt, null)
                            Column {
                                Text(
                                    "Sentinel‑2 Cloudless",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    "Cobertura global abierta. Menor resolución, ideal como respaldo y para descarga offline.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(onClick = onUseSentinel) {
                            Icon(Icons.Default.Cloud, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Usar Sentinel‑2")
                        }
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor =
                            if (currentMode == BaseMapMode.MAPTILER_HD) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Hd, null)
                            Column {
                                Text(
                                    "MapTiler Satellite Hybrid HD",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    "Imagen de mayor resolución con calles y etiquetas. Requiere internet y una API key de MapTiler.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it.trim() },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("API key de MapTiler") },
                            placeholder = { Text("Pega aquí tu API key") },
                            singleLine = true,
                            visualTransformation =
                                if (showKey) {
                                    androidx.compose.ui.text.input.VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                            trailingIcon = {
                                TextButton(onClick = { showKey = !showKey }) {
                                    Text(if (showKey) "Ocultar" else "Ver")
                                }
                            }
                        )

                        Spacer(Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    onSaveMapTiler(apiKey, printAllowed)
                                },
                                enabled = apiKey.isNotBlank() && !validating,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (validating) "Validando…" else "Validar y guardar")
                            }

                            if (mapTilerApiKey.isNotBlank()) {
                                FilledTonalButton(
                                    onClick = onUseMapTiler,
                                    enabled = !validating,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Usar HD")
                                }
                            }
                        }

                        if (mapTilerApiKey.isNotBlank()) {
                            TextButton(
                                onClick = onClearMapTiler,
                                enabled = !validating
                            ) {
                                Icon(Icons.Default.DeleteOutline, null)
                                Spacer(Modifier.width(5.dp))
                                Text("Eliminar configuración")
                            }
                        }

                        HorizontalDivider()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = printAllowed,
                                onCheckedChange = { printAllowed = it }
                            )
                            Column {
                                Text(
                                    "Mi plan permite impresión/exportación",
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Text(
                                    "Actívalo únicamente si tu licencia MapTiler permite usar la imagen en PDF o exportaciones.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Text(
                            "La clave se guarda únicamente en este dispositivo. GeoCampo no incluye ninguna clave de MapTiler dentro del APK.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    )
}
