package mrduck.donutSell

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ViewTracker {
    private val pages = ConcurrentHashMap<UUID, Int>()
    private val order = ConcurrentHashMap<UUID, SortOrder>()
    private val filters = ConcurrentHashMap<UUID, String>()

    fun setPage(player: UUID, page: Int) {
        pages[player] = page
    }

    fun getPage(player: UUID): Int =
        pages.getOrDefault(player, DEFAULT_PAGE)

    fun getOrder(player: UUID): SortOrder =
        order.getOrDefault(player, DEFAULT_ORDER)

    fun setOrder(player: UUID, sortOrder: SortOrder) {
        order[player] = sortOrder
    }

    fun cycleOrder(player: UUID) {
        val current = getOrder(player)
        val next = when (current) {
            SortOrder.HIGH_TO_LOW -> SortOrder.LOW_TO_HIGH
            SortOrder.LOW_TO_HIGH -> SortOrder.NAME
            SortOrder.NAME -> SortOrder.HIGH_TO_LOW
        }
        order[player] = next
    }

    fun setFilter(player: UUID, filter: String?) {
        if (filter == null) {
            filters.remove(player)
        } else {
            filters[player] = filter
        }
    }

    fun getFilter(player: UUID): String? = filters[player]

    fun clear(player: UUID) {
        pages.remove(player)
        order.remove(player)
        filters.remove(player)
    }

    fun isTracked(player: UUID): Boolean = pages.containsKey(player)

    enum class SortOrder {
        HIGH_TO_LOW,
        LOW_TO_HIGH,
        NAME
    }

    companion object {
        private const val DEFAULT_PAGE = 1
        private val DEFAULT_ORDER = SortOrder.HIGH_TO_LOW
    }
}