package mx.org.fraac.geocampo

import android.content.Context
import java.io.File
import java.util.Locale

data class OfflineRegionRequest(
    val name: String,
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
    val maxZoom: Int
)

class NativePmtilesExtractor(private val context: Context) {

    fun extract(request: OfflineRegionRequest): File {
        validate(request)

        val binary = File(context.applicationInfo.nativeLibraryDir, "libpmtiles.so")
        require(binary.exists()) {
            "El extractor PMTiles no está disponible para la arquitectura de este dispositivo."
        }

        val dir = File(context.filesDir, "basemaps").apply { mkdirs() }
        val safeName = request.name
            .ifBlank { "mapa_offline" }
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9áéíóúñ_-]+"), "_")
            .trim('_')
            .ifBlank { "mapa_offline" }

        val target = File(
            dir,
            "${System.currentTimeMillis()}_${safeName}.pmtiles"
        )

        val bbox = listOf(
            request.west,
            request.south,
            request.east,
            request.north
        ).joinToString(",")

        val command = listOf(
            binary.absolutePath,
            "extract",
            PLANET_SOURCE,
            target.absolutePath,
            "--bbox=$bbox",
            "--maxzoom=${request.maxZoom}",
            "--download-threads=4",
            "--overfetch=0"
        )

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()

        if (exit != 0) {
            target.delete()
            val detail = output
                .lineSequence()
                .filter { it.isNotBlank() }
                .toList()
                .takeLast(8)
                .joinToString("\n")
                .ifBlank { "Código de salida $exit" }
            error("No se pudo crear el mapa offline. $detail")
        }

        require(target.exists() && target.length() > 127L) {
            target.delete()
            "La extracción terminó sin producir un PMTiles válido."
        }

        PmTilesParser.read(target)
        return target
    }

    private fun validate(request: OfflineRegionRequest) {
        require(request.west in -180.0..180.0) { "Longitud oeste inválida" }
        require(request.east in -180.0..180.0) { "Longitud este inválida" }
        require(request.south in -85.0..85.0) { "Latitud sur inválida" }
        require(request.north in -85.0..85.0) { "Latitud norte inválida" }
        require(request.west < request.east) { "El oeste debe ser menor que el este" }
        require(request.south < request.north) { "El sur debe ser menor que el norte" }
        require(request.maxZoom in 8..15) { "El zoom máximo debe estar entre 8 y 15" }

        val lonSpan = request.east - request.west
        val latSpan = request.north - request.south
        require(lonSpan <= 10.0 && latSpan <= 10.0) {
            "El área seleccionada es demasiado grande para una descarga móvil."
        }
    }

    companion object {
        // Daily Protomaps v4 planet build documented in October 2026.
        const val PLANET_SOURCE =
            "https://build.protomaps.com/20261011.pmtiles"
    }
}
