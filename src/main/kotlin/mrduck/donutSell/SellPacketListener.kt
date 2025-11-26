package mrduck.donutSell

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.item.ItemStack
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.CreatureSpawner
import org.bukkit.block.ShulkerBox
import org.bukkit.event.Listener
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.inventory.meta.PotionMeta
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class SellPacketListener(private val plugin: DonutSell) : 
    PacketListenerAbstract(PacketListenerPriority.NORMAL), Listener {
    
    private val values = ConcurrentHashMap<Material, Double>()
    @Volatile private var loreTemplate: List<String> = emptyList()
    @Volatile private var lorePlainPrefixes: List<String> = emptyList()
    @Volatile private var defaultValue: Double = 0.0
    @Volatile private var noWorthGuiNames: List<String> = emptyList()
    @Volatile private var displayWorthLore: Boolean = false
    @Volatile private var disabledItems: Set<String> = emptySet()
    
    private val openWindowId = ConcurrentHashMap<UUID, Int>()
    private val noWorthOpen = ConcurrentHashMap<UUID, Boolean>()
    private val lastClickCancelled = ConcurrentHashMap<UUID, Boolean>()
    private val containerSize = ConcurrentHashMap<UUID, Int>()
    
    init {
        loadConfigData()
        PacketEvents.getAPI().eventManager.registerListener(this)
        plugin.server.pluginManager.registerEvents(this, plugin)
    }
    
    fun loadConfigData() {
        values.clear()
        plugin.itemValues.forEach { (key, price) ->
            val matName = key.replace(Regex("(?i)-value$"), "").uppercase()
            try {
                values[Material.valueOf(matName)] = price
            } catch (_: IllegalArgumentException) {
                // Ignore invalid material names
            }
        }
        
        defaultValue = plugin.config.getDouble("default-value", 0.1)
        loreTemplate = plugin.config.getStringList("lore")
        lorePlainPrefixes = loreTemplate.map { line ->
            PlainTextComponentSerializer.plainText()
                .serialize(Utils.formatColors(line.replace("%amount%", "")))
                .lowercase()
                .trim()
        }
        displayWorthLore = plugin.config.getBoolean("display-worth-lore", true)
        noWorthGuiNames = plugin.config.getStringList("no-worth-gui-names")
            .map { it.lowercase() }
        disabledItems = plugin.config.getStringList("disabled-items")
            .mapTo(mutableSetOf()) { it.uppercase() }
    }
    
    override fun onPacketSend(event: PacketSendEvent) {
        val user = event.user
        val uuid = user.uuid
        val player = plugin.server.getPlayer(uuid) ?: return
        
        if (player.gameMode == GameMode.CREATIVE) {
            stripAll(event)
            return
        }
        
        when (event.packetType) {
            PacketType.Play.Server.OPEN_WINDOW -> {
                handleOpenWindow(event, uuid)
                return
            }
        }
        
        if (!displayWorthLore || plugin.isWorthDisabled(uuid)) {
            stripAll(event)
            return
        }
        
        when (event.packetType) {
            PacketType.Play.Server.WINDOW_ITEMS -> handleWindowItems(event, uuid)
            PacketType.Play.Server.SET_SLOT -> handleSetSlot(event, uuid)
        }
    }
    
    private fun handleOpenWindow(event: PacketSendEvent, uuid: UUID) {
        val wrapper = WrapperPlayServerOpenWindow(event)
        val wid = wrapper.containerId
        openWindowId[uuid] = wid
        
        val titleComponent = wrapper.title
        val titleJson = GsonComponentSerializer.gson().serialize(titleComponent).lowercase()
        val bw = noWorthGuiNames.any { titleJson.contains(it) }
        noWorthOpen[uuid] = bw
        containerSize.remove(uuid)
    }
    
    private fun handleWindowItems(event: PacketSendEvent, uuid: UUID) {
        val wrapper = WrapperPlayServerWindowItems(event)
        val wid = wrapper.windowId
        val invWindow = wid == 0
        val guiNoWorth = noWorthOpen.getOrDefault(uuid, false) && !invWindow
        
        val items = wrapper.items.toMutableList()
        
        if (invWindow) {
            items.replaceAll { applyLorePE(it, uuid) }
        } else {
            val total = items.size
            val invSlots = 36
            val contSlots = (total - invSlots).coerceAtLeast(0)
            containerSize[uuid] = contSlots
            
            items.indices.forEach { i ->
                val orig = items[i]
                items[i] = when {
                    i < contSlots -> if (guiNoWorth) stripWorthLorePE(orig) else applyLorePE(orig, uuid)
                    else -> applyLorePE(orig, uuid)
                }
            }
        }
        
        wrapper.items = items
    }
    
    private fun handleSetSlot(event: PacketSendEvent, uuid: UUID) {
        val wrapper = WrapperPlayServerSetSlot(event)
        val wid = wrapper.windowId
        val invWindow = wid == 0
        val guiNoWorth = noWorthOpen.getOrDefault(uuid, false) && !invWindow
        val clickCancelled = lastClickCancelled.getOrDefault(uuid, false)
        val input = wrapper.item
        val slot = wrapper.slot
        
        val output = when {
            invWindow -> applyLorePE(input, uuid)
            else -> {
                val contSlots = containerSize.getOrDefault(uuid, 0)
                when {
                    slot >= contSlots -> applyLorePE(input, uuid)
                    clickCancelled -> stripWorthLorePE(input)
                    else -> if (guiNoWorth) stripWorthLorePE(input) else applyLorePE(input, uuid)
                }
            }
        }
        
        wrapper.item = output
        lastClickCancelled.remove(uuid)
    }
    
    private fun stripAll(event: PacketSendEvent) {
        when (event.packetType) {
            PacketType.Play.Server.WINDOW_ITEMS -> {
                val wrapper = WrapperPlayServerWindowItems(event)
                val items = wrapper.items.toMutableList()
                items.replaceAll { stripWorthLorePE(it) }
                wrapper.items = items
            }
            PacketType.Play.Server.SET_SLOT -> {
                val wrapper = WrapperPlayServerSetSlot(event)
                wrapper.item = stripWorthLorePE(wrapper.item)
            }
        }
    }
    
    private fun stripWorthLorePE(original: ItemStack?): ItemStack? {
        if (original == null || original.isEmpty) return original
        
        val bukkitItem = SpigotConversionUtil.toBukkitItemStack(original)
        val stripped = stripWorthLore(bukkitItem)
        return SpigotConversionUtil.fromBukkitItemStack(stripped)
    }
    
    fun stripWorthLore(original: org.bukkit.inventory.ItemStack?): org.bukkit.inventory.ItemStack? {
        original ?: return null
        
        val item = original.clone()
        val meta = item.itemMeta ?: return item
        if (!meta.hasLore()) return item
        
        val filtered = meta.lore()?.filterNot { line ->
            val plain = PlainTextComponentSerializer.plainText()
                .serialize(line)
                .lowercase()
                .trim()
            lorePlainPrefixes.any { plain.startsWith(it) }
        }
        
        meta.lore(filtered?.takeIf { it.isNotEmpty() })
        item.itemMeta = meta
        return item
    }
    
    private fun applyLorePE(original: ItemStack?, playerId: UUID): ItemStack? {
        if (original == null || original.isEmpty) return original
        
        val bukkitItem = SpigotConversionUtil.toBukkitItemStack(original)
        val modified = applyLore(bukkitItem, playerId)
        return SpigotConversionUtil.fromBukkitItemStack(modified)
    }
    
    fun applyLore(
        original: org.bukkit.inventory.ItemStack?,
        playerId: UUID
    ): org.bukkit.inventory.ItemStack? {
        if (original == null || original.type == Material.AIR) return original
        
        if (!displayWorthLore || 
            plugin.isWorthDisabled(playerId) || 
            original.type.name in disabledItems) {
            return original
        }
        
        val item = original.clone()
        val meta = item.itemMeta ?: return original
        
        val totalValue = calculateTotalValue(item, meta, playerId)
        val display = Utils.abbreviateNumber(totalValue)
        val newLines = loreTemplate.map { line ->
            Utils.formatColors(line.replace("%amount%", display))
        }
        
        val existing = (meta.lore()?.toMutableList() ?: mutableListOf()).apply {
            removeIf { line ->
                val plain = PlainTextComponentSerializer.plainText()
                    .serialize(line)
                    .lowercase()
                    .trim()
                lorePlainPrefixes.any { plain.startsWith(it) }
            }
        }
        
        newLines.forEach { nl ->
            if (existing.none {
                    it == nl
            }) {
                existing.add(nl)
            }
        }
        
        meta.lore(existing.takeIf { it.isNotEmpty() })
        item.itemMeta = meta
        return item
    }
    
    private fun calculateTotalValue(
        item: org.bukkit.inventory.ItemStack,
        meta: ItemMeta,
        playerId: UUID
    ): Double {
        if (meta is BlockStateMeta) {
            val state = meta.blockState
            if (state is ShulkerBox) {
                return calculateShulkerValue(item, state, playerId)
            }
        }
        
        val baseVal = calculateBaseValue(item, meta)
        val enchVal = calculateEnchantmentValue(meta)
        
        val amt = item.amount
        val raw = (baseVal + enchVal) * amt
        
        val cat = plugin.categoryItems.entries
            .firstOrNull { (_, items) -> item.type.name in items }
            ?.key
        
        val mult = cat?.let { plugin.getSellMultiplier(playerId, it) } ?: 1.0
        
        return raw * mult
    }
    
    private fun calculateShulkerValue(
        item: org.bukkit.inventory.ItemStack,
        box: ShulkerBox,
        playerId: UUID
    ): Double {
        var totalValue = 0.0
        
        val boxKey = "${item.type.name.lowercase()}-value"
        val boxUnitPrice = plugin.getPrice(boxKey)
        val boxCount = item.amount
        
        if (item.type.name !in disabledItems) {
            val boxCat = plugin.categoryItems.entries
                .firstOrNull { (_, items) -> item.type.name in items }
                ?.key
            val boxMult = boxCat?.let { plugin.getSellMultiplier(playerId, it) } ?: 1.0
            totalValue += boxUnitPrice * boxCount * boxMult
        }
        
        box.inventory.contents.forEach { inside ->
            if (inside != null && 
                inside.type != Material.AIR && 
                inside.type.name !in disabledItems) {
                val insideRaw = plugin.calculateItemWorth(inside)
                val insideCat = plugin.categoryItems.entries
                    .firstOrNull { (_, items) -> inside.type.name in items }
                    ?.key
                val insideMult = insideCat?.let { plugin.getSellMultiplier(playerId, it) } ?: 1.0
                totalValue += insideRaw * insideMult
            }
        }
        
        return totalValue
    }
    
    private fun calculateBaseValue(
        item: org.bukkit.inventory.ItemStack,
        meta: ItemMeta
    ): Double {
        if (item.type == Material.SPAWNER && meta is BlockStateMeta) {
            val state = meta.blockState
            if (state is CreatureSpawner && state.spawnedType != null) {
                val spawnerKey = "${state.spawnedType!!.name.lowercase()}_spawner-value"
                return plugin.getPrice(spawnerKey)
            }
            return values.getOrDefault(Material.SPAWNER, defaultValue)
        }
        
        val pKey = getPotionKey(item)
        return if (pKey != null) {
            plugin.getPrice("$pKey-value")
        } else {
            values.getOrDefault(item.type, defaultValue)
        }
    }
    
    private fun calculateEnchantmentValue(meta: ItemMeta): Double {
        var enchVal = 0.0
        
        if (meta is EnchantmentStorageMeta) {
            meta.storedEnchants.forEach { (ench, level) ->
                val enchKey = "${ench.key.key.lowercase()}$level-value"
                enchVal += plugin.getPrice(enchKey)
            }
        }
        
        meta.enchants.forEach { (ench, level) ->
            val enchKey = "${ench.key.key.lowercase()}$level-value"
            enchVal += plugin.getPrice(enchKey)
        }
        
        return enchVal
    }
    
    private fun getPotionKey(item: org.bukkit.inventory.ItemStack): String? {
        val meta = item.itemMeta as? PotionMeta ?: return null
        val data = meta.basePotionType ?: return null
        
        var base = data.name.lowercase()
        if (data.isExtendable) base = "long_$base"
        if (data.isUpgradeable) base = "strong_$base"
        
        return when (item.type) {
            Material.SPLASH_POTION -> "splash_$base"
            Material.LINGERING_POTION -> "lingering_$base"
            else -> base
        }
    }
    
    fun reloadConfigData() {
        loadConfigData()
    }
}