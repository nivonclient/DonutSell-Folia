package mrduck.donutSell

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.block.CreatureSpawner
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionType
import java.util.*
import kotlin.math.min

class CategoryGui(private val plugin: DonutSell, categoryKey: String) {
    
    private val categoryKey = categoryKey.lowercase()
    private val items = mutableListOf<ItemStack>()
    private val rows: Int
    private val defaultTitleTpl: Component
    private val customTitles = mutableMapOf<String, Component>()
    private val prevSlot: Int
    private val nextSlot: Int
    private val backSlot: Int
    private val prevMat: Material?
    private val nextMat: Material?
    private val backMat: Material?
    private val prevName: Component
    private val nextName: Component
    private val backName: Component
    private val prevLore: List<Component>
    private val nextLore: List<Component>
    private val backLore: List<Component>
    private val itemNameTpl: Component
    private val itemLoreTpl: List<Component>
    private val pageSwitch: Sound
    private val pdcCategory = NamespacedKey(plugin, "category")
    
    init {
        val cfg = plugin.config.getConfigurationSection("category-menu")!!
        
        rows = cfg.getInt("rows", 6)
        defaultTitleTpl = Utils.formatColors(cfg.getString("title", "%item% Items")!!)
        
        cfg.getConfigurationSection("titles")?.let { tsec ->
            tsec.getKeys(false).forEach { cat ->
                customTitles[cat.lowercase()] = Utils.formatColors(tsec.getString(cat)!!)
            }
        }
        
        prevSlot = cfg.getInt("previous-page-slot", 49)
        prevMat = Material.matchMaterial(cfg.getString("previous-page.material")!!)
        prevName = Utils.formatColors(cfg.getString("previous-page.displayname", "&aPrevious")!!)
        prevLore = Utils.formatColors(cfg.getStringList("previous-page.lore"))
        
        nextSlot = cfg.getInt("next-page-slot", 51)
        nextMat = Material.matchMaterial(cfg.getString("next-page.material")!!)
        nextName = Utils.formatColors(cfg.getString("next-page.displayname", "&aNext")!!)
        nextLore = Utils.formatColors(cfg.getStringList("next-page.lore"))
        
        backSlot = cfg.getInt("back-button.slot", 45)
        backMat = Material.matchMaterial(cfg.getString("back-button.material")!!)
        backName = Utils.formatColors(cfg.getString("back-button.displayname", "&cBack")!!)
        backLore = Utils.formatColors(cfg.getStringList("back-button.lore"))
        
        itemNameTpl = Utils.formatColors(cfg.getString("item.displayname", "%item%")!!)
        itemLoreTpl = cfg.getStringList("item.lore").map { Utils.formatColors(it) }
        
        val configured = plugin.config.getString("sounds.page-switch", "item.book.page_turn")!!
        pageSwitch = resolveSound(configured)
        
        loadCategoryItems()
    }
    
    private fun loadCategoryItems() {
        when (val rawNode = plugin.config.get("categories.$categoryKey")) {
            is ConfigurationSection -> {
                rawNode.getKeys(false).forEach { entryKey ->
                    val price = rawNode.getDouble(entryKey, -1.0)
                    if (price < 0.0) {
                        plugin.logger.warning("Invalid price for '$entryKey' in categories.$categoryKey")
                    } else {
                        handleEntry(entryKey, price)
                    }
                }
            }
            is List<*> -> {
                rawNode.filterIsInstance<Map<*, *>>().forEach { map ->
                    map.entries.forEach { (key, value) ->
                        val entryKey = key.toString()
                        val price = value.toString().toDoubleOrNull()
                        if (price == null) {
                            plugin.logger.warning("Invalid price for '$entryKey' in categories.$categoryKey")
                        } else {
                            handleEntry(entryKey, price)
                        }
                    }
                }
            }
            else -> plugin.logger.warning("No entries found for categories.$categoryKey")
        }
    }
    
    private fun resolveSound(soundName: String): Sound {
        if (soundName.isEmpty()) return Sound.ITEM_BOOK_PAGE_TURN
        
        val normalized = soundName.lowercase().replace('_', '.')
        
        // Try minecraft namespace with normalized name
        runCatching {
            NamespacedKey.minecraft(normalized)
        }.getOrNull()?.let { key ->
            Registry.SOUNDS.get(key)?.let { return it }
        }
        
        // Try parsing as full namespaced key
        runCatching {
            NamespacedKey.fromString(normalized)
        }.getOrNull()?.let { key ->
            Registry.SOUNDS.get(key)?.let { return it }
        }
        
        // Try uppercase with minecraft namespace
        runCatching {
            Registry.SOUNDS.get(NamespacedKey.minecraft(soundName.uppercase()))
        }.getOrNull()?.let { return it }
        
        plugin.logger.warning("Invalid sound '$soundName', defaulting to ITEM_BOOK_PAGE_TURN")
        return Sound.ITEM_BOOK_PAGE_TURN
    }
    
