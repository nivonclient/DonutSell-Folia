package mrduck.donutSell

import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent

class SellMenuClickListener(private val plugin: DonutSell) : Listener {
    
    private val useNew: Boolean = plugin.config.getBoolean("use-new-sell-menu", false)
    
    @EventHandler
    fun onClick(e: InventoryClickEvent) {
        val player = e.whoClicked as? Player ?: return
        val title = e.view.title()
        val oldTitle = Utils.formatColors(plugin.config.getString("sell-menu.title", "") ?: "")
        val newTitle = Utils.formatColors(plugin.config.getString("new-sell-menu.title", "") ?: "")
        val isOld = title == oldTitle
        val isNew = title == newTitle
        
        if (!isOld && !isNew) return
        
        val top = e.inventory
        val topSize = top.size
        val slot = e.rawSlot
        
        when {
            isNew -> handleNewMenu(e, player, slot, topSize)
            isOld && !useNew -> handleOldMenu(e, player, slot, topSize)
        }
    }
    
    private fun handleOldMenu(e: InventoryClickEvent, player: Player, slot: Int, topSize: Int) {
        val bottomStart = topSize - 9
        
        if (slot in bottomStart until (bottomStart + 9)) {
            e.isCancelled = true
            player.scheduler.run(plugin, { _ ->
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1.0f, 1.0f)
            }, null)
            
            val items = plugin.config.getStringList("sell-menu.items")
            val idx = slot - bottomStart
            
            if (idx in items.indices) {
                val category = items[idx]
                player.scheduler.run(plugin, { _ ->
                    plugin.progressGui.open(player, category)
                }, null)
            }
        }
    }
    
    private fun handleNewMenu(e: InventoryClickEvent, player: Player, slot: Int, topSize: Int) {
        val buttonSlots = getButtonSlots()
        
        when {
            slot in 0 until topSize -> {
                e.isCancelled = true
                if (slot in buttonSlots) {
                    player.scheduler.run(plugin, { _ ->
                        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1.0f, 1.0f)
                    }, null)
                    
                    val category = getCategoryForSlot(slot)
                    category?.let {
                        player.scheduler.run(plugin, { _ ->
                            plugin.progressGui.open(player, it)
                        }, null)
                    }
                }
            }
            e.isShiftClick || 
            e.click == ClickType.DOUBLE_CLICK || 
            e.click.isKeyboardClick ||
            e.action in BLOCKED_ACTIONS -> {
                e.isCancelled = true
            }
        }
    }
    
    private fun getButtonSlots(): Set<Int> {
        val buttonSlots = mutableSetOf<Int>()
        val section = plugin.config.getConfigurationSection("new-sell-menu.item-settings") ?: return buttonSlots
        
        section.getKeys(false).forEach { cat ->
            val slot = plugin.config.getInt("new-sell-menu.item-settings.$cat.slot", -1)
            if (slot >= 0) {
                buttonSlots.add(slot)
            }
        }
        
        return buttonSlots
    }
    
    private fun getCategoryForSlot(slot: Int): String? {
        val section = plugin.config.getConfigurationSection("new-sell-menu.item-settings") ?: return null
        
        return section.getKeys(false).firstOrNull { cat ->
            plugin.config.getInt("new-sell-menu.item-settings.$cat.slot", -1) == slot
        }
    }
    
    @EventHandler
    fun onDrag(e: InventoryDragEvent) {
        val player = e.whoClicked as? Player ?: return
        val title = e.view.title()
        val oldTitle = Utils.formatColors(plugin.config.getString("sell-menu.title", "") ?: "")
        val newTitle = Utils.formatColors(plugin.config.getString("new-sell-menu.title", "") ?: "")
        val isOld = title == oldTitle
        val isNew = title == newTitle
        
        if (!isOld && !isNew) return
        
        val top = e.inventory
        val topSize = top.size
        
        when {
            isNew -> {
                if (e.rawSlots.any { it in 0 until topSize }) {
                    e.isCancelled = true
                }
            }
            isOld && !useNew -> {
                val bottomStart = topSize - 9
                if (e.rawSlots.any { it in bottomStart until (bottomStart + 9) }) {
                    e.isCancelled = true
                }
            }
        }
    }
    
    companion object {
        private val BLOCKED_ACTIONS = setOf(
            InventoryAction.MOVE_TO_OTHER_INVENTORY,
            InventoryAction.HOTBAR_SWAP,
            InventoryAction.COLLECT_TO_CURSOR
        )
    }
}