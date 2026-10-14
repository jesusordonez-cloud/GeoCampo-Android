package mx.org.fraac.geocampo

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

object ShapefileExporter {
    data class BundleFiles(
        val files: Map<String, ByteArray>,
        val recordCount: Int
    )

    fun waypoints(
        waypoints: List<Waypoint>,
        photoRefs: Map<Long, List<String>>,
        baseName: String = "puntos"
    ): BundleFiles {
        val records = waypoints.map { w ->
            ShapeRecord.Point(w.lon, w.lat)
        }
        val dbfRows = waypoints.map { w ->
            listOf(
                w.id.toString(),
                w.title,
                w.category.label,
                w.note,
                formatDouble(w.lat, 8),
                formatDouble(w.lon, 8),
                w.altitude?.let { formatDouble(it, 2) } ?: "",
                w.accuracy?.let { formatDouble(it.toDouble(), 1) } ?: "",
                DATE_TIME.format(Date(w.timestamp)),
                photoRefs[w.id].orEmpty().joinToString(";")
            )
        }
        val fields = listOf(
            DbfField("ID", 'N', 10, 0),
            DbfField("NOMBRE", 'C', 80, 0),
            DbfField("CATEGORIA", 'C', 36, 0),
            DbfField("DESCRIP", 'C', 220, 0),
            DbfField("LAT", 'N', 18, 8),
            DbfField("LON", 'N', 18, 8),
            DbfField("ALT_M", 'N', 12, 2),
            DbfField("PREC_M", 'N', 10, 1),
            DbfField("FECHA", 'C', 20, 0),
            DbfField("FOTOS", 'C', 254, 0)
        )
        return build(baseName, ShapeType.POINT, records, fields, dbfRows)
    }

    fun tracks(
        tracks: List<TrackSummary>,
        pointsByTrack: Map<Long, List<TrackPoint>>,
        baseName: String = "recorridos"
    ): BundleFiles {
        val usable = tracks.mapNotNull { track ->
            val pts = pointsByTrack[track.id].orEmpty()
            if (pts.size < 2) null else track to pts
        }

        val records = usable.map { (_, pts) ->
            ShapeRecord.PolyLine(
                pts.map { GeoCoord(it.lat, it.lon, it.altitude) }
            )
        }
        val rows = usable.map { (t, pts) ->
            listOf(
                t.id.toString(),
                t.name,
                DATE_TIME.format(Date(t.startedAt)),
                t.endedAt?.let { DATE_TIME.format(Date(it)) } ?: "",
                pts.size.toString(),
                formatDouble(
                    GeoMath.pathDistanceMeters(pts.map { GeoCoord(it.lat, it.lon) }),
                    1
                )
            )
        }
        val fields = listOf(
            DbfField("ID", 'N', 10, 0),
            DbfField("NOMBRE", 'C', 100, 0),
            DbfField("INICIO", 'C', 20, 0),
            DbfField("FIN", 'C', 20, 0),
            DbfField("PUNTOS", 'N', 10, 0),
            DbfField("DIST_M", 'N', 16, 1)
        )
        return build(baseName, ShapeType.POLYLINE, records, fields, rows)
    }

    private fun build(
        baseName: String,
        type: ShapeType,
        records: List<ShapeRecord>,
        fields: List<DbfField>,
        rows: List<List<String>>
    ): BundleFiles {
        val geometry = writeShpAndShx(type, records)
        val dbf = writeDbf(fields, rows)
        val files = linkedMapOf(
            "$baseName.shp" to geometry.shp,
            "$baseName.shx" to geometry.shx,
            "$baseName.dbf" to dbf,
            "$baseName.prj" to WGS84_PRJ.toByteArray(Charsets.UTF_8),
            "$baseName.cpg" to "UTF-8".toByteArray(Charsets.US_ASCII)
        )
        return BundleFiles(files, records.size)
    }

    private data class GeometryFiles(val shp: ByteArray, val shx: ByteArray)

