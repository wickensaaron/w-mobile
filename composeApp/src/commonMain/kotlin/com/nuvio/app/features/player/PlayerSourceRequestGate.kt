package com.nuvio.app.features.player

/** One explicit choice owns all its asynchronous resolution stages. */
internal class PlayerSourceRequestGate {
    private var generation = 0L
    fun begin(): Long = ++generation
    fun isCurrent(token: Long): Boolean = token == generation
    fun invalidate() { generation++ }
}
