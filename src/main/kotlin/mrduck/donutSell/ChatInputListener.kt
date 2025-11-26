package mrduck.donutSell

import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

class ChatInputListener(private val plugin: DonutSell) : Listener {
    
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onChat(e: AsyncChatEvent) {
        val player = e.player
        val uuid = player.uniqueId
        val viewTracker = plugin.viewTracker
        val filter = viewTracker.getFilter(uuid)
        
        if (filter != null && filter.isEmpty()) {
            e.isCancelled = true
            
            val input = PlainTextComponentSerializer.plainText()
                .serialize(e.message())
                .trim()
            
            viewTracker.setFilter(uuid, input)
            
            plugin.server.scheduler.runTask(plugin) { _ ->
                plugin.itemPricesMenu.open(player, 1)
            }
        }
    }
}