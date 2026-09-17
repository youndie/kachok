package io.github.youndie.kachok.engine.nat

/**
 * B-103: ask the router on *this* network to forward a port, and say what it answered.
 *
 * ```
 * ./gradlew :engine:portMapProbe -Pport=6881
 * ```
 *
 * **It exists because the subject of this item is somebody else's router**, and the one on the
 * network this was written on answers neither NAT-PMP nor UPnP — which is a fact about that network
 * and not about the code. A unit test can show that a refusal is reported and that silence is given
 * up on quickly; only a real router can show that a mapping is made. This is how the person who has
 * one finds out, and it releases what it maps before it exits.
 */
public object PortMapProbe {
    @JvmStatic
    public fun main(arguments: Array<String>) {
        val port = (System.getProperty("port")?.takeIf { it.isNotBlank() } ?: arguments.getOrNull(0) ?: "6881").toInt()
        val gateway = PortMapper.defaultGateway()
        println("default gateway: ${gateway?.hostAddress ?: "none found"}")

        val mapper = PortMapper()
        val started = System.nanoTime()
        val result = mapper.map(port)
        val elapsed = (System.nanoTime() - started) / 1_000_000

        when (result) {
            is PortMapping.Mapped -> {
                println("MAPPED: external port ${result.externalPort} for ${result.lifetimeSeconds}s (${elapsed}ms)")
                println("renewal would be due in ${mapper.renewAfter(result).inWholeSeconds}s")
                mapper.release(port)
                println("released, because a hole left in somebody's router is worse than never having asked")
            }

            is PortMapping.NotMapped -> {
                println("NOT MAPPED after ${elapsed}ms: ${result.because}")
            }

            PortMapping.NotTried -> {
                println("NOT TRIED, which should be impossible here")
            }
        }
    }
}
