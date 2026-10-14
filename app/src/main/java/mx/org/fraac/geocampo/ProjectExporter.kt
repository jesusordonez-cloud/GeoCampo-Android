package mx.org.fraac.geocampo

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectExporter(
    private val context: Context,
    private val db: TrackDatabase
) {
    fun export(uri: Uri, format: ExportFormat, projectId: Long?): Int {
        val waypoints = db.getWaypoints(projectId, filterByProject = true)
        val tracks = db.getTracks(projectId, filterByProject = true)
        val project = db.getProjects().firstOrNull { it.id == projectId }
        val projectName = project?.displayName ?: "GeoCampo"

        when (format) {
            ExportFormat.GEOJSON -> writeText(uri, toGeoJson(waypoints, tracks, projectName))
            ExportFormat.KML -> writeText(uri, toKml(waypoints, tracks, projectName, emptyMap()))
            ExportFormat.GPX -> writeText(uri, toGpx(waypoints, tracks, projectName))
            ExportFormat.KMZ -> writeKmz(uri, waypoints, tracks, projectName)
            ExportFormat.SHAPE_ZIP -> writeShapeZip(uri, waypoints, tracks, projectName)
        }

        return waypoints.size + tracks.size
    }

    private fun writeText(uri: Uri, text: String) {
        context.contentResolver.openOutputStream(uri, "w")
            ?.bufferedWriter(Charsets.UTF_8)
            .use { writer ->
                requireNotNull(writer) { "No se pudo abrir el archivo de salida" }
                writer.write(text)
            }
    }

    private fun writeKmz(
        uri: Uri,
        waypoints: List<Waypoint>,
        tracks: List<TrackSummary>,
        projectName: String
    ) {
        val photoRefs = buildPhotoRefs(waypoints, "files")
        context.contentResolver.openOutputStream(uri, "w").use { output ->
            requireNotNull(output) { "No se pudo abrir el archivo KMZ" }
            ZipOutputStream(output).use { zip ->
                putText(zip, "doc.kml", toKml(waypoints, tracks, projectName, photoRefs))
                writePhotos(zip, waypoints, photoRefs)
                putText(
                    zip,
                    "manifest.csv",
                    evidenceManifestCsv(waypoints, photoRefs)
                )
            }
        }
    }

    private fun writeShapeZip(
        uri: Uri,
        waypoints: List<Waypoint>,
        tracks: List<TrackSummary>,
        projectName: String
    ) {
        val photoRefs = buildPhotoRefs(waypoints, "evidencias")
        val pointsByTrack = tracks.associate { it.id to db.getTrackPoints(it.id) }

        val pointBundle = ShapefileExporter.waypoints(
            waypoints = waypoints,
            photoRefs = photoRefs,
            baseName = "puntos"
        )
        val trackBundle = ShapefileExporter.tracks(
            tracks = tracks,
            pointsByTrack = pointsByTrack,
            baseName = "recorridos"
        )

        context.contentResolver.openOutputStream(uri, "w").use { output ->
            requireNotNull(output) { "No se pudo abrir el paquete Shapefile" }
            ZipOutputStream(output).use { zip ->
                pointBundle.files.forEach { (name, bytes) ->
                    putBytes(zip, "shapefile/$name", bytes)
                }
                trackBundle.files.forEach { (name, bytes) ->
                    putBytes(zip, "shapefile/$name", bytes)
                }
                writePhotos(zip, waypoints, photoRefs)
                putText(zip, "manifest.csv", evidenceManifestCsv(waypoints, photoRefs))
                putText(
                    zip,
                    "LEEME.txt",
                    buildString {
                        appendLine("GeoCampo – Exportación Shapefile")
                        appendLine("Proyecto: $projectName")
                        appendLine("Sistema de coordenadas: WGS 84 (EPSG:4326)")
                        appendLine("puntos.* = puntos georreferenciados")
                        appendLine("recorridos.* = recorridos GPS")
                        appendLine("evidencias/ = fotografías vinculadas mediante el campo FOTOS y manifest.csv")
                        appendLine("Puntos: ${pointBundle.recordCount}")
                        appendLine("Recorridos: ${trackBundle.recordCount}")
                        appendLine("Generado: ${DISPLAY_DATE.format(Date())}")
                    }
                )
            }
        }
    }

    private fun buildPhotoRefs(
        waypoints: List<Waypoint>,
        folder: String
    ): Map<Long, List<String>> =
        waypoints.associate { waypoint ->
            val refs = waypoint.photoPaths.mapIndexedNotNull { index, path ->
                val file = File(path)
                if (!file.exists()) return@mapIndexedNotNull null
                val ext = file.extension.ifBlank { "jpg" }.lowercase(Locale.ROOT)
                "$folder/punto_${waypoint.id}_${index + 1}.$ext"
            }
            waypoint.id to refs
        }

    private fun writePhotos(
        zip: ZipOutputStream,
        waypoints: List<Waypoint>,
        photoRefs: Map<Long, List<String>>
    ) {
        waypoints.forEach { waypoint ->
            val refs = photoRefs[waypoint.id].orEmpty()
            waypoint.photoPaths.forEachIndexed { index, path ->
                val target = refs.getOrNull(index) ?: return@forEachIndexed
                val file = File(path)
                if (!file.exists()) return@forEachIndexed

                zip.putNextEntry(ZipEntry(target))
                file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun evidenceManifestCsv(
        waypoints: List<Waypoint>,
        photoRefs: Map<Long, List<String>>
    ): String = buildString {
        appendLine("punto_id,nombre,categoria,latitud,longitud,descripcion,fotografia")
        waypoints.forEach { waypoint ->
            val refs = photoRefs[waypoint.id].orEmpty()
            if (refs.isEmpty()) {
                appendLine(
                    listOf(
                        waypoint.id,
                        csv(waypoint.title),
                        csv(waypoint.category.label),
                        waypoint.lat,
                        waypoint.lon,
                        csv(waypoint.note),
                        ""
                    ).joinToString(",")
                )
            } else {
                refs.forEach { ref ->
                    appendLine(
                        listOf(
                            waypoint.id,
                            csv(waypoint.title),
                            csv(waypoint.category.label),
                            waypoint.lat,
                            waypoint.lon,
                            csv(waypoint.note),
                            csv(ref)
                        ).joinToString(",")
                    )
                }
            }
        }
    }

    private fun toGeoJson(
        waypoints: List<Waypoint>,
        tracks: List<TrackSummary>,
        projectName: String
    ): String {
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
                    put(
                        "photos_local",
                        JSONArray().apply { w.photoPaths.forEach { put(it) } }
                    )
                })
                put("geometry", JSONObject().apply {
                    put("type", "Point")
                    put("coordinates", JSONArray().apply {
                        put(w.lon)
                        put(w.lat)
                        w.altitude?.let { put(it) }
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
                            pts.forEach { p ->
                                put(JSONArray().apply {
                                    put(p.lon)
                                    put(p.lat)
                                    p.altitude?.let { put(it) }
                                })
                            }
                        })
                    })
                })
            }
        }

        return JSONObject().apply {
            put("type", "FeatureCollection")
            put("name", projectName)
            put("features", features)
        }.toString(2)
    }

    private fun toKml(
        waypoints: List<Waypoint>,
        tracks: List<TrackSummary>,
        projectName: String,
        photoRefs: Map<Long, List<String>>
    ): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>\n")
        append("<name>${xml(projectName)}</name>\n")

        append("<Style id=\"track\"><LineStyle><color>ffff7800</color><width>4</width></LineStyle></Style>\n")
        append("<Style id=\"point\"><IconStyle><scale>1.1</scale></IconStyle></Style>\n")

        waypoints.forEach { w ->
            val photos = photoRefs[w.id].orEmpty()
            append("<Placemark><name>${xml(w.title)}</name><styleUrl>#point</styleUrl>")
            append("<description><![CDATA[")
            append("<b>${html(w.category.label)}</b><br/>")
            if (w.note.isNotBlank()) {
                append("${html(w.note).replace("]]>", "]]&gt;")}<br/>")
            }
            append(
                String.format(
                    Locale.US,
                    "Coordenadas: %.7f, %.7f<br/>",
                    w.lat,
                    w.lon
                )
            )
            w.accuracy?.let { append("Precisión GPS: ±${it.toInt()} m<br/>") }
            append("Fecha: ${DISPLAY_DATE_TIME.format(Date(w.timestamp))}<br/>")
            photos.forEach { ref ->
                append("<br/><img src=\"${html(ref)}\" width=\"640\"/>")
            }
            append("]]></description>")
            append(
                "<Point><coordinates>${w.lon},${w.lat},${w.altitude ?: 0.0}</coordinates></Point>"
            )
            append("</Placemark>\n")
        }

        tracks.forEach { t ->
            val pts = db.getTrackPoints(t.id)
            if (pts.size >= 2) {
                append("<Placemark><name>${xml(t.name)}</name><styleUrl>#track</styleUrl>")
                append("<LineString><tessellate>1</tessellate><coordinates>\n")
                pts.forEach { p ->
                    append("${p.lon},${p.lat},${p.altitude ?: 0.0} ")
                }
                append("\n</coordinates></LineString></Placemark>\n")
            }
        }

        append("</Document></kml>")
    }

    private fun toGpx(
        waypoints: List<Waypoint>,
        tracks: List<TrackSummary>,
        projectName: String
    ): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append(
            "<gpx version=\"1.1\" creator=\"GeoCampo\" " +
                "xmlns=\"http://www.topografix.com/GPX/1/1\">\n"
        )
        append("<metadata><name>${xml(projectName)}</name></metadata>\n")

        waypoints.forEach { w ->
            append("<wpt lat=\"${w.lat}\" lon=\"${w.lon}\">")
            w.altitude?.let { append("<ele>$it</ele>") }
            append("<time>${iso(w.timestamp)}</time>")
            append("<name>${xml(w.title)}</name>")
            append("<desc>${xml(w.note)}</desc>")
            append("<type>${xml(w.category.label)}</type>")
            append("</wpt>\n")
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

    private fun putText(zip: ZipOutputStream, name: String, text: String) =
        putBytes(zip, name, text.toByteArray(Charsets.UTF_8))

    private fun putBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun iso(ts: Long): String = ISO.format(Date(ts))

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun html(value: String): String = xml(value)
        .replace("\n", "<br/>")

    private fun csv(value: String): String =
        "\"" + value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\""

    companion object {
        private val ISO =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

        private val DISPLAY_DATE =
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        private val DISPLAY_DATE_TIME =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }
}
