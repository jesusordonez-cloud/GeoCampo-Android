package mx.org.fraac.geocampo

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import org.maplibre.android.MapLibre

class GeoCampoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                TrackRecorderService.CHANNEL_ID,
                "Grabación de recorridos",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Mantiene el GPS activo mientras se graba un recorrido" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
