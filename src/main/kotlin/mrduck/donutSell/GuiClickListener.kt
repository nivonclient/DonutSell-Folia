package mrduck.donutSell

import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import kotlin.math.ceil
import kotlin.math.max

class GuiClickListener(private val plugin: DonutSell) : Listener {
    
    @EventHandler
    fun onClick(e: InventoryClickEvent) {
        val holder = e.view.topInventory.holder as? GuiHolder ?: return
        
        e.isCancelled = true
        val player = e.whoClicked as Player
        val slot = e.rawSlot
        val config = plugin.config
        
        if (holder.page == -1) {
            handleProgressMenu(player, holder, slot, config)
        } else {
            handleCategoryMenu(player, holder, slot, config, e)
        }
    }
    
    private fun handleProgressMenu(player: Player, holder: GuiHolder, slot: Int, config: FileConfiguration) {
        val cat = holder.categoryKey
        val icon = plugin.progressGui.categoryIcons[cat]
        val backSlot = config.getInt("progress-menu.back-button.slot", 45)
        
        when (slot) {
            icon?.slot -> {
                player.playClickSound(config)
                player.scheduler.runDelayed(plugin, { _ -> 
                    CategoryGui(plugin, cat).open(player, 0)
                }, null, 1L)
            }
            
            backSlot -> {
                player.playClickSound(config)
                player.scheduler.run(plugin, { _ -> 
                    plugin.sellGui.open(player)
                }, null)
            }
        }
    }
    
    private fun handleCategoryMenu(
        player: Player,
        holder: GuiHolder,
        slot: Int,
        config: FileConfiguration,
        e: InventoryClickEvent
    ) {
        val cat = holder.categoryKey
        val page = holder.page
        val prevSlot = config.getInt("category-menu.previous-page-slot", 49)
        val nextSlot = config.getInt("category-menu.next-page-slot", 51)
        val backSlot = config.getInt("category-menu.back-button.slot", 45)
        val rows = config.getInt("category-menu.rows", 6)
        val perPage = (rows - 1) * 9
        
        when (slot) {
            in 0 until perPage -> {
                e.view.topInventory.getItem(slot)?.let {
                    player.playClickSound(config)
                }
            }
            
            prevSlot -> if (page > 0) {
                player.playClickSound(config)
                player.scheduler.runDelayed(plugin, { _ ->
                    CategoryGui(plugin, cat).open(player, page - 1)
                }, null, 1L)
            }
            
            nextSlot -> {
                val totalEntries = calculateTotalEntries(config, cat)
                val maxPage = max(0, ceil(totalEntries.toDouble() / perPage).toInt() - 1)
                
                if (page < maxPage) {
                    player.playClickSound(config)
                    player.scheduler.runDelayed(plugin, { _ ->
                        CategoryGui(plugin, cat).open(player, page + 1)
                    }, null, 1L)
                }
            }
            
            backSlot -> {
                player.playClickSound(config)
                player.scheduler.run(plugin, { _ ->
                    plugin.progressGui.open(player, cat)
                }, null)
            }
        }
    }
    
    private fun calculateTotalEntries(config: FileConfiguration, cat: String): Int {
        return config.getList("categories.$cat")
            ?.filterIsInstance<Map<*, *>>()
            ?.sumOf { it.size }
            ?: 0
    }
    
    @EventHandler
    fun onDrag(e: InventoryDragEvent) {
        if (e.view.topInventory.holder is GuiHolder) {
            e.isCancelled = true
        }
    }

    private fun Player.playClickSound(config: FileConfiguration) {
        val soundName = config.getString("sounds.click-sound", "UI_BUTTON_CLICK")!!

        val sound: Sound? = runCatching {
            Registry.SOUNDS.get(NamespacedKey.minecraft(soundName.uppercase()))
        }.getOrDefault(Sound.UI_BUTTON_CLICK)

        playSound(location, sound!!, 1.0f, 1.0f)
    }
}