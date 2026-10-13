# GeoCampo · Android MVP 0.2

GeoCampo es una aplicación Android nativa para trabajo geoespacial de campo con prioridad en operación **sin conexión a internet**, captura GPS y organización de evidencias por proyecto o ejido.

## Novedades de la versión 0.2

- **Proyectos / ejidos de campo**: nombre del proyecto, ejido/comunidad, municipio y notas.
- Puntos y recorridos asociados al proyecto activo.
- **Evidencia fotográfica georreferenciada**: toma una fotografía desde la ficha de un punto y se almacena localmente en la aplicación.
- **Exportación** del proyecto activo a GeoJSON, KML y GPX.
- **Historial local** de puntos y recorridos por proyecto.
- **Navegación básica a un punto**: distancia y rumbo desde la ubicación GPS actual.
- **Medición sobre el mapa** de distancia acumulada y superficie.
- Importación local de KML, KMZ, GPX, GeoJSON y Shapefile.
- PMTiles raster como mapa base completamente local.
- Grabación de recorridos GPS con servicio en primer plano.
- Persistencia SQLite local.
- Interfaz Jetpack Compose + Material 3 + MapLibre.

## Compilar APK

El proyecto incluye `.github/workflows/android.yml` para compilar automáticamente el APK mediante GitHub Actions.

El APK debug se genera en:

`app/build/outputs/apk/debug/app-debug.apk`
