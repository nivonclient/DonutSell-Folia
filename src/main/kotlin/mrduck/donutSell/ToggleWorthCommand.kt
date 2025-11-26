package mrduck.donutSell

import com.mojang.brigadier.Command
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.entity.Player

class ToggleWorthCommand(private val plugin: DonutSell) {
    
    init {
        register()
    }
    
    private fun register() {
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(build().build())
        }
    }
    
    private fun build(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("toggleworth")
            .requires { it.sender is Player }
            .executes { ctx ->
                val player = ctx.source.sender as Player
                val id = player.uniqueId
                val target = plugin.isWorthDisabled(id)
                
                plugin.setWorthEnabled(id, target)
                
                player.scheduler.run(plugin, { _ ->
                    if (!target) {
                        plugin.cleanupListener.stripAllLore(player)
                    } else {
                        player.updateInventory()
                    }
                }, null)
                
                val (path, fallback) = if (target) {
                    "messages.worth-enabled" to "&#34ee80Worth lore: &aENABLED"
                } else {
                    "messages.worth-disabled" to "&#34ee80Worth lore: &cDISABLED"
                }
                
                val message = plugin.config.getString(path, fallback) ?: fallback
                player.sendMessage(Utils.formatColors(message))
                
                Command.SINGLE_SUCCESS
            }
    }
}