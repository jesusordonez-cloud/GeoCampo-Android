package mx.org.fraac.geocampo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipFile

class LayerImporter(private val context: Context) {
    data class LoadResult(
        val layers: List<MapLayer>,
        val basemap: PmTilesBasemap?,
        val warnings: List<String> = emptyList()
    )

    private val layerDir = File(context.filesDir, "layers").apply { mkdirs() }
    private val basemapDir = File(context.filesDir, "basemaps").apply { mkdirs() }

    fun importUris(uris: List<Uri>): LoadResult {
        if (uris.isEmpty()) return loadAll()
        val batch = System.currentTimeMillis().toString()
        val warnings = mutableListOf<String>()
        uris.forEachIndexed { index, uri ->
            val original = displayName(uri) ?: "archivo_$index"
            val safe = sanitize(original)
            val ext = safe.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val targetDir = if (ext == "pmtiles") basemapDir else layerDir
            val target = uniqueFile(targetDir, "${batch}_$safe")
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                } ?: error("No se pudo abrir $original")
            }.onFailure { warnings += "No se pudo importar $original: ${it.message}" }
        }
        val loaded = loadAll()
        return loaded.copy(warnings = loaded.warnings + warnings)
    }

    fun downloadPmTiles(url: String, displayName: String): LoadResult {
        require(url.startsWith("https://")) { "Solo se permiten enlaces HTTPS" }
        val fileName = sanitize(displayName.ifBlank { "mapa_offline" })
            .let { if (it.endsWith(".pmtiles", true)) it else "$it.pmtiles" }
        val target = uniqueFile(basemapDir, "${System.currentTimeMillis()}_$fileName")
        try {
            val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "GeoCampo/0.3 Android")
            }
            connection.connect()
            require(connection.responseCode in 200..299) {
                "El servidor respondió HTTP ${connection.responseCode}"
            }
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            PmTilesParser.read(target)
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        return loadAll()
    }

    fun loadAll(): LoadResult {
        val warnings = mutableListOf<String>()
        val layers = layerDir.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase(Locale.ROOT) in setOf("kml", "kmz", "gpx", "geojson", "json", "shp", "zip") }
            .sortedBy { it.name }
            .mapNotNull { file ->
                runCatching { parseFile(file) }
                    .onFailure { warnings += "${prettyName(file)}: ${it.message ?: "error al leer"}" }
                    .getOrNull()
            }

        val basemap = basemapDir.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("pmtiles", true) }
            .maxByOrNull { it.lastModified() }
            ?.let { file ->
                runCatching { PmTilesParser.read(file) }
                    .onFailure { warnings += "${prettyName(file)}: ${it.message}" }
                    .getOrNull()
            }
        return LoadResult(layers, basemap, warnings)
    }

    fun deleteLayer(layer: MapLayer) {
        val primary = File(layer.sourceFile)
        if (primary.extension.equals("shp", true)) {
            val base = primary.nameWithoutExtension
            primary.parentFile?.listFiles()?.filter { it.nameWithoutExtension == base }?.forEach { it.delete() }
        } else primary.delete()
    }

    fun deleteBasemap(basemap: PmTilesBasemap) {
        File(basemap.path).delete()
    }

    private fun parseFile(file: File): MapLayer {
        val ext = file.extension.lowercase(Locale.ROOT)
        val features = when (ext) {
            "kml" -> KmlParser.parse(file)
            "kmz" -> parseKmz(file)
            "gpx" -> GpxParser.parse(file)
            "geojson", "json" -> GeoJsonParser.parse(file)
            "shp" -> {
                val base = file.nameWithoutExtension
                val prj = file.parentFile?.resolve("$base.prj")?.takeIf { it.exists() }
                val dbf = file.parentFile?.resolve("$base.dbf")?.takeIf { it.exists() }
                ShapefileParser.parse(file, prj, dbf)
            }
            "zip" -> parseZip(file)
            else -> error("Formato no compatible")
        }
        require(features.isNotEmpty()) { "No se encontraron geometrías compatibles" }
        return MapLayer(
            id = file.absolutePath,
            name = prettyName(file),
            sourceFile = file.absolutePath,
            format = ext.uppercase(),
            features = features
        )
    }

    private fun parseKmz(file: File): List<GeoFeature> {
        ZipFile(file).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull { !it.isDirectory && it.name.endsWith(".kml", true) }
                ?: error("El KMZ no contiene KML")
            val temp = File.createTempFile("geocampo_", ".kml", context.cacheDir)
            zip.getInputStream(entry).use { input -> temp.outputStream().use { input.copyTo(it) } }
            return try { KmlParser.parse(temp) } finally { temp.delete() }
        }
    }

    private fun parseZip(file: File): List<GeoFeature> {
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
            entries.firstOrNull { it.name.endsWith(".kml", true) }?.let { entry ->
                val temp = File.createTempFile("geocampo_", ".kml", context.cacheDir)
                zip.getInputStream(entry).use { input -> temp.outputStream().use { input.copyTo(it) } }
                return try { KmlParser.parse(temp) } finally { temp.delete() }
            }
            val shpEntry = entries.firstOrNull { it.name.endsWith(".shp", true) } ?: error("ZIP sin .shp ni .kml")
            val baseName = shpEntry.name.substringAfterLast('/').substringBeforeLast('.')
            fun extract(ext: String): File? {
                val e = entries.firstOrNull {
                    it.name.substringAfterLast('/').substringBeforeLast('.').equals(baseName, true) && it.name.endsWith(".$ext", true)
                } ?: return null
                val t = File.createTempFile("geocampo_", ".$ext", context.cacheDir)
                zip.getInputStream(e).use { input -> t.outputStream().use { input.copyTo(it) } }
                return t
            }
            val shp = extract("shp") ?: error("No se pudo extraer SHP")
            val prj = extract("prj")
            val dbf = extract("dbf")
            return try { ShapefileParser.parse(shp, prj, dbf) }
            finally { listOfNotNull(shp, prj, dbf).forEach { it.delete() } }
        }
    }

    private fun displayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9._áéíóúÁÉÍÓÚñÑ -]"), "_")

    private fun uniqueFile(dir: File, proposed: String): File {
        var file = File(dir, proposed)
        var n = 2
        while (file.exists()) {
            val ext = file.extension
            val base = file.nameWithoutExtension
            file = File(dir, if (ext.isBlank()) "${base}_$n" else "${base}_$n.$ext")
            n++
        }
        return file
    }

    private fun prettyName(file: File): String {
        return file.name.substringAfter('_', file.name).substringBeforeLast('.')
    }
}
