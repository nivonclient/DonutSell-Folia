package mrduck.donutSell

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class HistoryTracker {
    
    private val pages = ConcurrentHashMap<UUID, Int>()
    private val orders = ConcurrentHashMap<UUID, SortOrder>()
    private val filters = ConcurrentHashMap<UUID, String>()
    
    fun setPage(player: UUID, page: Int) {
        pages[player] = page
    }
    
    fun getPage(player: UUID): Int = pages.getOrDefault(player, 1)
    
    fun setOrder(player: UUID, order: SortOrder) {
        orders[player] = order
    }
    
    fun getOrder(player: UUID): SortOrder = orders.getOrDefault(player, SortOrder.HIGH)
    
    fun cycleOrder(player: UUID) {
        val next = when (getOrder(player)) {
            SortOrder.HIGH -> SortOrder.LOW
            SortOrder.LOW -> SortOrder.NAME
            SortOrder.NAME -> SortOrder.HIGH
        }
        orders[player] = next
    }
    
    fun setFilter(player: UUID, filter: String?) {
        if (filter == null) {
            filters.remove(player)
        } else {
            filters[player] = filter
        }
    }
    
    fun getFilter(player: UUID): String? = filters[player]
    
    fun isTracked(player: UUID): Boolean = pages.containsKey(player)
    
    fun clear(player: UUID) {
        pages.remove(player)
        orders.remove(player)
        filters.remove(player)
    }
    
    enum class SortOrder {
        HIGH,
        LOW,
        NAME
    }
}