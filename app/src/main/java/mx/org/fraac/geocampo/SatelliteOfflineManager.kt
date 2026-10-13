package mx.org.fraac.geocampo

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

data class SatelliteRegionRequest(
    val name: String,
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
    val maxZoom: Int
)

data class SatelliteDownloadProgress(
    val completed: Int,
    val total: Int,
    val downloadedBytes: Long
)

class SatelliteOfflineManager(private val context: Context) {
    private val dir = File(context.filesDir, "satellite_offline").apply { mkdirs() }

    fun load(): PmTilesBasemap? =
        dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("pmtiles", true) }
            ?.maxByOrNull { it.lastModified() }
            ?.let { runCatching { PmTilesParser.read(it) }.getOrNull() }

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    suspend fun download(
        request: SatelliteRegionRequest,
        onProgress: (SatelliteDownloadProgress) -> Unit
    ): PmTilesBasemap = withContext(Dispatchers.IO) {
        validate(request)

        val sourceYear = resolveSourceYear(request)
        val tiles = enumerateTiles(request)

        require(tiles.isNotEmpty()) {
            "No se encontraron teselas para el área seleccionada."
        }
        require(tiles.size <= MAX_TILE_COUNT) {
            "El área generaría ${tiles.size} teselas. Reduce el área o el nivel de zoom."
        }

        val safe = request.name
            .ifBlank { "satelite_offline" }
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9áéíóúñ_-]+"), "_")
            .trim('_')
            .ifBlank { "satelite_offline" }

        val stamp = System.currentTimeMillis()
        val mbtiles = File(dir, "tmp_${stamp}.mbtiles")
        val target = File(dir, "${stamp}_${safe}.pmtiles")

        val db = SQLiteDatabase.openOrCreateDatabase(mbtiles, null)
        try {
            createMbtilesSchema(db)
            insertMetadata(db, request, sourceYear)

            val semaphore = Semaphore(4)
            var completed = 0
            var bytes = 0L
            var failures = 0

            for (batch in tiles.chunked(48)) {
                val results = coroutineScope {
                    batch.map { tile ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                tile to downloadTile(tile, sourceYear)
                            }
                        }
                    }.awaitAll()
                }

                db.beginTransaction()
                try {
                    for ((tile, data) in results) {
                        completed++
                        if (data == null) {
                            failures++
                            continue
                        }
                        bytes += data.size
                        insertTile(db, tile, data)
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }

                onProgress(
                    SatelliteDownloadProgress(
                        completed = completed,
                        total = tiles.size,
                        downloadedBytes = bytes
                    )
                )
            }

            require(failures <= max(12, tiles.size / 8)) {
                "Fallaron demasiadas teselas ($failures de ${tiles.size}). Verifica tu conexión e inténtalo de nuevo."
            }

            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles(zoom_level, tile_column, tile_row)"
            )
        } finally {
            db.close()
        }

        val binary = File(context.applicationInfo.nativeLibraryDir, "libpmtiles.so")
        require(binary.exists()) {
            mbtiles.delete()
            "No se encontró el convertidor PMTiles en este dispositivo."
        }

        convertMbtiles(binary, mbtiles, target)
        mbtiles.delete()

        require(target.exists() && target.length() > 127L) {
            target.delete()
            "La conversión terminó sin producir un PMTiles válido."
        }

        verifyPmtiles(binary, target)

        val parsed = PmTilesParser.read(target)
        require(parsed.isRaster) {
            target.delete()
            "El archivo satelital generado no fue reconocido como raster."
        }

        dir.listFiles()
            ?.filter {
                it.isFile &&
                    it.extension.equals("pmtiles", true) &&
                    it.absolutePath != target.absolutePath
            }
            ?.forEach { it.delete() }

        parsed
    }

    fun estimateTileCount(request: SatelliteRegionRequest): Int =
        runCatching {
            validate(request)
            enumerateTiles(request).size
        }.getOrDefault(0)

    private fun validate(request: SatelliteRegionRequest) {
        require(request.west in -180.0..180.0) { "Longitud oeste inválida" }
        require(request.east in -180.0..180.0) { "Longitud este inválida" }
        require(request.south in -85.0..85.0) { "Latitud sur inválida" }
        require(request.north in -85.0..85.0) { "Latitud norte inválida" }
        require(request.west < request.east) { "El oeste debe ser menor que el este" }
        require(request.south < request.north) { "El sur debe ser menor que el norte" }
        require(request.maxZoom in 8..14) { "El zoom satelital debe estar entre 8 y 14" }
        require(request.east - request.west <= 10.0 && request.north - request.south <= 10.0) {
            "El área seleccionada es demasiado grande."
        }
    }

    private data class Tile(val z: Int, val x: Int, val y: Int)

    private fun enumerateTiles(request: SatelliteRegionRequest): List<Tile> {
        val result = ArrayList<Tile>()
        for (z in 0..request.maxZoom) {
            val westX = lonToTileX(request.west, z)
            val eastX = lonToTileX(request.east, z)
            val northY = latToTileY(request.north, z)
            val southY = latToTileY(request.south, z)

            val xMin = min(westX, eastX)
            val xMax = max(westX, eastX)
            val yMin = min(northY, southY)
            val yMax = max(northY, southY)

            for (x in xMin..xMax) {
                for (y in yMin..yMax) {
                    result += Tile(z, x, y)
                    if (result.size > MAX_TILE_COUNT) return result
                }
            }
        }
        return result
    }

    private fun lonToTileX(lon: Double, z: Int): Int {
        val n = 1 shl z
        return floor((lon + 180.0) / 360.0 * n)
            .toInt()
            .coerceIn(0, n - 1)
    }

    private fun latToTileY(lat: Double, z: Int): Int {
        val n = 1 shl z
        val clamped = lat.coerceIn(-85.05112878, 85.05112878)
        val rad = Math.toRadians(clamped)
        val y = (1.0 - asinh(tan(rad)) / PI) / 2.0 * n
        return floor(y).toInt().coerceIn(0, n - 1)
    }

    private fun createMbtilesSchema(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE metadata (name TEXT, value TEXT)")
        db.execSQL(
            """CREATE TABLE tiles(
                zoom_level INTEGER,
                tile_column INTEGER,
                tile_row INTEGER,
                tile_data BLOB
            )""".trimIndent()
        )
    }

    private fun insertMetadata(
        db: SQLiteDatabase,
        request: SatelliteRegionRequest,
        sourceYear: Int
    ) {
        val centerLon = (request.west + request.east) / 2.0
        val centerLat = (request.south + request.north) / 2.0
        val values = linkedMapOf(
            "name" to request.name,
            "type" to "baselayer",
            "version" to "1.0",
            "description" to "GeoCampo Sentinel-2 Cloudless ${sourceYear} offline",
            "format" to "jpg",
            "bounds" to String.format(
                Locale.US,
                "%.7f,%.7f,%.7f,%.7f",
                request.west,
                request.south,
                request.east,
                request.north
            ),
            "center" to String.format(
                Locale.US,
                "%.7f,%.7f,%d",
                centerLon,
                centerLat,
                min(request.maxZoom, 13)
            ),
            "minzoom" to "0",
            "maxzoom" to request.maxZoom.toString(),
            "attribution" to attributionFor(sourceYear)
        )

        values.forEach { (key, value) ->
            db.insertOrThrow(
                "metadata",
                null,
                ContentValues().apply {
                    put("name", key)
                    put("value", value)
                }
            )
        }
    }

    private fun insertTile(db: SQLiteDatabase, tile: Tile, data: ByteArray) {
        val tmsRow = (1 shl tile.z) - 1 - tile.y
        db.insertOrThrow(
            "tiles",
            null,
            ContentValues().apply {
                put("zoom_level", tile.z)
                put("tile_column", tile.x)
                put("tile_row", tmsRow)
                put("tile_data", data)
            }
        )
    }

    private fun downloadTile(tile: Tile, year: Int): ByteArray? {
        val url = tileUrl(tile, year)
        var lastError: Exception? = null

        repeat(3) { attempt ->
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 35_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "GeoCampo/0.4.5 Android")
                    setRequestProperty("Accept", "image/jpeg,image/*")
                }

                try {
                    val code = connection.responseCode
                    if (code == 404 || code == 204) return null

                    if (code == 429 || code >= 500) {
                        Thread.sleep(600L * (attempt + 1))
                        return@repeat
                    }

                    require(code in 200..299) { "HTTP $code" }
                    return connection.inputStream.use { it.readBytes() }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt < 2) {
                    Thread.sleep(500L * (attempt + 1))
                }
            }
        }

        throw lastError ?: IllegalStateException("No se pudo descargar la tesela")
    }

    private fun resolveSourceYear(request: SatelliteRegionRequest): Int {
        val centerLon = (request.west + request.east) / 2.0
        val centerLat = (request.south + request.north) / 2.0
        val z = min(request.maxZoom, 10)
        val tile = Tile(
            z = z,
            x = lonToTileX(centerLon, z),
            y = latToTileY(centerLat, z)
        )

        for (year in listOf(2025, 2024)) {
            try {
                val connection = (URL(tileUrl(tile, year)).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 15_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "GeoCampo/0.4.5 Android")
                    setRequestProperty("Accept", "image/jpeg,image/*")
                    setRequestProperty("Range", "bytes=0-1023")
                }

                try {
                    val code = connection.responseCode
                    if (code in 200..299 || code == 206) {
                        connection.inputStream.use { input ->
                            val probe = ByteArray(32)
                            if (input.read(probe) > 0) return year
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                // Try the previous published mosaic.
            }
        }

        error(
            "No fue posible acceder a la fuente satelital EOX. " +
                "Verifica tu conexión a internet antes de iniciar la descarga."
        )
    }

    private fun convertMbtiles(binary: File, mbtiles: File, target: File) {
        val process = ProcessBuilder(
            binary.absolutePath,
            "convert",
            mbtiles.absolutePath,
            target.absolutePath
        )
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()

        if (exit != 0) {
            target.delete()
            error(
                "No se pudo crear el archivo satelital offline. " +
                    output.lineSequence()
                        .filter { it.isNotBlank() }
                        .toList()
                        .takeLast(8)
                        .joinToString(" ")
            )
        }
    }

    private fun verifyPmtiles(binary: File, target: File) {
        val process = ProcessBuilder(
            binary.absolutePath,
            "verify",
            target.absolutePath
        )
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()

        if (exit != 0) {
            target.delete()
            error(
                "El archivo satelital se generó, pero no pasó la verificación PMTiles. " +
                    output.lineSequence()
                        .filter { it.isNotBlank() }
                        .toList()
                        .takeLast(6)
                        .joinToString(" ")
            )
        }
    }

    private fun tileUrl(tile: Tile, year: Int): String =
        "https://tiles.maps.eox.at/wmts/1.0.0/" +
            "s2cloudless-${year}_3857/default/g/${tile.z}/${tile.y}/${tile.x}.jpg"

    companion object {
        const val ATTRIBUTION =
            "EOxCloudless https://cloudless.eox.at by EOX IT Services GmbH " +
                "(Contains modified Copernicus Sentinel data)"

        fun attributionFor(year: Int): String =
            "EOxCloudless https://cloudless.eox.at by EOX IT Services GmbH " +
                "(Contains modified Copernicus Sentinel data ${year})"

        private const val MAX_TILE_COUNT = 9_000
    }
}
