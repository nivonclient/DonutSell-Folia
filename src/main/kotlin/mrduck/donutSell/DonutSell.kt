package mrduck.donutSell

import com.github.retrooper.packetevents.PacketEvents
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.title.Title
import net.milkbowl.vault2.economy.Economy
import org.bukkit.*
import org.bukkit.block.CreatureSpawner
import org.bukkit.block.ShulkerBox
import org.bukkit.command.SimpleCommandMap
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.math.BigDecimal
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class DonutSell : JavaPlugin(), Listener {

    // Properties
    private lateinit var packetListener: SellPacketListener
    lateinit var cleanupListener: CleanupListener
    lateinit var viewTracker: ViewTracker
    lateinit var itemPricesMenu: ItemPricesMenu
    lateinit var sellGui: SellGui
    lateinit var progressGui: ProgressGui
    lateinit var historyTracker: HistoryTracker
    lateinit var sellHistoryGui: SellHistoryGui
    lateinit var resetConfirmationGui: ResetConfirmationGui
    private lateinit var sellAxeKey: NamespacedKey
    private lateinit var expiryKey: NamespacedKey
    private lateinit var saveFile: File
    private lateinit var saveConfig: FileConfiguration

    var econ: Economy? = null
        private set

    private val totalSold = ConcurrentHashMap<UUID, Double>()
    private val soldByCategory = ConcurrentHashMap<UUID, MutableMap<String, Double>>()
    private val itemHistory = ConcurrentHashMap<UUID, MutableMap<String, Stats>>()
    val categoryItems = mutableMapOf<String, List<String>>()
    val itemValues = mutableMapOf<String, Double>()
    private val toggleWorthDisabled = mutableSetOf<UUID>()
    private var sellAxeCountdownTask: ScheduledTask? = null

    // Data class for statistics
    data class Stats(var count: Double, var revenue: Double) {
        operator fun plus(other: Stats) = Stats(count + other.count, revenue + other.revenue)
    }

    // Enum for sell notification modes
    enum class SellNotifyMode {
        TITLE, ACTIONBAR, CHAT
    }

    companion object {
        lateinit var instance: DonutSell
            private set
    }

    override fun onEnable() {
        instance = this

        saveDefaultConfig()
        setupSaveFile()
        loadHistory()
        loadToggleWorthFromSave()
        buildCategoryItems()

        // Register new sell menu command if enabled
        if (config.getBoolean("use-new-sell-menu", false)) {
            lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { commands ->
                commands.registrar().register(
                    Commands.literal("sellmulti").executes { ctx ->
                        val sender = ctx.source.sender
                        if (sender !is Player) {
                            sender.sendMessage("§cOnly players can use this command.")
                            return@executes com.mojang.brigadier.Command.SINGLE_SUCCESS
                        }

                        Bukkit.getScheduler().runTask(this) { _ -> sellGui.open(sender) }
                        com.mojang.brigadier.Command.SINGLE_SUCCESS
                    }.build()
                )
            }
        }

        // Initialize components
        packetListener = SellPacketListener(this)
        cleanupListener = CleanupListener(this)

        server.pluginManager.registerEvents(cleanupListener, this)
        server.pluginManager.registerEvents(this, this)
        server.pluginManager.registerEvents(InventoryClickListener(this), this)
        server.pluginManager.registerEvents(ChatInputListener(this), this)
        server.pluginManager.registerEvents(SellMenuClickListener(this), this)
        server.pluginManager.registerEvents(HistoryClickListener(this), this)
        server.pluginManager.registerEvents(GuiClickListener(this), this)
        server.pluginManager.registerEvents(SellListener(this), this)

        // Strip lore for online players
        server.onlinePlayers.forEach { player ->
            cleanupListener.stripAllLore(player)
            player.scheduler.run(this, { _ -> player.updateInventory() }, null)
        }

        viewTracker = ViewTracker()
        itemPricesMenu = ItemPricesMenu(this)
        sellGui = SellGui(this)
        progressGui = ProgressGui(this)
        historyTracker = HistoryTracker()
        sellHistoryGui = SellHistoryGui(this)
        resetConfirmationGui = ResetConfirmationGui(this)

        // Initialize keys
        sellAxeKey = NamespacedKey(this, "sell_wand")
        expiryKey = NamespacedKey(this, "sell_wand_expiry")

        // Initialize commands
        SellHistoryCommand(this)
        SellPlaceholderExpansion(this).register()
        SellCommand(this)
        WorthCommand(this)
        SellAxeCommand(this, sellAxeKey, expiryKey)
        SellAxe(this, sellAxeKey, expiryKey)
        VaultMoneyPlaceholder(this).register()
        ToggleWorthCommand(this)

        unregisterOtherSellCommands()
        startSellAxeCountdown()

        logger.info("DonutSell plugin enabled.")
    }

    override fun onDisable() {
        sellAxeCountdownTask?.takeIf { !it.isCancelled }?.cancel()

        server.onlinePlayers.forEach { player ->
            cleanupListener.stripAllLore(player)
            player.scheduler.run(this, { _ -> player.updateInventory() }, null)
        }

        PacketEvents.getAPI().eventManager.unregisterListener(packetListener)
        saveHistory()
        saveToggleWorthToSave()

        logger.info("DonutSell plugin disabled.")
    }

    fun reloadPlugin() {
        reloadConfig()
        packetListener.reloadConfigData()
        itemPricesMenu = ItemPricesMenu(this)
        sellGui.loadConfig()
        progressGui.loadConfig()
        sellHistoryGui.loadConfig()
        buildCategoryItems()

        server.onlinePlayers.forEach { player ->
            cleanupListener.stripAllLore(player)
            player.scheduler.run(this, { _ -> player.updateInventory() }, null)
        }

        logger.info("DonutSell config reloaded.")
    }

    private fun getNotifyModes(): EnumSet<SellNotifyMode> {
        val modes = EnumSet.noneOf(SellNotifyMode::class.java)
        val raw = config.get("sell-notify-mode") ?: config.get("sell-shower")

        when (raw) {
            is String -> addModesFromString(raw, modes)
            is List<*> -> raw.forEach { addModesFromString(it.toString(), modes) }
            else -> raw?.let { addModesFromString(it.toString(), modes) }
        }

        if (modes.isEmpty()) {
            modes.add(SellNotifyMode.ACTIONBAR)
        }

        return modes
    }

    private fun addModesFromString(s: String?, modes: EnumSet<SellNotifyMode>) {
        s?.split(Regex("[,;\\s]+"))
            ?.map { it.trim().uppercase(Locale.ROOT) }
            ?.filter { it.isNotEmpty() }
            ?.forEach { part ->
                runCatching { modes.add(SellNotifyMode.valueOf(part)) }
            }
    }

    private fun notifySale(player: Player, moneyEarned: Double, itemsSold: Long) {
        val modes = getNotifyModes()
        val amt = Utils.abbreviateNumber(moneyEarned)
        val itemsStr = itemsSold.toString()

        if (SellNotifyMode.CHAT in modes) {
            val chatMsg = Utils.formatColors(
                config.getString("sell-menu.chat-message", "&#34ee80+$%amount%")
            ).replace("%amount%", amt).replace("%items%", itemsStr)
            player.sendMessage(chatMsg)
        }

        if (SellNotifyMode.ACTIONBAR in modes) {
            val actionbar = Utils.formatColors(
                config.getString("sell-menu.actionbar-message", "&#34ee80+$%amount%")
            ).replace("%amount%", amt).replace("%items%", itemsStr)
            player.sendActionBar(actionbar)
        }

        if (SellNotifyMode.TITLE in modes) {
            val title = Utils.formatColors(
                config.getString("sell-notify.screen.title", "&a+$%amount%")
            ).replace("%amount%", amt).replace("%items%", itemsStr)
            val subtitle = Utils.formatColors(
                config.getString("sell-notify.screen.subtitle", "&7You sold %items% items")
            ).replace("%amount%", amt).replace("%items%", itemsStr)

            player.showTitle(
                Title.title(
                    title,
                    subtitle,
                    Title.Times.times(
                        Duration.ofMillis(config.getInt("sell-notify.screen.fade-in", 5).toLong()),
                        Duration.ofMillis(config.getInt("sell-notify.screen.stay", 40).toLong()),
                        Duration.ofMillis(config.getInt("sell-notify.screen.fade-out", 10).toLong())
                    )
                )
            )
        }
    }

    @EventHandler
    fun onPluginEnableNormalize(event: PluginEnableEvent) {
        if (event.plugin.name == name) {
            server.onlinePlayers.forEach { player ->
                cleanupListener.stripAllLore(player)
                player.scheduler.run(this, { _ -> player.updateInventory() }, null)
                player.scheduler.runDelayed(this, { _ -> player.updateInventory() }, {}, 1L)
            }
        }
    }

    @EventHandler
    fun onPlayerJoinStrip(event: PlayerJoinEvent) {
        val player = event.player
        cleanupListener.stripAllLore(player)
        player.scheduler.run(this, { _ -> player.updateInventory() }, null)
        player.scheduler.runDelayed(this, { _ -> player.updateInventory() }, {}, 1L)
    }

    @EventHandler
    fun onPlayerQuitStrip(event: PlayerQuitEvent) {
        val player = event.player
        cleanupListener.stripAllLore(player)
        player.scheduler.run(this, { _ -> player.updateInventory() }, null)
    }

    private fun unregisterOtherSellCommands() {
        runCatching {
            val cmdMapField = Bukkit.getServer()::class.java.getDeclaredField("commandMap").apply {
                isAccessible = true
            }
            val cmdMap = cmdMapField.get(Bukkit.getServer()) as SimpleCommandMap

            val knownCmdsField = SimpleCommandMap::class.java.getDeclaredField("knownCommands").apply {
                isAccessible = true
            }
            @Suppress("UNCHECKED_CAST")
            val known = knownCmdsField.get(cmdMap) as MutableMap<String, org.bukkit.command.Command>

            known.entries.removeIf { (key, _) ->
                key.lowercase(Locale.ROOT).let { it == "sell" || it.endsWith(":sell") }
            }
        }.onFailure { e ->
            logger.warning("Failed to unregister other /sell commands: ${e.message}")
        }
    }

    private fun setupSaveFile() {
        saveFile = File(dataFolder, "saves.yml")
        if (!saveFile.exists()) {
            saveResource("saves.yml", false)
        }
        saveConfig = YamlConfiguration.loadConfiguration(saveFile)
    }

    private fun loadHistory() {
        saveConfig.getKeys(false).forEach { key ->
            val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: return@forEach

            totalSold[uuid] = saveConfig.getDouble("$key.total", 0.0)

            // Load categories
            saveConfig.getConfigurationSection("$key.categories")?.let { catSec ->
                soldByCategory[uuid] = catSec.getKeys(false)
                    .associateWith { catSec.getDouble(it, 0.0) }
                    .toMutableMap()
            }

            // Load items
            saveConfig.getConfigurationSection("$key.items")?.let { itemSec ->
                itemHistory[uuid] = itemSec.getKeys(false)
                    .associateWith { itemKey ->
                        Stats(
                            saveConfig.getDouble("$key.items.$itemKey.count", 0.0),
                            saveConfig.getDouble("$key.items.$itemKey.revenue", 0.0)
                        )
                    }.toMutableMap()
            }
        }
    }

    private fun saveHistory() {
        totalSold.forEach { (uuid, total) ->
            saveConfig.set("$uuid.total", total)
        }

        soldByCategory.forEach { (uuid, categories) ->
            categories.forEach { (cat, value) ->
                saveConfig.set("$uuid.categories.$cat", value)
            }
        }

        itemHistory.forEach { (uuid, items) ->
            items.forEach { (itemKey, stats) ->
                saveConfig.set("$uuid.items.$itemKey.count", stats.count)
                saveConfig.set("$uuid.items.$itemKey.revenue", stats.revenue)
            }
        }

        runCatching { saveConfig.save(saveFile) }
            .onFailure { it.printStackTrace() }
    }

    private fun loadToggleWorthFromSave() {
        toggleWorthDisabled.clear()
        saveConfig.getStringList("toggleworth-disabled")
            .mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            .forEach { toggleWorthDisabled.add(it) }
    }

    private fun saveToggleWorthToSave() {
        saveConfig.set("toggleworth-disabled", toggleWorthDisabled.map { it.toString() })
        runCatching { saveConfig.save(saveFile) }
            .onFailure { it.printStackTrace() }
    }

    fun isWorthDisabled(id: UUID) = id in toggleWorthDisabled

    fun setWorthEnabled(id: UUID, enabled: Boolean) {
        if (enabled) toggleWorthDisabled.remove(id) else toggleWorthDisabled.add(id)
        saveToggleWorthToSave()
    }

    private fun buildCategoryItems() {
        itemValues.clear()
        categoryItems.clear()

        config.getConfigurationSection("categories")?.let { cats ->
            cats.getKeys(false).forEach { cat ->
                val mats = mutableListOf<String>()

                config.getList("categories.$cat")?.forEach { obj ->
                    (obj as? Map<*, *>)?.forEach { (key, value) ->
                        val entryKey = key.toString().trim()
                        val price = runCatching { value.toString().toDouble() }
                            .getOrElse {
                                logger.warning("Invalid price for '$entryKey' in category '$cat'")
                                return@forEach
                            }

                        val lowerKey = entryKey.lowercase(Locale.ROOT)
                        itemValues[lowerKey] = price
                        mats.add(lowerKey.replace(Regex("(?i)-value$"), "").uppercase(Locale.ROOT))
                    }
                }

                categoryItems[cat] = mats
            }
        }
    }

    fun getPrice(key: String) = itemValues[key.lowercase(Locale.ROOT)]
        ?: config.getDouble("default-value", 0.1)

    fun getItemMap(): Map<String, Double> = itemValues.toMap()

    fun recordSale(player: Player, sold: Map<String, Stats>) {
        val uuid = player.uniqueId

        // Update item history
        val playerHistory = itemHistory.getOrPut(uuid) { mutableMapOf() }
        sold.forEach { (itemKey, stats) ->
            playerHistory.merge(itemKey, stats) { old, new -> old + new }
        }

        // Update total sold
        val totalRevenue = sold.values.sumOf { it.revenue }
        totalSold.merge(uuid, totalRevenue, Double::plus)

        // Update category revenue
        val playerCategoryRevenue = soldByCategory.getOrPut(uuid) { mutableMapOf() }
        categoryItems.forEach { (category, categoryItemsList) ->
            val categoryRevenue = sold.entries
                .filter { it.key.uppercase(Locale.ROOT) in categoryItemsList }
                .sumOf { it.value.revenue }

            if (categoryRevenue > 0.0) {
                playerCategoryRevenue.merge(category, categoryRevenue, Double::plus)
            }
        }
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as Player

        player.scheduler.run(this, { _ ->
            val openTitle = event.view.title()
            val classicTitle = Utils.formatColors(config.getString("sell-menu.title", "&aSell Items") ?: "&aSell Items")
            val newTitle = Utils.formatColors(config.getString("new-sell-menu.title", "&aSell Items") ?: "&aSell Items")

            if (openTitle == classicTitle && openTitle != newTitle) {
                processSellInventory(player, event.inventory)
            }
        }, null)
    }

    private fun processSellInventory(player: Player, inv: Inventory) {
        val useNewFlag = config.getBoolean("use-new-sell-menu", false)
        val excludeBottomRow = !useNewFlag && isUseMultipliers()
        val sellableSlots = inv.size - if (excludeBottomRow) 9 else 0

        val disabledSet = config.getStringList("disabled-items")
            .map { it.uppercase(Locale.ROOT) }
            .toSet()

        val declineSound = Sound.ENTITY_VILLAGER_NO
        val declineMsg = Utils.formatColors(config.getString("messages.cannot-sell", "&cYou cannot sell that item!"))
        val sold = mutableMapOf<String, Stats>()
        val revCats = mutableMapOf<String, Double>()
        var notifiedDecline = false

        // Return disabled items
        for (i in 0 until sellableSlots) {
            inv.getItem(i)?.takeIf { !it.type.isAir }?.let { item ->
                val mat = item.type.name
                val isShulkerBox = mat.endsWith("_SHULKER_BOX") || item.type == Material.SHULKER_BOX

                if (mat in disabledSet && !isShulkerBox) {
                    player.inventory.addItem(item).values.forEach { leftover ->
                        player.world.dropItemNaturally(player.location, leftover)
                    }
                    inv.setItem(i, null)

                    if (!notifiedDecline) {
                        player.playSound(player.location, declineSound, 1.0f, 1.0f)
                        player.sendMessage(declineMsg)
                        notifiedDecline = true
                    }
                }
            }
        }

        // Process items
        for (i in 0 until sellableSlots) {
            inv.getItem(i)?.takeIf { !it.type.isAir }?.let { item ->
                processItem(item, player, inv, i, disabledSet, sold, revCats)
            }
        }

        // Calculate payout and notify
        if (sold.isNotEmpty()) {
            recordSale(player, sold)

            val payout = if (isUseMultipliers()) {
                val categorized = revCats.entries.sumOf { (cat, value) ->
                    value * getSellMultiplier(player.uniqueId, cat)
                }
                val uncategorized = sold.entries
                    .filter { (key, _) ->
                        categoryItems.values.none { key.uppercase(Locale.ROOT) in it }
                    }
                    .sumOf { it.value.revenue }
                categorized + uncategorized
            } else {
                sold.values.sumOf { it.revenue }
            }

            econ?.deposit(name, player.uniqueId, BigDecimal.valueOf(payout))
            player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f)

            val itemsSold = sold.values.sumOf { it.count }.toLong()
            notifySale(player, payout, itemsSold)
        }
    }

    private fun processItem(
        item: ItemStack,
        player: Player,
        inv: Inventory,
        slot: Int,
        disabledSet: Set<String>,
        sold: MutableMap<String, Stats>,
        revCats: MutableMap<String, Double>
    ) {
        val mat = item.type.name
        val isShulkerBox = mat.endsWith("_SHULKER_BOX") || item.type == Material.SHULKER_BOX

        when {
            isShulkerBox && mat in disabledSet -> processDisabledShulkerBox(item, player, inv, slot, disabledSet, sold, revCats)
            isShulkerBox -> processShulkerBox(item, sold, revCats)
            mat !in disabledSet -> processRegularItem(item, sold, revCats)
        }
    }

    private fun processDisabledShulkerBox(
        item: ItemStack,
        player: Player,
        inv: Inventory,
        slot: Int,
        disabledSet: Set<String>,
        sold: MutableMap<String, Stats>,
        revCats: MutableMap<String, Double>
    ) {
        val meta = item.itemMeta as? BlockStateMeta ?: return
        val boxState = meta.blockState as? ShulkerBox ?: return

        boxState.inventory.contents.filterNotNull()
            .filter { !it.type.isAir && it.type.name !in disabledSet }
            .forEach { inside ->
                processInsideItem(inside, sold, revCats)
            }

        inv.setItem(slot, null)
        val emptyBox = ItemStack(item.type, item.amount)
        player.inventory.addItem(emptyBox).values.forEach { leftover ->
            player.world.dropItemNaturally(player.location, leftover)
        }
    }

    private fun processShulkerBox(
        item: ItemStack,
        sold: MutableMap<String, Stats>,
        revCats: MutableMap<String, Double>
    ) {
        val boxKey = item.type.name.lowercase(Locale.ROOT)
        val boxValue = getPrice("$boxKey-value") * item.amount
        sold.merge(boxKey, Stats(item.amount.toDouble(), boxValue)) { a, b -> a + b }

        categoryItems.entries
            .filter { item.type.name in it.value }
            .forEach { (cat, _) -> revCats.merge(cat, boxValue, Double::plus) }

        val meta = item.itemMeta as? BlockStateMeta ?: return
        val boxState = meta.blockState as? ShulkerBox ?: return

        boxState.inventory.contents.filterNotNull()
            .filter { !it.type.isAir }
            .forEach { inside -> processInsideItem(inside, sold, revCats) }
    }

    private fun processRegularItem(
        item: ItemStack,
        sold: MutableMap<String, Stats>,
        revCats: MutableMap<String, Double>
    ) {
        val meta = item.itemMeta

        if (item.type == Material.ENCHANTED_BOOK && meta is EnchantmentStorageMeta) {
            processEnchantedBook(meta, item.amount, sold, revCats)
        } else {
            val key = item.type.name.lowercase(Locale.ROOT)
            val value = calculateItemWorth(item)
            sold.merge(key, Stats(item.amount.toDouble(), value)) { a, b -> a + b }

            categoryItems.entries
                .filter { item.type.name in it.value }
                .forEach { (cat, _) -> revCats.merge(cat, value, Double::plus) }
        }
    }

    private fun processInsideItem(
        inside: ItemStack,
        sold: MutableMap<String, Stats>,
        revCats: MutableMap<String, Double>
    ) {
        val insideMeta = inside.itemMeta

        if (inside.type == Material.ENCHANTED_BOOK && insideMeta is EnchantmentStorageMeta) {
            processEnchantedBook(insideMeta, inside.amount, sold, revCats)
        } else {
            val insideKey = inside.type.name.lowercase(Locale.ROOT)
            val insideValue = calculateItemWorth(inside)
            sold.merge(insideKey, Stats(inside.amount.toDouble(), insideValue)) { a, b -> a + b }

            categoryItems.entries
                .filter { inside.type.name in it.value }
                .forEach { (cat, _) -> revCats.merge(cat, insideValue, Double::plus) }
        }
    }

    private fun processEnchantedBook(
        meta: EnchantmentStorageMeta,
        amount: Int,
        sold: MutableMap<String, Stats>,
        revCats: MutableMap<String, Double>
    ) {
        meta.storedEnchants.forEach { (enchantment, level) ->
            val enchName = enchantment.key.key.lowercase(Locale.ROOT)
            val keyName = "$enchName$level"
            val singlePrice = getPrice("$keyName-value")
            val totalRev = singlePrice * amount

            sold.merge(keyName, Stats(amount.toDouble(), totalRev)) { a, b -> a + b }

            categoryItems.entries
                .filter { keyName.uppercase(Locale.ROOT) in it.value }
                .forEach { (cat, _) -> revCats.merge(cat, totalRev, Double::plus) }
        }
    }

    private fun getPotionKey(item: ItemStack): String? {
        val meta = item.itemMeta as? PotionMeta ?: return null
        val basePotionType = meta.basePotionType ?: return null

        var key = basePotionType.name.lowercase(Locale.ROOT)

        if (basePotionType.isExtendable) key = "long_$key"
        if (basePotionType.isUpgradeable) key = "strong_$key"

        when (item.type) {
            Material.SPLASH_POTION -> key = "splash_$key"
            Material.LINGERING_POTION -> key = "lingering_$key"
            else -> {}
        }

        return key
    }

    fun calculateItemWorth(item: ItemStack): Double {
        val meta = item.itemMeta

        // Calculate base price
        val base = when {
            item.type == Material.SPAWNER && meta is BlockStateMeta -> {
                val spawnerState = meta.blockState as? CreatureSpawner
                spawnerState?.spawnedType?.let { type ->
                    getPrice("${type.name.lowercase(Locale.ROOT)}_spawner-value")
                } ?: getPrice("spawner-value")
            }
            else -> {
                getPotionKey(item)?.let { getPrice("$it-value") }
                    ?: getPrice("${item.type.name.lowercase(Locale.ROOT)}-value")
            }
        }

        // Calculate enchantment value
        var enchValue = 0.0

        if (meta is EnchantmentStorageMeta) {
            enchValue += meta.storedEnchants.entries.sumOf { (ench, level) ->
                getPrice("${ench.key.key.lowercase(Locale.ROOT)}$level-value")
            }
        }

        meta?.enchants?.entries?.forEach { (ench, level) ->
            enchValue += getPrice("${ench.key.key.lowercase(Locale.ROOT)}$level-value")
        }

        var total = (base + enchValue) * item.amount

        // Add shulker box contents
        if (meta is BlockStateMeta) {
            val boxState = meta.blockState as? ShulkerBox
            boxState?.inventory?.contents
                ?.filterNotNull()
                ?.filter { it.type != Material.AIR }
                ?.forEach { inside ->
                    total += calculateItemWorth(inside)
                }
        }

        return total
    }

    fun resetPlayerData(uuid: UUID) {
        totalSold.remove(uuid)
        soldByCategory.remove(uuid)
        itemHistory.remove(uuid)
        saveConfig.set(uuid.toString(), null)

        runCatching { saveConfig.save(saveFile) }
            .onFailure { e ->
                logger.severe("Could not save resets for $uuid: ${e.message}")
            }
    }

    fun getSellMultiplier(playerId: UUID, category: String): Double {
        val levels = config.getConfigurationSection("progress-menu.levels") ?: return 1.0

        val soldInCategory = soldByCategory[playerId]?.get(category) ?: 0.0

        return levels.getKeys(false)
            .mapNotNull { levelKey ->
                levels.getConfigurationSection(levelKey)?.let { levelSection ->
                    val amountNeeded = levelSection.getDouble("amountNeeded", Double.MAX_VALUE)
                    val multi = levelSection.getDouble("multi", 1.0)
                    if (soldInCategory >= amountNeeded) multi else null
                }
            }
            .maxOrNull() ?: 1.0
    }

    fun getHistory(playerId: UUID): Map<String, Stats> =
        itemHistory[playerId] ?: emptyMap()

    fun getFormattedTotalSold(playerId: UUID): String =
        Utils.abbreviateNumber(totalSold[playerId] ?: 0.0)

    fun getRawTotalSold(playerId: UUID, category: String): Double =
        soldByCategory[playerId]?.get(category) ?: 0.0

    fun sumInventory(inv: Inventory): Double =
        inv.contents
            .filterNotNull()
            .filter { !it.type.isAir }
            .sumOf { calculateItemWorth(it) }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val days = totalSeconds / 86400
        val hours = (totalSeconds % 86400) / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "${days}d ${hours}h ${minutes}m ${seconds}s"
    }

    fun isUseMultipliers() = config.getBoolean("use-multipliers", true)

    private fun startSellAxeCountdown() {
        val useCountdown = config.getBoolean("sell-axe.use-countdown", true)
        if (!useCountdown) return

        sellAxeCountdownTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, { _ ->
            val now = System.currentTimeMillis()

            Bukkit.getOnlinePlayers().forEach { player ->
                player.scheduler.run(this, { _ ->
                    val inv = player.inventory

                    for (slot in 0 until inv.size) {
                        inv.getItem(slot)?.takeIf { !it.type.isAir }?.let { item ->
                            updateSellAxeCountdown(item, slot, inv, player, now)
                        }
                    }
                }, null)
            }
        }, 1L, 200L)
    }

    private fun updateSellAxeCountdown(
        item: ItemStack,
        slot: Int,
        inv: Inventory,
        player: Player,
        now: Long
    ) {
        val meta = item.itemMeta ?: return
        val pdc = meta.persistentDataContainer

        val marker = pdc.get(sellAxeKey, PersistentDataType.BYTE)
        if (marker != 1.toByte()) return

        val expiry = pdc.get(expiryKey, PersistentDataType.LONG) ?: return

        when {
            now >= expiry -> {
                inv.setItem(slot, null)
                player.sendMessage(Utils.formatColors("&cYour DonutSell Wand has expired and been removed."))
            }
            else -> {
                val remainingMillis = expiry - now
                val formatted = formatDuration(remainingMillis)
                val template = config.getStringList("sell-axe.lore")

                val newLore = template.map { line ->
                    Utils.formatColors(line.replace("%countdown%", formatted))
                }

                meta.lore(newLore)
                item.itemMeta = meta
            }
        }
    }
}