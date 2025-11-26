package mrduck.donutSell

import me.clip.placeholderapi.PlaceholderAPI
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import kotlin.math.min

class SellHistoryGui(private val plugin: DonutSell) {
    
    var rows: Int = 0
        private set
    var size: Int = 0
        private set
    private var titleTemplate: Component = Component.empty()
    private var titlePrefix: Component = Component.empty()
    private var itemNameTpl: Component = Component.empty()
    private var loreTpl: List<Component> = emptyList()
    
    var backSlot: Int = 0
    private var backName: Component = Component.empty()
    private var backMat: Material = Material.ARROW
    private var backLore: List<Component> = emptyList()
    
    var nextSlot: Int = 0
    private var nextName: Component = Component.empty()
    private var nextMat: Material = Material.ARROW
    private var nextLore: List<Component> = emptyList()
    
    var sortSlot: Int = 0
    private var sortName: Component = Component.empty()
    private var sortMat: Material = Material.HOPPER
    private var notCurrentColor: Component = Component.empty()
    private var currentColor: Component = Component.empty()
    private var sortLoreBase: List<Component> = emptyList()
    
    var refreshSlot: Int = 0
    private var refreshName: Component = Component.empty()
    private var refreshMat: Material = Material.ANVIL
    private var refreshLore: List<Component> = emptyList()
    
    var playerSlot: Int = 0
    private var playerNameTpl: Component = Component.empty()
    private var playerMat: Material = Material.PLAYER_HEAD
    private var playerLoreTpl: List<Component> = emptyList()
    
    init {
        loadConfig()
    }
    
    fun loadConfig() {
        val cfg = plugin.config.getConfigurationSection("sellhistory-menu")!!
        
        rows = cfg.getInt("rows", 6)
        size = rows * 9
        
        val rawTitle = cfg.getString("title", "&8Sell history (Page %history-page%)") ?: "&8Sell history (Page %history-page%)"
        titleTemplate = Utils.formatColors(rawTitle)
        
        val idx = rawTitle.indexOf("%history-page%").takeIf { it >= 0 } ?: rawTitle.length
        titlePrefix = Utils.formatColors(rawTitle.take(idx))
        
        // Sold items config
        val sold = cfg.getConfigurationSection("sold-items")!!
        itemNameTpl = Utils.formatColors(sold.getString("displayname", "%Item-Name%") ?: "%Item-Name%")
        loreTpl = sold.getStringList("lore").map { Utils.formatColors(it)}
        
        // Back button config
        val prev = cfg.getConfigurationSection("history-back")!!
        backSlot = prev.getInt("slot", 45)
        backName = Utils.formatColors(prev.getString("displayname", "&fBack") ?: "&fBack")
        backMat = Material.valueOf((prev.getString("material", "ARROW") ?: "ARROW").uppercase())
        backLore = prev.getStringList("lore").map { Utils.formatColors(it) }
        
        // Next button config
        val nxt = cfg.getConfigurationSection("history-next")!!
        nextSlot = nxt.getInt("slot", 53)
        nextName = Utils.formatColors(nxt.getString("displayname", "&fNext") ?: "&fNext")
        nextMat = Material.valueOf((nxt.getString("material", "ARROW") ?: "ARROW").uppercase())
        nextLore = nxt.getStringList("lore").map { Utils.formatColors(it) }
        
        // Sort button config
        val sort = cfg.getConfigurationSection("history-sort")!!
        sortSlot = sort.getInt("slot", 50)
        sortMat = Material.valueOf((sort.getString("material", "HOPPER") ?: "HOPPER").uppercase())
        sortName = Utils.formatColors(sort.getString("displayname", "&aSort") ?: "&aSort")
        notCurrentColor = (sort.getString("NotCurrentColor", "&f") ?: "&f").component()
        currentColor = (sort.getString("CurrentColor", "&a") ?: "&a").component()
        sortLoreBase = sort.getStringList("lore").component()
        
        // Refresh button config
        val ref = cfg.getConfigurationSection("history-refresh")!!
        refreshSlot = ref.getInt("slot", 49)
        refreshName = Utils.formatColors(ref.getString("displayname", "&aRefresh") ?: "&aRefresh")
        refreshMat = Material.valueOf((ref.getString("material", "ANVIL") ?: "ANVIL").uppercase())
        refreshLore = ref.getStringList("lore").map { Utils.formatColors(it) }
        
        // Player head config
        val pp = cfg.getConfigurationSection("history-player")!!
        playerSlot = pp.getInt("slot", 48)
        playerMat = Material.valueOf((pp.getString("material", "PLAYER_HEAD") ?: "PLAYER_HEAD").uppercase())
        playerNameTpl = (pp.getString("displayname", "%player%") ?: "%player%").component()
        playerLoreTpl = pp.getStringList("lore").component()
    }
    
