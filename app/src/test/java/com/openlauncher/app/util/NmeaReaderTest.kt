package com.openlauncher.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaReaderTest {

    // Captured from GPS2IP Lite on an iPhone (Braga, 2026-10-31)
    private val rmc1 = "\$GPRMC,181005,A,4131.79591,N,00825.56480,W,1.07,0.00,311026,003.1,W*7C"
    private val rmc2 = "\$GPRMC,181008,A,4131.79525,N,00825.56617,W,1.14,288.0,311026,003.1,W*42"

    @Test fun `parses GPS2IP RMC`() {
        val fix = NmeaReader().accept(rmc1)
        assertNotNull(fix)
        fix!!
        assertEquals(41.0 + 31.79591 / 60.0, fix.latitude, 1e-9)
        assertEquals(-(8.0 + 25.56480 / 60.0), fix.longitude, 1e-9)
        assertEquals(1.07f * 0.514444f, fix.speedMps!!, 1e-4f)
        assertEquals(0f, fix.bearingDeg!!, 0f)
        assertNull(fix.altitudeM)
    }

    @Test fun `bearing is kept`() {
        assertEquals(288f, NmeaReader().accept(rmc2)!!.bearingDeg!!, 0f)
    }

    @Test fun `duplicate sentence is dropped`() {
        val reader = NmeaReader()
        assertNotNull(reader.accept(rmc1))
        assertNull(reader.accept(rmc1))
        assertNotNull(reader.accept(rmc2))
    }

    @Test fun `bad checksum is dropped`() {
        assertNull(NmeaReader().accept(rmc1.replace("*7C", "*7D")))
    }

    @Test fun `void status is dropped`() {
        assertNull(NmeaReader().accept("\$GPRMC,,V,,,,,,,,,,N*53"))
    }

    @Test fun `trailing CRLF and missing checksum are accepted`() {
        assertNotNull(NmeaReader().accept("\$GPRMC,181005,A,4131.79591,N,00825.56480,W,1.07,0.00,311026,003.1,W\r\n"))
    }

    @Test fun `southern and eastern hemispheres are signed`() {
        val f = NmeaReader().accept("\$GNRMC,120000.00,A,3352.12800,S,15112.54000,E,0.0,,010125,,,A")!!
        assertTrue(f.latitude < 0)
        assertTrue(f.longitude > 0)
        assertNull(f.bearingDeg)
    }

    @Test fun `GGA with same time adds altitude and hdop`() {
        val reader = NmeaReader()
        assertNull(reader.accept("\$GPGGA,181005,4131.79591,N,00825.56480,W,1,09,0.9,182.4,M,50.0,M,,"))
        val fix = reader.accept(rmc1)!!
        assertEquals(182.4, fix.altitudeM!!, 1e-9)
        assertEquals(0.9f, fix.hdop!!, 0f)
    }

    @Test fun `GGA from another second is ignored`() {
        val reader = NmeaReader()
        reader.accept("\$GPGGA,181004,4131.79591,N,00825.56480,W,1,09,0.9,182.4,M,50.0,M,,")
        assertNull(reader.accept(rmc1)!!.altitudeM)
    }

    @Test fun `garbage is ignored`() {
        val reader = NmeaReader()
        assertNull(reader.accept(""))
        assertNull(reader.accept("hello"))
        assertNull(reader.accept("\$GPRMC,1,A,xx,N,yy,W"))
        assertNull(reader.accept("\$GPTXT,01,01,02,u-blox ag - www.u-blox.com*50"))
    }
}
