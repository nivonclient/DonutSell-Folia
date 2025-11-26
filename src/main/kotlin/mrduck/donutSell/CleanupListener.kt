package mrduck.donutSell

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.ItemStack

class CleanupListener(private val plugin: DonutSell) : Listener {
    
    private val lorePrefixes: List<String> = plugin.config.getStringList("lore")
        .map { line ->
            PlainTextComponentSerializer.plainText()
                .serialize(LegacyComponentSerializer.legacySection().deserialize(line.replace("%amount%", "")))
                .lowercase()
                .trim()
        }
    
    fun stripAllLore(player: Player) {
        player.inventory.contents
            .filterNotNull()
            .filter { it.hasItemMeta() }
            .forEach { item ->
                item.itemMeta?.let { meta ->
                    meta.lore()?.takeIf { it.isNotEmpty() }?.let { lore ->
                        val filtered = lore.filterLore()
                        meta.lore(filtered.takeIf { it.isNotEmpty() })
                        item.itemMeta = meta
                    }
                }
            }
        
        player.updateInventory()
    }
    
    @EventHandler
    fun onPlayerJoin(e: PlayerJoinEvent) {
        if (e.player.gameMode == GameMode.CREATIVE) {
            stripAllLore(e.player)
        }
    }
    
    @EventHandler
    fun onPlayerGameModeChange(e: PlayerGameModeChangeEvent) {
        when (e.newGameMode) {
            GameMode.CREATIVE -> stripAllLore(e.player)
            GameMode.SURVIVAL -> e.player.updateInventory()
            else -> {}
        }
    }
    
    @EventHandler
    fun onPickup(e: EntityPickupItemEvent) {
        (e.entity as? Player)?.let { player ->
            if (player.gameMode == GameMode.CREATIVE) {
                e.item.itemStack = stripLoreFromStack(e.item.itemStack)
            }
        }
    }
    
    @EventHandler
    fun onItemMerge(e: ItemMergeEvent) {
        e.target.itemStack = stripLoreFromStack(e.target.itemStack)
    }
    
    private fun stripLoreFromStack(original: ItemStack?): ItemStack {
        if (original == null || !original.hasItemMeta()) return original ?: ItemStack.empty()
        
        return original.clone().apply {
            itemMeta = itemMeta?.apply {
                lore()?.takeIf { it.isNotEmpty() }?.let { lore ->
                    val filtered = lore.filterLore()
                    lore(filtered.takeIf { it.isNotEmpty() })
                }
            }
        }
    }
    
    private fun List<Component>.filterLore(): List<Component> {
        return filter { comp ->
            val plain = PlainTextComponentSerializer.plainText()
                .serialize(comp)
                .lowercase()
                .trim()
            lorePrefixes.none { plain.startsWith(it) }
        }
    }
    
    @EventHandler
    fun onInventoryClick(e: InventoryClickEvent) {
        val player = e.whoClicked as Player
        if (player.gameMode != GameMode.CREATIVE) {
            player.scheduler.runDelayed(plugin, { _ ->
                player.updateInventory()
            }, null, 1L)
        }
    }
    
    @EventHandler
    fun onInventoryDrag(e: InventoryDragEvent) {
        val player = e.whoClicked as Player
        if (player.gameMode != GameMode.CREATIVE) {
            player.scheduler.runDelayed(plugin, { _ ->
                player.updateInventory()
            }, null, 1L)
        }
    }
    
    @EventHandler
    fun onInventoryClose(e: InventoryCloseEvent) {
        val player = e.player as Player
        if (player.gameMode == GameMode.CREATIVE) {
            stripAllLore(player)
        }
    }
}