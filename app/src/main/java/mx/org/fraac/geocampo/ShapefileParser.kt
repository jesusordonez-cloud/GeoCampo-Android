package mx.org.fraac.geocampo

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ShapefileParser {
    fun parse(shp: File, prj: File? = null, dbf: File? = null): List<GeoFeature> {
        val bytes = shp.readBytes()
        require(bytes.size >= 100) { "Shapefile incompleto" }
        val projection = ProjectionUtils.fromPrj(prj?.takeIf { it.exists() }?.readText())
        val attrs = DbfParser.parse(dbf)
        val features = mutableListOf<GeoFeature>()
        var pos = 100
        var recordIndex = 0
        while (pos + 8 <= bytes.size) {
            val header = ByteBuffer.wrap(bytes, pos, 8).order(ByteOrder.BIG_ENDIAN)
            header.int
            val contentLengthBytes = header.int * 2
            val contentStart = pos + 8
            val contentEnd = (contentStart + contentLengthBytes).coerceAtMost(bytes.size)
            if (contentStart + 4 > contentEnd) break
            val bb = ByteBuffer.wrap(bytes, contentStart, contentEnd - contentStart).order(ByteOrder.LITTLE_ENDIAN)
            val shapeType = bb.int
            val props = attrs.getOrNull(recordIndex) ?: emptyMap()
            val name = preferredName(props, recordIndex)
            when (shapeType) {
                0 -> Unit
                1, 11, 21 -> {
                    if (bb.remaining() >= 16) {
                        val x = bb.double; val y = bb.double
                        features += GeoFeature(name, GeoGeometry.Point(ProjectionUtils.toWgs84(x, y, projection)), props)
                    }
                }
                3, 5, 13, 15, 23, 25 -> parseParts(bb, shapeType, projection, name, props)?.let { features += it }
                8, 18, 28 -> parseMultiPoint(bb, projection, name, props).forEach { features += it }
            }
            recordIndex++
            pos = contentEnd
        }
        return features
    }

    private fun parseParts(
        bb: ByteBuffer,
        shapeType: Int,
        projection: ProjectionUtils.Projection?,
        name: String,
        props: Map<String, String>
    ): GeoFeature? {
        if (bb.remaining() < 40) return null
        repeat(4) { bb.double }
        val numParts = bb.int
        val numPoints = bb.int
        if (numParts <= 0 || numPoints <= 0 || bb.remaining() < numParts * 4 + numPoints * 16) return null
        val parts = IntArray(numParts) { bb.int }
        val points = ArrayList<GeoCoord>(numPoints)
        repeat(numPoints) {
            val x = bb.double; val y = bb.double
            points += ProjectionUtils.toWgs84(x, y, projection)
        }
        val chunks = parts.indices.map { i ->
            val start = parts[i].coerceIn(0, points.size)
            val end = (if (i + 1 < parts.size) parts[i + 1] else points.size).coerceIn(start, points.size)
            points.subList(start, end).toList()
        }.filter { it.isNotEmpty() }
        return if (shapeType == 5 || shapeType == 15 || shapeType == 25) {
            GeoFeature(name, GeoGeometry.Polygon(chunks), props)
        } else {
            if (chunks.size == 1) GeoFeature(name, GeoGeometry.LineString(chunks.first()), props)
            else {
                val merged = chunks.flatMapIndexed { i, part -> if (i == 0) part else listOf(GeoCoord(Double.NaN, Double.NaN)) + part }
                GeoFeature(name, GeoGeometry.LineString(merged), props)
            }
        }
    }

    private fun parseMultiPoint(
        bb: ByteBuffer,
        projection: ProjectionUtils.Projection?,
        name: String,
        props: Map<String, String>
    ): List<GeoFeature> {
        if (bb.remaining() < 36) return emptyList()
        repeat(4) { bb.double }
        val num = bb.int
        if (num <= 0 || bb.remaining() < num * 16) return emptyList()
        return List(num) { idx ->
            val x = bb.double; val y = bb.double
            GeoFeature("$name ${idx + 1}", GeoGeometry.Point(ProjectionUtils.toWgs84(x, y, projection)), props)
        }
    }

    private fun preferredName(props: Map<String, String>, index: Int): String {
        val preferred = listOf("NOMBRE", "NAME", "NOM", "CLAVE", "ID", "ETIQUETA", "LABEL")
        preferred.forEach { key ->
            props.entries.firstOrNull { it.key.uppercase() == key && it.value.isNotBlank() }?.let { return it.value }
        }
        return "Elemento ${index + 1}"
    }
}
