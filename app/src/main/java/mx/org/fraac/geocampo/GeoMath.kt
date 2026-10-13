package mx.org.fraac.geocampo

import kotlin.math.*

object GeoMath {
    private const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceMeters(a: GeoCoord, b: GeoCoord): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun pathDistanceMeters(points: List<GeoCoord>): Double =
        points.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }

    /** Área aproximada sobre esfera; adecuada para polígonos de campo de escala local. */
    fun polygonAreaSquareMeters(points: List<GeoCoord>): Double {
        if (points.size < 3) return 0.0
        var sum = 0.0
        val ring = if (points.first() == points.last()) points else points + points.first()
        for (i in 0 until ring.lastIndex) {
            val p1 = ring[i]
            val p2 = ring[i + 1]
            sum += Math.toRadians(p2.lon - p1.lon) *
                (2 + sin(Math.toRadians(p1.lat)) + sin(Math.toRadians(p2.lat)))
        }
        return abs(sum * EARTH_RADIUS_M * EARTH_RADIUS_M / 2.0)
    }

    fun bearingDegrees(from: GeoCoord, to: GeoCoord): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLon = Math.toRadians(to.lon - from.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}
