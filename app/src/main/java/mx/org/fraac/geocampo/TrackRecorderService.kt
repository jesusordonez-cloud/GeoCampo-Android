package mx.org.fraac.geocampo

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.roundToInt

class TrackRecorderService : Service() {
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var db: TrackDatabase
    private var activeTrackId: Long? = null
    private var startedAt: Long = 0L
    private var lastLocation: Location? = null
    private var totalDistance = 0.0
    private var pointsSinceNotification = 0

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val trackId = activeTrackId ?: return
            result.locations.forEach { location ->
                if (!location.latitude.isFinite() || !location.longitude.isFinite()) return@forEach
                val previous = lastLocation
                if (previous != null) {
                    val segment = previous.distanceTo(location).toDouble()
                    if (segment in 0.5..300.0) totalDistance += segment
                }
                lastLocation = location
                val point = TrackPoint(
                    trackId = trackId,
                    lat = location.latitude,
                    lon = location.longitude,
                    altitude = if (location.hasAltitude()) location.altitude else null,
                    accuracy = if (location.hasAccuracy()) location.accuracy else null,
                    timestamp = location.time.takeIf { it > 0 } ?: System.currentTimeMillis()
                )
                val id = db.insertTrackPoint(point)
                TrackingBus.push(point.copy(id = id), totalDistance)
                pointsSinceNotification++
                if (pointsSinceNotification >= 5) {
                    pointsSinceNotification = 0
                    updateNotification()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        db = TrackDatabase(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking(
                trackId = intent.getLongExtra(EXTRA_TRACK_ID, -1L),
                startTime = intent.getLongExtra(EXTRA_STARTED_AT, System.currentTimeMillis())
            )
            ACTION_STOP -> stopTracking()
        }
        return START_REDELIVER_INTENT
    }

    private fun startTracking(trackId: Long, startTime: Long) {
        if (trackId <= 0 || activeTrackId != null) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }

        activeTrackId = trackId
        startedAt = startTime
        totalDistance = 0.0
        lastLocation = null
        TrackingBus.start(trackId, startTime)

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (android.os.Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3_000L)
            .setMinUpdateIntervalMillis(1_500L)
            .setMinUpdateDistanceMeters(2f)
            .build()
        fused.requestLocationUpdates(request, callback, mainLooper)
    }

    private fun stopTracking() {
        val trackId = activeTrackId
        fused.removeLocationUpdates(callback)
        if (trackId != null) db.stopTrack(trackId)
        activeTrackId = null
        TrackingBus.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, TrackRecorderService::class.java).setAction(ACTION_STOP)
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val meters = totalDistance.roundToInt()
        val text = if (meters < 1000) "$meters m registrados" else String.format("%.2f km registrados", meters / 1000.0)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_track)
            .setContentTitle("GeoCampo · grabando recorrido")
            .setContentText(text)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Detener", pendingStop)
            .build()
    }

    private fun updateNotification() {
        val nm = ContextCompat.getSystemService(this, android.app.NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        const val CHANNEL_ID = "track_recording"
        private const val NOTIFICATION_ID = 4201
        const val ACTION_START = "mx.org.fraac.geocampo.START_TRACK"
        const val ACTION_STOP = "mx.org.fraac.geocampo.STOP_TRACK"
        const val EXTRA_TRACK_ID = "track_id"
        const val EXTRA_STARTED_AT = "started_at"
    }
}
