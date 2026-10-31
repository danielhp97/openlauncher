package com.openlauncher.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.openlauncher.app.data.PhoneGpsMode
import com.openlauncher.app.util.NmeaFix
import com.openlauncher.app.util.NmeaReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

data class PhoneGpsConfig(
    val enabled: Boolean,
    val mode: PhoneGpsMode,
    val host: String,   // blank = the Wi-Fi gateway (the iPhone when the unit is on its hotspot)
    val port: Int
)

/**
 * Turns NMEA from the rider's iPhone (GPS2IP) into the unit's GPS location: the
 * internal GPS is unusable and the USB-C socket has no data lines, so the phone is
 * the only receiver. Positions go in as a mock "gps" provider, so OsmAnd, Maps and
 * the odometer all use them. Needs the mock_location app-op, granted once over adb:
 *   adb shell appops set com.openlauncher.app android:mock_location allow
 */
class PhoneGpsReceiver(context: Context, private val scope: CoroutineScope) {

    enum class State { OFF, NO_WIFI, CONNECTING, WAITING, RECEIVING, NO_PERMISSION }

    data class Status(val state: State = State.OFF, val endpoint: String = "", val lastFixMs: Long = 0L)

    companion object {
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val READ_TIMEOUT_MS    = 10_000
        private const val RETRY_DELAY_MS     = 2_000L
        // After this long without a fix the mock provider is removed, so the unit's
        // own GPS (if it ever works) and other location apps aren't stuck on a stale point
        private const val STALE_MS           = 60_000L
        private const val DEFAULT_ACCURACY_M = 5f

        private val _status = MutableStateFlow(Status())
        val status: StateFlow<Status> = _status
    }

    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val reader = NmeaReader()

    private var config: PhoneGpsConfig? = null
    private var jobs: List<Job> = emptyList()
    private val openSockets = mutableSetOf<Closeable>()

    @Volatile private var providerAdded = false
    @Volatile private var lastFixElapsed = 0L

    fun apply(newConfig: PhoneGpsConfig) {
        if (newConfig == config) return
        stop()
        config = newConfig
        if (!newConfig.enabled) return
        jobs = when (newConfig.mode) {
            PhoneGpsMode.CONNECT -> listOf(scope.launch(Dispatchers.IO) { connectLoop(newConfig) })
            PhoneGpsMode.LISTEN  -> listOf(
                scope.launch(Dispatchers.IO) { tcpListenLoop(newConfig.port) },
                scope.launch(Dispatchers.IO) { udpListenLoop(newConfig.port) }
            )
        } + scope.launch(Dispatchers.IO) { staleWatchdog() }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        synchronized(openSockets) {
            openSockets.forEach { runCatching { it.close() } }
            openSockets.clear()
        }
        removeProvider()
        config = null
        _status.value = Status()
    }

    // ── Transports ──────────────────────────────────────────────────────────

    private suspend fun CoroutineScope.connectLoop(cfg: PhoneGpsConfig) {
        while (isActive) {
            val host = cfg.host.trim().ifEmpty { wifiGateway() }
            if (host == null) {
                setState(State.NO_WIFI, "")
                delay(RETRY_DELAY_MS)
                continue
            }
            val endpoint = "$host:${cfg.port}"
            setState(State.CONNECTING, endpoint)
            try {
                Socket().track().use { socket ->
                    socket.connect(InetSocketAddress(host, cfg.port), CONNECT_TIMEOUT_MS)
                    socket.soTimeout = READ_TIMEOUT_MS
                    setState(State.WAITING, endpoint)
                    readLines(socket.getInputStream().bufferedReader(Charsets.US_ASCII)::readLine)
                }
            } catch (_: IOException) {
                // refused / timed out / dropped — GPS2IP closed or phone out of range
            }
            delay(RETRY_DELAY_MS)
        }
    }

    /** For GPS2IP's push modes: the phone connects to the unit instead. */
    private suspend fun CoroutineScope.tcpListenLoop(port: Int) {
        setState(State.WAITING, ":$port")
        while (isActive) {
            try {
                ServerSocket().track().use { server ->
                    server.reuseAddress = true
                    server.bind(InetSocketAddress(port))
                    while (isActive) {
                        server.accept().track().use { client ->
                            client.soTimeout = READ_TIMEOUT_MS
                            readLines(client.getInputStream().bufferedReader(Charsets.US_ASCII)::readLine)
                        }
                    }
                }
            } catch (_: IOException) {
            }
            delay(RETRY_DELAY_MS)
        }
    }

