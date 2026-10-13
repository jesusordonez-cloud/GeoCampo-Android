package mx.org.fraac.geocampo

import java.io.File
import java.io.RandomAccessFile

object PmTilesParser {
    fun read(file: File): PmTilesBasemap {
        require(file.length() >= 127) { "PMTiles incompleto" }
        val b = ByteArray(127)
        RandomAccessFile(file, "r").use { it.readFully(b) }
        val magic = String(b.copyOfRange(0, 7), Charsets.US_ASCII)
        require(magic == "PMTiles") { "El archivo no es PMTiles v3" }
        val version = u8(b[7])
        require(version <= 3) { "Versión PMTiles no compatible: $version" }
        val tileType = u8(b[99])
        val minZoom = u8(b[100])
        val maxZoom = u8(b[101])
        val centerZoom = u8(b[118])
        val centerLon = int32le(b, 119) / 10_000_000.0
        val centerLat = int32le(b, 123) / 10_000_000.0
        return PmTilesBasemap(
            path = file.absolutePath,
            name = file.nameWithoutExtension,
            tileType = tileType,
            minZoom = minZoom,
            maxZoom = maxZoom,
            centerLat = centerLat,
            centerLon = centerLon,
            centerZoom = centerZoom
        )
    }

    private fun u8(v: Byte): Int = v.toInt() and 0xFF
    private fun int32le(b: ByteArray, o: Int): Int =
        (u8(b[o])) or (u8(b[o + 1]) shl 8) or (u8(b[o + 2]) shl 16) or (b[o + 3].toInt() shl 24)
}
