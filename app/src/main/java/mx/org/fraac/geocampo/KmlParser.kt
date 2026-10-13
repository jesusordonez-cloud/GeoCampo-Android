package mx.org.fraac.geocampo

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

object KmlParser {
    fun parse(file: File): List<GeoFeature> {
        val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        val doc = dbf.newDocumentBuilder().parse(file)
        val placemarks = doc.getElementsByTagName("Placemark")
        val out = mutableListOf<GeoFeature>()
        for (i in 0 until placemarks.length) {
            val pm = placemarks.item(i) as? Element ?: continue
            val name = firstText(pm, "name")?.trim().takeUnless { it.isNullOrBlank() } ?: "Elemento ${i + 1}"
            val description = firstText(pm, "description")?.trim().orEmpty()
            val props = if (description.isBlank()) emptyMap() else mapOf("description" to description)

            val points = pm.getElementsByTagName("Point")
            for (j in 0 until points.length) {
                val e = points.item(j) as? Element ?: continue
                parseCoords(firstText(e, "coordinates")).firstOrNull()?.let {
                    out += GeoFeature(name, GeoGeometry.Point(it), props)
                }
            }
            val lines = pm.getElementsByTagName("LineString")
            for (j in 0 until lines.length) {
                val e = lines.item(j) as? Element ?: continue
                val coords = parseCoords(firstText(e, "coordinates"))
                if (coords.size >= 2) out += GeoFeature(name, GeoGeometry.LineString(coords), props)
            }
            val polygons = pm.getElementsByTagName("Polygon")
            for (j in 0 until polygons.length) {
                val e = polygons.item(j) as? Element ?: continue
                val outer = e.getElementsByTagName("outerBoundaryIs").item(0) as? Element
                val outerCoords = parseCoords(outer?.let { firstText(it, "coordinates") })
                val rings = mutableListOf<List<GeoCoord>>()
                if (outerCoords.size >= 3) rings += outerCoords
                val inners = e.getElementsByTagName("innerBoundaryIs")
                for (k in 0 until inners.length) {
                    val inner = inners.item(k) as? Element ?: continue
                    val coords = parseCoords(firstText(inner, "coordinates"))
                    if (coords.size >= 3) rings += coords
                }
                if (rings.isNotEmpty()) out += GeoFeature(name, GeoGeometry.Polygon(rings), props)
            }
        }
        return out
    }

    private fun firstText(element: Element, tag: String): String? =
        element.getElementsByTagName(tag).item(0)?.textContent

    private fun parseCoords(text: String?): List<GeoCoord> {
        if (text.isNullOrBlank()) return emptyList()
        return text.trim().split(Regex("\\s+")).mapNotNull { token ->
            val p = token.split(',')
            val lon = p.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            val lat = p.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
            GeoCoord(lat = lat, lon = lon, altitude = p.getOrNull(2)?.toDoubleOrNull())
        }
    }
}