    private fun writeShpAndShx(
        type: ShapeType,
        records: List<ShapeRecord>
    ): GeometryFiles {
        val encoded = records.map { encodeRecord(it) }
        val bbox = calculateBounds(records)

        val shpLengthBytes = 100 + encoded.sumOf { 8 + it.size }
        val shxLengthBytes = 100 + encoded.size * 8

        val shp = ByteArrayOutputStream(shpLengthBytes)
        shp.write(header(type.code, shpLengthBytes / 2, bbox))

        val shx = ByteArrayOutputStream(shxLengthBytes)
        shx.write(header(type.code, shxLengthBytes / 2, bbox))

        var offsetWords = 50
        encoded.forEachIndexed { index, bytes ->
            val contentWords = bytes.size / 2

            shp.write(intBe(index + 1))
            shp.write(intBe(contentWords))
            shp.write(bytes)

            shx.write(intBe(offsetWords))
            shx.write(intBe(contentWords))

            offsetWords += 4 + contentWords
        }

        return GeometryFiles(shp.toByteArray(), shx.toByteArray())
    }

    private fun header(
        shapeType: Int,
        fileLengthWords: Int,
        bbox: Bounds
    ): ByteArray {
        val bb = ByteBuffer.allocate(100)
        bb.order(ByteOrder.BIG_ENDIAN)
        bb.putInt(9994)
        repeat(5) { bb.putInt(0) }
        bb.putInt(fileLengthWords)

        bb.order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(1000)
        bb.putInt(shapeType)
        bb.putDouble(bbox.xMin)
        bb.putDouble(bbox.yMin)
        bb.putDouble(bbox.xMax)
        bb.putDouble(bbox.yMax)
        bb.putDouble(0.0)
        bb.putDouble(0.0)
        bb.putDouble(0.0)
        bb.putDouble(0.0)
        return bb.array()
    }

    private fun encodeRecord(record: ShapeRecord): ByteArray =
        when (record) {
            is ShapeRecord.Point -> {
                ByteBuffer.allocate(20)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(ShapeType.POINT.code)
                    .putDouble(record.x)
                    .putDouble(record.y)
                    .array()
            }

            is ShapeRecord.PolyLine -> {
                val pts = record.points
                val bounds = calculatePointBounds(pts)
                val size = 4 + 32 + 4 + 4 + 4 + (pts.size * 16)
                val bb = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
                bb.putInt(ShapeType.POLYLINE.code)
                bb.putDouble(bounds.xMin)
                bb.putDouble(bounds.yMin)
                bb.putDouble(bounds.xMax)
                bb.putDouble(bounds.yMax)
                bb.putInt(1)
                bb.putInt(pts.size)
                bb.putInt(0)
                pts.forEach {
                    bb.putDouble(it.lon)
                    bb.putDouble(it.lat)
                }
                bb.array()
            }
        }

    private fun calculateBounds(records: List<ShapeRecord>): Bounds {
        if (records.isEmpty()) return Bounds.ZERO
        var xMin = Double.POSITIVE_INFINITY
        var yMin = Double.POSITIVE_INFINITY
        var xMax = Double.NEGATIVE_INFINITY
        var yMax = Double.NEGATIVE_INFINITY

        records.forEach { record ->
            when (record) {
                is ShapeRecord.Point -> {
                    xMin = min(xMin, record.x)
                    xMax = max(xMax, record.x)
                    yMin = min(yMin, record.y)
                    yMax = max(yMax, record.y)
                }
                is ShapeRecord.PolyLine -> {
                    record.points.forEach {
                        xMin = min(xMin, it.lon)
                        xMax = max(xMax, it.lon)
                        yMin = min(yMin, it.lat)
                        yMax = max(yMax, it.lat)
                    }
                }
            }
        }
        return Bounds(xMin, yMin, xMax, yMax)
    }

