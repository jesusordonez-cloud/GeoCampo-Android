package mx.org.fraac.geocampo

data class GeoCoord(
    val lat: Double,
    val lon: Double,
    val altitude: Double? = null
)

sealed interface GeoGeometry {
    data class Point(val p: GeoCoord) : GeoGeometry
    data class LineString(val points: List<GeoCoord>) : GeoGeometry
    data class Polygon(val rings: List<List<GeoCoord>>) : GeoGeometry
}

data class GeoFeature(
    val name: String? = null,
    val geometry: GeoGeometry,
    val properties: Map<String, String> = emptyMap()
)

data class MapLayer(
    val id: String,
    val name: String,
    val sourceFile: String,
    val format: String,
    val features: List<GeoFeature>,
    val visible: Boolean = true
)

data class PmTilesBasemap(
    val path: String,
    val name: String,
    val tileType: Int,
    val minZoom: Int,
    val maxZoom: Int,
    val centerLat: Double,
    val centerLon: Double,
    val centerZoom: Int
) {
    val isRaster: Boolean get() = tileType in 2..5
    val isVector: Boolean get() = tileType == 1
}

enum class WaypointCategory(val label: String, val glyph: String) {
    OBSERVATION("Observación", "O"),
    TREE("Árbol / vegetación", "V"),
    WATER("Agua / aguada", "A"),
    CENOTE("Cenote", "C"),
    INFRASTRUCTURE("Infraestructura", "I"),
    ACCESS("Acceso / camino", "R"),
    FIREBREAK("Brecha corta fuego", "B"),
    WILDLIFE("Fauna / evidencia", "F"),
    PHOTO("Fotografía", "P"),
    HAZARD("Riesgo", "!"),
    BOUNDARY("Límite / mojonera", "L"),
    OTHER("Otro", "+")
}

data class FieldFolder(
    val id: Long,
    val parentId: Long?,
    val name: String,
    val notes: String,
    val createdAt: Long
)

data class FieldProject(
    val id: Long,
    val name: String,
    val ejido: String,
    val municipality: String,
    val notes: String,
    val createdAt: Long,
    val folderId: Long? = null
) {
    val displayName: String get() = ejido.ifBlank { name }
}

data class Waypoint(
    val id: Long,
    val projectId: Long?,
    val title: String,
    val note: String,
    val category: WaypointCategory,
    val lat: Double,
    val lon: Double,
    val altitude: Double?,
    val accuracy: Float?,
    val timestamp: Long,
    val photoPaths: List<String> = emptyList()
) {
    val photoPath: String? get() = photoPaths.firstOrNull()
}

data class TrackPoint(
    val id: Long = 0,
    val trackId: Long,
    val lat: Double,
    val lon: Double,
    val altitude: Double?,
    val accuracy: Float?,
    val timestamp: Long
)

data class TrackSummary(
    val id: Long,
    val projectId: Long?,
    val name: String,
    val startedAt: Long,
    val endedAt: Long?,
    val pointCount: Int
)

data class LiveTrackingState(
    val isTracking: Boolean = false,
    val trackId: Long? = null,
    val startedAt: Long? = null,
    val distanceMeters: Double = 0.0,
    val lastPoint: TrackPoint? = null
)

enum class ExportFormat(val label: String, val extension: String, val mime: String) {
    KML("KML", "kml", "application/vnd.google-earth.kml+xml"),
    KMZ("KMZ + evidencias", "kmz", "application/vnd.google-earth.kmz"),
    SHAPE_ZIP("Shapefile + evidencias", "zip", "application/zip"),
    GEOJSON("GeoJSON", "geojson", "application/geo+json"),
    GPX("GPX", "gpx", "application/gpx+xml")
}

enum class MeasurementMode(val label: String) {
    DISTANCE("Distancia"),
    AREA("Área")
}
