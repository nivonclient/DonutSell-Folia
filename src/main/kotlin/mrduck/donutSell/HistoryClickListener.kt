package mrduck.donutSell

import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import java.util.*

class HistoryClickListener(private val plugin: DonutSell) : Listener {
    
    private val suppressClear = mutableSetOf<UUID>()
    
    @EventHandler
    fun onClick(e: InventoryClickEvent) {
        val player = e.whoClicked as? Player ?: return
        val uuid = player.uniqueId
        val tracker = plugin.historyTracker
        
        if (!tracker.isTracked(uuid)) return
        
        val gui = plugin.sellHistoryGui
        if (!gui.matchesTitle(e.view.title())) return
        
        e.isCancelled = true
        
        val slot = e.rawSlot
        val page = tracker.getPage(uuid)
        
        // Play click sound if there's an item
        e.currentItem?.takeIf { !it.type.isAir }?.let {
            player.playClickSound()
        }
        
        when (slot) {
            gui.sortSlot -> {
                tracker.cycleOrder(uuid)
                suppressClear.add(uuid)
                player.scheduler.run(plugin, { _ -> gui.open(player, page) }, null)
            }
            
            gui.refreshSlot -> {
                tracker.setFilter(uuid, null)
                suppressClear.add(uuid)
                player.scheduler.run(plugin, { _ -> gui.open(player, 1) }, null)
            }
            
            else -> handleNavigation(player, gui, slot, page)
        }
    }
    
    private fun handleNavigation(player: Player, gui: SellHistoryGui, slot: Int, page: Int) {
        val uuid = player.uniqueId
        val perPage = (gui.rows - 1) * 9
        val total = plugin.getHistory(uuid).size
        val maxPages = maxOf(1, (total + perPage - 1) / perPage)
        
        when (slot) {
            gui.backSlot -> if (page > 1) {
                suppressClear.add(uuid)
                player.scheduler.run(plugin, { _ -> gui.open(player, page - 1) }, null)
            }
            
            gui.nextSlot -> if (page < maxPages) {
                suppressClear.add(uuid)
                player.scheduler.run(plugin, { _ -> gui.open(player, page + 1) }, null)
            }
        }
    }
    
    private fun Player.playClickSound() {
        val soundName = plugin.config.getString("sounds.click-sound", "UI_BUTTON_CLICK")!!
        
        runCatching {
            Registry.SOUNDS.get(NamespacedKey.minecraft(soundName.uppercase()))
        }.getOrElse {
            Sound.UI_BUTTON_CLICK
        }?.let { sound ->
            playSound(location, sound, 1.0f, 1.0f)
        }
    }
    
    @EventHandler
    fun onDrag(e: InventoryDragEvent) {
        val player = e.whoClicked as? Player ?: return
        val tracker = plugin.historyTracker
        
        if (!tracker.isTracked(player.uniqueId)) return
        
        val gui = plugin.sellHistoryGui
        if (gui.matchesTitle(e.view.title())) {
            e.isCancelled = true
        }
    }
    
    @EventHandler
    fun onClose(e: InventoryCloseEvent) {
        val player = e.player as? Player ?: return
        val uuid = player.uniqueId
        
        if (!suppressClear.remove(uuid)) {
            plugin.historyTracker.clear(uuid)
        }
    }
}