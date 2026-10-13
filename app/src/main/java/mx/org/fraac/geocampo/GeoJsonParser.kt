package mx.org.fraac.geocampo

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object GeoJsonParser {
    fun parse(file: File): List<GeoFeature> = parseRoot(JSONObject(file.readText()))

    private fun parseRoot(root: JSONObject): List<GeoFeature> {
        return when (root.optString("type")) {
            "FeatureCollection" -> {
                val arr = root.optJSONArray("features") ?: JSONArray()
                (0 until arr.length()).flatMap { parseFeature(arr.optJSONObject(it) ?: JSONObject()) }
            }
            "Feature" -> parseFeature(root)
            else -> geometries(root, null, emptyMap())
        }
    }

    private fun parseFeature(obj: JSONObject): List<GeoFeature> {
        val propsObj = obj.optJSONObject("properties") ?: JSONObject()
        val props = linkedMapOf<String, String>()
        propsObj.keys().forEach { k -> props[k] = propsObj.opt(k)?.toString().orEmpty() }
        val name = sequenceOf("name", "nombre", "title", "id", "clave")
            .mapNotNull { key -> props.entries.firstOrNull { it.key.equals(key, true) }?.value }
            .firstOrNull { it.isNotBlank() }
        val geometry = obj.optJSONObject("geometry") ?: return emptyList()
        return geometries(geometry, name, props)
    }

    private fun geometries(obj: JSONObject, name: String?, props: Map<String, String>): List<GeoFeature> {
        val type = obj.optString("type")
        val c = obj.opt("coordinates")
        return when (type) {
            "Point" -> coord(c as? JSONArray)?.let { listOf(GeoFeature(name, GeoGeometry.Point(it), props)) } ?: emptyList()
            "MultiPoint" -> (c as? JSONArray)?.let { a ->
                (0 until a.length()).mapNotNull { idx -> coord(a.optJSONArray(idx))?.let { GeoFeature(name, GeoGeometry.Point(it), props) } }
            } ?: emptyList()
            "LineString" -> line(c as? JSONArray)?.takeIf { it.size >= 2 }?.let { listOf(GeoFeature(name, GeoGeometry.LineString(it), props)) } ?: emptyList()
            "MultiLineString" -> (c as? JSONArray)?.let { a ->
                (0 until a.length()).mapNotNull { idx -> line(a.optJSONArray(idx)).takeIf { it.size >= 2 }?.let { GeoFeature(name, GeoGeometry.LineString(it), props) } }
            } ?: emptyList()
            "Polygon" -> polygon(c as? JSONArray)?.let { listOf(GeoFeature(name, GeoGeometry.Polygon(it), props)) } ?: emptyList()
            "MultiPolygon" -> (c as? JSONArray)?.let { a ->
                (0 until a.length()).mapNotNull { idx -> polygon(a.optJSONArray(idx))?.let { GeoFeature(name, GeoGeometry.Polygon(it), props) } }
            } ?: emptyList()
            "GeometryCollection" -> {
                val arr = obj.optJSONArray("geometries") ?: JSONArray()
                (0 until arr.length()).flatMap { geometries(arr.optJSONObject(it) ?: JSONObject(), name, props) }
            }
            else -> emptyList()
        }
    }

    private fun coord(a: JSONArray?): GeoCoord? {
        a ?: return null
        if (a.length() < 2) return null
        return GeoCoord(lat = a.optDouble(1), lon = a.optDouble(0), altitude = if (a.length() > 2) a.optDouble(2) else null)
    }

    private fun line(a: JSONArray?): List<GeoCoord> {
        a ?: return emptyList()
        return (0 until a.length()).mapNotNull { coord(a.optJSONArray(it)) }
    }

    private fun polygon(a: JSONArray?): List<List<GeoCoord>>? {
        a ?: return null
        val rings = (0 until a.length()).map { line(a.optJSONArray(it)) }.filter { it.size >= 3 }
        return rings.takeIf { it.isNotEmpty() }
    }
}
