package mrduck.donutSell

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.block.Chest
import org.bukkit.block.ShulkerBox
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.persistence.PersistentDataType
import java.math.BigDecimal

class SellAxe(
    private val plugin: DonutSell,
    private val sellAxeKey: NamespacedKey,
    private val expiryKey: NamespacedKey
) : Listener {
    
    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (event.isCancelled) return
        
        val block = event.block
        val state = block.state
        val isChest = state is Chest
        val isShulker = state is ShulkerBox
        
        if (!isChest && !isShulker) return
        
        val player = event.player
        val inHand = player.inventory.itemInMainHand
        
        if (inHand.type.isAir) return
        
        val meta = inHand.itemMeta ?: return
        val pdc = meta.persistentDataContainer
        val marker = pdc.get(sellAxeKey, PersistentDataType.BYTE)
        
        if (marker != 1.toByte()) return
        
        // Check expiry
        if (plugin.config.getBoolean("sell-axe.use-countdown", true)) {
            val expiry = pdc.get(expiryKey, PersistentDataType.LONG)
            if (expiry == null || System.currentTimeMillis() >= expiry) {
                event.isCancelled = true
                player.scheduler.run(plugin, { _ ->
                    player.inventory.remove(inHand)
                    val msg = plugin.config.getString(
                        "messages.expired-wand",
                        "&cYour DonutSell Wand has expired and been removed."
                    ) ?: "&cYour DonutSell Wand has expired and been removed."
                    player.sendMessage(Utils.formatColors(msg))
                }, null)
                return
            }
        }
        
        val containerInv = when (state) {
            is Chest -> state.inventory
            is ShulkerBox -> state.inventory
            else -> return
        }
        
        val sold = mutableMapOf<String, DonutSell.Stats>()
        val revCats = mutableMapOf<String, Double>()
        
        containerInv.contents.filterNotNull().filter { !it.type.isAir }.forEach { item ->
            processItem(item, sold, revCats)
        }
        
        if (sold.isEmpty()) {
            event.isCancelled = true
            player.sendMessage(Utils.formatColors(
                plugin.config.getString(
                    "messages.empty-chest",
                    "&7Chest is empty – nothing to sell."
                ) ?: "&7Chest is empty – nothing to sell."
            ))
        } else {
            event.isCancelled = true
            
            player.scheduler.run(plugin, { _ ->
                plugin.recordSale(player, sold)
                
                val categorizedPayout = revCats.entries.sumOf { (category, value) ->
                    value * plugin.getSellMultiplier(player.uniqueId, category)
                }
                
                val uncategorizedPayout = sold.entries
                    .filter { (key, _) ->
                        plugin.categoryItems.values.none { list ->
                            key.uppercase() in list
                        }
                    }
                    .sumOf { it.value.revenue }
                
                val payout = categorizedPayout + uncategorizedPayout
                
                plugin.econ?.deposit(plugin.name, player.uniqueId, BigDecimal.valueOf(payout))
                player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f)
                
                val actionbar = Utils.formatColors(
                    plugin.config.getString(
                        "sell-menu.actionbar-message",
                        "&aSold $%amount%"
                    ) ?: "&aSold $%amount%"
                ).toString().replace("%amount%", Utils.abbreviateNumber(payout))
                player.sendActionBar(Component.text(actionbar))
                
                val chatMsg = Utils.formatColors(
                    plugin.config.getString(
                        "sell-menu.chat-message",
                        "&7[DonutSell]&r $%amount%"
                    ) ?: "&7[DonutSell]&r $%amount%"
                ).toString().replace("%amount%", Utils.abbreviateNumber(payout))
                player.sendMessage(chatMsg)
                
                containerInv.clear()
            }, null)
        }
    }
    
    private fun processItem(
        item: ItemStack,
        sold: MutableMap<String, DonutSell.Stats>,
        revCats: MutableMap<String, Double>
    ) {
        val meta = item.itemMeta
        
        // Handle enchanted books
        if (item.type == Material.ENCHANTED_BOOK && meta is EnchantmentStorageMeta) {
            meta.storedEnchants.forEach { (enchantment, level) ->
                val enchName = enchantment.key.key.lowercase()
                val key = "$enchName$level"
                val total = plugin.getPrice("$key-value") * item.amount
                
                mergeStats(sold, key, item.amount.toDouble(), total)
                mergeCategoryRevenue(revCats, key.uppercase(), total)
            }
            return
        }
        
        // Handle shulker boxes
        if (meta is BlockStateMeta) {
            val state = meta.blockState
            if (state is ShulkerBox) {
                val boxKey = item.type.name.lowercase()
                val boxValue = plugin.getPrice("$boxKey-value") * item.amount
                
                mergeStats(sold, boxKey, item.amount.toDouble(), boxValue)
                mergeCategoryRevenue(revCats, item.type.name, boxValue)
                
                // Process nested items
                state.inventory.contents.filterNotNull().filter { !it.type.isAir }.forEach { inside ->
                    processNestedItem(inside, sold, revCats)
                }
                return
            }
        }
        
        // Handle regular items
        val key = item.type.name.lowercase()
        val value = plugin.calculateItemWorth(item)
        
        mergeStats(sold, key, item.amount.toDouble(), value)
        mergeCategoryRevenue(revCats, item.type.name, value)
    }
    
    private fun processNestedItem(
        inside: ItemStack,
        sold: MutableMap<String, DonutSell.Stats>,
        revCats: MutableMap<String, Double>
    ) {
        val innerMeta = inside.itemMeta
        
        if (inside.type == Material.ENCHANTED_BOOK && innerMeta is EnchantmentStorageMeta) {
            innerMeta.storedEnchants.forEach { (enchantment, level) ->
                val enchName = enchantment.key.key.lowercase()
                val key = "$enchName$level"
                val value = plugin.getPrice("$key-value") * inside.amount
                
                mergeStats(sold, key, inside.amount.toDouble(), value)
                mergeCategoryRevenue(revCats, key.uppercase(), value)
            }
        } else {
            val innerKey = inside.type.name.lowercase()
            val value = plugin.calculateItemWorth(inside)
            
            mergeStats(sold, innerKey, inside.amount.toDouble(), value)
            mergeCategoryRevenue(revCats, inside.type.name, value)
        }
    }
    
    private fun mergeStats(
        sold: MutableMap<String, DonutSell.Stats>,
        key: String,
        count: Double,
        revenue: Double
    ) {
        sold.merge(key, DonutSell.Stats(count, revenue)) { a, b ->
            DonutSell.Stats(a.count + b.count, a.revenue + b.revenue)
        }
    }
    
    private fun mergeCategoryRevenue(
        revCats: MutableMap<String, Double>,
        itemName: String,
        value: Double
    ) {
        plugin.categoryItems.entries.forEach { (category, items) ->
            if (itemName in items) {
                revCats.merge(category, value, Double::plus)
            }
        }
    }
}