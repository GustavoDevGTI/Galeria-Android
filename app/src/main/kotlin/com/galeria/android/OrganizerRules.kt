package com.galeria.android

import kotlin.math.atan2
import kotlin.math.hypot

/** The same angular hit rules are used for dragging, flicking and reordering. */
internal object OrganizerRules {
    const val MAX_DESTINATIONS = 8

    fun sector(dx: Float, dy: Float, count: Int = MAX_DESTINATIONS): Int {
        require(count in 1..MAX_DESTINATIONS)
        val sweep = 360.0 / count
        return (((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 90 + sweep / 2 + 360) % 360) / sweep).toInt()
            .coerceAtMost(count - 1)
    }

    fun releasedTarget(dx: Float, dy: Float, diameter: Float, velocity: Float, count: Int = MAX_DESTINATIONS): Int? {
        if (diameter <= 0 || count !in 1..MAX_DESTINATIONS) return null
        val distance = hypot(dx, dy)
        return if (distance >= diameter * .12f || (distance >= diameter * .065f && velocity >= .35f))
            sector(dx, dy, count) else null
    }

    fun <T> swap(items: MutableList<T>, from: Int, to: Int): Boolean {
        if (from !in items.indices || to !in items.indices || from == to) return false
        val old = items[from]; items[from] = items[to]; items[to] = old
        return true
    }

    fun validSelection(source: String?, destinations: List<String>): Boolean =
        !source.isNullOrBlank() && destinations.size in 1..MAX_DESTINATIONS &&
            destinations.distinct().size == destinations.size && source !in destinations
}

/** Taking a ticket is immediate. Filesystem completion never gates the next item. */
internal class OrganizerQueue<T>(items: List<T>, reservedItems: List<T> = emptyList()) {
    data class Ticket<T>(val item: T, val reserved: Boolean, val index: Int, val order: Long)
    val queue = items.toMutableList()
    val reserved = reservedItems.toMutableList()
    private val queueOrder = items.withIndex().associate { it.value to it.index.toLong() }.toMutableMap()
    private val reserveOrder = reservedItems.withIndex().associate { it.value to it.index.toLong() }.toMutableMap()
    private var nextReserveOrder = reservedItems.size.toLong()

    fun take(item: T, fromReserve: Boolean): Ticket<T>? {
        val list = if (fromReserve) reserved else queue
        val index = list.indexOf(item)
        if (index < 0 || (!fromReserve && index != 0)) return null
        list.removeAt(index)
        return Ticket(item, fromReserve, index, (if (fromReserve) reserveOrder else queueOrder)[item] ?: 0L)
    }

    fun restore(ticket: Ticket<T>, item: T = ticket.item) {
        val list = if (ticket.reserved) reserved else queue
        val order = if (ticket.reserved) reserveOrder else queueOrder
        order[item] = ticket.order
        val index = list.indexOfFirst { (order[it] ?: Long.MAX_VALUE) > ticket.order }.let { if (it < 0) list.size else it }
        if (item !in list) list.add(index, item)
    }

    fun reserve(): Ticket<T>? {
        val item = queue.firstOrNull() ?: return null
        val ticket = take(item, false) ?: return null
        reserved.add(item)
        reserveOrder[item] = nextReserveOrder++
        return ticket
    }
}
