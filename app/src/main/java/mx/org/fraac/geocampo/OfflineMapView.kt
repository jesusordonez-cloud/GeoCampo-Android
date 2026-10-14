package mx.org.fraac.geocampo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolygonOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.VectorSource

enum class BaseMapMode {
    MAP,
    SATELLITE_ONLINE,
    SATELLITE_OFFLINE,
    MAPTILER_HD
}

class MapSnapshotBridge {
    internal var capture: (((Bitmap?) -> Unit) -> Unit)? = null

    fun request(callback: (Bitmap?) -> Unit) {
        val current = capture
        if (current == null) callback(null) else current(callback)
    }
}

@Suppress("DEPRECATION")
@Composable
fun OfflineMapView(
    basemap: PmTilesBasemap?,
    layers: List<MapLayer>,
    waypoints: List<Waypoint>,
    trackPoints: List<TrackPoint>,
    currentFix: TrackPoint?,
    satelliteOffline: PmTilesBasemap?,
    baseMapMode: BaseMapMode,
    mapTilerApiKey: String,
    centerOnLocationToken: Int,
    snapshotBridge: MapSnapshotBridge,
    modifier: Modifier = Modifier,
    onLongPress: (GeoCoord) -> Unit,
    onWaypointSelected: (Waypoint) -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val longPressCallback by rememberUpdatedState(onLongPress)
    val waypointCallback by rememberUpdatedState(onWaypointSelected)
    val markerWaypoints = remember { mutableMapOf<Long, Waypoint>() }

    val mapView = remember {
        MapView(context).apply {
            onCreate(Bundle())
        }
    }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleEpoch by remember { mutableIntStateOf(0) }
    var centeredBasemapPath by remember { mutableStateOf<String?>(null) }

    DisposableEffect(mapView, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> Unit
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)

        mapView.getMapAsync { readyMap ->
            map = readyMap
            readyMap.uiSettings.apply {
                isCompassEnabled = true
                isZoomGesturesEnabled = true
                isScrollGesturesEnabled = true
                isRotateGesturesEnabled = true
                isTiltGesturesEnabled = true
                isAttributionEnabled = true
                isLogoEnabled = true
            }
            readyMap.addOnMapLongClickListener(
                object : MapLibreMap.OnMapLongClickListener {
                    override fun onMapLongClick(point: LatLng): Boolean {
                        longPressCallback(GeoCoord(point.latitude, point.longitude))
                        return true
                    }
                }
            )
            readyMap.setOnMarkerClickListener(
                object : MapLibreMap.OnMarkerClickListener {
                    override fun onMarkerClick(marker: Marker): Boolean {
                        val waypoint = markerWaypoints[marker.id] ?: return false
                        waypointCallback(waypoint)
                        return true
                    }
                }
            )
            snapshotBridge.capture = { callback ->
                readyMap.snapshot { bitmap -> callback(bitmap) }
            }
        }

        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            snapshotBridge.capture = null
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(
        map,
        basemap?.path,
        satelliteOffline?.path,
        baseMapMode,
        mapTilerApiKey
    ) {
        val targetMap = map ?: return@LaunchedEffect
        val styleJson = when (baseMapMode) {
            BaseMapMode.SATELLITE_ONLINE -> SATELLITE_STYLE
            BaseMapMode.MAPTILER_HD ->
                if (mapTilerApiKey.isBlank()) EMPTY_STYLE
                else mapTilerHybridStyle(mapTilerApiKey)
            else -> EMPTY_STYLE
        }

        targetMap.setStyle(Style.Builder().fromJson(styleJson)) { style ->
            val activeBase = when (baseMapMode) {
                BaseMapMode.MAP -> basemap
                BaseMapMode.SATELLITE_OFFLINE -> satelliteOffline
                BaseMapMode.SATELLITE_ONLINE,
                BaseMapMode.MAPTILER_HD -> null
            }

            if (activeBase != null) {
                runCatching { addPmTilesBasemap(style, activeBase) }
                if (centeredBasemapPath != activeBase.path) {
                    targetMap.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(activeBase.centerLat, activeBase.centerLon))
                        .zoom(activeBase.centerZoom.coerceIn(3, 17).toDouble())
                        .build()
                    centeredBasemapPath = activeBase.path
                }
            }

            if (
                (baseMapMode == BaseMapMode.SATELLITE_ONLINE ||
                    baseMapMode == BaseMapMode.SATELLITE_OFFLINE) &&
                basemap?.isVector == true
            ) {
                runCatching { addHybridRoadOverlay(style, basemap) }
            }

            styleEpoch++
        }
    }

    LaunchedEffect(
        map,
        styleEpoch,
        layers,
        waypoints,
        trackPoints,
        currentFix
    ) {
        val targetMap = map ?: return@LaunchedEffect
        if (styleEpoch == 0) return@LaunchedEffect
        renderAnnotations(
            context = context,
            map = targetMap,
            layers = layers,
            waypoints = waypoints,
            trackPoints = trackPoints,
            currentFix = currentFix,
            markerWaypoints = markerWaypoints
        )

        if (basemap == null && centeredBasemapPath == null) {
            val first = firstCoordinate(layers, waypoints, trackPoints, currentFix)
            if (first != null) {
                targetMap.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(first.lat, first.lon))
                    .zoom(14.0)
                    .build()
                centeredBasemapPath = "__data__"
            }
        }
    }

    LaunchedEffect(map, centerOnLocationToken, currentFix) {
        if (centerOnLocationToken <= 0) return@LaunchedEffect
        val targetMap = map ?: return@LaunchedEffect
        val fix = currentFix ?: return@LaunchedEffect
        targetMap.cameraPosition = CameraPosition.Builder()
            .target(LatLng(fix.lat, fix.lon))
            .zoom(maxOf(targetMap.cameraPosition.zoom, 16.0))
            .build()
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier
    )
}

