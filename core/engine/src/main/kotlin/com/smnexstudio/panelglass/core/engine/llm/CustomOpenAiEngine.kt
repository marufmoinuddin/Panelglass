package com.smnexstudio.panelglass.core.engine.llm

import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.data.secure.SecureKeyStore
import com.smnexstudio.panelglass.core.engine.http.HttpEngineSupport
import com.smnexstudio.panelglass.core.model.CustomEndpoint
import com.smnexstudio.panelglass.core.model.EngineException
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's own OpenAI-compatible server (the "Custom AI" engine).
 *
 * The base address and the model id come from Settings, the key from the key store. The request is the plain Chat
 * Completions shape with text only: the on-device OCR does the reading, so no image, crop or SFX montage is ever
 * attached. The address is used exactly as the user wrote it — no version segment is appended — and the completions
 * path is added by this engine, so both `https://host/v1` and a pasted `https://host/v1/chat/completions` work.
 *
 * The server may be on the user's own network, in which case plain `http` is allowed: [CustomEndpoint] decides which
 * hosts those are. The API key is optional: a server at home usually has none, and then no `Authorization` header is
 * sent at all.
 *
 * Some compatible servers refuse the optional `temperature` field with a 400. The request is then sent once more
 * without it; that is the only request shape this engine changes.
 */
@Singleton
class CustomOpenAiEngine @Inject constructor(
    support: HttpEngineSupport,
    keys: SecureKeyStore,
    private val settings: SettingsRepository,
) : LlmEngine(support, keys) {
    override val id = EngineId.CUSTOM
    /** Text only: the crops and the SFX montage stay on the phone. */
    override val acceptsImages: Boolean get() = false

    /** The address of the last call, so the connection warm-up reaches the same host. */
    @Volatile private var lastBase: String = ""
    override val warmUrl: String get() = lastBase.trimEnd('/') + "/"

    /**
     * The parsed address, or the failure the user must see.
     *
     * The wording is the same wherever the address is checked — the dialog, the picker and the engine — so the user
     * reads one message for one mistake.
     */
    private fun endpoint(raw: String): CustomEndpoint.Parsed.Ok =
        when (val p = CustomEndpoint.parse(raw)) {
            is CustomEndpoint.Parsed.Ok -> p
            is CustomEndpoint.Parsed.Bad -> throw EngineException(
                EngineFailure.Unavailable(
                    id,
                    when (p.problem) {
                        CustomEndpoint.Problem.HTTP_NOT_LOCAL ->
                            "http is only allowed for addresses on your own network; use https for other servers"
                        CustomEndpoint.Problem.CREDENTIALS ->
                            "Do not put a username or password in the address"
                        else -> "Server address is not valid"
                    },
                )
            )
        }

    /** The normalized base address from Settings, or null when the user has not set a usable one. */
    private fun baseUrl(s: Settings): String? =
        (CustomEndpoint.parse(s.customBaseUrl) as? CustomEndpoint.Parsed.Ok)?.chatUrl?.removeSuffix(CustomEndpoint.COMPLETIONS_PATH)

    override suspend fun warmUp(src: Lang, tgt: Lang) {
        val base = baseUrl(settings.current()) ?: return
        lastBase = base
        support.warm(warmUrl)
    }

    override suspend fun complete(apiKey: String, system: String, turns: List<Turn>, maxOutputTokens: Int): String {
        val s = settings.current()
        val model = s.modelFor(id)
        if (model.isBlank()) throw EngineException(EngineFailure.Unavailable(id, "No model configured"))
        val endpoint = endpoint(s.customBaseUrl)
        lastBase = endpoint.chatUrl

        val headers = headersFor(apiKey)
        val reply = try {
            call { support.post(id, endpoint.chatUrl, headers, body(model, system, turns, maxOutputTokens, withTemperature = true)) }
        } catch (e: EngineException) {
            // A 400 that names the request (not the key) is the server refusing a field of ours: send it once more
            // without `temperature`, the one optional field some compatible servers refuse.
            val refused = e.failure as? EngineFailure.Unavailable
            if (refused == null || !refused.reason.startsWith("HTTP 400")) throw e
            call { support.post(id, endpoint.chatUrl, headers, body(model, system, turns, maxOutputTokens, withTemperature = false)) }
        }
        return reply.str("choices", "0", "message", "content") ?: throw EngineException(EngineFailure.Malformed(id))
    }

    /**
     * The model list of a server, for the "Refresh" action: the ids the server itself offers.
     *
     * It is advisory: the user may save the address even when the server has no model list, so a failure here is a
     * message and never a refusal to save.
     */
    suspend fun probe(rawBaseUrl: String, typedKey: String?): List<Pair<String, String>> {
        val endpoint = endpoint(rawBaseUrl)
        val key = typedKey?.takeIf { it.isNotBlank() } ?: keys.get(id).orEmpty()
        val headers = headersFor(key).plus("Accept" to "application/json")
        val body = call { support.get(id, endpoint.modelsUrl, headers) }
        val ids = when {
            body is JsonObject -> {
                val data = body["data"]
                if (data is JsonArray) data.mapNotNull { (it as? JsonObject)?.get("id")?.asString() }
                else emptyList()
            }
            else -> emptyList()
        }
        if (ids.isEmpty()) {
            // Some servers answer with a bare array of {model} or {name} objects instead.
            val array = body as? JsonArray
            val alt = array?.mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                (o["model"] ?: o["name"])?.asString()
            }.orEmpty()
            if (alt.isEmpty()) throw EngineException(EngineFailure.Malformed(id))
            return alt.distinct().sorted().take(200).map { it to it }
        }
        return ids.distinct().sorted().take(200).map { it to it }
    }

    /** The request headers: no `Authorization` when there is no key. */
    private fun headersFor(key: String): Map<String, String> {
        val headers = mutableMapOf<String, String>("Content-Type" to "application/json")
        if (key.isNotBlank()) headers["Authorization"] = "Bearer $key"
        return headers
    }

    /**
     * A network call whose failure is always typed: a reply that is not JSON (an HTML page from a proxy, say) would
     * otherwise escape as a serialization error the user sees as a crash.
     *
     * It must be a suspend function: a non-suspend lambda that calls a suspending function would have its
     * `IllegalStateException` swallowed here and reported as a malformed reply, which is a lie.
     */
    private suspend inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: EngineException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw EngineException(EngineFailure.Malformed(id))
    }

    /** The Chat Completions request: the model, the system prompt, the turns as plain text messages. */
    private fun body(model: String, system: String, turns: List<Turn>, maxOutputTokens: Int, withTemperature: Boolean) = buildJsonObject {
        put("model", s(model))
        if (withTemperature) put("temperature", n(0.2))
        // The reply is capped the way the other engines cap it: one asking for an essay per line must not run up
        // the user's bill.
        put("max_tokens", n(maxOutputTokens))
        put("messages", buildJsonArray {
            add(obj("role" to s("system"), "content" to s(system)))
            for (t in turns) add(obj("role" to s(t.role), "content" to s(t.text)))
        })
    }

    /** A JSON string, or null when the field is not there. */
    private fun JsonElement.asString(): String? = (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
}
