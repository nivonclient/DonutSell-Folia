package mrduck.donutSell

import org.bukkit.*
import org.bukkit.block.CreatureSpawner
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionType
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class ItemPricesMenu(private val plugin: DonutSell) : Listener {
    
    private val defaultValue: Double = plugin.config.getDouble("default-value", 0.1)
    private var masterEntries: List<Pair<ItemStack, Double>> = emptyList()
    private val disabledSet: Set<String>
    private val titlePrefix: String
    private val titleTemplate: String
    private val rows: Int
    
    private val prevMat: Material
    private val prevSlot: Int
    private val prevName: String
    private val prevLore: List<String>
    
    private val nextMat: Material
    private val nextSlot: Int
    private val nextName: String
    private val nextLore: List<String>
    
    private val refreshMat: Material
    private val refreshSlot: Int
    private val refreshName: String
    private val refreshLore: List<String>
    
    private val sortMat: Material
    private val sortSlot: Int
    private val sortName: String
    private val sortNotCurColor: String
    private val sortCurColor: String
    private val sortOptions: List<String>
    
    private val filterMat: Material
    private val filterSlot: Int
    private val filterName: String
    private val filterNotCurColor: String
    private val filterCurColor: String
    private val filterOptions: List<String>
    
    private val itemDisplayTemplate: String
    private val itemLoreTemplate: List<String>
    private val pageSwitchSoundName: String
    
    private val pdcCategory: NamespacedKey
    
    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        pdcCategory = NamespacedKey(plugin, "category")
        
        disabledSet = plugin.config.getStringList("disabled-items")
            .mapTo(mutableSetOf()) { it.uppercase() }
        
        val menu = plugin.config.getConfigurationSection("item-prices-menu")!!
        titleTemplate = menu.getString("title")!!
        titlePrefix = titleTemplate.split("%page%", limit = 2)[0]
        rows = menu.getInt("rows", 6)
        
        menu.getConfigurationSection("previous")!!.let { prev ->
            prevMat = Material.valueOf(prev.getString("previous-page-material")!!.uppercase())
            prevSlot = prev.getInt("previous-page-slot")
            prevName = prev.getString("previous-page-displayname")!!
            prevLore = prev.getStringList("previous-page-lore")
        }
        
        menu.getConfigurationSection("next")!!.let { nxt ->
            nextMat = Material.valueOf(nxt.getString("next-page-material")!!.uppercase())
            nextSlot = nxt.getInt("next-page-slot")
            nextName = nxt.getString("next-page-displayname")!!
            nextLore = nxt.getStringList("next-page-lore")
        }
        
        menu.getConfigurationSection("refresh")!!.let { rf ->
            refreshMat = Material.valueOf(rf.getString("material")!!.uppercase())
            refreshSlot = rf.getInt("slot")
            refreshName = rf.getString("displayname")!!
            refreshLore = rf.getStringList("lore")
        }
        
        menu.getConfigurationSection("sort")!!.let { st ->
            sortMat = Material.valueOf(st.getString("material")!!.uppercase())
            sortSlot = st.getInt("slot")
            sortName = st.getString("displayname")!!
            sortNotCurColor = st.getString("NotCurrentColor")!!
            sortCurColor = st.getString("CurrentColor")!!
            sortOptions = st.getStringList("lore")
        }
        
        menu.getConfigurationSection("filter")!!.let { fl ->
            filterMat = Material.valueOf(fl.getString("material")!!.uppercase())
            filterSlot = fl.getInt("slot")
            filterName = fl.getString("displayname")!!
            filterNotCurColor = fl.getString("NotCurrentColor")!!
            filterCurColor = fl.getString("CurrentColor")!!
            filterOptions = fl.getStringList("lore")
        }
        
        menu.getConfigurationSection("items")!!.let { items ->
            itemDisplayTemplate = items.getString("displayname")!!
            itemLoreTemplate = items.getStringList("lore")
        }
        
        pageSwitchSoundName = plugin.config.getString("sounds.page-switch", "ITEM_BOOK_PAGE_TURN")!!
            .uppercase()
        
        buildEntries()
    }
    
    private fun buildEntries() {
        val list = mutableListOf<Pair<ItemStack, Double>>()
        val seen = mutableSetOf<String>()
        val disableAllSpawnEggs = disabledSet.contains("SPAWN_EGG")
        
        // Add regular materials
        Material.entries
            .filter { m ->
                m.isItem && m != Material.AIR && 
                !disabledSet.contains(m.name) &&
                (!disableAllSpawnEggs || !m.name.endsWith("_SPAWN_EGG"))
            }
            .forEach { m ->
                when (m) {
                    Material.SPAWNER -> {
                        val key = "spawner-value"
                        list.add(ItemStack(Material.SPAWNER) to plugin.getPrice(key))
                        seen.add(key)
                    }
                    else -> {
                        val key = "${m.name.lowercase()}-value"
                        list.add(ItemStack(m) to plugin.getPrice(key))
                        seen.add(key)
                    }
                }
            }
        
        // Add enchanted books
        plugin.itemValues.keys
            .filter { it.endsWith("-value") && !seen.contains(it) }
            .forEach { key ->
                ENCH_PATTERN.matchEntire(key)?.let { match ->
                    val (enKey, lvlStr) = match.destructured
                    val lvl = lvlStr.toInt()
                    val `val` = plugin.getPrice(key)

                    Enchantment.values()
                        .find { it.key.key.equals(enKey, ignoreCase = true) }
                        ?.let { ench ->
                            ItemStack(Material.ENCHANTED_BOOK).apply {
                                itemMeta = (itemMeta as? EnchantmentStorageMeta)?.apply {
                                    addStoredEnchant(ench, lvl, true)
                                    persistentDataContainer.set(pdcCategory, PersistentDataType.STRING, "book")
                                }
                            }.also { book ->
                                list.add(book to `val`)
                                seen.add(key)
                            }
                        }
                }
            }
        
        // Add potions
        plugin.itemValues.keys
            .filter { it.endsWith("-value") && !seen.contains(it) }
            .forEach { rawKey ->
                val baseKey = rawKey.dropLast(6)
                POTION_PATTERN.matchEntire(baseKey)?.let { match ->
                    val (splashOrLingering, longOrStrong, effectName) = match.destructured
                    
                    if (looksLikePotion(effectName)) {
                        createPotionItem(splashOrLingering, longOrStrong, effectName)?.let { pot ->
                            list.add(pot to plugin.getPrice(rawKey))
                            seen.add(rawKey)
                        }
                    }
                }
            }
        
        // Add spawners with entity types
        plugin.itemValues.keys
            .filter { it.endsWith("-value") && !seen.contains(it) }
            .forEach { rawKey ->
                SPAWNER_PATTERN.matchEntire(rawKey)?.let { match ->
                    val entityName = match.groupValues[1]
                    
                    runCatching { EntityType.valueOf(entityName.uppercase()) }
                        .getOrNull()
                        ?.let { entityType ->
                            createSpawnerItem(entityType)?.let { spawner ->
                                list.add(spawner to plugin.getPrice(rawKey))
                                seen.add(rawKey)
                            }
                        }
                }
            }
        
        masterEntries = list
    }
    
    private fun createPotionItem(splashOrLingering: String?, longOrStrong: String?, effectName: String): ItemStack? {
        val potMat = when (splashOrLingering?.lowercase()) {
            "splash" -> Material.SPLASH_POTION
            "lingering" -> Material.LINGERING_POTION
            else -> Material.POTION
        }
        
        return ItemStack(potMat).apply {
            itemMeta = (itemMeta as? PotionMeta)?.apply {
                val type = resolvePotionType(effectName)
                if (type != null) {
                    basePotionType = type
                } else {
                    basePotionType = PotionType.AWKWARD
                    displayName(Utils.formatColors("${effectName.prettify()} Potion"))
                }
                persistentDataContainer.set(pdcCategory, PersistentDataType.STRING, "brewing_stand")
            }
        }
    }
    
    private fun createSpawnerItem(entityType: EntityType): ItemStack? {
        return ItemStack(Material.SPAWNER).apply {
            itemMeta = (itemMeta as? BlockStateMeta)?.apply {
                (blockState as? CreatureSpawner)?.let { cs ->
                    cs.spawnedType = entityType
                    blockState = cs
                }
            }
        }
    }
    
    @EventHandler
    fun onInventoryClick(e: InventoryClickEvent) {
        val player = e.whoClicked as? Player ?: return
        val title = e.view.title()
        
        if (!title.content().startsWith(titlePrefix)) return
        
        val slot = e.rawSlot
        if (slot !in 0 until e.view.topInventory.size) return
        
        e.isCancelled = true
        val vt = plugin.viewTracker
        val currentPage = vt.getPage(player.uniqueId)
        
        when (slot) {
            filterSlot -> {
                val cur = vt.getFilter(player.uniqueId) ?: "all"
                val idx = filterOptions.indexOf(cur).let { if (it < 0) 0 else it }
                val nextIdx = (idx + 1) % filterOptions.size
                val nextCat = filterOptions[nextIdx]
                vt.setFilter(player.uniqueId, if (nextCat.equals("all", ignoreCase = true)) null else nextCat)
                open(player, currentPage)
            }
            prevSlot -> open(player, currentPage - 1)
            nextSlot -> open(player, currentPage + 1)
            refreshSlot -> open(player, currentPage)
        }
    }
    
    fun open(player: Player, reqPage: Int) {
        val vt = plugin.viewTracker
        val filterCategory = vt.getFilter(player.uniqueId)
        
        var sorted = masterEntries.toMutableList()
        
        // Apply sorting
        when (vt.getOrder(player.uniqueId)) {
            ViewTracker.SortOrder.HIGH_TO_LOW -> sorted.sortByDescending { it.second }
            ViewTracker.SortOrder.LOW_TO_HIGH -> sorted.sortBy { it.second }
            ViewTracker.SortOrder.NAME -> sorted.sortBy { entry ->
                entry.first.itemMeta?.let { meta ->
                    if (meta.hasDisplayName()) meta.displayName().toString() else entry.first.type.prettify()
                } ?: entry.first.type.prettify()
            }
        }
        
        // Apply filtering
        if (filterCategory != null && !filterCategory.equals("all", ignoreCase = true)) {
            val allowed = plugin.categoryItems[filterCategory] ?: emptyList()
            sorted.removeIf { !isAllowedInCategory(it.first, filterCategory, allowed) }
        }
        
        val size = rows * 9
        val per = size - 9
        val maxPage = max(1, ceil(sorted.size.toDouble() / per).toInt())
        val page = min(maxPage, max(1, reqPage))
        
        val inv = Bukkit.createInventory(null, size,
            Utils.formatColors(titleTemplate.replace("%page%", page.toString())))
        
        val start = (page - 1) * per
        val end = min(start + per, sorted.size)
        
        // Populate items
        (start until end).forEach { i ->
            val (item, value) = sorted[i]
            val displayItem = item.clone().apply {
                val baseName = getItemBaseName(this@apply)

                itemMeta = itemMeta?.apply {
                    displayName(Utils.formatColors(
                        itemDisplayTemplate
                            .replace("%ItemName%", baseName)
                            .replace("%amount%", Utils.abbreviateNumber(value))
                    ))
                    
                    lore(itemLoreTemplate.map { line ->
                        Utils.formatColors(
                            line.replace("%ItemName%", baseName)
                                .replace("%amount%", Utils.abbreviateNumber(value))
                        )
                    })
                }
            }
            inv.setItem(i - start, displayItem)
        }
        
        // Add control buttons
        placeItem(inv, prevMat, prevSlot, prevName, prevLore)
        placeItem(inv, nextMat, nextSlot, nextName, nextLore)
        placeItem(inv, refreshMat, refreshSlot, refreshName, refreshLore)
        
        // Add sort button
        inv.setItem(sortSlot, ItemStack(sortMat).apply {
            itemMeta = itemMeta?.apply {
                displayName(Utils.formatColors(sortName))
                val cur = vt.getOrder(player.uniqueId)
                lore(sortOptions.mapIndexed { i, txt ->
                    val isActive = when {
                        i == 0 -> cur == ViewTracker.SortOrder.HIGH_TO_LOW
                        i == 1 -> cur == ViewTracker.SortOrder.LOW_TO_HIGH
                        i == 2 -> cur == ViewTracker.SortOrder.NAME
                        else -> false
                    }
                    Utils.formatColors("${if (isActive) sortCurColor else sortNotCurColor}• $txt")
                })
            }
        })
        
        // Add filter button
        inv.setItem(filterSlot, ItemStack(filterMat).apply {
            itemMeta = itemMeta?.apply {
                displayName(Utils.formatColors(filterName))
                val curFilt = filterCategory ?: "all"
                lore(filterOptions.map { opt ->
                    val col = if (opt.equals(curFilt, ignoreCase = true)) filterCurColor else filterNotCurColor
                    Utils.formatColors("$col• ${opt.prettify()}")
                })
            }
        })
        
        player.openInventory(inv)
        vt.setPage(player.uniqueId, page)
        
        // Play sound
        player.scheduler.run(plugin, { _ ->
            runCatching {
                Registry.SOUNDS.get(NamespacedKey.minecraft(pageSwitchSoundName.lowercase()))
                    ?.let { player.playSound(player.location, it, 1.0f, 1.0f) }
            }.getOrElse {
                player.playSound(player.location, Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 1.0f)
            }
        }, null)
    }
    
    private fun getItemBaseName(item: ItemStack): String {
        val meta = item.itemMeta
        
        return when {
            item.type == Material.SPAWNER && meta is BlockStateMeta -> {
                (meta.blockState as? CreatureSpawner)?.spawnedType?.let { type ->
                    "${type.name}_SPAWNER".prettify()
                } ?: item.type.prettify()
            }
            meta?.hasDisplayName() == true -> meta.displayName().toString()
            else -> item.type.prettify()
        }
    }
    
    private fun placeItem(inv: Inventory, mat: Material, slot: Int, name: String, lore: List<String>) {
        inv.setItem(slot, ItemStack(mat).apply {
            itemMeta = itemMeta?.apply {
                displayName(Utils.formatColors(name))
                lore(Utils.formatColors(lore))
            }
        })
    }
    
    private fun Material.prettify(): String = name.prettify()
    
    private fun String.prettify(): String {
        return lowercase()
            .split('_')
            .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase() } }
    }
    
    private fun isAllowedInCategory(item: ItemStack, filterCategory: String, allowed: List<String>): Boolean {
        val type = item.type.name
        val meta = item.itemMeta
        val catTag = meta?.persistentDataContainer?.get(pdcCategory, PersistentDataType.STRING)
        
        when (filterCategory.lowercase()) {
            "book" -> {
                if (catTag == "book") return true
                if (meta is EnchantmentStorageMeta) return true
                if (type.endsWith("_BOOK")) return true
            }
            "brewing_stand" -> {
                if (catTag == "brewing_stand") return true
                if (type in listOf("POTION", "SPLASH_POTION", "LINGERING_POTION")) return true
            }
        }
        
        if (allowed.isNotEmpty()) {
            if (type in allowed) return true
            
            if ((allowed.any { it.equals("BOOKS", true) || it.equals("ANY_BOOK", true) }) &&
                (type.endsWith("_BOOK") || meta is EnchantmentStorageMeta)) {
                return true
            }
            
            if ((allowed.any { it.equals("POTIONS", true) || it.equals("ANY_POTION", true) }) &&
                type in listOf("POTION", "SPLASH_POTION", "LINGERING_POTION")) {
                return true
            }
        }
        
        return false
    }
    
    private fun looksLikePotion(effectName: String): Boolean {
        val keywords = listOf(
            "potion", "vision", "invisibility", "leaping", "jump", "fire_resistance",
            "swiftness", "speed", "slowness", "water_breathing", "healing", "harming",
            "poison", "regeneration", "strength", "weakness", "turtle_master",
            "slow_falling", "mundane", "thick", "awkward", "water", "wind_charged",
            "weaving", "oozing", "infested"
        )
        return keywords.any { effectName.contains(it) }
    }
    
    private fun resolvePotionType(effectName: String): PotionType? {
        return when (effectName.lowercase()) {
            "leaping" -> PotionType.LEAPING
            "swiftness" -> PotionType.SWIFTNESS
            "healing" -> PotionType.HEALING
            "harming" -> PotionType.HARMING
            "water", "potion" -> PotionType.WATER
            else -> runCatching {
                PotionType.valueOf(effectName.uppercase().replace('-', '_'))
            }.getOrNull()
        }
    }
    
    companion object {
        private val ENCH_PATTERN = Regex("""([a-z0-9_]+?)[_-]?(\d+)-value""")
        private val POTION_PATTERN = Regex("""(?:(splash|lingering)_)?(?:(long|strong)_)?([a-z_]+)$""")
        private val SPAWNER_PATTERN = Regex("""([a-z_]+)_spawner-value""")
    }
}