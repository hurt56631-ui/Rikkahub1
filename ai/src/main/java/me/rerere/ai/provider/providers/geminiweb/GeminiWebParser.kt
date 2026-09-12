package me.rerere.ai.provider.providers.geminiweb

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
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
            val array = entry as? JsonArray ?: return@forEach
            val payloadString = (array.getOrNull(2) as? JsonPrimitive)?.contentOrNull ?: return@forEach
            val payload = runCatching { json.parseToJsonElement(payloadString).jsonArray }.getOrNull()
                ?: return@forEach
            val candidates = payload.getOrNull(4) as? JsonArray ?: return@forEach
            val first = candidates.getOrNull(0) as? JsonArray ?: return@forEach

            var text = ((first.getOrNull(1) as? JsonArray)?.getOrNull(0) as? JsonPrimitive)
                ?.contentOrNull.orEmpty()
            val thoughts = (((first.getOrNull(37) as? JsonArray)?.getOrNull(0) as? JsonArray)
                ?.getOrNull(0) as? JsonPrimitive)?.contentOrNull

            val images = linkedSetOf<String>()
            val placeholderFound = booleanArrayOf(generatedPlaceholder.containsMatchIn(text))
            first.forEachIndexed { index, element ->
                if (index != 1) scanImages(element, images, placeholderFound, 0)
            }

            if (images.isNotEmpty()) {
                text = generatedPlaceholder.replace(text, "")
                images.forEach { imageUrl ->
                    val escaped = Regex.escape(imageUrl)
                    text = text.replace(Regex("!?\\[[^]]*]\\(\\s*$escaped(?:\\s+[\\\"'][^)]*[\\\"'])?\\s*\\)"), "")
                    text = text.replace(imageUrl, "")
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
                hasGeneratedImagePlaceholder = placeholderFound[0],
            )
        }
        return null
    }

    private fun scanImages(
        element: JsonElement?,
        output: MutableSet<String>,
        placeholderFound: BooleanArray,
        depth: Int,
    ) {
        if (element == null || depth > 20) return
        when (element) {
            is JsonPrimitive -> {
                if (!element.isString) return
                var value = element.content
                if (value.contains("image_generation_content")) {
                    placeholderFound[0] = true
                    return
                }
                if (!(value.startsWith("http") || value.startsWith("//"))) return
                if (!(value.contains("googleusercontent.com") || value.contains("ggpht.com"))) return
                if (value.startsWith("//")) value = "https:$value"
                if (value.startsWith("http://")) value = "https://${value.removePrefix("http://")}" 
                output += value
            }
            is JsonArray -> element.forEach { scanImages(it, output, placeholderFound, depth + 1) }
            is kotlinx.serialization.json.JsonObject -> element.values.forEach {
                scanImages(it, output, placeholderFound, depth + 1)
            }
            else -> Unit
        }
    }
}
