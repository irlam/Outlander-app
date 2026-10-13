package phev

import java.net.Socket
import java.net.SocketTimeoutException

/** Read-only session: handshake, pings, register acks. Never sends write registers. */
class CarClient(
    private val host: String = "192.168.8.46",
    private val port: Int = 8080,
    private val onRegister: (reg: Int, data: ByteArray) -> Unit,
    private val onLog: (String) -> Unit = {},
) {
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }
    @Volatile var running = true
    private val keys = Keys()
    private val sendQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()

    /** Query car to dump latest register state */
    fun refresh() {
        sendQueue.add(buildFrame(0xF6, 0, 0x06, byteArrayOf(0x03)))
    }

    /**
     * Start Pre-AirCon climate control.
     * @param mode 1 = Cool, 2 = Heat, 3 = Windscreen Defrost
     * @param duration 0 = 10 min, 1 = 20 min, 2 = 30 min
     * @param delay 0 = immediate, 1 = 5 min, 2 = 10 min
     */
    fun startAirCon(mode: Int, duration: Int = 0, delay: Int = 0) {
        onLog("CMD: startAirCon(mode=$mode, dur=$duration, delay=$delay)")
        sendQueue.add(buildFrame(0xF6, 0, 0x1B, byteArrayOf(0x02, mode.toByte(), duration.toByte(), delay.toByte())))
    }

    /**
     * Stop Pre-AirCon climate control.
     * Sends register 0x13 reset.
     */
    fun stopAirCon() {
        onLog("CMD: stopAirCon")
        sendQueue.add(buildFrame(0xF6, 0, 0x13, byteArrayOf(0x01)))
    }

    /**
     * Set Headlights state.
     * Register 0x0A: 1 = ON, 2 = OFF.
     */
    fun setHeadlights(on: Boolean) {
        onLog("CMD: setHeadlights($on)")
        sendQueue.add(buildFrame(0xF6, 0, 0x0A, byteArrayOf(if (on) 0x01 else 0x02)))
    }

    /**
     * Set Parking Lights state.
     * Register 0x0B: 1 = ON, 2 = OFF.
     */
    fun setParkingLights(on: Boolean) {
        onLog("CMD: setParkingLights($on)")
        sendQueue.add(buildFrame(0xF6, 0, 0x0B, byteArrayOf(if (on) 0x01 else 0x02)))
    }

    private fun send(out: java.io.OutputStream, msg: ByteArray, inc: Boolean = false, plain: Boolean = false) {
        val x = if (plain) 0 else keys.key(inc)
        val w = xor(msg, x)
        onLog("TX   ${hex(w)}")
        out.write(w)
        out.flush()
    }

    /** Blocking; run on a background thread. On Android, pass a socket created from the bound Wi-Fi Network. */
    fun run(socket: Socket = Socket(host, port)) {
        socket.use { s ->
            s.soTimeout = 1000
            val input = s.getInputStream()
            val out = s.getOutputStream()
            var buf = ByteArray(0)
            var seq = 0
            var handshook = false
            var lastPing = 0L
            val tmp = ByteArray(1024)
            while (running) {
                try {
                    val n = input.read(tmp)
                    if (n < 0) break
                    onLog("RAW  ${hex(tmp.copyOf(n))}")
                    buf += tmp.copyOf(n)
                } catch (_: SocketTimeoutException) {
                }
                val (frames, rest) = splitFrames(buf)
                buf = rest
                for (m in frames) {
                    onLog("RX   ${hex(m)}")
                    when (m[0].toInt() and 0xFF) {
                        0x5E, 0x4E -> if (m[2].toInt() == 1) {
                            handshook = true
                        } else {
                            // phev2mqtt: every start request refreshes the key and gets an ack-flagged response
                            keys.update(m)
                            onLog("INIT keys updated, sending start response")
                            val ackType = if ((m[0].toInt() and 0xFF) == 0x5E) 0xE5 else 0xE4
                            send(out, buildFrame(ackType, 1, 0x01, byteArrayOf(0)), plain = true)
                            seq = 0x0A
                            lastPing = System.currentTimeMillis() - 1500
                        }
                        0x2F -> {
                            onLog("START RESP from car")
                        }
                        0x6F -> {
                            val reg = m[3].toInt() and 0xFF
                            onLog("REG  0x%02X data=${hex(m.copyOfRange(4, m.size - 1))}".format(reg))
                            onRegister(reg, m.copyOfRange(4, m.size - 1))
                            if (m[2].toInt() == 0) send(out, buildFrame(0xF6, 1, reg, byteArrayOf(0)), inc = true)
                        }
                    }
                }
                while (true) {
                    val q = sendQueue.poll() ?: break
                    send(out, q, inc = true)
                }
                val now = System.currentTimeMillis()
                if (now - lastPing > 2000) {
                    send(out, buildFrame(0xF3, 0, seq, byteArrayOf(0)))
                    seq = (seq + 1) % 100
                    lastPing = now
                }
            }
        }
    }
}
