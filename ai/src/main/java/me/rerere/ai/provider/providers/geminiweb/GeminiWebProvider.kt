package me.rerere.ai.provider.providers.geminiweb

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.uuid.Uuid

const val GEMINI_WEB_IMAGE_MODE_METADATA = "gemini_web_image_mode"

class GeminiWebProvider(
    private val client: OkHttpClient,
    @Suppress("UNUSED_PARAMETER") context: Context,
) : Provider<ProviderSetting.GeminiWeb> {
    private val session = GeminiWebSessionManager(client)
    private val mediaClient = client.newBuilder().callTimeout(90, TimeUnit.SECONDS).build()
    private val downloadClient = client.newBuilder().callTimeout(60, TimeUnit.SECONDS).build()

    override suspend fun listModels(providerSetting: ProviderSetting.GeminiWeb): List<Model> =
        GeminiWebModels.defaultModels()

    override suspend fun generateText(
        providerSetting: ProviderSetting.GeminiWeb,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val images = mutableMapOf<String, Pair<String, StringBuilder>>()
        streamText(providerSetting, messages, params).collect { chunk ->
            when (chunk) {
                is StreamChunk.TextDelta -> text.append(chunk.text)
                is StreamChunk.ReasoningDelta -> reasoning.append(chunk.text)
                is StreamChunk.ImageStart -> images[chunk.id] = chunk.mimeType to StringBuilder()
                is StreamChunk.ImageDelta -> images[chunk.id]?.second?.append(chunk.data)
                is StreamChunk.ImageSnapshot -> {
                    val holder = images[chunk.id] ?: ("image/png" to StringBuilder())
                    holder.second.clear(); holder.second.append(chunk.data); images[chunk.id] = holder
                }
                else -> Unit
            }
        }
        val parts = buildList {
            if (reasoning.isNotBlank()) add(UIMessagePart.Reasoning(reasoning.toString()))
            if (text.isNotBlank()) add(UIMessagePart.Text(text.toString()))
            images.values.forEach { (mime, data) -> add(UIMessagePart.Image("data:$mime;base64,$data")) }
        }
        return TextGenerationResult(
            id = Uuid.random().toString(),
            model = params.model.modelId,
            message = UIMessage(role = MessageRole.ASSISTANT, parts = parts),
            finishReason = "stop",
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.GeminiWeb,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = flow {
        val config = GeminiWebModels.config(params.model.modelId)
            ?: error("不支持的 Gemini Web 模型: ${params.model.modelId}")
        val auth = session.fetchRequestParams(providerSetting.authUser)
        val prepared = preparePrompt(messages)
        val uploaded = uploadImages(prepared.images, auth)
        val requestId = Uuid.random().toString().uppercase()
        val endpoint = buildEndpoint(auth)
        val request = Request.Builder()
            .url(endpoint)
            .header("Cookie", session.cookieHeader(endpoint.toString()))
            .header("User-Agent", GeminiWebSessionManager.ANDROID_CHROME_UA)
            .header("Origin", "https://gemini.google.com")
            .header("Referer", "https://gemini.google.com/")
            .header("X-Same-Domain", "1")
            .header("x-goog-ext-525001261-jspb", buildModelHeader(config, requestId, params.reasoningLevel, providerSetting.temporaryChatOnGoogle))
            .header("x-goog-ext-525005358-jspb", json.encodeToString(JsonArray(listOf(JsonPrimitive(requestId), JsonPrimitive(1)))))
            .header("x-goog-ext-73010989-jspb", "[0]")
            .header("x-goog-ext-73010990-jspb", "[0,0,0]")
            .apply { if (auth.authUser != "0") header("X-Goog-AuthUser", auth.authUser) }
            .post(
                FormBody.Builder()
                    .add("at", auth.atValue)
                    .add("f.req", constructPayload(prepared.prompt, uploaded, providerSetting.temporaryChatOnGoogle))
                    .build()
            )
            .build()

        client.newCall(request).execute().use { response ->
            session.syncResponseCookies(endpoint.toString(), response.headers("Set-Cookie"))
            if (!response.isSuccessful) error("Gemini Web 请求失败: HTTP ${response.code}")
            val source = response.body.source()
            val textId = "text-${Uuid.random()}"
            val reasoningId = "reasoning-${Uuid.random()}"
            var textStarted = false
            var reasoningStarted = false
            var lastText = ""
            var lastThoughts = ""
            var hasMeaningfulContent = false
            val imageUrls = linkedSetOf<String>()

            while (!source.exhausted()) {
                currentCoroutineContext().ensureActive()
                val line = source.readUtf8Line() ?: break
                if (line.contains("<!DOCTYPE html>", true) || line.contains("accounts.google.com/ServiceLogin", true)) {
                    error("Gemini Web 登录已失效，请重新登录 Google。")
                }
                val parsed = GeminiWebParser.parseLine(line) ?: continue

                if (parsed.thoughts.orEmpty().isNotBlank()) {
                    hasMeaningfulContent = true
                    if (!reasoningStarted) { emit(StreamChunk.ReasoningStart(reasoningId)); reasoningStarted = true }
                    snapshotDelta(lastThoughts, parsed.thoughts.orEmpty())?.takeIf { it.isNotEmpty() }?.let {
                        emit(StreamChunk.ReasoningDelta(reasoningId, it))
                    }
                    lastThoughts = parsed.thoughts.orEmpty()
                }
                if (parsed.text.isNotBlank()) {
                    hasMeaningfulContent = true
                    if (!textStarted) { emit(StreamChunk.TextStart(textId)); textStarted = true }
                    snapshotDelta(lastText, parsed.text)?.takeIf { it.isNotEmpty() }?.let {
                        emit(StreamChunk.TextDelta(textId, it))
                    }
                    lastText = parsed.text
                }

                val accepted = parsed.images.filter { url ->
                    val knownGeneratedPath = url.contains("/gg-dl/") || url.contains("/image_generation_content/")
                    when {
                        knownGeneratedPath || parsed.hasGeneratedImagePlaceholder -> true
                        prepared.imageMode && !prepared.hasInputImages -> true
                        // Image edit: do NOT accept a generic hosted image by itself; Gemini Web
                        // often echoes the uploaded original before the generated result arrives.
                        prepared.imageMode && prepared.hasInputImages -> false
                        else -> false
                    }
                }
                if (accepted.isNotEmpty()) {
                    hasMeaningfulContent = true
                    imageUrls += accepted
                }
            }

            if (!hasMeaningfulContent) {
                error("Gemini Web 返回了空响应，未检测到文字、Thinking 或生成图片。")
            }
            if (reasoningStarted) emit(StreamChunk.ReasoningEnd(reasoningId))
            if (textStarted) emit(StreamChunk.TextEnd(textId))
            imageUrls.forEachIndexed { index, url ->
                val image = downloadImage(url)
                val id = "image-$index-${Uuid.random()}"
                emit(StreamChunk.ImageStart(id, image.mimeType))
                emit(StreamChunk.ImageDelta(id, image.data))
                emit(StreamChunk.ImageEnd(id))
            }
            emit(StreamChunk.Finish(finishReason = "stop", responseId = requestId, model = params.model.modelId))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun generateImage(providerSetting: ProviderSetting, params: ImageGenerationParams): Flow<ImageGenerationItem> {
        val web = providerSetting as? ProviderSetting.GeminiWeb ?: error("Gemini Web 图片生成需要 Gemini Web Provider")
        return imageFlow(web, params.prompt, emptyList(), params.model)
    }

    override suspend fun editImage(providerSetting: ProviderSetting, params: ImageEditParams): Flow<ImageGenerationItem> {
        val web = providerSetting as? ProviderSetting.GeminiWeb ?: error("Gemini Web 图片编辑需要 Gemini Web Provider")
        return imageFlow(web, params.prompt, params.images, params.model)
    }

    private fun imageFlow(
        provider: ProviderSetting.GeminiWeb,
        prompt: String,
        imageUrls: List<String>,
        model: Model,
    ): Flow<ImageGenerationItem> = flow {
        val parts = buildList<UIMessagePart> {
            add(UIMessagePart.Text(prompt, metadata = JsonObject(mapOf(GEMINI_WEB_IMAGE_MODE_METADATA to JsonPrimitive(true)))))
            imageUrls.forEach { add(UIMessagePart.Image(it)) }
        }
        val useModel = if (GeminiWebModels.config(model.modelId) != null) model
            else GeminiWebModels.defaultModels().first { it.modelId == GeminiWebModels.IMAGE_MODEL }
        val buffers = mutableMapOf<String, Pair<String, StringBuilder>>()
        streamText(
            provider,
            listOf(UIMessage(role = MessageRole.USER, parts = parts)),
            TextGenerationParams(model = useModel, reasoningLevel = ReasoningLevel.OFF),
        ).collect { chunk ->
            when (chunk) {
                is StreamChunk.ImageStart -> buffers[chunk.id] = chunk.mimeType to StringBuilder()
                is StreamChunk.ImageDelta -> buffers[chunk.id]?.second?.append(chunk.data)
                is StreamChunk.ImageSnapshot -> {
                    val holder = buffers[chunk.id] ?: ("image/png" to StringBuilder())
                    holder.second.clear(); holder.second.append(chunk.data); buffers[chunk.id] = holder
                }
                is StreamChunk.ImageEnd -> buffers[chunk.id]?.let { emit(ImageGenerationItem(it.second.toString(), it.first)) }
                else -> Unit
            }
        }
    }.flowOn(Dispatchers.IO)

    private data class PreparedPrompt(
        val prompt: String,
        val images: List<UIMessagePart.Image>,
        val imageMode: Boolean,
        val hasInputImages: Boolean,
    )

    private fun preparePrompt(messages: List<UIMessage>): PreparedPrompt {
        val currentIndex = messages.indexOfLast { it.role == MessageRole.USER }
        require(currentIndex >= 0) { "Gemini Web 请求缺少用户消息" }
        val current = messages[currentIndex]
        val unsupported = current.parts.filter { it !is UIMessagePart.Text && it !is UIMessagePart.Image }
        if (unsupported.isNotEmpty()) error("Gemini Web 当前只支持文字和图片附件。")
        val imageMode = current.parts.filterIsInstance<UIMessagePart.Text>().any {
            (it.metadata?.get(GEMINI_WEB_IMAGE_MODE_METADATA) as? JsonPrimitive)?.content == "true"
        }
        val currentText = current.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n\n") { it.text }.trim()
            .ifBlank { if (current.parts.any { it is UIMessagePart.Image }) "请处理这张图片。" else "" }
        val history = messages.take(currentIndex)
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .mapNotNull { msg ->
                val body = msg.parts.joinToString("\n") {
                    when (it) { is UIMessagePart.Text -> it.text; is UIMessagePart.Image -> "[Image]"; else -> "" }
                }.trim()
                body.takeIf { it.isNotBlank() }?.let { "${if (msg.role == MessageRole.USER) "User" else "Assistant"}: $it" }
            }
        val system = messages.filter { it.role == MessageRole.SYSTEM }.joinToString("\n\n") { it.toText() }.trim()
        val actual = if (imageMode) "Generate or edit an image from the following instruction. Return the generated image.\n\n$currentText" else currentText
        val prompt = buildString {
            if (system.isNotBlank()) append("System instructions:\n$system\n\n")
            if (history.isNotEmpty()) append("Conversation history:\n${history.joinToString("\n\n")}\n\nCurrent user message:\n")
            append(actual)
        }
        val images = current.parts.filterIsInstance<UIMessagePart.Image>()
        return PreparedPrompt(prompt, images, imageMode, images.isNotEmpty())
    }

    private suspend fun uploadImages(
        images: List<UIMessagePart.Image>,
        auth: GeminiWebSessionManager.RequestParams,
    ): List<Pair<String, String>> {
        if (images.isEmpty()) return emptyList()
        val pushId = auth.uploadPushId ?: error("Gemini Web 缺少 Push-ID，请刷新登录状态。")
        val pctx = auth.uploadClientPctx ?: error("Gemini Web 缺少 X-Client-Pctx，请刷新登录状态。")
        return images.mapIndexed { index, image ->
            val (bytes, mime) = imageBytes(image)
            require(bytes.size <= 20 * 1024 * 1024) { "图片超过 20MB" }
            val fileName = "image_${index + 1}.${if (mime == "image/jpeg") "jpg" else "png"}"
            val start = Request.Builder()
                .url(UPLOAD_ENDPOINT)
                .header("Cookie", session.cookieHeader(UPLOAD_ENDPOINT))
                .header("User-Agent", GeminiWebSessionManager.ANDROID_CHROME_UA)
                .header("Push-ID", pushId)
                .header("X-Tenant-Id", "bard-storage")
                .header("X-Client-Pctx", pctx)
                .header("X-Goog-Upload-Protocol", "resumable")
                .header("X-Goog-Upload-Command", "start")
                .post("File name: $fileName".toRequestBody("text/plain".toMediaTypeOrNull()))
                .build()
            val uploadUrl = mediaClient.newCall(start).execute().use { response ->
                session.syncResponseCookies(UPLOAD_ENDPOINT, response.headers("Set-Cookie"))
                if (!response.isSuccessful) error("Gemini Web 图片上传初始化失败: HTTP ${response.code}")
                response.header("X-Goog-Upload-URL") ?: error("Gemini Web 图片上传未返回上传地址")
            }
            val host = uploadUrl.toHttpUrl().host.lowercase()
            require(host == "google.com" || host.endsWith(".google.com") || host.endsWith(".googleusercontent.com")) {
                "Gemini Web 返回了不可信的图片上传地址"
            }
            val finalize = Request.Builder()
                .url(uploadUrl)
                .header("Cookie", session.cookieHeader(uploadUrl))
                .header("User-Agent", GeminiWebSessionManager.ANDROID_CHROME_UA)
                .header("Push-ID", pushId)
                .header("X-Tenant-Id", "bard-storage")
                .header("X-Client-Pctx", pctx)
                .header("X-Goog-Upload-Command", "upload, finalize")
                .header("X-Goog-Upload-Offset", "0")
                .post(bytes.toRequestBody(mime.toMediaTypeOrNull()))
                .build()
            mediaClient.newCall(finalize).execute().use { response ->
                session.syncResponseCookies(uploadUrl, response.headers("Set-Cookie"))
                if (!response.isSuccessful) error("Gemini Web 图片上传失败: HTTP ${response.code}")
                response.body.string().trim().ifBlank { error("Gemini Web 图片上传返回空 ID") }
            } to fileName
        }
    }

    private fun imageBytes(image: UIMessagePart.Image): Pair<ByteArray, String> {
        if (image.url.startsWith("http://") || image.url.startsWith("https://")) {
            downloadClient.newCall(Request.Builder().url(image.url).get().build()).execute().use { response ->
                if (!response.isSuccessful) error("读取图片失败: HTTP ${response.code}")
                return response.body.bytes() to (response.body.contentType()?.toString() ?: "image/png")
            }
        }
        val encoded = image.encodeBase64(withPrefix = false).getOrThrow()
        val raw = encoded.base64.substringAfter(",", encoded.base64)
        return Base64.decode(raw, Base64.DEFAULT) to encoded.mimeType
    }

    private fun constructPayload(prompt: String, files: List<Pair<String, String>>, temporary: Boolean): String {
        val messageStruct = mutableListOf<JsonElement>(JsonPrimitive(prompt))
        if (files.isNotEmpty()) {
            val fileList = JsonArray(files.map { (id, name) -> JsonArray(listOf(JsonArray(listOf(JsonPrimitive(id))), JsonPrimitive(name))) })
            messageStruct.add(JsonPrimitive(0)); messageStruct.add(JsonNull); messageStruct.add(fileList)
        }
        val payload = mutableListOf<JsonElement>(
            JsonArray(messageStruct), JsonNull,
            JsonArray(listOf(JsonPrimitive(""), JsonPrimitive(""), JsonPrimitive("")))
        )
        if (temporary) { while (payload.size <= 45) payload.add(JsonNull); payload[45] = JsonPrimitive(true) }
        return json.encodeToString(JsonArray(listOf(JsonNull, JsonPrimitive(json.encodeToString(JsonArray(payload))))))
    }

    private fun buildEndpoint(auth: GeminiWebSessionManager.RequestParams) =
        "https://gemini.google.com${if (auth.authUser == "0") "" else "/u/${auth.authUser}"}/_/BardChatUi/data/assistant.lamda.BardFrontendService/StreamGenerate"
            .toHttpUrl().newBuilder()
            .addQueryParameter("bl", auth.blValue).addQueryParameter("f.sid", auth.fSid)
            .addQueryParameter("hl", auth.locale).addQueryParameter("_reqid", Random.nextInt(100000, 1000000).toString())
            .addQueryParameter("rt", "c").build()

    private fun buildModelHeader(config: GeminiWebHeaderConfig, requestId: String, level: ReasoningLevel, temporary: Boolean): String {
        val header = MutableList<JsonElement>(17) { JsonNull }
        header[0] = JsonPrimitive(1); header[4] = JsonPrimitive(config.hash)
        header[7] = if (temporary) JsonPrimitive(true) else JsonPrimitive(0)
        header[8] = JsonArray(config.capabilities.map(::JsonPrimitive))
        header[11] = JsonPrimitive(config.legacyMode ?: config.mode); header[14] = JsonPrimitive(config.mode)
        val thinking = when (level) {
            ReasoningLevel.OFF, ReasoningLevel.AUTO, ReasoningLevel.LOW -> 1
            ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH, ReasoningLevel.MAX -> 2
        }
        header[15] = JsonPrimitive(thinking); header[16] = JsonPrimitive(requestId)
        return json.encodeToString(JsonArray(header))
    }

    private fun snapshotDelta(previous: String, current: String): String? = when {
        current == previous -> ""
        current.startsWith(previous) -> current.substring(previous.length)
        else -> null
    }

    private fun downloadImage(url: String): ImageGenerationItem {
        val request = Request.Builder().url(url).header("User-Agent", GeminiWebSessionManager.ANDROID_CHROME_UA).get().build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Gemini Web 生成图片下载失败: HTTP ${response.code}")
            val bytes = response.body.bytes()
            return ImageGenerationItem(Base64.encodeToString(bytes, Base64.NO_WRAP), response.body.contentType()?.toString() ?: "image/png")
        }
    }

    companion object {
        private const val UPLOAD_ENDPOINT = "https://push.clients6.google.com/upload/"
    }
}
