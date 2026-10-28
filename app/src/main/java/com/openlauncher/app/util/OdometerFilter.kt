package com.openlauncher.app.util

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Plain-primitive GPS fix, so the filter stays testable on the local JVM. */
data class OdometerFix(
    val timeSeconds: Double,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float?,
    val speedMps: Float?
)

/**
 * Turns a stream of GPS fixes into odometer distance. Extracted from
 * OdometerService so the gating rules can be unit-tested without
 * android.location.Location (same approach as [isBetterLocation]).
 *
 * A segment between consecutive fixes counts only if the new fix is accurate
 * enough, the bike is actually moving (GPS jitter while parked is ignored),
 * and the implied speed is plausible. A gap in fixes (tunnel, lost signal)
 * restarts from the next fix instead of bridging it with a straight line.
 */
class OdometerFilter(
    private val maxAccuracyM: Float = 25f,
    private val minSpeedMps: Float = 1.0f,     // below this it's GPS jitter while parked
    private val maxSpeedMps: Float = 70.0f,    // ~250 km/h — anything faster is a bad fix
    private val maxGapSeconds: Double = 10.0,
    private val distanceM: (OdometerFix, OdometerFix) -> Double = ::haversineMeters
) {
    private var lastFix: OdometerFix? = null

    /** Forget the anchor fix, e.g. when the GPS provider is disabled. */
    fun reset() { lastFix = null }

    /** Feeds one fix and returns the meters it adds to the odometer (often 0). */
    fun accept(fix: OdometerFix): Double {
        val accuracy = fix.accuracyM ?: return 0.0
        if (accuracy > maxAccuracyM) return 0.0

        val prev = lastFix
        val dt = if (prev == null) 0.0 else fix.timeSeconds - prev.timeSeconds
        if (prev != null && dt <= 0.0) return 0.0   // duplicate / out-of-order fix: keep the anchor
        if (prev == null || dt > maxGapSeconds) { lastFix = fix; return 0.0 }

        val d = distanceM(prev, fix)
        val impliedSpeed = d / dt

        if (fix.speedMps == null) {
            // No receiver speed: a few metres of jitter per second already looks like
            // walking pace, so hold the anchor until the move clearly exceeds the two
            // fixes' combined uncertainty. Slow progress accumulates against the anchor.
            if (d < (prev.accuracyM ?: 0f) + accuracy) return 0.0
            lastFix = fix
            return if (impliedSpeed in minSpeedMps.toDouble()..maxSpeedMps.toDouble()) d else 0.0
        }

        lastFix = fix
        if (fix.speedMps < minSpeedMps) return 0.0
        if (impliedSpeed > maxSpeedMps) return 0.0
        return d
    }
}

fun haversineMeters(a: OdometerFix, b: OdometerFix): Double {
    val r = 6_371_008.8
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * asin(sqrt(h))
}
