package mrduck.donutSell

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import kotlin.math.min
import kotlin.math.roundToInt

class ProgressGui(private val plugin: DonutSell) {

    private lateinit var defaultTitle: Component
    private val customTitles = mutableMapOf<String, Component>()
    private var size: Int = 0
    private lateinit var loadingColor: Component
    private lateinit var completeLoadingColor: Component
    private lateinit var barSymbol: Component
    private var barLength: Int = 0

    private lateinit var incompleteMat: Material
    private lateinit var incompleteName: Component
    private lateinit var incompleteLore: List<Component>

    private lateinit var workingMat: Material
    private lateinit var workingName: Component
    private lateinit var workingLore: List<Component>

    private lateinit var completeMat: Material
    private lateinit var completeName: Component
    private lateinit var completeLore: List<Component>

    private var fillerEnabled: Boolean = false
    private lateinit var fillerMat: Material
    private lateinit var fillerName: Component
    private lateinit var fillerLore: List<Component>

    private var backEnabled: Boolean = false
    private var backSlot: Int = -1
    private lateinit var backMat: Material
    private lateinit var backName: Component
    private lateinit var backLore: List<Component>

    private val levels = mutableListOf<Level>()
    val categoryIcons = mutableMapOf<String, CategoryIcon>()

    init {
        loadConfig()
    }

    fun loadConfig() {
        val cfg = plugin.config.getConfigurationSection("progress-menu") ?: return

        defaultTitle = Utils.formatColors(cfg.getString("title", "%item% Progress")!!)

        customTitles.clear()
        cfg.getConfigurationSection("titles")?.let { titles ->
            titles.getKeys(false).forEach { cat ->
                customTitles[cat.lowercase()] = Utils.formatColors(titles.getString(cat)!!)
            }
        }

        size = cfg.getInt("rows", 3) * 9
        loadingColor = Utils.formatColors(cfg.getString("loading-color", "&#20f706")!!)
        completeLoadingColor = Utils.formatColors(cfg.getString("complete-loading-color", loadingColor.content())!!)
        barLength = cfg.getInt("bar-length", 10)
        barSymbol = cfg.getString("bar-symbol", "━")!!.component()

        loadSection(cfg, "incomplete").also { inc ->
            incompleteMat = Material.valueOf(inc.getString("material")!!)
            incompleteName = Utils.formatColors(inc.getString("displayname")!!)
            incompleteLore = Utils.formatColors(inc.getStringList("lore"))
        }

        loadSection(cfg, "working").also { wk ->
            workingMat = Material.valueOf(wk.getString("material")!!)
            workingName = Utils.formatColors(wk.getString("displayname")!!)
            workingLore = Utils.formatColors(wk.getStringList("lore"))
        }

        loadSection(cfg, "complete").also { com ->
            completeMat = Material.valueOf(com.getString("material")!!)
            completeName = Utils.formatColors(com.getString("displayname")!!)
            completeLore = Utils.formatColors(com.getStringList("lore"))
        }

        loadSection(cfg, "filler").also { fill ->
            fillerEnabled = fill.getBoolean("enabled", false)
            fillerMat = Material.valueOf(fill.getString("material")!!)
            fillerName = Utils.formatColors(fill.getString("displayname")!!)
            fillerLore = Utils.formatColors(fill.getStringList("lore"))
        }

        loadSection(cfg, "back-button").also { back ->
            backEnabled = back.getBoolean("enabled", false)
            backSlot = back.getInt("slot", -1)
            backMat = Material.valueOf(back.getString("material")!!)
            backName = Utils.formatColors(back.getString("displayname")!!)
            backLore = Utils.formatColors(back.getStringList("lore"))
        }

        levels.clear()
        cfg.getConfigurationSection("levels")?.let { lvlSec ->
            lvlSec.getKeys(false).forEach { key ->
                lvlSec.getConfigurationSection(key)?.let { ls ->
                    levels.add(Level(
                        amountNeeded = ls.getLong("amountNeeded"),
                        multi = ls.getDouble("multi"),
                        slot = ls.getInt("slot")
                    ))
                }
            }
        }

        levels.sortBy { it.amountNeeded }

        categoryIcons.clear()
        cfg.getConfigurationSection("categories")?.let { cats ->
            cats.getKeys(false).forEach { cat ->
                cats.getConfigurationSection("$cat.icon")?.let { ico ->
                    categoryIcons[cat.lowercase()] = CategoryIcon(
                        material = Material.valueOf(ico.getString("material")!!),
                        slot = ico.getInt("slot"),
                        displayName = Utils.formatColors(ico.getString("displayname")!!),
                        lore = Utils.formatColors(ico.getStringList("lore"))
                    )
                }
            }
        }
    }

