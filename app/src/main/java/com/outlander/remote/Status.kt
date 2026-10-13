package phev

/** Readable view of status registers. Field positions verified from live car logs + phev2mqtt. */
data class CarStatus(
    var batteryPct: Int? = null,
    var locked: Boolean? = null,
    var doorsSummary: String? = null,
    var doorDriverOpen: Boolean = false,
    var doorPassengerOpen: Boolean = false,
    var doorRearRightOpen: Boolean = false,
    var doorRearLeftOpen: Boolean = false,
    var bootOpen: Boolean = false,
    var bonnetOpen: Boolean = false,
    var charging: Boolean? = null,
    var chargeRemainingMin: Int? = null,
    var plugged: Boolean? = null,
    var plugType: String? = null,
    var rawPlugHex: String? = null,
    var rawChargeHex: String? = null,
    var vin: String? = null,
    var ssid: String? = null,
    var ecuVersion: String? = null,
    var carTime: String? = null,
    var acMode: String? = null,
    var acOperating: Boolean? = null,
    var acDurationMin: Int? = null,
    var headlights: Boolean? = null,
    var parkingLights: Boolean? = null,
)

fun CarStatus.apply(reg: Int, d: ByteArray) {
    fun u(i: Int) = d.getOrNull(i)?.toInt()?.and(0xFF)
    when (reg) {
        0x06, 0x15 -> {
            if (d.size >= 18) {
                vin = String(d, 1, 17, Charsets.US_ASCII).trim()
            }
        }
        0x12 -> {
            if (d.size >= 6) {
                val yr = 2000 + (u(0) ?: 0)
                carTime = "%04d-%02d-%02d %02d:%02d:%02d".format(
                    yr, u(1) ?: 0, u(2) ?: 0, u(3) ?: 0, u(4) ?: 0, u(5) ?: 0
                )
            }
        }
        0x1A -> {
            acOperating = u(1)?.let { it == 1 }
        }
        0x1C -> {
            val modeStr = when ((u(0) ?: 0) and 0x0F) {
                1 -> "Cool"
                2 -> "Heat"
                3 -> "Windscreen"
                else -> "Off"
            }
            val dur = when ((u(0) ?: 0) and 0xF0) {
                0x00 -> 10
                0x10 -> 20
                0x20 -> 30
                else -> 0
            }
            acMode = if (modeStr != "Off") "$modeStr (${dur}m)" else "Off"
        }
        0x1D -> {
            val lvl = u(0)
            if (lvl != null && lvl in 1..100) {
                batteryPct = lvl
            }
            parkingLights = u(2)?.let { it == 1 }
        }
        0x1E -> {
            rawPlugHex = d.joinToString(" ") { "%02X".format(it) }
            val b0 = u(0) ?: 0
            val b1 = u(1) ?: 0
            plugged = (b0 > 0 || b1 == 1)
            plugType = when {
                b0 == 1 -> "DC CHAdeMO (Right socket)"
                b0 == 2 || b1 == 1 -> "AC Normal (Left socket)"
                b0 > 0 -> "Plugged (type $b0)"
                else -> null
            }
        }
        0x1F -> {
            rawChargeHex = d.joinToString(" ") { "%02X".format(it) }
            charging = u(0)?.let { it == 1 }
            chargeRemainingMin = if (d.size >= 3 && d[2] != 0xFF.toByte()) {
                ((u(2) ?: 0) shl 8) or (u(1) ?: 0)
            } else {
                null
            }
        }
        0x24 -> {
            locked = u(0)?.let { it == 1 }
            doorDriverOpen = (u(3) == 1)
            doorPassengerOpen = (u(4) == 1)
            doorRearRightOpen = (u(5) == 1)
            doorRearLeftOpen = (u(6) == 1)
            bootOpen = (u(7) == 1)
            bonnetOpen = (u(8) == 1)
            val open = mutableListOf<String>()
            if (doorDriverOpen) open.add("Driver")
            if (doorPassengerOpen) open.add("Passenger")
            if (doorRearRightOpen) open.add("Rear-R")
            if (doorRearLeftOpen) open.add("Rear-L")
            if (bootOpen) open.add("Boot")
            if (bonnetOpen) open.add("Bonnet")
            doorsSummary = if (open.isEmpty()) "All closed" else open.joinToString(", ")
            headlights = u(9)?.let { it == 1 }
        }
        0x28 -> {
            val valid = d.takeWhile { it != 0xFF.toByte() && it != 0.toByte() }.toByteArray()
            if (valid.isNotEmpty()) ssid = String(valid, Charsets.US_ASCII).trim()
        }
        0xC0 -> {
            if (d.size >= 9) {
                ecuVersion = String(d, 0, 9, Charsets.US_ASCII).trim()
            }
        }
    }
}
