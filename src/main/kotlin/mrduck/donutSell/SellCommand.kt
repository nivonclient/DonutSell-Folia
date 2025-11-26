package mrduck.donutSell

import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.CreatureSpawner
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionType

class SellCommand(private val plugin: DonutSell) {
    
    init {
        registerBrigadier()
    }
    
    private fun registerBrigadier() {
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(
                Commands.literal("sell")
                    .then(Commands.literal("reload")
                        .requires { it.sender.hasPermission("sell.admin") }
                        .executes { ctx ->
                            val sender = ctx.source.sender
                            Bukkit.getGlobalRegionScheduler().run(plugin) { _ ->
                                plugin.reloadPlugin()
                                sender.sendMessage(Utils.formatColors("&aSell config reloaded."))
                            }
                            1
                        }
                    )
                    .then(Commands.literal("resetall")
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
                                val sender = ctx.source.sender
                                val target = StringArgumentType.getString(ctx, "player")
                                
                                Bukkit.getAsyncScheduler().runNow(plugin) { _ ->
                                    val offline = Bukkit.getOfflinePlayer(target)
                                    
                                    if (!offline.hasPlayedBefore() && Bukkit.getPlayerExact(target) == null) {
                                        sender.sendMessage(Utils.formatColors("&cPlayer &e$target &cnot found."))
                                        return@runNow
                                    }
                                    
                                    val player = sender as? Player
                                    if (player == null) {
                                        sender.sendMessage(Utils.formatColors("&cOnly in-game players can confirm a reset."))
                                        return@runNow
                                    }
                                    
                                    player.scheduler.run(plugin, { _ ->
                                        plugin.resetConfirmationGui.open(player, offline)
                                    }, null)
                                }
                                
                                1
                            }
                        )
                    )
                    .then(Commands.literal("addhanditem")
                        .requires { it.sender.hasPermission("sell.admin") }
                        .then(Commands.argument("category", StringArgumentType.word())
                            .suggests { _, builder ->
                                val cats = plugin.config.getConfigurationSection("categories")
                                if (cats != null) {
                                    val prefix = builder.remainingLowerCase
                                    cats.getKeys(false)
                                        .filter { it.lowercase().startsWith(prefix) }
                                        .forEach { builder.suggest(it) }
                                }
                                builder.buildFuture()
                            }
                            .then(Commands.argument("price", DoubleArgumentType.doubleArg(0.0))
                                .executes { ctx ->
                                    val sender = ctx.source.sender
                                    val player = sender as? Player
                                    if (player == null) {
                                        sender.sendMessage(Utils.formatColors("&cOnly players can use this."))
                                        return@executes 1
                                    }
                                    
                                    val category = StringArgumentType.getString(ctx, "category")
                                    val price = DoubleArgumentType.getDouble(ctx, "price")
                                    
                                    if (!Bukkit.isOwnedByCurrentRegion(player)) {
                                        player.scheduler.run(plugin, { _ ->
                                            handleAddHandItem(player, category, price)
                                        }, null)
                                    } else {
                                        handleAddHandItem(player, category, price)
                                    }
                                    
                                    1
                                }
                            )
                        )
                    )
                    .executes { ctx ->
                        val sender = ctx.source.sender
                        val player = sender as? Player
                        if (player == null) {
                            sender.sendMessage(Utils.formatColors("&cOnly players may open the sell menu."))
                            return@executes 1
                        }
                        
                        if (!Bukkit.isOwnedByCurrentRegion(player)) {
                            player.scheduler.run(plugin, { _ ->
                                plugin.sellGui.open(player)
                            }, null)
                        } else {
                            plugin.sellGui.open(player)
                        }
                        1
                    }
                    .build()
            )
        }
    }
    
    private fun handleAddHandItem(player: Player, category: String, price: Double) {
        val hand = player.inventory.itemInMainHand
        
        if (hand.type.isAir) {
            player.sendMessage(Utils.formatColors("&cHold an item to set its price."))
            return
        }
        
        val entryKey = getEntryKey(hand, player) ?: return
        
        val cfg = plugin.config
        val path = "categories.$category"
        val rawList = cfg.getMapList(path)
        val newList = rawList.map { map ->
            map.entries.associate { (k, v) -> k.toString() to v }.toMutableMap()
        }.toMutableList()
        
        var replaced = false
        for (map in newList) {
            if (entryKey in map) {
                map[entryKey] = price
                replaced = true
                break
            }
        }
        
        if (!replaced) {
            newList.add(mutableMapOf(entryKey to price))
        }
        
        cfg.set(path, newList)
        
        Bukkit.getGlobalRegionScheduler().run(plugin) { _ ->
            plugin.saveConfig()
            plugin.reloadPlugin()
        }
        
        player.sendMessage(Utils.formatColors("&aSet &e$entryKey &ain category &e$category &ato &e$price"))
    }
    
    private fun getEntryKey(hand: ItemStack, player: Player): String? {
        val type = hand.type
        
        if (type == Material.SPAWNER) {
            val meta = hand.itemMeta
            if (meta is BlockStateMeta) {
                val state = meta.blockState
                if (state is CreatureSpawner) {
                    val spawned = state.spawnedType?.name?.lowercase() ?: return "spawner-value"
                    return "${spawned}_spawner-value"
                }
            }
            return "spawner-value"
        }
        
        if (type == Material.ENCHANTED_BOOK) {
            val meta = hand.itemMeta
            if (meta is EnchantmentStorageMeta) {
                if (meta.storedEnchants.size != 1) {
                    player.sendMessage(Utils.formatColors("&cHold an enchanted book with exactly one enchantment."))
                    return null
                }
                
                val (enchantment, level) = meta.storedEnchants.entries.first()
                val enchName = enchantment.key.key.lowercase()
                return "$enchName$level-value"
            }
        }
        
        val meta = hand.itemMeta
        if (meta is PotionMeta) {
            val potionType = meta.basePotionType ?: return null
            var base = potionType.name.lowercase()
            
            base = when (type) {
                Material.SPLASH_POTION -> "splash_$base"
                Material.LINGERING_POTION -> "lingering_$base"
                else -> base
            }
            
            return "$base-value"
        }
        
        return "${type.name.lowercase()}-value"
    }
}