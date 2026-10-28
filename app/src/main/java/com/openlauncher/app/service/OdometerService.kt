package com.openlauncher.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.openlauncher.app.MainActivity
import com.openlauncher.app.R
import com.openlauncher.app.data.OdometerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Virtual odometer: accumulates GPS distance in its own foreground service so
 * it keeps counting while OsmAnd / CarPlay is in front (MainActivity stops its
 * own location updates in onStop). The unit is powered from ACC and loses power
 * hard, so the total is persisted periodically rather than only on shutdown.
 */
class OdometerService : Service() {

    companion object {
        private const val CHANNEL_ID      = "odometer"
        private const val NOTIFICATION_ID = 4201

        private const val FIX_INTERVAL_MS     = 1000L
        private const val MAX_ACCURACY_M      = 25f
        private const val MIN_SPEED_MPS       = 1.0f   // below this it's GPS jitter while parked
        private const val MAX_SPEED_MPS       = 70.0f  // ~250 km/h — anything faster is a bad fix
        private const val MAX_GAP_S           = 10.0   // tunnel / lost fix: don't bridge with a straight line
        private const val SAVE_EVERY_M        = 200.0
        private const val SAVE_EVERY_MS       = 30_000L

        /** Live total while the service runs; null when it isn't (UI falls back to the stored value). */
        private val _liveMeters = MutableStateFlow<Double?>(null)
        val liveMeters: StateFlow<Double?> = _liveMeters

        /** Idempotent — safe to call from boot and from every MainActivity.onStart. */
        fun start(context: Context) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) return
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, OdometerService::class.java))
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: OdometerStore
    private val locationManager by lazy { getSystemService(Context.LOCATION_SERVICE) as LocationManager }

    // Only touched on the main thread (location callbacks use the main looper)
    private var loaded = false
    private var meters = 0.0
    private var unsavedMeters = 0.0
    private var lastSaveMs = 0L
    private var lastFix: Location? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) { lastFix = null }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = saveBlocking()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = OdometerStore(applicationContext)
        createChannel()
        // ACTION_SHUTDOWN is a protected system broadcast, so EXPORTED can't be spoofed; NOT_EXPORTED
        // would make ContextCompat attach a signature permission below API 33 that the system sender lacks.
        ContextCompat.registerReceiver(
            this, shutdownReceiver, IntentFilter(Intent.ACTION_SHUTDOWN), ContextCompat.RECEIVER_EXPORTED
        )
        scope.launch {
            meters = store.load().odometerMeters
            lastSaveMs = SystemClock.elapsedRealtime()
            loaded = true
            _liveMeters.value = meters
            requestUpdates()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() must be followed by startForeground(), even when already running.
        val promoted = runCatching {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            )
        }.isSuccess
        if (!promoted) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Re-register on each start: a boot-time start may not have had while-in-use
        // location access yet; once the launcher is in front it does.
        if (loaded) requestUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { locationManager.removeUpdates(listener) }
        runCatching { unregisterReceiver(shutdownReceiver) }
        saveBlocking()
        _liveMeters.value = null
        scope.cancel()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates() {
        runCatching {
            locationManager.removeUpdates(listener)
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, FIX_INTERVAL_MS, 0f, listener, Looper.getMainLooper()
            )
        }
    }

    private fun fixTimeSeconds(l: Location): Double =
        if (l.elapsedRealtimeNanos > 0) l.elapsedRealtimeNanos / 1e9 else l.time / 1000.0

    private fun onFix(fix: Location) {
        if (!loaded) return
        if (!fix.hasAccuracy() || fix.accuracy > MAX_ACCURACY_M) return

        val prev = lastFix
        lastFix = fix
        if (prev == null) return

        val dt = fixTimeSeconds(fix) - fixTimeSeconds(prev)
        if (dt <= 0.0) { lastFix = prev; return }   // duplicate / out-of-order fix
        if (dt > MAX_GAP_S) return                  // gap: restart from this fix

        val d = prev.distanceTo(fix).toDouble()
        val impliedSpeed = d / dt
        val speed = if (fix.hasSpeed()) fix.speed.toDouble() else impliedSpeed
        if (speed < MIN_SPEED_MPS) return
        if (impliedSpeed > MAX_SPEED_MPS) return

        meters += d
        unsavedMeters += d
        _liveMeters.value = meters

        val now = SystemClock.elapsedRealtime()
        if (unsavedMeters >= SAVE_EVERY_M || now - lastSaveMs >= SAVE_EVERY_MS) {
            unsavedMeters = 0.0
            lastSaveMs = now
            val snapshot = meters
            scope.launch { store.saveOdometer(snapshot) }
        }
    }

    /** Best effort on shutdown/destroy — the periodic save is what actually protects against ACC cut. */
    private fun saveBlocking() {
        if (!loaded || unsavedMeters <= 0.0) return
        val snapshot = meters
        unsavedMeters = 0.0
        runCatching { runBlocking { withTimeoutOrNull(1500) { store.saveOdometer(snapshot) } } }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.odometer_channel_name), NotificationManager.IMPORTANCE_MIN)
        )
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.odometer_notification_title))
            .setContentText(getString(R.string.odometer_notification_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
