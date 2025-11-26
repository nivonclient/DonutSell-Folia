package mrduck.donutSell

import org.bukkit.Material
import org.bukkit.block.ShulkerBox
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import java.util.concurrent.ConcurrentHashMap

class SellListener(private val plugin: DonutSell) : Listener {
    
    private val values = ConcurrentHashMap<Material, Int>()
    private val loreTemplate: List<String> = plugin.config.getStringList("lore")
    
    init {
        loadValues()
    }
    
    private fun loadValues() {
        val config = plugin.config
        
        config.getKeys(false)
            .filter { it.endsWith(VALUE_SUFFIX) }
            .forEach { key ->
                val matName = key.dropLast(VALUE_SUFFIX_LENGTH).uppercase()
                
                try {
                    val mat = Material.valueOf(matName)
                    values[mat] = config.getInt(key)
                } catch (e: IllegalArgumentException) {
                    plugin.logger.warning("Unknown material in config: $matName")
                }
            }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    fun onInventoryOpen(event: InventoryOpenEvent) {
        if (event.isCancelled) return
        val player = event.player as? Player ?: return
        
        val inv = if (event.view.type == InventoryType.CRAFTING) {
            event.view.bottomInventory
        } else {
            event.inventory
        }
        
        player.scheduler.run(plugin, { _ ->
            updateInventoryLore(inv)
        }, null)
    }
    
    private fun updateInventoryLore(inv: Inventory) {
        val size = inv.size
        
        for (slot in 0 until size) {
            val item = inv.getItem(slot) ?: continue
            if (item.type == Material.AIR) continue
            
            val totalValue = calculateItemValue(item)
            if (totalValue <= 0) continue
            
            val meta = item.itemMeta ?: continue
            
            val newLore = buildLore(totalValue)
            meta.lore(newLore.component())
            item.itemMeta = meta
            inv.setItem(slot, item)
        }
    }
    
    private fun calculateItemValue(item: ItemStack): Int {
        val baseValue = values.getOrDefault(item.type, 0)
        var totalValue = baseValue * item.amount
        
        val meta = item.itemMeta
        if (meta is BlockStateMeta) {
            val state = meta.blockState
            if (state is ShulkerBox) {
                totalValue += calculateShulkerValue(state)
            }
        }
        
        return totalValue
    }
    
    private fun calculateShulkerValue(box: ShulkerBox): Int {
        return box.inventory.contents
            .filterNotNull()
            .filter { it.type != Material.AIR }
            .sumOf { inside ->
                val insideBase = values.getOrDefault(inside.type, 0)
                insideBase * inside.amount
            }
    }
    
    private fun buildLore(totalValue: Int): List<String> {
        val valueStr = totalValue.toString()
        return loreTemplate.map { line ->
            line.replace("%amount%", valueStr)
        }
    }
    
    companion object {
        private const val VALUE_SUFFIX = "-value"
        private const val VALUE_SUFFIX_LENGTH = 6
    }
}