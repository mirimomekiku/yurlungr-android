package dev.yurlungr.app.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Parsing (and re-encoding, in the same ACP shape, for mirroring to another device) of the
 * session extras: slash commands, config options, usage and prompt capabilities.
 */
object AcpExtras {
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    fun parseCommands(arr: JsonElement?): List<SlashCommand> = (arr as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        SlashCommand(
            name = o.str("name")?.removePrefix("/") ?: return@mapNotNull null,
            description = o.str("description").orEmpty(),
            hint = (o["input"] as? JsonObject)?.str("hint"),
        )
    }

    fun encodeCommands(commands: List<SlashCommand>) = buildJsonArray {
        commands.forEach { c ->
            addJsonObject {
                put("name", c.name)
                put("description", c.description)
                c.hint?.let { h -> putJsonObject("input") { put("hint", h) } }
            }
        }
    }

    fun parseConfigOptions(arr: JsonElement?): List<ConfigOption> = (arr as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val id = o.str("id") ?: return@mapNotNull null
        val current = o["currentValue"] as? JsonPrimitive
        val isBoolean = o.str("type") == "boolean"
        // Options are either a flat list or a list of named groups; flatten groups.
        val choices = (o["options"] as? JsonArray).orEmpty().flatMap { opt ->
            val oo = opt as? JsonObject ?: return@flatMap emptyList()
            if (oo["group"] != null) {
                (oo["options"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::choice) }
            } else {
                listOfNotNull(choice(oo))
            }
        }
        ConfigOption(
            id = id,
            name = o.str("name") ?: id,
            description = o.str("description"),
            category = o.str("category"),
            choices = choices,
            currentValue = if (isBoolean) null else current?.contentOrNull,
            booleanValue = if (isBoolean) current?.booleanOrNull ?: false else null,
        )
    }

    private fun choice(o: JsonObject): ConfigChoice? {
        val value = o.str("value") ?: return null
        return ConfigChoice(value, o.str("name") ?: value, o.str("description"))
    }

    fun encodeConfigOptions(options: List<ConfigOption>) = buildJsonArray {
        options.forEach { c ->
            addJsonObject {
                put("id", c.id)
                put("name", c.name)
                c.description?.let { put("description", it) }
                c.category?.let { put("category", it) }
                if (c.isBoolean) {
                    put("type", "boolean")
                    put("currentValue", c.booleanValue)
                } else {
                    put("type", "select")
                    c.currentValue?.let { put("currentValue", it) }
                    putJsonArray("options") {
                        c.choices.forEach { ch ->
                            addJsonObject {
                                put("value", ch.value)
                                put("name", ch.name)
                                ch.description?.let { put("description", it) }
                            }
                        }
                    }
                }
            }
        }
    }

    fun parseUsage(o: JsonObject?): Usage? {
        o ?: return null
        val used = (o["used"] as? JsonPrimitive)?.longOrNull ?: return null
        val size = (o["size"] as? JsonPrimitive)?.longOrNull ?: return null
        val cost = o["cost"] as? JsonObject
        return Usage(used, size, (cost?.get("amount") as? JsonPrimitive)?.doubleOrNull, cost?.str("currency"))
    }

    fun encodeUsage(u: Usage) = buildJsonObject {
        put("used", u.used)
        put("size", u.size)
        if (u.costAmount != null) {
            putJsonObject("cost") {
                put("amount", u.costAmount)
                put("currency", u.currency ?: "USD")
            }
        }
    }

    fun parseCaps(agentCapabilities: JsonObject?): PromptCaps {
        val p = agentCapabilities?.get("promptCapabilities") as? JsonObject ?: return PromptCaps()
        return PromptCaps(
            image = (p["image"] as? JsonPrimitive)?.booleanOrNull ?: false,
            embeddedContext = (p["embeddedContext"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    /** "12k / 200k tokens · $0.04" */
    fun usageLabel(u: Usage): String {
        fun k(n: Long) = if (n >= 1000) "${n / 1000}k" else n.toString()
        val cost = u.costAmount?.let { a ->
            val symbol = if (u.currency.isNullOrEmpty() || u.currency == "USD") "$" else "${u.currency} "
            " · $symbol" + String.format(java.util.Locale.US, "%.2f", a)
        }.orEmpty()
        return "${k(u.used)} / ${k(u.size)} tokens$cost"
    }
}
