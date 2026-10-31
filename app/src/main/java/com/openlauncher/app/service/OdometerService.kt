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
import com.openlauncher.app.data.SettingsRepository
import com.openlauncher.app.util.OdometerFilter
import com.openlauncher.app.util.OdometerFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    // Lives here because this service is already the always-on foreground process
    private lateinit var phoneGps: PhoneGpsReceiver

    // Only touched on the main thread (location callbacks use the main looper)
    private var loaded = false
    private var meters = 0.0
    private var unsavedMeters = 0.0
    private var lastSaveMs = 0L
    // Android's ellipsoid distance on device; the filter defaults to haversine for JVM tests
    private val filter = OdometerFilter(distanceM = { a, b ->
        val out = FloatArray(1)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, out)
        out[0].toDouble()
    })

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) { filter.reset() }
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
        phoneGps = PhoneGpsReceiver(this, scope)
        scope.launch {
            SettingsRepository(applicationContext).settingsFlow
                .map { PhoneGpsConfig(it.phoneGpsEnabled, it.phoneGpsMode, it.phoneGpsHost, it.phoneGpsPort) }
                .distinctUntilChanged()
                .collect { phoneGps.apply(it) }
        }
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
        phoneGps.stop()
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
        val d = filter.accept(
            OdometerFix(
                timeSeconds = fixTimeSeconds(fix),
                latitude    = fix.latitude,
                longitude   = fix.longitude,
                accuracyM   = if (fix.hasAccuracy()) fix.accuracy else null,
                speedMps    = if (fix.hasSpeed()) fix.speed else null
            )
        )
        if (d > 0.0) {
            meters += d
            unsavedMeters += d
            _liveMeters.value = meters
        }

        // Checked on every fix, not just moving ones: the usual power cut comes after
        // parking, and the tail of the ride must be flushed while standing still.
        val now = SystemClock.elapsedRealtime()
        if (unsavedMeters > 0.0 && (unsavedMeters >= SAVE_EVERY_M || now - lastSaveMs >= SAVE_EVERY_MS)) {
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
