package mrduck.donutSell

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

class WorthCommand(private val plugin: DonutSell) {
    
    private val materialKeys: List<String> = Material.entries
        .filter { it.isItem }
        .map { it.name.lowercase() }
        .sorted()
    
    init {
        register()
    }
    
    private fun register() {
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(build().build())
        }
    }
    
    private fun build(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("worth")
            .requires { it.sender is Player }
            
            // /worth
            .executes { ctx ->
                val player = ctx.source.sender as Player
                val id = player.uniqueId
                
                player.scheduler.run(plugin, { _ ->
                    plugin.viewTracker.setFilter(id, null)
                    plugin.itemPricesMenu.open(player, 1)
                }, null)
                
                Command.SINGLE_SUCCESS
            }
            
            // /worth <item>
            .then(
                Commands.argument("item", StringArgumentType.greedyString())
                    .suggests { _, builder ->
                        val prefix = builder.remaining.lowercase().replace(" ", "_")
                        materialKeys
                            .filter { it.startsWith(prefix) }
                            .forEach { builder.suggest(it.replace("_", " ")) }
                        builder.buildFuture()
                    }
                    .executes { ctx ->
                        val player = ctx.source.sender as Player
                        val id = player.uniqueId
                        val input = StringArgumentType.getString(ctx, "item")
                        val joined = input.replace(" ", "_").uppercase()
                        
                        val material = Material.matchMaterial(joined)
                        
                        if (material == null || !material.isItem) {
                            val template = plugin.config.getString(
                                "messages.worth-invalid",
                                "&cUnknown item: %input%"
                            ) ?: "&cUnknown item: %input%"
                            
                            player.sendMessage(
                                Utils.formatColors(template.replace("%input%", input))
                            )
                            return@executes Command.SINGLE_SUCCESS
                        }
                        
                        player.scheduler.run(plugin, { _ ->
                            val stack = ItemStack(material)
                            val base = plugin.calculateItemWorth(stack)
                            
                            var multiplier = 1.0
                            var category: String? = null
                            
                            for ((categoryName, items) in plugin.categoryItems) {
                                if (material.name in items) {
                                    multiplier = plugin.getSellMultiplier(id, categoryName)
                                    category = categoryName
                                    break
                                }
                            }
                            
                            val finalValue = base * multiplier
                            val pretty = material.prettify()
                            val amount = Utils.abbreviateNumber(finalValue)
                            
                            val template = plugin.config.getString(
                                "messages.worth",
                                "&e1 %item% is worth %amount%"
                            ) ?: "&e1 %item% is worth %amount%"
                            
                            val msg = Utils.formatColors(
                                template
                                    .replace("%item%", pretty)
                                    .replace("%amount%", amount)
                                    .replace("%mult%", multiplier.toString())
                            )
                            
                            player.sendActionBar(msg)
                            player.sendMessage(msg)
                        }, null)
                        
                        Command.SINGLE_SUCCESS
                    }
            )
    }
    
    private fun Material.prettify(): String =
        name.lowercase()
            .split("_")
            .joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
}