    private fun handleEntry(entryKey: String, price: Double) {
        SPAWNER_PATTERN.matchEntire(entryKey)?.let { match ->
            return handleSpawnerEntry(match.groupValues[1], entryKey, price)
        }
        
        if (entryKey.endsWith("-value")) {
            ENCH_PATTERN.matchEntire(entryKey)?.let { match ->
                return handleEnchantmentEntry(match.groupValues[1], match.groupValues[2].toInt(), entryKey, price)
            }
            
            POTION_PATTERN.matchEntire(entryKey)?.let { match ->
                return handlePotionEntry(match.groupValues[1], match.groupValues[2], match.groupValues[3], entryKey, price)
            }
        }
        
        handleMaterialEntry(entryKey, price)
    }
    
    private fun handleSpawnerEntry(entityName: String, entryKey: String, price: Double) {
        runCatching {
            EntityType.valueOf(entityName.uppercase())
        }.getOrNull()?.let { type ->
            ItemStack(Material.SPAWNER).apply {
                itemMeta = (itemMeta as? BlockStateMeta)?.apply {
                    (blockState as? CreatureSpawner)?.let { cs ->
                        cs.spawnedType = type
                        blockState = cs
                    }
                }
                applyDisplayAndLore(this, entryKey, price, null)
                items.add(this)
            }
        } ?: plugin.logger.warning("Unknown spawner entity '$entityName' in categories.$categoryKey")
    }
    
    private fun handleEnchantmentEntry(enKeyRaw: String, level: Int, entryKey: String, price: Double) {
        val prettyEn = enKeyRaw.prettyName()
        val enchKey = NamespacedKey.minecraft(enKeyRaw.lowercase())
        val enchantment = Registry.ENCHANTMENT.get(enchKey)
        
        ItemStack(Material.ENCHANTED_BOOK).apply {
            itemMeta = (itemMeta as? EnchantmentStorageMeta)?.apply {
                if (enchantment != null) {
                    addStoredEnchant(enchantment, level, true)
                } else {
                    displayName(Component.text("Enchanted Book ($prettyEn ${level.toRoman()})"))
                }
                persistentDataContainer.set(pdcCategory, PersistentDataType.STRING, "book")
            }
            
            val customName = if (enchantment == null) "Enchanted Book ($prettyEn ${level.toRoman()})" else null
            applyDisplayAndLore(this, entryKey, price, customName)
            items.add(this)
        }
    }
    
    private fun handlePotionEntry(splashOrLingering: String?, longOrStrong: String?, effectName: String, entryKey: String, price: Double) {
        val type = resolvePotionType(effectName, longOrStrong)
        if (type == null) {
            plugin.logger.warning("Unknown potion effect '$effectName' for key '$entryKey' in categories.$categoryKey — skipping potion item.")
            return
        }
        
        val potMat = when (splashOrLingering?.lowercase()) {
            "splash" -> Material.SPLASH_POTION
            "lingering" -> Material.LINGERING_POTION
            else -> Material.POTION
        }
        
        ItemStack(potMat).apply {
            itemMeta = (itemMeta as? PotionMeta)?.apply {
                basePotionType = type
                persistentDataContainer.set(pdcCategory, PersistentDataType.STRING, "brewing_stand")
            }
            applyDisplayAndLore(this, entryKey, price, null)
            items.add(this)
        }
    }
    
    private fun handleMaterialEntry(entryKey: String, price: Double) {
        val matName = entryKey.replace("-value", "")
        Material.matchMaterial(matName)?.let { mat ->
            ItemStack(mat).apply {
                applyDisplayAndLore(this, entryKey, price, null)
                items.add(this)
            }
        } ?: plugin.logger.warning("Unrecognized entry key '$entryKey' in categories.$categoryKey — not a spawner, book, material, or known potion.")
    }
    
    fun open(player: Player, page: Int) {
        val perPage = (rows - 1) * 9
        val start = page * perPage
        val end = min(start + perPage, items.size)
        
        val titleTpl = customTitles.getOrDefault(categoryKey, defaultTitleTpl)
        val title = MiniMessage.miniMessage().deserialize(
            MiniMessage.miniMessage().serialize(titleTpl),
            Placeholder.parsed("item", categoryKey.prettyName())
        )
        
        player.scheduler.run(plugin, { _ ->
            val inv = Bukkit.createInventory(
                GuiHolder(categoryKey, page),
                rows * 9,
                title
            )
            
            (start until end).forEach { i ->
                inv.setItem(i - start, items[i])
            }
            
            prevMat?.let { inv.setItem(prevSlot, buildButton(it, prevName, prevLore)) }
            nextMat?.let { inv.setItem(nextSlot, buildButton(it, nextName, nextLore)) }
            backMat?.let { inv.setItem(backSlot, buildButton(it, backName, backLore)) }
            
            player.openInventory(inv)
            player.playSound(player.location, pageSwitch, 1.0f, 1.0f)
        }, null)
    }
    
