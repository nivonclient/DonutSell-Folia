package mrduck.donutSell

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import net.milkbowl.vault2.economy.Economy
import org.bukkit.entity.Player
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat

class VaultMoneyPlaceholder(
    private val plugin: DonutSell
) : PlaceholderExpansion() {
    
    private val econ: Economy = plugin.econ!!
    private val twoDecimals = DecimalFormat("#.##")
    
    override fun persist(): Boolean = true
    
    override fun canRegister(): Boolean = true
    
    override fun getIdentifier(): String = "vaultmoney"

    override fun getAuthor(): String = plugin.description.authors.joinToString(", ")
    
    override fun getVersion(): String = plugin.description.version
    
    override fun onPlaceholderRequest(player: Player?, identifier: String): String? {
        player ?: return ""
        
        val balance = econ.getBalance(plugin.name, player.uniqueId)
        
        return when (identifier) {
            "sell_money" -> twoDecimals.format(balance)
            "sell_money_formatted" -> abbreviate(balance)
            else -> null
        }
    }
    
    private fun abbreviate(value: BigDecimal): String {
        val abs = value.abs()
        
        val (shortVal, suffix) = when {
            abs >= TRILLION -> value.divide(TRILLION, 2, RoundingMode.HALF_UP) to SUFFIX_TRILLION
            abs >= BILLION -> value.divide(BILLION, 2, RoundingMode.HALF_UP) to SUFFIX_BILLION
            abs >= MILLION -> value.divide(MILLION, 2, RoundingMode.HALF_UP) to SUFFIX_MILLION
            abs >= THOUSAND -> value.divide(THOUSAND, 2, RoundingMode.HALF_UP) to SUFFIX_THOUSAND
            else -> return twoDecimals.format(value.toDouble())
        }
        
        return "${twoDecimals.format(shortVal.toDouble())}$suffix"
    }
    
    private companion object {
        private val THOUSAND = BigDecimal.valueOf(1_000)
        private val MILLION = BigDecimal.valueOf(1_000_000)
        private val BILLION = BigDecimal.valueOf(1_000_000_000)
        private val TRILLION = BigDecimal.valueOf(1_000_000_000_000L)
        
        private const val SUFFIX_TRILLION = "T"
        private const val SUFFIX_BILLION = "B"
        private const val SUFFIX_MILLION = "M"
        private const val SUFFIX_THOUSAND = "K"
    }
}