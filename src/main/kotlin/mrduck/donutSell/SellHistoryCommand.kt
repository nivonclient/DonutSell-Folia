package mrduck.donutSell

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.entity.Player

class SellHistoryCommand(private val plugin: DonutSell) {
    
    init {
        register()
    }
    
    private fun register() {
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(buildCommand().build())
        }
    }
    
    private fun buildCommand(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("sellhistory")
            .requires { it.sender is Player }
            .executes { ctx ->
                val player = ctx.source.sender as Player
                player.scheduler.run(plugin, { _ ->
                    plugin.sellHistoryGui.open(player, 1)
                }, null)
                Command.SINGLE_SUCCESS
            }
            // Optional: /sellhistory <page>
            .then(
                Commands.argument("page", IntegerArgumentType.integer(1))
                    .executes { ctx ->
                        val player = ctx.source.sender as Player
                        val page = IntegerArgumentType.getInteger(ctx, "page")
                        player.scheduler.run(plugin, { _ ->
                            plugin.sellHistoryGui.open(player, page)
                        }, null)
                        Command.SINGLE_SUCCESS
                    }
            )
    }
}