    private fun loadSection(cfg: org.bukkit.configuration.ConfigurationSection, path: String) =
        cfg.getConfigurationSection(path)!!

    fun open(p: Player, categoryKey: String): Inventory {
        val key = categoryKey.lowercase()
        val title = customTitles.getOrDefault(key, defaultTitle)
            .replace("%item%", key.capitalize())

        val inv = Bukkit.createInventory(GuiHolder(key, -1), size, title)
        val sold = plugin.getRawTotalSold(p.uniqueId, key)

        // Find working level index
        val workingIndex = levels.indexOfFirst { sold < it.amountNeeded }
            .takeIf { it >= 0 } ?: levels.size

        // Populate level items
        levels.forEachIndexed { i, lvl ->
            val isComplete = i < workingIndex
            val isWorking = i == workingIndex

            inv.setItem(lvl.slot, createLevelItem(lvl, sold, isComplete, isWorking))
        }

        // Add category icon
        categoryIcons[key]?.let { ci ->
            inv.setItem(ci.slot, createCategoryIcon(ci, key, sold))
        }

        // Fill empty slots
        if (fillerEnabled) {
            (0 until size).forEach { i ->
                if (inv.getItem(i) == null) {
                    inv.setItem(i, createFillerItem())
                }
            }
        }

        // Add back button
        if (backEnabled && backSlot in 0 until size) {
            inv.setItem(backSlot, createBackButton())
        }

        p.openInventory(inv)
        return inv
    }

    private fun createLevelItem(lvl: Level, sold: Double, isComplete: Boolean, isWorking: Boolean): ItemStack {
        val material = when {
            isComplete -> completeMat
            isWorking -> workingMat
            else -> incompleteMat
        }

        return ItemStack(material).apply {
            itemMeta = itemMeta?.apply {
                displayName(when {
                    isComplete -> completeName
                    isWorking -> workingName
                    else -> incompleteName
                })

                val progress = min(sold, lvl.amountNeeded.toDouble())
                val fraction = if (lvl.amountNeeded > 0L) progress / lvl.amountNeeded else 0.0
                val needed = Utils.abbreviateNumber(lvl.amountNeeded.toDouble())
                val done = Utils.abbreviateNumber(progress)
                val amtStr = if (isComplete) "$needed/$needed" else "$done/$needed"

                val bar = createProgressBar(isComplete, isWorking, fraction)

                val template = when {
                    isComplete -> completeLore
                    isWorking -> workingLore
                    else -> incompleteLore
                }

                lore(template.map { line ->
                    Utils.formatColors(
                        line.replace("%loading-bar%", bar.content())
                            .replace("%multi%", lvl.multi.toString())
                            .replace("%progress%", "%.1f".format(fraction * 100.0))
                            .replace("%amount-needed%", amtStr).content()
                    )
                })
            }
        }
    }

    private fun createProgressBar(isComplete: Boolean, isWorking: Boolean, fraction: Double): Component {
        return when {
            isComplete -> completeLoadingColor.append(barSymbol.repeat(barLength))
            isWorking -> {
                val filled = (fraction * barLength).roundToInt()
                loadingColor.append(barSymbol.repeat(filled).append(Component.text("&f")).append(barSymbol.repeat(barLength - filled)))
            }
            else -> Component.text("&f").append(barSymbol.repeat(barLength))
        }
    }

    private fun createCategoryIcon(ci: CategoryIcon, key: String, sold: Double): ItemStack {
        return ItemStack(ci.material).apply {
            itemMeta = itemMeta?.apply {
                displayName(ci.displayName
                    .replace("%item%", key.capitalize())
                    .replace("%sold%", Utils.abbreviateNumber(sold)))

                lore(ci.lore.map {
                    Utils.formatColors(
                        it.replace("%item%", key.capitalize())
                            .replace("%sold%", Utils.abbreviateNumber(sold))
                            .content()
                    )
                })
            }
        }
    }

    private fun createFillerItem(): ItemStack {
        return ItemStack(fillerMat).apply {
            itemMeta = itemMeta?.apply {
                displayName(fillerName)
                lore(fillerLore)
            }
        }
    }

    private fun createBackButton(): ItemStack {
        return ItemStack(backMat).apply {
            itemMeta = itemMeta?.apply {
                displayName(backName)
                lore(backLore)
            }
        }
    }

    private fun String.capitalize(): String {
        return replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    data class Level(
        val amountNeeded: Long,
        val multi: Double,
        val slot: Int
    )

    data class CategoryIcon(
        val material: Material,
        val slot: Int,
        val displayName: Component,
        val lore: List<Component>
    )
}