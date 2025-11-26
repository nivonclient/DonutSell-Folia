package mrduck.donutSell

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.entity.Player
import java.util.UUID

class SellPlaceholderExpansion(private val plugin: DonutSell) : PlaceholderExpansion() {
    
    override fun persist(): Boolean = true
    
    override fun canRegister(): Boolean = 
        plugin.server.pluginManager.isPluginEnabled("PlaceholderAPI")
    
    override fun getIdentifier(): String = "sell"
    
    override fun getAuthor(): String = plugin.description.authors.toString()
    
    override fun getVersion(): String = plugin.description.version
    
    override fun onPlaceholderRequest(player: Player?, identifier: String): String? {
        player ?: return ""
        
        return when (identifier) {
            "totalsold" -> plugin.getFormattedTotalSold(player.uniqueId)
            else -> null
        }
    }
}