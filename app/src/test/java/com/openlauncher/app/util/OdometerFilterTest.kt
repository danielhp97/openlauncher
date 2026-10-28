package com.openlauncher.app.util

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class OdometerFilterTest {

    // Start point and a metres-north helper (1 m of latitude ≈ 1 / 111_195 °)
    private val lat0 = 41.15
    private val lon0 = -8.61
    private fun fixAt(
        t: Double, northM: Double, eastM: Double = 0.0,
        accuracy: Float? = 5f, speed: Float? = null
    ) = OdometerFix(
        timeSeconds = t,
        latitude    = lat0 + northM / 111_195.0,
        longitude   = lon0 + eastM / (111_195.0 * Math.cos(Math.toRadians(lat0))),
        accuracyM   = accuracy,
        speedMps    = speed
    )

    private fun OdometerFilter.feed(fixes: List<OdometerFix>) = fixes.sumOf { accept(it) }

    @Test
    fun `haversine matches one degree of latitude`() {
        val a = OdometerFix(0.0, 0.0, 0.0, 5f, null)
        val b = OdometerFix(1.0, 1.0, 0.0, 5f, null)
        assertEquals(111_195.0, haversineMeters(a, b), 50.0)
    }

    @Test
    fun `first fix adds nothing`() {
        assertEquals(0.0, OdometerFilter().accept(fixAt(0.0, 0.0, speed = 20f)), 0.0)
    }

    @Test
    fun `steady ride at 72 kmh for a minute counts about 1200 m`() {
        val fixes = (0..60).map { fixAt(it.toDouble(), it * 20.0, speed = 20f) }
        assertEquals(1200.0, OdometerFilter().feed(fixes), 1.0)
    }

    @Test
    fun `parked with GPS jitter adds nothing`() {
        val rnd = Random(42)
        val fixes = (0..600).map {
            fixAt(it.toDouble(), rnd.nextDouble(-4.0, 4.0), rnd.nextDouble(-4.0, 4.0), speed = 0.3f)
        }
        assertEquals(0.0, OdometerFilter().feed(fixes), 0.0)
    }

    @Test
    fun `parked jitter without reported speed is gated by implied speed`() {
        // ±0.4 m wobble per second → implied speed < 1 m/s
        val fixes = (0..300).map { fixAt(it.toDouble(), if (it % 2 == 0) 0.2 else -0.2, speed = null) }
        assertEquals(0.0, OdometerFilter().feed(fixes), 0.0)
    }

    @Test
    fun `parked 2D jitter without reported speed adds next to nothing`() {
        // Found on the emulator: ±4 m jitter with no speed implied 4-5 m/s and
        // added ~250 m in a minute of standing still.
        val rnd = Random(7)
        val fixes = (0..600).map {
            fixAt(it.toDouble(), rnd.nextDouble(-4.0, 4.0), rnd.nextDouble(-4.0, 4.0), speed = null)
        }
        assertEquals(0.0, OdometerFilter().feed(fixes), 15.0)
    }

    @Test
    fun `slow ride without reported speed still counts`() {
        // 3 m/s, 1 Hz, 5 m accuracy: each step is below the 10 m threshold, so
        // distance is booked in chunks against the held anchor
        val fixes = (0..60).map { fixAt(it.toDouble(), it * 3.0, speed = null) }
        assertEquals(180.0, OdometerFilter().feed(fixes), 12.0)
    }

    @Test
    fun `fixes with poor or missing accuracy are skipped`() {
        val f = OdometerFilter()
        f.accept(fixAt(0.0, 0.0, speed = 20f))
        assertEquals(0.0, f.accept(fixAt(1.0, 20.0, accuracy = 40f, speed = 20f)), 0.0)
        assertEquals(0.0, f.accept(fixAt(2.0, 40.0, accuracy = null, speed = 20f)), 0.0)
        // Next good fix still links to the last good anchor (t=0) — 3 s, 60 m, 20 m/s
        assertEquals(60.0, f.accept(fixAt(3.0, 60.0, speed = 20f)), 0.5)
    }

    @Test
    fun `tunnel gap is not bridged with a straight line`() {
        val f = OdometerFilter()
        val before = f.feed((0..10).map { fixAt(it.toDouble(), it * 20.0, speed = 20f) })
        // 30 s without fixes, reappear 600 m further on
        val afterGap = f.accept(fixAt(40.0, 800.0, speed = 20f))
        val resumed = f.feed((41..45).map { fixAt(it.toDouble(), 800.0 + (it - 40) * 20.0, speed = 20f) })
        assertEquals(200.0, before, 0.5)
        assertEquals(0.0, afterGap, 0.0)
        assertEquals(100.0, resumed, 0.5)
    }

    @Test
    fun `implausible jump is dropped and counting resumes from the new position`() {
        val f = OdometerFilter()
        f.accept(fixAt(0.0, 0.0, speed = 20f))
        assertEquals(0.0, f.accept(fixAt(1.0, 500.0, speed = 20f)), 0.0)   // 500 m/s
        assertEquals(20.0, f.accept(fixAt(2.0, 520.0, speed = 20f)), 0.5)
    }

    @Test
    fun `repeated frozen fix adds no phantom distance`() {
        // The old speed×time loop kept adding km when location froze at the last speed
        val f = OdometerFilter()
        f.accept(fixAt(0.0, 0.0, speed = 25f))
        val frozen = fixAt(1.0, 25.0, speed = 25f)
        f.accept(frozen)
        assertEquals(0.0, f.feed(List(100) { frozen }), 0.0)
    }

    @Test
    fun `out of order fix keeps the anchor`() {
        val f = OdometerFilter()
        f.accept(fixAt(10.0, 0.0, speed = 20f))
        assertEquals(0.0, f.accept(fixAt(9.0, -20.0, speed = 20f)), 0.0)
        assertEquals(20.0, f.accept(fixAt(11.0, 20.0, speed = 20f)), 0.5)
    }

    @Test
    fun `missing speed falls back to implied speed`() {
        val fixes = (0..10).map { fixAt(it.toDouble(), it * 15.0, speed = null) }
        assertEquals(150.0, OdometerFilter().feed(fixes), 0.5)
    }

    @Test
    fun `reset forgets the anchor`() {
        val f = OdometerFilter()
        f.accept(fixAt(0.0, 0.0, speed = 20f))
        f.reset()
        assertEquals(0.0, f.accept(fixAt(1.0, 20.0, speed = 20f)), 0.0)
    }

    @Test
    fun `slow walking pace stop-and-go still counts when moving`() {
        // 2 m/s for 10 s, stop for 20 s (jitter), 2 m/s for 10 s
        val moving1 = (0..10).map { fixAt(it.toDouble(), it * 2.0, speed = 2f) }
        val stopped = (11..30).map { fixAt(it.toDouble(), 20.0, speed = 0f) }
        val moving2 = (31..40).map { fixAt(it.toDouble(), 20.0 + (it - 30) * 2.0, speed = 2f) }
        assertEquals(40.0, OdometerFilter().feed(moving1 + stopped + moving2), 0.5)
    }
}
