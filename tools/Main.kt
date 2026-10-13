package phev

fun main(args: Array<String>) {
    val port = args.getOrNull(0)?.toInt() ?: 18080
    val seen = mutableListOf<Int>()
    val c = CarClient("127.0.0.1", port) { reg, data ->
        seen.add(reg)
        println("reg 0x%02X %s".format(reg, data.joinToString("") { "%02x".format(it) }))
    }
    Thread { Thread.sleep(8000); c.running = false }.start()
    try { c.run() } catch (e: Exception) { println("ended: $e") }
    println("REGS SEEN: $seen")
}