    private fun calculatePointBounds(points: List<GeoCoord>): Bounds {
        if (points.isEmpty()) return Bounds.ZERO
        return Bounds(
            xMin = points.minOf { it.lon },
            yMin = points.minOf { it.lat },
            xMax = points.maxOf { it.lon },
            yMax = points.maxOf { it.lat }
        )
    }

    private data class DbfField(
        val name: String,
        val type: Char,
        val length: Int,
        val decimals: Int
    )

    private fun writeDbf(
        fields: List<DbfField>,
        rows: List<List<String>>
    ): ByteArray {
        val charset = Charset.forName("UTF-8")
        val headerLength = 32 + fields.size * 32 + 1
        val recordLength = 1 + fields.sumOf { it.length }
        val total = headerLength + recordLength * rows.size + 1

        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        val now = java.util.Calendar.getInstance()

        out.put(0x03)
        out.put((now.get(java.util.Calendar.YEAR) - 1900).toByte())
        out.put((now.get(java.util.Calendar.MONTH) + 1).toByte())
        out.put(now.get(java.util.Calendar.DAY_OF_MONTH).toByte())
        out.putInt(rows.size)
        out.putShort(headerLength.toShort())
        out.putShort(recordLength.toShort())
        repeat(20) { out.put(0) }

        fields.forEach { field ->
            val nameBytes = field.name.uppercase(Locale.ROOT)
                .take(10)
                .toByteArray(Charsets.US_ASCII)
            val nameBlock = ByteArray(11)
            System.arraycopy(nameBytes, 0, nameBlock, 0, nameBytes.size)
            out.put(nameBlock)
            out.put(field.type.code.toByte())
            out.putInt(0)
            out.put(field.length.toByte())
            out.put(field.decimals.toByte())
            repeat(14) { out.put(0) }
        }
        out.put(0x0D)

        rows.forEach { row ->
            out.put(0x20)
            fields.forEachIndexed { index, field ->
                val rawValue = row.getOrNull(index).orEmpty()
                out.put(fitDbf(rawValue, field, charset))
            }
        }
        out.put(0x1A)
        return out.array()
    }

    private fun fitDbf(
        value: String,
        field: DbfField,
        charset: Charset
    ): ByteArray {
        val isNumeric = field.type == 'N' || field.type == 'F'
        val clean = value.replace("\r", " ").replace("\n", " ")
        var bytes = clean.toByteArray(charset)

        if (bytes.size > field.length) {
            var end = clean.length
            while (end > 0) {
                val candidate = clean.substring(0, end).toByteArray(charset)
                if (candidate.size <= field.length) {
                    bytes = candidate
                    break
                }
                end--
            }
        }

        val block = ByteArray(field.length) { 0x20 }
        val start = if (isNumeric) field.length - bytes.size else 0
        System.arraycopy(
            bytes,
            0,
            block,
            start.coerceAtLeast(0),
            min(bytes.size, field.length)
        )
        return block
    }

    private fun intBe(value: Int): ByteArray =
        ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(value)
            .array()

    private fun formatDouble(value: Double, decimals: Int): String =
        String.format(Locale.US, "%." + decimals + "f", value)

    private enum class ShapeType(val code: Int) {
        POINT(1),
        POLYLINE(3)
    }

    private sealed interface ShapeRecord {
        data class Point(val x: Double, val y: Double) : ShapeRecord
        data class PolyLine(val points: List<GeoCoord>) : ShapeRecord
    }

    private data class Bounds(
        val xMin: Double,
        val yMin: Double,
        val xMax: Double,
        val yMax: Double
    ) {
        companion object {
            val ZERO = Bounds(0.0, 0.0, 0.0, 0.0)
        }
    }

    private val DATE_TIME = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    private const val WGS84_PRJ =
        """GEOGCS["WGS 84",DATUM["WGS_1984",SPHEROID["WGS 84",6378137,298.257223563]],PRIMEM["Greenwich",0],UNIT["degree",0.0174532925199433]]"""
}
