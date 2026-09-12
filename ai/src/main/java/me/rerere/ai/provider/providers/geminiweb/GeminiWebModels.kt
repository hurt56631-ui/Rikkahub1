package me.rerere.ai.provider.providers.geminiweb

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import kotlin.uuid.Uuid

data class GeminiWebHeaderConfig(
    val hash: String,
    val mode: Int,
    val legacyMode: Int? = null,
    val capabilities: List<Int> = listOf(4, 5, 6, 8),
    val fastThinkingLevel: String = "low",
)

object GeminiWebModels {
    const val GEMINI_38_FLASH = "gemini-3.8-flash"
    const val GEMINI_36_FLASH = "gemini-3.6-flash"
    const val GEMINI_35_FLASH_LITE = "gemini-3.5-flash-lite"
    const val GEMINI_31_PRO = "gemini-3.1-pro"
    const val IMAGE_MODEL = "gemini-web-image"

    private val configs = mapOf(
        GEMINI_38_FLASH to GeminiWebHeaderConfig(
            hash = "56fdd199312815e2",
            legacyMode = 2,
            mode = 1,
            capabilities = listOf(4, 5, 6, 8, 4, 5, 6, 8),
            fastThinkingLevel = "minimal",
        ),
        // Gemini Nexus removed this hash from its Web catalog in v5.2.0, but it is kept
        // here as an explicit compatibility option requested by the user.
        GEMINI_36_FLASH to GeminiWebHeaderConfig(
            hash = "fbb127bbb056c959",
            mode = 1,
            capabilities = listOf(4, 5, 6, 8),
            fastThinkingLevel = "minimal",
        ),
        GEMINI_35_FLASH_LITE to GeminiWebHeaderConfig(
            hash = "cf41b0e0dd7d53e5",
            mode = 6,
            fastThinkingLevel = "minimal",
        ),
        GEMINI_31_PRO to GeminiWebHeaderConfig(
            hash = "e6fa609c3fa255c0",
            mode = 3,
            fastThinkingLevel = "low",
        ),
        IMAGE_MODEL to GeminiWebHeaderConfig(
            hash = "56fdd199312815e2",
            legacyMode = 2,
            mode = 1,
            capabilities = listOf(4, 5, 6, 8, 4, 5, 6, 8),
            fastThinkingLevel = "minimal",
        ),
    )

    fun config(modelId: String): GeminiWebHeaderConfig? = configs[modelId]

    fun defaultModels(): List<Model> = listOf(
        chatModel(GEMINI_38_FLASH, "Gemini 3.8 Flash", "e313e983-3684-40e5-8349-b1c22dc3d460"),
        chatModel(GEMINI_36_FLASH, "Gemini 3.6 Flash (兼容)", "a7b0d475-dbe4-42fc-9637-229a67f41074"),
        chatModel(GEMINI_35_FLASH_LITE, "Gemini 3.5 Flash-Lite", "aa0212da-9266-489e-b30c-1f89c2d036c9"),
        chatModel(GEMINI_31_PRO, "Gemini 3.1 Pro", "185cccf6-e007-4976-a304-b0c1a9cf6145"),
        Model(
            modelId = IMAGE_MODEL,
            displayName = "Gemini Web 图片生成",
            id = Uuid.parse("a23a0433-2283-40db-ba4e-4dd93cfc1063"),
            type = ModelType.IMAGE,
            inputModalities = listOf(Modality.TEXT),
            outputModalities = listOf(Modality.IMAGE),
        ),
    )

    private fun chatModel(modelId: String, displayName: String, id: String) = Model(
        modelId = modelId,
        displayName = displayName,
        id = Uuid.parse(id),
        type = ModelType.CHAT,
        inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
        outputModalities = listOf(Modality.TEXT, Modality.IMAGE),
        abilities = listOf(ModelAbility.REASONING),
    )
}
