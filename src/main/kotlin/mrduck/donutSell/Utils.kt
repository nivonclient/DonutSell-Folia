package mrduck.donutSell

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer


object Utils {
    private val MINI = MiniMessage.miniMessage()
    private val LEGACY_HEX = Regex("&#([A-Fa-f0-9]{6})")
    private val UNITS = arrayOf("K", "M", "B", "T", "Q")
    private val LEGACY_SERIALIZER = LegacyComponentSerializer.legacyAmpersand()

    fun formatColors(input: String?): Component {
        if (input == null) return Component.empty()

        // Convert &#RRGGBB → <#RRGGBB>
        val withHex = LEGACY_HEX.replace(input, "<#$1>")
        
        // Convert legacy color codes and serialize back to MiniMessage format
        val component = LEGACY_SERIALIZER.deserialize(withHex)
        
        return MINI.deserialize(component.toString())
    }

    fun formatColors(lines: List<String>): List<Component> =
        lines.map { formatColors(it) }

    fun abbreviateNumber(number: Double): String {
        if (number < 1000) {
            val whole = number.toLong()
            return if (number == whole.toDouble()) whole.toString() else number.toString()
        }

        var value = number
        var idx = -1
        
        while (value >= 1000 && idx < UNITS.lastIndex) {
            value /= 1000.0
            idx++
        }

        val whole = value.toLong()
        val formatted = if (value == whole.toDouble()) {
            whole.toString()
        } else {
            "%.2f".format(value)
        }
        
        return "$formatted${UNITS[idx]}"
    }
}

fun Component.content() : String  {
    return PlainTextComponentSerializer.plainText().serialize(this)
}

fun String.component() : Component  {
    return Component.text(this)
}

fun List<String>.component() : List<Component>  {
    val comp = ArrayList<Component>();
    for (str in this) {
        comp.add(str.component())
    }
    return comp
}

fun Component.replace(placeholder: String, value: String): Component {
    val serialized = MiniMessage.miniMessage().serialize(this)
    return MiniMessage.miniMessage().deserialize(
        serialized,
        Placeholder.parsed(placeholder, value)
    )
}

fun Component.repeat(n: Int): Component {
    require(n >= 0) { "Count 'n' must be non-negative, but was $n." }

    return when (n) {
        0 -> Component.empty()
        1 -> this
        else -> {
            val builder = Component.text()
            repeat(n) {
                builder.append(this)
            }
            builder.build()
        }
    }
}