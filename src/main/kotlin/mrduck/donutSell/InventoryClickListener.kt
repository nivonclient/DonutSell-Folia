package mrduck.donutSell

import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class InventoryClickListener(private val plugin: DonutSell) : Listener {
    
    // Thread-safe set for Folia
    private val suppressClear = ConcurrentHashMap.newKeySet<UUID>()
    
    @EventHandler
    fun onClick(e: InventoryClickEvent) {
        val player = e.whoClicked as? Player ?: return
        val uuid = player.uniqueId
        val vt = plugin.viewTracker
        
        if (!vt.isTracked(uuid)) return
        
        val top = e.view.topInventory
        if (e.clickedInventory != top) return
        
        e.isCancelled = true
        
        player.playClickSound()
        
        val slot = e.rawSlot
        val page = vt.getPage(uuid)
        val config = plugin.config.getConfigurationSection("item-prices-menu")!!
        
        val prevSlot = config.getInt("previous.previous-page-slot")
        val nextSlot = config.getInt("next.next-page-slot")
        val filterSlot = config.getInt("filter.slot")
        val refreshSlot = config.getInt("refresh.slot")
        val sortSlot = config.getInt("sort.slot")
        
        when (slot) {
            prevSlot -> if (page > 1) {
                suppressClear.add(uuid)
                plugin.itemPricesMenu.open(player, page - 1)
            }
            
            nextSlot -> {
                suppressClear.add(uuid)
                plugin.itemPricesMenu.open(player, page + 1)
            }
            
            sortSlot -> {
                vt.cycleOrder(uuid)
                suppressClear.add(uuid)
                plugin.itemPricesMenu.open(player, page)
            }
            
            filterSlot -> {
                val options = config.getStringList("filter.lore")
                val cur = vt.getFilter(uuid) ?: "all"
                val idx = options.indexOf(cur).let { if (it < 0) 0 else it }
                val nextIdx = (idx + 1) % options.size
                val nextCat = options[nextIdx]
                
                vt.setFilter(uuid, if (nextCat.equals("all", ignoreCase = true)) null else nextCat)
                suppressClear.add(uuid)
                plugin.itemPricesMenu.open(player, page)
            }
            
            refreshSlot -> {
                vt.setFilter(uuid, null)
                suppressClear.add(uuid)
                plugin.itemPricesMenu.open(player, 1)
            }
        }
    }
    
    @EventHandler
    fun onDrag(e: InventoryDragEvent) {
        val player = e.whoClicked as? Player ?: return
        val uuid = player.uniqueId
        val vt = plugin.viewTracker
        
        if (!vt.isTracked(uuid)) return
        
        val top = e.view.topInventory
        val size = top.size
        
        // Check if any dragged slot is in the top inventory
        if (e.rawSlots.any { it in 0 until size }) {
            e.isCancelled = true
            player.playClickSound()
        }
    }
    
    @EventHandler
    fun onClose(e: InventoryCloseEvent) {
        val player = e.player as? Player ?: return
        val uuid = player.uniqueId
        val vt = plugin.viewTracker
        
        if (!vt.isTracked(uuid)) return
        
        // Only clear if not suppressed
        if (!suppressClear.remove(uuid)) {
            val filter = vt.getFilter(uuid)
            if (filter == null || filter.isNotEmpty()) {
                vt.clear(uuid)
            }
        }
    }
    
    private fun Player.playClickSound() {
        scheduler.run(plugin, { _ ->
            playSound(location, Sound.UI_BUTTON_CLICK, 1.0f, 1.0f)
        }, null)
    }
}