    fun open(player: Player, page: Int) {
        player.scheduler.run(plugin, { _ ->
            val tracker = plugin.historyTracker
            val order = tracker.getOrder(player.uniqueId)
            val history = plugin.getHistory(player.uniqueId)
            
            val all = history.entries.toMutableList().apply {
                when (order) {
                    HistoryTracker.SortOrder.HIGH -> sortByDescending { it.value.revenue }
                    HistoryTracker.SortOrder.LOW -> sortBy { it.value.revenue }
                    HistoryTracker.SortOrder.NAME -> sortBy { it.key }
                }
            }
            
            val perPage = (rows - 1) * 9
            val from = (page - 1) * perPage
            val to = min(from + perPage, all.size)
            
            val inv = Bukkit.createInventory(
                null,
                size,
                titleTemplate.replace("%history-page%", page.toString())
            )
            
            all.subList(from, to).forEachIndexed { slotIndex, (key, stats) ->
                val mat = try {
                    Material.valueOf(key.uppercase())
                } catch (_: IllegalArgumentException) {
                    Material.STONE
                }
                
                val item = ItemStack(mat)
                item.itemMeta = item.itemMeta?.apply {
                    displayName(itemNameTpl.replace("%Item-Name%", key.capitalize()))
                    lore (loreTpl.map { line ->
                        line.replace("%amount-sold%", stats.count.toLong().toString())
                            .replace("%price-sold%", Utils.abbreviateNumber(stats.revenue))
                    })
                }
                inv.setItem(slotIndex, item)
            }
            
            // Set static items
            setStaticItem(inv, backSlot, backMat, backName, backLore)
            setStaticItem(inv, refreshSlot, refreshMat, refreshName, refreshLore)
            
            // Sort button
            val sortBtn = ItemStack(sortMat)
            sortBtn.itemMeta = sortBtn.itemMeta?.apply {
                displayName(sortName)
                lore(sortLoreBase.map { line ->
                    val trimmed = line.content().trim()
                    val lowerLine = trimmed.lowercase()

                    val prefix = when (order) {
                        HistoryTracker.SortOrder.HIGH if "highest" in lowerLine -> currentColor
                        HistoryTracker.SortOrder.LOW if "lowest" in lowerLine -> currentColor
                        HistoryTracker.SortOrder.NAME if "name" in lowerLine -> currentColor
                        else -> notCurrentColor
                    }

                    Utils.formatColors(prefix.content() + trimmed)
                })
            }
            inv.setItem(sortSlot, sortBtn)
            
            // Player head
            val head = ItemStack(playerMat)
            (head.itemMeta as? SkullMeta)?.let { meta ->
                meta.owningPlayer = player

                val display = PlaceholderAPI.setPlaceholders(
                    player,
                    Utils.formatColors(playerNameTpl.content()).replace("%player%", player.name).content()
                )
                meta.displayName(display.component())

                val loreList = playerLoreTpl.map { line ->
                    PlaceholderAPI.setPlaceholders(player, Utils.formatColors(line.content()).replace("%player%", player.name).content())
                }
                meta.lore(loreList.component())

                head.itemMeta = meta
            }
            inv.setItem(playerSlot, head)
            
            setStaticItem(inv, nextSlot, nextMat, nextName, nextLore)
            
            player.openInventory(inv)
            plugin.historyTracker.setPage(player.uniqueId, page)
        }, null)
    }
    
    private fun setStaticItem(
        inv: Inventory,
        slot: Int,
        mat: Material,
        name: Component,
        lore: List<Component>
    ) {
        val item = ItemStack(mat)
        item.itemMeta = item.itemMeta?.apply {
            displayName(name)
            lore(lore)
        }
        inv.setItem(slot, item)
    }
    
    fun matchesTitle(openTitle: Component): Boolean {
        val plain = openTitle.content()
        return plain.startsWith(titlePrefix.content())
    }
    
    private fun String.capitalize(): String =
        split("_")
            .joinToString(" ") { part ->
                part.lowercase().replaceFirstChar { it.uppercase() }
            }
}