private fun addPmTilesBasemap(style: Style, basemap: PmTilesBasemap) {
    val uri = "pmtiles://file://${basemap.path}"
    val sourceId = "geocampo-basemap"

    if (basemap.isRaster) {
        style.addSource(RasterSource(sourceId, uri, 256))
        style.addLayer(RasterLayer("geocampo-basemap-raster", sourceId))
        return
    }

    if (!basemap.isVector) return

    style.addSource(VectorSource(sourceId, uri))

    style.addLayer(
        FillLayer("base-earth", sourceId).apply {
            setSourceLayer("earth")
            setProperties(fillColor(Color.rgb(238, 239, 232)))
        }
    )
    style.addLayer(
        FillLayer("base-landcover", sourceId).apply {
            setSourceLayer("landcover")
            setProperties(
                fillColor(Color.rgb(214, 230, 205)),
                fillOpacity(0.75f)
            )
        }
    )
    style.addLayer(
        FillLayer("base-landuse", sourceId).apply {
            setSourceLayer("landuse")
            setProperties(
                fillColor(Color.rgb(225, 234, 215)),
                fillOpacity(0.65f)
            )
        }
    )
    style.addLayer(
        FillLayer("base-water", sourceId).apply {
            setSourceLayer("water")
            setProperties(fillColor(Color.rgb(166, 211, 232)))
        }
    )
    style.addLayer(
        FillLayer("base-buildings", sourceId).apply {
            setSourceLayer("buildings")
            setProperties(
                fillColor(Color.rgb(210, 202, 190)),
                fillOpacity(0.85f)
            )
        }
    )
    style.addLayer(
        LineLayer("base-boundaries", sourceId).apply {
            setSourceLayer("boundaries")
            setProperties(
                lineColor(Color.rgb(128, 128, 128)),
                lineWidth(1.0f),
                lineOpacity(0.75f)
            )
        }
    )
    style.addLayer(
        LineLayer("base-roads", sourceId).apply {
            setSourceLayer("roads")
            setProperties(
                lineColor(Color.rgb(238, 183, 104)),
                lineWidth(1.8f),
                lineOpacity(0.92f)
            )
        }
    )
}