    private suspend fun CoroutineScope.udpListenLoop(port: Int) {
        while (isActive) {
            try {
                DatagramSocket(null).track().use { socket ->
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(port))
                    socket.soTimeout = READ_TIMEOUT_MS
                    val buf = ByteArray(4096)
                    while (isActive) {
                        val packet = DatagramPacket(buf, buf.size)
                        try {
                            socket.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        String(packet.data, 0, packet.length, Charsets.US_ASCII).lineSequence().forEach(::onLine)
                    }
                }
            } catch (_: IOException) {
            }
            delay(RETRY_DELAY_MS)
        }
    }

    private fun CoroutineScope.readLines(readLine: () -> String?) {
        while (isActive) {
            val line = try {
                readLine()
            } catch (_: SocketTimeoutException) {
                return  // connected but silent for 10 s: reconnect
            } ?: return
            onLine(line)
        }
    }

    private fun <T : Closeable> T.track(): T = also { synchronized(openSockets) { openSockets.add(it) } }

    // ── Location output ─────────────────────────────────────────────────────

    private fun onLine(line: String) {
        val fix = synchronized(reader) { reader.accept(line) } ?: return
        publish(fix)
    }

    private fun publish(fix: NmeaFix) {
        val location = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = fix.latitude
            longitude = fix.longitude
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            accuracy = fix.hdop?.let { (it * DEFAULT_ACCURACY_M).coerceIn(3f, 50f) } ?: DEFAULT_ACCURACY_M
            fix.speedMps?.let { speed = it }
            fix.bearingDeg?.let { bearing = it }
            fix.altitudeM?.let { altitude = it }
        }
        try {
            ensureProvider()
            locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location)
        } catch (_: SecurityException) {
            providerAdded = false
            setState(State.NO_PERMISSION, _status.value.endpoint)
            return
        } catch (_: IllegalArgumentException) {
            // provider was removed from under us (another mock app); re-add on the next fix
            providerAdded = false
            return
        }
        lastFixElapsed = SystemClock.elapsedRealtime()
        _status.value = _status.value.copy(state = State.RECEIVING, lastFixMs = System.currentTimeMillis())
    }

    // Criteria.POWER_LOW / ACCURACY_FINE share their values with the API 31
    // ProviderProperties constants lint asks for
    @SuppressLint("WrongConstant")
    @Suppress("DEPRECATION")
    private fun ensureProvider() {
        if (providerAdded) return
        locationManager.addTestProvider(
            LocationManager.GPS_PROVIDER,
            false, true, false, false, true, true, true,
            Criteria.POWER_LOW, Criteria.ACCURACY_FINE
        )
        locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        providerAdded = true
    }

    private fun removeProvider() {
        if (!providerAdded) return
        providerAdded = false
        runCatching { locationManager.removeTestProvider(LocationManager.GPS_PROVIDER) }
    }

    private suspend fun CoroutineScope.staleWatchdog() {
        while (isActive) {
            delay(10_000)
            if (providerAdded && SystemClock.elapsedRealtime() - lastFixElapsed > STALE_MS) {
                removeProvider()
                if (_status.value.state == State.RECEIVING) setState(State.WAITING, _status.value.endpoint)
            }
        }
    }

    private fun setState(state: State, endpoint: String) {
        val current = _status.value
        // Keep showing RECEIVING while fixes flow; transports only report their own progress
        if (current.state == State.RECEIVING && state == State.WAITING && current.endpoint == endpoint) return
        _status.value = current.copy(state = state, endpoint = endpoint)
    }

    @Suppress("DEPRECATION")
    private fun wifiGateway(): String? {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return cm.allNetworks.firstNotNullOfOrNull { network ->
            val caps = cm.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return@firstNotNullOfOrNull null
            cm.getLinkProperties(network)?.routes
                ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway?.hostAddress
        }
    }
}
