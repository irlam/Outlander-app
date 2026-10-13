package phev

/** Pure-Kotlin port of phev_client.py (read-only). No Android dependencies. */

fun checksum(m: ByteArray, len: Int = m.size - 1): Int {
    var s = 0
    for (i in 0 until len) s += m[i].toInt() and 0xFF
    return s and 0xFF
}

fun buildFrame(type: Int, ack: Int, reg: Int, data: ByteArray = byteArrayOf()): ByteArray {
    val m = ByteArray(5 + data.size)
    m[0] = type.toByte()
    m[1] = (m.size - 2).toByte()
    m[2] = ack.toByte()
    m[3] = reg.toByte()
    data.copyInto(m, 4)
    m[m.size - 1] = checksum(m).toByte()
    return m
}

class Keys {
    private var map = IntArray(0)
    private var sNum = 0

    fun update(msg: ByteArray) {
        if (msg.size < 12) {
            map = IntArray(0); sNum = 0; return
        }
        var k = 0
        for (i in 0 until 8) k = k or (((msg[4 + i].toInt() and 8) shr 3) shl i)
        val m = IntArray(256) { it }
        var idx = 0
        for (i in 0 until 256) {
            idx = (idx + m[i] + k) % 256
            val t = m[i]; m[i] = m[idx]; m[idx] = t
        }
        map = m; sNum = 0
    }

    fun key(inc: Boolean = false): Int {
        if (map.isEmpty()) return 0
        val v = map[sNum]
        if (inc) sNum = (sNum + 1) % 256
        return v
    }
}

fun xor(b: ByteArray, x: Int) = ByteArray(b.size) { (b[it].toInt() xor x).toByte() }

/** Extracts complete frames from [buf]; returns decoded frames and the unconsumed remainder. */
fun splitFrames(buf: ByteArray): Pair<List<ByteArray>, ByteArray> {
    var b = buf
    val out = mutableListOf<ByteArray>()
    while (b.size >= 4) {
        var found = false
        for (x in intArrayOf(b[2].toInt() and 0xFF, (b[2].toInt() and 0xFF) xor 1)) {
            val n = ((b[1].toInt() and 0xFF) xor x) + 2
            if (n <= b.size) {
                val m = xor(b.copyOfRange(0, n), x)
                if (checksum(m) == (m[n - 1].toInt() and 0xFF)) {
                    out.add(m)
                    b = b.copyOfRange(n, b.size)
                    found = true
                    break
                }
            }
        }
        if (!found) {
            if (b.size < 64) break
            b = b.copyOfRange(1, b.size)
        }
    }
    return out to b
}
