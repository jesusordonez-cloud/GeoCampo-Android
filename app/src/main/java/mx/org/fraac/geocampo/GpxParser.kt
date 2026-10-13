package mx.org.fraac.geocampo

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

object GpxParser {
    fun parse(file: File): List<GeoFeature> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = mutableListOf<GeoFeature>()

        val wpts = doc.getElementsByTagName("wpt")
        for (i in 0 until wpts.length) {
            val e = wpts.item(i) as? Element ?: continue
            coord(e)?.let { c ->
                val name = e.getElementsByTagName("name").item(0)?.textContent?.trim().takeUnless { it.isNullOrBlank() } ?: "Punto ${i + 1}"
                out += GeoFeature(name, GeoGeometry.Point(c))
            }
        }

        val tracks = doc.getElementsByTagName("trk")
        for (i in 0 until tracks.length) {
            val trk = tracks.item(i) as? Element ?: continue
            val name = trk.getElementsByTagName("name").item(0)?.textContent?.trim().takeUnless { it.isNullOrBlank() } ?: "Recorrido ${i + 1}"
            val segs = trk.getElementsByTagName("trkseg")
            for (j in 0 until segs.length) {
                val seg = segs.item(j) as? Element ?: continue
                val pts = seg.getElementsByTagName("trkpt")
                val coords = (0 until pts.length).mapNotNull { coord(pts.item(it) as? Element) }
                if (coords.size >= 2) out += GeoFeature(if (segs.length > 1) "$name · ${j + 1}" else name, GeoGeometry.LineString(coords))
            }
        }

        val routes = doc.getElementsByTagName("rte")
        for (i in 0 until routes.length) {
            val rte = routes.item(i) as? Element ?: continue
            val name = rte.getElementsByTagName("name").item(0)?.textContent?.trim().takeUnless { it.isNullOrBlank() } ?: "Ruta ${i + 1}"
            val pts = rte.getElementsByTagName("rtept")
            val coords = (0 until pts.length).mapNotNull { coord(pts.item(it) as? Element) }
            if (coords.size >= 2) out += GeoFeature(name, GeoGeometry.LineString(coords))
        }
        return out
    }

    private fun coord(e: Element?): GeoCoord? {
        e ?: return null
        val lat = e.getAttribute("lat").toDoubleOrNull() ?: return null
        val lon = e.getAttribute("lon").toDoubleOrNull() ?: return null
        val ele = e.getElementsByTagName("ele").item(0)?.textContent?.toDoubleOrNull()
        return GeoCoord(lat, lon, ele)
    }
}