private fun addHybridRoadOverlay(style: Style, basemap: PmTilesBasemap) {
    if (!basemap.isVector) return

    val sourceId = "geocampo-hybrid-vector"
    style.addSource(
        VectorSource(
            sourceId,
            "pmtiles://file://${basemap.path}"
        )
    )

    style.addLayer(
        LineLayer("hybrid-roads-casing", sourceId).apply {
            setSourceLayer("roads")
            setProperties(
                lineColor(Color.WHITE),
                lineWidth(5.2f),
                lineOpacity(0.86f)
            )
        }
    )
    style.addLayer(
        LineLayer("hybrid-roads", sourceId).apply {
            setSourceLayer("roads")
            setProperties(
                lineColor(Color.rgb(246, 178, 66)),
                lineWidth(2.5f),
                lineOpacity(0.96f)
            )
        }
    )
    style.addLayer(
        LineLayer("hybrid-boundaries", sourceId).apply {
            setSourceLayer("boundaries")
            setProperties(
                lineColor(Color.rgb(255, 255, 255)),
                lineWidth(1.2f),
                lineOpacity(0.82f)
            )
        }
    )
}

@Suppress("DEPRECATION")
private fun renderAnnotations(
    context: android.content.Context,
    map: MapLibreMap,
    layers: List<MapLayer>,
    waypoints: List<Waypoint>,
    trackPoints: List<TrackPoint>,
    currentFix: TrackPoint?,
    markerWaypoints: MutableMap<Long, Waypoint>
) {
    map.removeAnnotations()
    markerWaypoints.clear()

    val layerColors = listOf(
        Color.rgb(25, 110, 89),
        Color.rgb(67, 103, 170),
        Color.rgb(154, 91, 32),
        Color.rgb(123, 76, 145),
        Color.rgb(61, 123, 54),
        Color.rgb(176, 67, 67)
    )

    layers.forEachIndexed { index, layer ->
        val color = layerColors[index % layerColors.size]
        layer.features.forEach { feature ->
            when (val geometry = feature.geometry) {
                is GeoGeometry.Point -> {
                    map.addMarker(
                        MarkerOptions()
                            .position(LatLng(geometry.p.lat, geometry.p.lon))
                            .title(feature.name ?: layer.name)
                    )
                }

                is GeoGeometry.LineString -> {
                    val points = geometry.points
                        .filter { it.lat.isFinite() && it.lon.isFinite() }
                        .map { LatLng(it.lat, it.lon) }
                    if (points.size >= 2) {
                        map.addPolyline(
                            PolylineOptions()
                                .add(*points.toTypedArray())
                                .color(color)
                                .width(3.5f)
                        )
                    }
                }

                is GeoGeometry.Polygon -> {
                    geometry.rings.firstOrNull()?.let { ring ->
                        val points = ring
                            .filter { it.lat.isFinite() && it.lon.isFinite() }
                            .map { LatLng(it.lat, it.lon) }
                        if (points.size >= 3) {
                            map.addPolygon(
                                PolygonOptions()
                                    .add(*points.toTypedArray())
                                    .fillColor(withAlpha(color, 58))
                                    .strokeColor(color)
                            )
                        }
                    }
                }
            }
        }
    }

    val iconCache = mutableMapOf<WaypointCategory, Icon>()
    waypoints.forEach { waypoint ->
        val icon = iconCache.getOrPut(waypoint.category) {
            createCategoryIcon(context, waypoint.category)
        }
        val details = buildString {
            append(waypoint.category.label)
            if (waypoint.note.isNotBlank()) append(" · ${waypoint.note}")
            waypoint.accuracy?.let { append(" · ±${it.toInt()} m") }
            if (waypoint.photoPaths.isNotEmpty()) {
                append(" · " + waypoint.photoPaths.size + " foto(s)")
            }
        }
        val marker = map.addMarker(
            MarkerOptions()
                .position(LatLng(waypoint.lat, waypoint.lon))
                .title(waypoint.title)
                .snippet(details)
                .icon(icon)
        )
        markerWaypoints[marker.id] = waypoint
    }

    if (trackPoints.size >= 2) {
        val points = trackPoints.map { LatLng(it.lat, it.lon) }
        map.addPolyline(
            PolylineOptions()
                .add(*points.toTypedArray())
                .color(Color.rgb(13, 121, 245))
                .width(6f)
        )
    }

    currentFix?.let { fix ->
        map.addMarker(
            MarkerOptions()
                .position(LatLng(fix.lat, fix.lon))
                .title("Mi posición")
                .snippet(fix.accuracy?.let { "Precisión ±${it.toInt()} m" })
                .icon(createGpsIcon(context))
        )
    }
}

