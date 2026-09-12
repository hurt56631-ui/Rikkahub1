package me.rerere.ai.provider.providers.geminiweb

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import me.rerere.ai.util.json

internal data class GeminiWebParsedResult(
    val text: String,
    val thoughts: String?,
    val images: List<String>,
    val hasGeneratedImagePlaceholder: Boolean,
)

internal object GeminiWebParser {
    private val generatedPlaceholder = Regex("https?://googleusercontent\\.com/image_generation_content/\\d+")

    fun parseLine(line: String): GeminiWebParsedResult? {
        val clean = line.replace(Regex("^\\)\\]}'"), "").trim()
        if (clean.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(clean) as? JsonArray }.getOrNull() ?: return null

        root.forEach { entry ->
            val envelope = entry as? JsonArray ?: return@forEach
            val payloadString = (envelope.getOrNull(2) as? JsonPrimitive)?.contentOrNull ?: return@forEach
            val payload = runCatching { json.parseToJsonElement(payloadString).jsonArray }.getOrNull()
                ?: return@forEach
            val candidates = payload.getOrNull(4) as? JsonArray ?: return@forEach
            val first = candidates.getOrNull(0) as? JsonArray ?: return@forEach

            var text = ((first.getOrNull(1) as? JsonArray)?.getOrNull(0) as? JsonPrimitive)
                ?.contentOrNull.orEmpty()
            var hasGeneratedPlaceholder = generatedPlaceholder.containsMatchIn(text)
            val thoughts = (((first.getOrNull(37) as? JsonArray)?.getOrNull(0) as? JsonArray)
                ?.getOrNull(0) as? JsonPrimitive)?.contentOrNull

            val images = linkedSetOf<String>()
            first.forEachIndexed { index, element ->
                if (index != 1) scanImages(element, images, 0) { hasGeneratedPlaceholder = true }
            }

            if (images.isNotEmpty()) {
                text = generatedPlaceholder.replace(text, "")
                images.forEach { imageUrl ->
                    text = removeImageReferences(text, imageUrl)
                }
                text = text.replace(Regex("\\[\\s*]\\(\\s*\\)"), "")
                    .replace(Regex("[ \\t]+\\n"), "\n")
                    .replace(Regex("\\n{3,}"), "\n\n")
                    .trim()
            }

            return GeminiWebParsedResult(
                text = text,
                thoughts = thoughts,
                images = images.toList(),
                hasGeneratedImagePlaceholder = hasGeneratedPlaceholder,
            )
        }
        return null
    }

    private fun removeImageReferences(text: String, normalizedUrl: String): String {
        val variants = linkedSetOf(normalizedUrl)
        if (normalizedUrl.startsWith("https://")) {
            variants += normalizedUrl.replaceFirst("https://", "http://")
            variants += normalizedUrl.removePrefix("https:")
        }
        var result = text
        variants.forEach { url ->
            val escaped = Regex.escape(url)
            result = result.replace(
                Regex("""!?\[[^]]*]\(\s*$escaped\s*(?:["'][^)]*["'])?\s*\)"""),
                "",
            )
            result = result.replace(
                Regex("""<img\b[^>]*\bsrc=["']$escaped["'][^>]*>""", RegexOption.IGNORE_CASE),
                "",
            )
            result = result.replace(url, "")
        }
        return result
    }

    private fun scanImages(
        element: JsonElement?,
        output: MutableSet<String>,
        depth: Int,
        onPlaceholder: () -> Unit,
    ) {
        if (element == null || depth > 20) return
        when (element) {
            is JsonPrimitive -> {
                if (!element.isString) return
                var value = element.content
                if (!(value.startsWith("http") || value.startsWith("//"))) return
                val lower = value.lowercase()
                if (!(lower.contains("googleusercontent.com") || lower.contains("ggpht.com"))) return
                if (lower.contains("image_generation_content")) {
                    onPlaceholder()
                    return
                }
                if (value.startsWith("//")) value = "https:$value"
                if (value.startsWith("http://")) value = "https://${value.removePrefix("http://")}" 
                val host = runCatching { java.net.URI(value).host?.lowercase() }.getOrNull() ?: return
                if (!(host == "googleusercontent.com" || host.endsWith(".googleusercontent.com") || host == "ggpht.com" || host.endsWith(".ggpht.com"))) return
                output += value
            }
            is JsonArray -> element.forEach { scanImages(it, output, depth + 1, onPlaceholder) }
            is JsonObject -> element.values.forEach { scanImages(it, output, depth + 1, onPlaceholder) }
            else -> Unit
        }
    }
}
