package mrduck.donutSell

import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

data class GuiHolder(
    val categoryKey: String,
    val page: Int
) : InventoryHolder {

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getInventory(): Inventory = throw UnsupportedOperationException("GuiHolder does not provide an inventory")
}