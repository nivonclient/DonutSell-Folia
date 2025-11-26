package mrduck.donutSell

import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType

class SellAxeCommand(
    private val plugin: DonutSell,
    private val sellAxeKey: NamespacedKey,
    private val expiryKey: NamespacedKey
) {
    
    init {
        registerBrigadier()
    }
    
    private fun registerBrigadier() {
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { commands ->
            commands.registrar().register(
                Commands.literal("donutsell")
                    .then(Commands.literal("givesellwand")
                        .requires { it.sender.hasPermission("sell.admin") }
                        .then(Commands.argument("player", StringArgumentType.word())
                            .suggests { _, builder ->
                                val prefix = builder.remainingLowerCase
                                Bukkit.getOnlinePlayers()
                                    .filter { it.name.lowercase().startsWith(prefix) }
                                    .forEach { builder.suggest(it.name) }
                                builder.buildFuture()
                            }
                            .executes { ctx ->
                                val targetName = StringArgumentType.getString(ctx, "player")
                                executeGiveWand(ctx.source, targetName)
                                1
                            }
                        )
                    )
                    .build()
            )
        }
    }
    
    fun executeGiveWand(source: CommandSourceStack, targetName: String) {
        val target = Bukkit.getPlayerExact(targetName)
        
        if (target == null) {
            source.sender.sendMessage(Utils.formatColors("&cPlayer not found: $targetName"))
            return
        }
        
        val wand = createSellWand()
        if (wand == null) {
            plugin.logger.warning("Failed to create Sell Wand!")
            return
        }
        
        target.scheduler.run(plugin, { _ ->
            target.inventory.addItem(wand)
        }, null)
        
        source.sender.sendMessage(Utils.formatColors("&aGave Sell Wand to &f${target.name}"))
    }
    
    private fun createSellWand(): ItemStack? {
        val sellAxe = ItemStack(Material.NETHERITE_AXE, 1)
        val meta = sellAxe.itemMeta ?: return null
        
        val rawName = plugin.config.getString("sell-axe.display-name", "&aSell Wand") ?: "&aSell Wand"
        meta.displayName(Component.text(Utils.formatColors(rawName).toString()))
        
        val loreTemplate = plugin.config.getStringList("sell-axe.lore").toMutableList()
        val useCountdown = plugin.config.getBoolean("sell-axe.use-countdown", true)
        var expiryMillis = 0L
        
        if (useCountdown) {
            val durationSeconds = plugin.config.getLong("sell-axe.duration-seconds", 259200L)
            expiryMillis = System.currentTimeMillis() + durationSeconds * MILLIS_PER_SECOND
            val formatted = formatDuration(durationSeconds * MILLIS_PER_SECOND)
            loreTemplate.replaceAll { s ->
                Utils.formatColors(s.replace("%countdown%", formatted)).toString()
            }
        } else {
            loreTemplate.replaceAll { Utils.formatColors(it).toString() }
        }
        
        meta.lore(loreTemplate.map { Component.text(it) })
        
        val enchList = plugin.config.getStringList("sell-axe.enchantments")
        applyEnchantments(meta, enchList)
        
        val pdc = meta.persistentDataContainer
        pdc.set(sellAxeKey, PersistentDataType.BYTE, 1.toByte())
        if (useCountdown) {
            pdc.set(expiryKey, PersistentDataType.LONG, expiryMillis)
        }
        
        sellAxe.itemMeta = meta
        return sellAxe
    }
    
    private fun applyEnchantments(meta: ItemMeta, enchList: List<String>) {
        enchList.forEach { entry ->
            val parts = entry.split(":")
            if (parts.size != 2) return@forEach
            
            val enchName = parts[0].trim().uppercase()
            val ench = Enchantment.getByName(enchName)
            
            try {
                val level = parts[1].trim().toInt()
                if (ench != null) {
                    meta.addEnchant(ench, level, true)
                } else {
                    plugin.logger.warning("Unknown enchantment: $enchName")
                }
            } catch (e: NumberFormatException) {
                plugin.logger.warning("Invalid enchant level: $entry")
            }
        }
    }
    
    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / MILLIS_PER_SECOND
        val days = totalSeconds / SECONDS_PER_DAY
        val hours = (totalSeconds % SECONDS_PER_DAY) / SECONDS_PER_HOUR
        val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        return "${days}d ${hours}h ${minutes}m ${seconds}s"
    }
    
    companion object {
        private const val MILLIS_PER_SECOND = 1000L
        private const val SECONDS_PER_MINUTE = 60L
        private const val SECONDS_PER_HOUR = 3600L
        private const val SECONDS_PER_DAY = 86400L
    }
}