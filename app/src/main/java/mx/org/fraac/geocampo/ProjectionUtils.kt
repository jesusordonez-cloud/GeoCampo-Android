package mx.org.fraac.geocampo

import kotlin.math.*

object ProjectionUtils {
    data class Projection(val kind: Kind, val zone: Int? = null, val north: Boolean = true) {
        enum class Kind { WGS84, UTM }
    }

    fun fromPrj(prj: String?): Projection? {
        if (prj.isNullOrBlank()) return null
        val t = prj.uppercase()

        if ("PROJCS" !in t && ("GEOGCS" in t || "GEODCRS" in t)) {
            return Projection(Projection.Kind.WGS84)
        }

        if ("UTM" in t || "TRANSVERSE_MERCATOR" in t) {
            val utmRegex = Regex("""UTM[^0-9]{0,20}(\d{1,2})\s*([NS])?""")
            val match = utmRegex.find(t)
            var zone = match?.groupValues?.getOrNull(1)?.toIntOrNull()
            var north = match?.groupValues?.getOrNull(2)?.let { it != "S" } ?: true

            if (zone == null) {
                val cm = Regex("""CENTRAL_MERIDIAN"?\s*,\s*(-?\d+(?:\.\d+)?)""")
                    .find(t)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toDoubleOrNull()

                if (cm != null) {
                    zone = ((cm + 183.0) / 6.0).roundToInt().coerceIn(1, 60)
                }
            }

            val falseNorthing = Regex("""FALSE_NORTHING"?\s*,\s*(-?\d+(?:\.\d+)?)""")
                .find(t)
                ?.groupValues
                ?.getOrNull(1)
                ?.toDoubleOrNull()

            if (falseNorthing != null && falseNorthing > 5_000_000) {
                north = false
            }

            if (zone != null) {
                return Projection(Projection.Kind.UTM, zone, north)
            }
        }

        return null
    }

    fun toWgs84(x: Double, y: Double, projection: Projection?): GeoCoord {
        if (projection?.kind == Projection.Kind.UTM) {
            return utmToLatLon(
                x,
                y,
                projection.zone ?: error("Zona UTM faltante"),
                projection.north
            )
        }

        if (x in -180.0..180.0 && y in -90.0..90.0) {
            return GeoCoord(lat = y, lon = x)
        }

        throw IllegalArgumentException(
            "Las coordenadas no parecen WGS84 y falta un .prj compatible para reproyectarlas."
        )
    }

    private fun utmToLatLon(
        easting: Double,
        northingInput: Double,
        zone: Int,
        north: Boolean
    ): GeoCoord {
        val a = 6378137.0
        val eccSquared = 0.00669438
        val k0 = 0.9996
        val eccPrimeSquared = eccSquared / (1 - eccSquared)
        val e1 = (1 - sqrt(1 - eccSquared)) / (1 + sqrt(1 - eccSquared))

        val x = easting - 500000.0
        var y = northingInput
        if (!north) y -= 10000000.0

        val longOrigin = (zone - 1) * 6 - 180 + 3
        val m = y / k0
        val mu = m / (
            a * (
                1 -
                    eccSquared / 4 -
                    3 * eccSquared.pow(2) / 64 -
                    5 * eccSquared.pow(3) / 256
                )
            )

        val phi1Rad =
            mu +
                (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) +
                (21 * e1.pow(2) / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) +
                (151 * e1.pow(3) / 96) * sin(6 * mu) +
                (1097 * e1.pow(4) / 512) * sin(8 * mu)

        val n1 = a / sqrt(1 - eccSquared * sin(phi1Rad).pow(2))
        val t1 = tan(phi1Rad).pow(2)
        val c1 = eccPrimeSquared * cos(phi1Rad).pow(2)
        val r1 = a * (1 - eccSquared) /
            (1 - eccSquared * sin(phi1Rad).pow(2)).pow(1.5)
        val d = x / (n1 * k0)

        var lat = phi1Rad -
            (n1 * tan(phi1Rad) / r1) * (
                d.pow(2) / 2 -
                    (5 + 3 * t1 + 10 * c1 - 4 * c1.pow(2) - 9 * eccPrimeSquared) *
                    d.pow(4) / 24 +
                    (61 + 90 * t1 + 298 * c1 + 45 * t1.pow(2) -
                        252 * eccPrimeSquared - 3 * c1.pow(2)) *
                    d.pow(6) / 720
                )

        lat = Math.toDegrees(lat)

        var lon = (
            d -
                (1 + 2 * t1 + c1) * d.pow(3) / 6 +
                (5 - 2 * c1 + 28 * t1 - 3 * c1.pow(2) +
                    8 * eccPrimeSquared + 24 * t1.pow(2)) *
                d.pow(5) / 120
            ) / cos(phi1Rad)

        lon = longOrigin + Math.toDegrees(lon)

        return GeoCoord(lat, lon)
    }
}