    private fun applyDisplayAndLore(item: ItemStack, entryKey: String, price: Double, customName: String?) {
        item.itemMeta = item.itemMeta?.apply {
            when {
                customName != null -> displayName(Component.text(customName))
                !hasDisplayName() -> {
                    val base = entryKey.replace("-value", "").prettyName()
                    displayName(Component.text(
                        PlainTextComponentSerializer.plainText().serialize(itemNameTpl).replace("%item%", base)
                    ))
                }
            }
            
            lore(itemLoreTpl.map { line ->
                Utils.formatColors(
                    PlainTextComponentSerializer.plainText()
                        .serialize(line)
                        .replace("%item-value%", Utils.abbreviateNumber(price))
                )
            })
        }
    }
    
    private fun buildButton(mat: Material, name: Component, lore: List<Component>): ItemStack {
        return ItemStack(mat).apply {
            itemMeta = itemMeta?.apply {
                displayName(name)
                lore(lore)
            }
        }
    }
    
    private fun String.prettyName(): String {
        return replace('-', '_')
            .split('_')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.titlecase() } }
    }
    
    private fun safePotionType(vararg names: String): PotionType? {
        return names.firstNotNullOfOrNull { name ->
            runCatching { PotionType.valueOf(name) }.getOrNull()
        }
    }
    
    private fun resolvePotionType(effectName: String, modifier: String?): PotionType? {
        val normalized = effectName.lowercase().replace('-', '_')
        val isLong = modifier.equals("long", ignoreCase = true)
        val isStrong = modifier.equals("strong", ignoreCase = true)
        
        return when (normalized) {
            "leaping", "jump_boost" -> when {
                isLong -> safePotionType("LONG_LEAPING")
                isStrong -> safePotionType("STRONG_LEAPING")
                else -> safePotionType("LEAPING")
            }
            "swiftness", "speed" -> when {
                isLong -> safePotionType("LONG_SWIFTNESS")
                isStrong -> safePotionType("STRONG_SWIFTNESS")
                else -> safePotionType("SWIFTNESS")
            }
            "healing", "instant_heal" -> when {
                isStrong -> safePotionType("STRONG_HEALING")
                else -> safePotionType("HEALING")
            }
            "harming", "instant_damage" -> when {
                isStrong -> safePotionType("STRONG_HARMING")
                else -> safePotionType("HARMING")
            }
            "regeneration", "regen" -> when {
                isLong -> safePotionType("LONG_REGENERATION")
                isStrong -> safePotionType("STRONG_REGENERATION")
                else -> safePotionType("REGENERATION")
            }
            "strength" -> when {
                isLong -> safePotionType("LONG_STRENGTH")
                isStrong -> safePotionType("STRONG_STRENGTH")
                else -> safePotionType("STRENGTH")
            }
            "poison" -> when {
                isLong -> safePotionType("LONG_POISON")
                isStrong -> safePotionType("STRONG_POISON")
                else -> safePotionType("POISON")
            }
            "slowness" -> when {
                isLong -> safePotionType("LONG_SLOWNESS")
                isStrong -> safePotionType("STRONG_SLOWNESS")
                else -> safePotionType("SLOWNESS")
            }
            "water", "potion" -> safePotionType("WATER")
            else -> {
                val upperName = normalized.uppercase()
                when {
                    isLong -> safePotionType("LONG_$upperName") ?: safePotionType(upperName)
                    isStrong -> safePotionType("STRONG_$upperName") ?: safePotionType(upperName)
                    else -> safePotionType(upperName)
                }
            }
        }
    }
    
    private fun Int.toRoman(): String {
        if (this <= 0) return toString()
        
        val floorKey = ROMAN.floorKey(this) ?: return toString()
        return if (this == floorKey) {
            ROMAN[this]!!
        } else {
            ROMAN[floorKey]!! + (this - floorKey).toRoman()
        }
    }
    
    companion object {
        private val ENCH_PATTERN = Regex("""([a-z0-9_]+?)[_-]?(\d+)-value""")
        private val POTION_PATTERN = Regex("""(?:(splash|lingering)_)?(?:(long|strong)_)?([a-z_]+)-value""")
        private val SPAWNER_PATTERN = Regex("""([a-z_]+)_spawner-value""")
        
        private val ROMAN = TreeMap<Int, String>().apply {
            put(1000, "M")
            put(900, "CM")
            put(500, "D")
            put(400, "CD")
            put(100, "C")
            put(90, "XC")
            put(50, "L")
            put(40, "XL")
            put(10, "X")
            put(9, "IX")
            put(5, "V")
            put(4, "IV")
            put(1, "I")
        }
    }
}