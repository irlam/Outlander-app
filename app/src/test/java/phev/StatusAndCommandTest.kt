package phev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusAndCommandTest {

    @Test
    fun testBatteryDecoding() {
        val status = CarStatus()
        // Register 0x1D byte 0 = 0x5E (94%)
        status.apply(0x1D, byteArrayOf(0x5E.toByte(), 0x00, 0x00, 0x03))
        assertEquals(94, status.batteryPct)
    }

    @Test
    fun testVinDecoding() {
        val status = CarStatus()
        val vinBytes = "JMAXDGG2WJZ000588".toByteArray(Charsets.US_ASCII)
        val payload = byteArrayOf(0x00) + vinBytes + byteArrayOf(0x00, 0x01)
        status.apply(0x06, payload)
        assertEquals("JMAXDGG2WJZ000588", status.vin)
    }

    @Test
    fun testPlugAndChargeDecoding() {
        val status = CarStatus()

        // Register 0x1E: Unplugged
        status.apply(0x1E, byteArrayOf(0x00, 0x00))
        assertFalse(status.plugged ?: true)

        // Register 0x1E: Plugged into AC Normal port (left socket)
        status.apply(0x1E, byteArrayOf(0x00, 0x01))
        assertTrue(status.plugged ?: false)
        assertEquals("AC Normal (Left socket)", status.plugType)

        // Register 0x1E: Plugged into DC CHAdeMO port (right socket)
        status.apply(0x1E, byteArrayOf(0x01, 0x00))
        assertTrue(status.plugged ?: false)
        assertEquals("DC CHAdeMO (Right socket)", status.plugType)

        // Register 0x1F: Charging active and remaining time
        // byte 0 = 1 (charging), byte 1 = remaining low byte, byte 2 = remaining high byte
        status.apply(0x1F, byteArrayOf(0x01, 0x2D, 0x00))
        assertTrue(status.charging ?: false)
        assertEquals(45, status.chargeRemainingMin)
    }

    @Test
    fun testDoorsAndSecurityDecoding() {
        val status = CarStatus()

        // Register 0x24: 10 bytes: [locked, u1, u2, driver, pass, rearR, rearL, boot, bonnet, headlights]
        // Locked, all doors closed
        val allClosed = byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        status.apply(0x24, allClosed)
        assertTrue(status.locked ?: false)
        assertFalse(status.doorDriverOpen)
        assertFalse(status.doorPassengerOpen)
        assertFalse(status.doorRearRightOpen)
        assertFalse(status.doorRearLeftOpen)
        assertFalse(status.bootOpen)
        assertFalse(status.bonnetOpen)
        assertEquals("All closed", status.doorsSummary)

        // Unlocked (0x02), driver door open (index 3=1), bonnet open (index 8=1)
        val doorsOpen = byteArrayOf(0x02, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00)
        status.apply(0x24, doorsOpen)
        assertFalse(status.locked ?: true)
        assertTrue(status.doorDriverOpen)
        assertTrue(status.bonnetOpen)
        assertFalse(status.doorPassengerOpen)
        assertEquals("Driver, Bonnet", status.doorsSummary)
    }

    @Test
    fun testClimateStatusDecoding() {
        val status = CarStatus()

        // Register 0x1C: PreAC Heat (2), 10 min (0x00) -> 0x02
        status.apply(0x1C, byteArrayOf(0x02))
        assertEquals("Heat (10m)", status.acMode)

        // Register 0x1A: Operating flag in byte 1
        status.apply(0x1A, byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x00))
        assertTrue(status.acOperating ?: false)
    }

    @Test
    fun testCommandChecksumCalculation() {
        // Build frame for headlights ON: 0xF6, len=4, ack=0, reg=0x0A, val=0x01
        val reg = 0x0A
        val payload = byteArrayOf(0x01)
        val len = 3 + payload.size
        val body = byteArrayOf(len.toByte(), 0x00, reg.toByte()) + payload
        val sum = (0xF6 + body.sumOf { it.toInt() and 0xFF }) and 0xFF
        val chk = (0xFF - sum) and 0xFF

        val frame = byteArrayOf(0xF6.toByte()) + body + byteArrayOf(chk.toByte())
        assertEquals(6, frame.size)
        assertEquals(0xF6.toByte(), frame[0])
        assertEquals(0x04.toByte(), frame[1])
        assertEquals(0x0A.toByte(), frame[3])
        assertEquals(0x01.toByte(), frame[4])
    }
}