private fun firstCoordinate(
    layers: List<MapLayer>,
    waypoints: List<Waypoint>,
    trackPoints: List<TrackPoint>,
    currentFix: TrackPoint?
): GeoCoord? {
    currentFix?.let { return GeoCoord(it.lat, it.lon) }
    waypoints.firstOrNull()?.let { return GeoCoord(it.lat, it.lon) }
    trackPoints.firstOrNull()?.let { return GeoCoord(it.lat, it.lon) }

    layers.forEach { layer ->
        layer.features.forEach { feature ->
            when (val geometry = feature.geometry) {
                is GeoGeometry.Point -> return geometry.p
                is GeoGeometry.LineString ->
                    geometry.points.firstOrNull { it.lat.isFinite() && it.lon.isFinite() }?.let { return it }
                is GeoGeometry.Polygon ->
                    geometry.rings.firstOrNull()?.firstOrNull()?.let { return it }
            }
        }
    }
    return null
}

private fun createCategoryIcon(
    context: android.content.Context,
    category: WaypointCategory
): Icon {
    val background = when (category) {
        WaypointCategory.WATER, WaypointCategory.CENOTE -> Color.rgb(32, 132, 196)
        WaypointCategory.TREE -> Color.rgb(44, 128, 72)
        WaypointCategory.WILDLIFE -> Color.rgb(104, 92, 66)
        WaypointCategory.HAZARD -> Color.rgb(200, 61, 47)
        WaypointCategory.INFRASTRUCTURE -> Color.rgb(108, 92, 170)
        WaypointCategory.ACCESS, WaypointCategory.FIREBREAK -> Color.rgb(172, 111, 40)
        WaypointCategory.BOUNDARY -> Color.rgb(92, 92, 92)
        WaypointCategory.PHOTO -> Color.rgb(78, 97, 174)
        else -> Color.rgb(21, 105, 84)
    }
    return createLetterIcon(context, category.glyph, background)
}

private fun createGpsIcon(context: android.content.Context): Icon =
    createLetterIcon(context, "●", Color.rgb(13, 121, 245))

private fun createLetterIcon(
    context: android.content.Context,
    text: String,
    background: Int
): Icon {
    val size = 72
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = background
        style = Paint.Style.FILL
    }
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    canvas.drawCircle(size / 2f, size / 2f, 28f, circle)
    canvas.drawCircle(size / 2f, size / 2f, 28f, border)
    val y = size / 2f - (label.descent() + label.ascent()) / 2f
    canvas.drawText(text.take(2), size / 2f, y, label)

    return IconFactory.getInstance(context).fromBitmap(bitmap)
}

private fun withAlpha(color: Int, alpha: Int): Int =
    Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

private fun mapTilerHybridStyle(apiKey: String): String {
    val escaped = apiKey
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    return """
    {
      "version": 8,
      "name": "GeoCampo MapTiler HD",
      "sources": {
        "maptiler-hybrid": {
          "type": "raster",
          "url": "https://api.maptiler.com/maps/hybrid/256/tiles.json?key=$escaped",
          "tileSize": 256,
          "attribution": "© MapTiler © OpenStreetMap contributors"
        }
      },
      "layers": [
        {
          "id": "maptiler-hybrid",
          "type": "raster",
          "source": "maptiler-hybrid"
        }
      ]
    }
    """.trimIndent()
}

private const val SATELLITE_STYLE =
    """{
      "version": 8,
      "name": "GeoCampo satélite",
      "sources": {
        "satellite": {
          "type": "raster",
          "tiles": [
            "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2025_3857/default/g/{z}/{y}/{x}.jpg"
          ],
          "tileSize": 256,
          "maxzoom": 14,
          "attribution": "EOxCloudless https://cloudless.eox.at by EOX IT Services GmbH · Contains modified Copernicus Sentinel data 2025"
        }
      },
      "layers": [
        {
          "id": "satellite",
          "type": "raster",
          "source": "satellite"
        }
      ]
    }"""

private const val EMPTY_STYLE =
    """{
      "version": 8,
      "name": "GeoCampo offline",
      "sources": {},
      "layers": [
        {
          "id": "background",
          "type": "background",
          "paint": { "background-color": "#eef2ef" }
        }
      ]
    }"""
