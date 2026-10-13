package mx.org.fraac.geocampo

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProjectExporter(private val context: Context, private val db: TrackDatabase) {
    fun export(uri: Uri, format: ExportFormat, projectId: Long?): Int {
        val waypoints = db.getWaypoints(projectId, filterByProject = true)
        val tracks = db.getTracks(projectId, filterByProject = true)
        val text = when (format) {
            ExportFormat.GEOJSON -> toGeoJson(waypoints, tracks)
            ExportFormat.KML -> toKml(waypoints, tracks)
            ExportFormat.GPX -> toGpx(waypoints, tracks)
        }
        context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8).use { writer ->
            requireNotNull(writer) { "No se pudo abrir el archivo de salida" }
            writer.write(text)
        }
        return waypoints.size + tracks.size
    }

    private fun toGeoJson(waypoints: List<Waypoint>, tracks: List<TrackSummary>): String {
        val features = JSONArray()
        waypoints.forEach { w ->
            features.put(JSONObject().apply {
                put("type", "Feature")
                put("properties", JSONObject().apply {
                    put("name", w.title)
                    put("note", w.note)
                    put("category", w.category.name)
                    put("timestamp", iso(w.timestamp))
                    w.accuracy?.let { put("accuracy_m", it) }
                    w.photoPath?.let { put("photo_local_path", it) }
                })
                put("geometry", JSONObject().apply {
                    put("type", "Point")
                    put("coordinates", JSONArray().apply {
                        put(w.lon); put(w.lat); w.altitude?.let { put(it) }
                    })
                })
            })
        }
        tracks.forEach { t ->
            val pts = db.getTrackPoints(t.id)
            if (pts.size >= 2) {
                features.put(JSONObject().apply {
                    put("type", "Feature")
                    put("properties", JSONObject().apply {
                        put("name", t.name)
                        put("kind", "track")
                        put("started_at", iso(t.startedAt))
                        t.endedAt?.let { put("ended_at", iso(it)) }
                    })
                    put("geometry", JSONObject().apply {
                        put("type", "LineString")
                        put("coordinates", JSONArray().apply {
                            pts.forEach { p -> put(JSONArray().apply { put(p.lon); put(p.lat); p.altitude?.let { put(it) } }) }
                        })
                    })
                })
            }
        }
        return JSONObject().apply {
            put("type", "FeatureCollection")
            put("name", "GeoCampo export")
            put("features", features)
        }.toString(2)
    }

    private fun toKml(waypoints: List<Waypoint>, tracks: List<TrackSummary>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>\n")
        append("<name>GeoCampo export</name>\n")
        waypoints.forEach { w ->
            append("<Placemark><name>${xml(w.title)}</name>")
            append("<description>${xml(listOf(w.category.label, w.note).filter { it.isNotBlank() }.joinToString(" · "))}</description>")
            append("<Point><coordinates>${w.lon},${w.lat},${w.altitude ?: 0.0}</coordinates></Point></Placemark>\n")
        }
        tracks.forEach { t ->
            val pts = db.getTrackPoints(t.id)
            if (pts.size >= 2) {
                append("<Placemark><name>${xml(t.name)}</name><LineString><tessellate>1</tessellate><coordinates>\n")
                pts.forEach { p -> append("${p.lon},${p.lat},${p.altitude ?: 0.0} ") }
                append("\n</coordinates></LineString></Placemark>\n")
            }
        }
        append("</Document></kml>")
    }

    private fun toGpx(waypoints: List<Waypoint>, tracks: List<TrackSummary>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"GeoCampo\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        waypoints.forEach { w ->
            append("<wpt lat=\"${w.lat}\" lon=\"${w.lon}\">")
            w.altitude?.let { append("<ele>$it</ele>") }
            append("<time>${iso(w.timestamp)}</time><name>${xml(w.title)}</name><desc>${xml(w.note)}</desc><type>${xml(w.category.label)}</type></wpt>\n")
        }
        tracks.forEach { t ->
            val pts = db.getTrackPoints(t.id)
            if (pts.isNotEmpty()) {
                append("<trk><name>${xml(t.name)}</name><trkseg>\n")
                pts.forEach { p ->
                    append("<trkpt lat=\"${p.lat}\" lon=\"${p.lon}\">")
                    p.altitude?.let { append("<ele>$it</ele>") }
                    append("<time>${iso(p.timestamp)}</time></trkpt>\n")
                }
                append("</trkseg></trk>\n")
            }
        }
        append("</gpx>")
    }

    private fun iso(ts: Long): String = ISO.format(Date(ts))

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    companion object {
        private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
    }
}
