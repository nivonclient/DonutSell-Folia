package mrduck.donutSell

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class ResetConfirmationGui(private val plugin: DonutSell) : Listener {
    
    private val pendingReset = ConcurrentHashMap<UUID, UUID>()
    
    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }
    
    fun open(admin: Player, target: OfflinePlayer) {
        pendingReset[admin.uniqueId] = target.uniqueId
        val title = "$GUI_TITLE_PREFIX${target.name}"
        
        admin.scheduler.run(plugin, { _ ->
            val inv = Bukkit.createInventory(null, 36, title)
            
            // Add player head
            inv.setItem(13, createPlayerHead(target))
            
            // Add confirm button
            inv.setItem(15, createItem(Material.LIME_STAINED_GLASS_PANE, "§aConfirm Reset"))
            
            // Add cancel button
            inv.setItem(11, createItem(Material.RED_STAINED_GLASS_PANE, "§cCancel"))
            
            // Add category items
            addCategoryItems(inv, target)
            
            admin.openInventory(inv)
        }, null)
    }
    
    private fun createPlayerHead(target: OfflinePlayer): ItemStack {
        return ItemStack(Material.PLAYER_HEAD).apply {
            itemMeta = (itemMeta as? org.bukkit.inventory.meta.SkullMeta)?.apply {
                playerProfile = Bukkit.createProfile(target.uniqueId)
                displayName("§e${target.name}".component())
                lore(listOf("§7Total sold: §b${plugin.getFormattedTotalSold(target.uniqueId)}").component())
            }
        }
    }
    
    private fun createItem(material: Material, displayNameIn: String): ItemStack {
        return ItemStack(material).apply {
            itemMeta = itemMeta?.apply {
                this.displayName(displayNameIn.component())
            }
        }
    }
    
    private fun addCategoryItems(inv: org.bukkit.inventory.Inventory, target: OfflinePlayer) {
        val categoryOrder = plugin.config.getStringList("sell-menu.items")
        val settingsSection = plugin.config.getConfigurationSection("sell-menu.item-settings")
        
        categoryOrder.take(9).forEachIndexed { index, catKey ->
            val catKeyLower = catKey.lowercase()
            val categorySection = settingsSection?.getConfigurationSection(catKey)
            
            // Determine material
            val material = categorySection?.getString("material")
                ?.uppercase()
                ?.let { Material.matchMaterial(it) }
                ?: Material.matchMaterial(catKey)
            
            // Determine display name
            val displayName: Component = categorySection?.getString("displayname")
                ?.let { Utils.formatColors(it) }
                ?: "§f$catKey".component()
            
            material?.let { mat ->
                val rawCatSold = plugin.getRawTotalSold(target.uniqueId, catKeyLower)
                val formattedSold = Utils.abbreviateNumber(rawCatSold)
                
                inv.setItem(27 + index, ItemStack(mat).apply {
                    itemMeta = itemMeta?.apply {
                        this.displayName(displayName)
                        lore = listOf("§7Sold: §b$formattedSold")
                    }
                })
            }
        }
    }
    
    @EventHandler
    fun onInventoryClick(e: InventoryClickEvent) {
        val title = e.view.title
        if (!title.startsWith(GUI_TITLE_PREFIX)) return
        
        e.isCancelled = true
        val admin = e.whoClicked as? Player ?: return
        val targetUUID = pendingReset[admin.uniqueId] ?: run {
            admin.closeInventory()
            return
        }
        
        when (e.rawSlot) {
            15 -> handleConfirm(admin, targetUUID)
            11 -> handleCancel(admin)
        }
    }
    
    private fun handleConfirm(admin: Player, targetUUID: UUID) {
        val targetOffline = Bukkit.getOfflinePlayer(targetUUID)
        
        Bukkit.getGlobalRegionScheduler().run(plugin) { _ ->
            plugin.resetPlayerData(targetUUID)
        }
        
        admin.scheduler.run(plugin, { _ ->
            admin.playSound(admin.location, Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 1.0f)
            admin.sendMessage(Utils.formatColors("§aAll sell stats for §e${targetOffline.name} §ahas been reset."))
        }, null)
        
        (targetOffline as? Player)?.let { targetPlayer ->
            targetPlayer.scheduler.run(plugin, { _ ->
                targetPlayer.sendMessage(Utils.formatColors("§cYour sell stats has been reset by an admin."))
            }, null)
        }
        
        pendingReset.remove(admin.uniqueId)
        admin.closeInventory()
    }
    
    private fun handleCancel(admin: Player) {
        admin.scheduler.run(plugin, { _ ->
            admin.playSound(admin.location, Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f)
            admin.sendMessage(Utils.formatColors("§cReset cancelled."))
        }, null)
        
        pendingReset.remove(admin.uniqueId)
        admin.closeInventory()
    }
    
    companion object {
        private const val GUI_TITLE_PREFIX = "Confirm Reset: "
    }
}