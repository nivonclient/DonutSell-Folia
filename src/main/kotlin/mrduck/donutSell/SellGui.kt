package mrduck.donutSell

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import kotlin.math.min
import kotlin.math.round

class SellGui(private val plugin: DonutSell) {
    
    private var title: Component = Component.empty()
    private var rows: Int = 0
    var size: Int = 0
        private set
    private var barLength: Int = 0
    private var barSymbol: String = ""
    private var loadingColor: String = ""
    private var completeLoadingColor: String = ""
    private val useNewMenuFlag: Boolean = plugin.config.getBoolean("use-new-sell-menu", false)
    
    // Cached level data to avoid repeated config reads
    private var cachedLevels: List<LevelData> = emptyList()
    
    init {
        loadConfig()
    }
    
    private fun loadSection(section: String) {
        val cfg = plugin.config.getConfigurationSection(section)
            ?: throw IllegalStateException("Missing section '$section' in config.yml")
        
        title = Utils.formatColors(cfg.getString("title", "&aSell Items") ?: "&aSell Items")
        rows = cfg.getInt("rows", 5)
        size = rows * 9
        barLength = cfg.getInt("bar-length", 10)
        barSymbol = Utils.formatColors(cfg.getString("bar-symbol", "#") ?: "#").toString()
        loadingColor = Utils.formatColors(cfg.getString("loading-color", "") ?: "").toString()
        completeLoadingColor = Utils.formatColors(
            cfg.getString("complete-loading-color", loadingColor) ?: loadingColor
        ).toString()
    }
    
    fun loadConfig() {
        loadSection("sell-menu")
        // Pre-cache levels on config load
        cachedLevels = loadLevels()
    }
    
    fun open(player: Player): Inventory? {
        // Folia: Must be executed on entity scheduler
        if (!Bukkit.isOwnedByCurrentRegion(player)) {
            player.scheduler.run(plugin, { _ -> open(player) }, null)
            return null
        }
        
        val inv = Bukkit.createInventory(null, size, title)
        
        if (!useNewMenuFlag && plugin.isUseMultipliers()) {
            populateBottomRow("sell-menu", inv, cachedLevels, player)
        }
        
        player.openInventory(inv)
        return inv
    }
    
    fun DonutSell(player: Player): Inventory? {
        // Folia: Must be executed on entity scheduler
        if (!Bukkit.isOwnedByCurrentRegion(player)) {
            player.scheduler.run(plugin, { _ -> DonutSell(player) }, null)
            return null
        }
        
        loadSection("new-sell-menu")
        val inv = Bukkit.createInventory(null, size, title)
        val lvlList = loadLevels()
        
        if (plugin.isUseMultipliers()) {
            populateBottomRow("new-sell-menu", inv, lvlList, player)
        }
        
        player.openInventory(inv)
        loadConfig()
        return inv
    }
    
    private fun loadLevels(): List<LevelData> {
        val lvlSec = plugin.config.getConfigurationSection("progress-menu.levels")
            ?: return emptyList()
        
        return lvlSec.getKeys(false)
            .mapNotNull { key ->
                lvlSec.getConfigurationSection(key)?.let { ls ->
                    LevelData(
                        amountNeeded = ls.getLong("amountNeeded", 0L),
                        multi = ls.getDouble("multi", 1.0)
                    )
                }
            }
            .sortedBy { it.amountNeeded }
    }
    
    private fun populateBottomRow(
        section: String,
        inv: Inventory,
        lvlList: List<LevelData>,
        player: Player
    ) {
        if (!plugin.isUseMultipliers()) return
        
        val items = plugin.config.getStringList("$section.items")
        val settings = plugin.config.getConfigurationSection("$section.item-settings")
        val defaultStart = (rows - 1) * 9
        
        items.forEachIndexed { i, catKey ->
            val itemSettings = settings?.getConfigurationSection(catKey)
            val slot = itemSettings?.getInt("slot") ?: (defaultStart + i)
            
            val matName = itemSettings?.getString("material") ?: catKey
            val mat = try {
                Material.valueOf(matName.uppercase())
            } catch (_: IllegalArgumentException) {
                return@forEachIndexed
            }
            
            val button = ItemStack(mat, 1)
            button.itemMeta = button.itemMeta?.apply {
                val next = findNextLevel(lvlList, player, catKey)
                val pct = computePct(next, player, catKey)
                val bar = buildBar(pct)
                
                if (itemSettings != null) {
                    displayName(Component.text(
                        Utils.formatColors(
                            itemSettings.getString("displayname", catKey) ?: catKey
                        ).toString()
                    ))
                    
                    lore(itemSettings.getStringList("lore").map { line ->
                        Utils.formatColors(
                            line.replace("%next-multi%", "%.1f".format(next.multi))
                                .replace("%progress%", "%.1f".format(pct))
                                .replace("%progress-bar%", bar)
                        ).toString()
                    }.component())
                } else {
                    displayName(Component.text(
                        Utils.formatColors("&e${catKey.lowercase()}").toString()
                    ))
                }
            }
            
            inv.setItem(slot, button)
        }
    }
    
    private fun findNextLevel(lvlList: List<LevelData>, player: Player, key: String): LevelData {
        val sold = plugin.getRawTotalSold(player.uniqueId, key.lowercase())
        
        return lvlList.firstOrNull { it.amountNeeded > sold }
            ?: lvlList.lastOrNull()
            ?: LevelData(1L, 1.0)
    }
    
    private fun computePct(ld: LevelData, player: Player, key: String): Double {
        val sold = plugin.getRawTotalSold(player.uniqueId, key.lowercase())
        return if (ld.amountNeeded > 0L) {
            min(sold / ld.amountNeeded * 100.0, 100.0)
        } else {
            100.0
        }
    }
    
    private fun buildBar(pct: Double): String {
        if (pct >= 100.0) {
            return completeLoadingColor + barSymbol.repeat(barLength)
        }
        
        val filled = round(barLength * (pct / 100.0)).toInt()
        return buildString {
            append(loadingColor)
            append(barSymbol.repeat(filled.coerceAtLeast(0)))
            append("&f")
            append(barSymbol.repeat((barLength - filled).coerceAtLeast(0)))
        }
    }
    
    private data class LevelData(
        val amountNeeded: Long,
        val multi: Double
    )
}