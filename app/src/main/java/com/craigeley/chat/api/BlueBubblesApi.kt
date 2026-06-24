package com.craigeley.chat.api

import com.craigeley.chat.Attachment
import com.craigeley.chat.ChatMessage
import com.craigeley.chat.Conversation
import com.craigeley.chat.IncomingMessage
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/** Carries the HTTP status so the ViewModel can treat 401/403 as a bad password. */
class ApiException(val code: Int, message: String) : IOException(message) {
    val isAuthError: Boolean get() = code == 401 || code == 403
}

/**
 * What `GET /server/info` tells us. [reachable] is the password/connectivity check
 * setup relies on; [privateApiReady] is true only when the server has the Private
 * API enabled *and* the Messages helper is actually connected — the gate for
 * offering tapbacks (sending needs both; the SIP/helper setup is in the README).
 */
data class ServerInfo(val reachable: Boolean, val privateApiReady: Boolean)

/**
 * Minimal BlueBubbles Server REST client — plain [HttpURLConnection] + `org.json`,
 * no networking dependency. Auth is the server password passed as
 * the `password` query param on every call. The base URL is the user-configured
 * server (`Store.baseUrl`), typically reached over Tailscale Serve, passed into
 * the constructor. The live event feed is separate (Socket.IO, see the `socket`
 * package); message parsing is shared with it via the [companion object].
 *
 * Timestamps come back as epoch millis and are carried through unchanged — the UI
 * sorts on them, which is the whole point of doing this from scratch.
 */
class BlueBubblesApi(private val baseUrl: String, private val password: String) {

    /** `GET /api/v1/server/info` — used to validate the password on setup. */
    fun validate(): Boolean = request("GET", "/api/v1/server/info", null).first in 200..299

    /**
     * `GET /api/v1/server/info` — like [validate] but also reads whether the Private
     * API is live (`private_api` && `helper_connected`), so the app can offer
     * tapbacks only when the server can actually send them.
     */
    fun serverInfo(): ServerInfo {
        val (code, text) = request("GET", "/api/v1/server/info", null)
        if (code !in 200..299) return ServerInfo(reachable = false, privateApiReady = false)
        val data = runCatching { JSONObject(text).optJSONObject("data") }.getOrNull()
        val privateApi = data?.optBoolean("private_api", false) == true
        val helper = data?.optBoolean("helper_connected", false) == true
        return ServerInfo(reachable = true, privateApiReady = privateApi && helper)
    }

    /**
     * The conversation list, in true most-recent-activity order.
     *
     * Deliberately NOT built from `chat/query`: that endpoint sorts by an
     * unreliable `lastmessage` cache, so on a large account (this one has ~2700
     * chats) a freshly-active chat can fall outside its first page entirely and
     * never appear. Instead we drive the list from a single `message/query` DESC
     * sweep — the newest [limit] messages globally — taking the first (newest)
     * message seen per chat and reading each chat's metadata from the embedded
     * chat object (`with:["chats","chats.participants"]`). The result is already
     * newest-first; the ViewModel re-sorts defensively.
     *
     * Trade-off: only chats with activity inside the sweep window appear — i.e.
     * the recently-active ones, which is exactly what a messages list shows.
     */
    fun conversations(limit: Int = 1000): List<Conversation> {
        val body = JSONObject()
            .put("limit", limit)
            .put("offset", 0)
            // `attachment` so an attachment-only message gets a real list preview
            // ("Photo" etc.) rather than a blank line; without it the sweep can't
            // tell text-less messages apart from genuinely empty ones.
            .put("with", JSONArray().put("chats").put("chats.participants").put("attachment"))
            .put("sort", "DESC")
        val (code, respText) = request("POST", "/api/v1/message/query", body)
        if (code !in 200..299) throw ApiException(code, "message/query failed ($code)")
        val data = JSONObject(respText).optJSONArray("data") ?: JSONArray()
        val byGuid = LinkedHashMap<String, Conversation>()
        for (i in 0 until data.length()) {
            val m = data.getJSONObject(i)
            val chats = m.optJSONArray("chats") ?: continue
            val lastMsg = parseMessage(m)
            val lastText = lastMsg.previewText
            val lastDate = lastMsg.date
            val lastFromMe = lastMsg.fromMe
            for (j in 0 until chats.length()) {
                val chat = chats.getJSONObject(j)
                val guid = chat.optString("guid")
                if (guid.isBlank() || byGuid.containsKey(guid)) continue // newest seen wins
                byGuid[guid] = chatToConversation(chat, guid, lastText, lastDate, lastFromMe)
            }
        }
        return byGuid.values.toList()
    }

    /** `GET /api/v1/chat/:guid/message` — messages in one conversation, newest first. */
    fun messages(chatGuid: String, limit: Int = 100, offset: Int = 0): List<ChatMessage> {
        val path = "/api/v1/chat/${enc(chatGuid)}/message"
        val query = "with=handle,attachment&sort=DESC&limit=$limit&offset=$offset"
        val (code, text) = request("GET", path, null, extraQuery = query)
        if (code !in 200..299) throw ApiException(code, "message query failed ($code)")
        val data = JSONObject(text).optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).map { parseMessage(data.getJSONObject(it)) }
    }

    /**
     * `POST /api/v1/message/text` — sends a text into a chat. Only `chatGuid` and
     * `message` are required; we also pass a client `tempGuid` so the echoed
     * new-message can be correlated, and force `apple-script` since the server's
     * Private API is off. Returns the created message (real guid) parsed from the
     * response, falling back to a synthetic one if the body is unexpected.
     */
    fun send(chatGuid: String, text: String, tempGuid: String): ChatMessage {
        val body = JSONObject()
            .put("chatGuid", chatGuid)
            .put("tempGuid", tempGuid)
            .put("message", text)
            .put("method", "apple-script")
        val (code, resp) = request("POST", "/api/v1/message/text", body)
        if (code !in 200..299) throw ApiException(code, "send failed ($code)")
        val data = runCatching { JSONObject(resp).optJSONObject("data") }.getOrNull()
        return data?.let { parseMessage(it) }
            ?: ChatMessage(tempGuid, text, System.currentTimeMillis(), fromMe = true, sender = null)
    }

    /**
     * `POST /api/v1/message/react` — sends a tapback onto [selectedMessageGuid].
     * Private-API only (gated in the UI on [serverInfo]); [reaction] is a
     * [ReactionType.apiValue], prefixed `-` to remove. [partIndex] is 0 for a
     * normal single-part message. Returns the created reaction message (real guid)
     * parsed from the response, so the ViewModel can reconcile its optimistic echo.
     */
    fun react(
        chatGuid: String,
        selectedMessageGuid: String,
        reaction: String,
        partIndex: Int = 0,
    ): ChatMessage {
        val body = JSONObject()
            .put("chatGuid", chatGuid)
            .put("selectedMessageGuid", selectedMessageGuid)
            .put("reaction", reaction)
            .put("partIndex", partIndex)
        val (code, resp) = request("POST", "/api/v1/message/react", body)
        if (code !in 200..299) throw ApiException(code, "react failed ($code)")
        val data = runCatching { JSONObject(resp).optJSONObject("data") }.getOrNull()
        return data?.let { parseMessage(it) }
            ?: throw ApiException(code, "react: no message returned")
    }

    /**
     * `POST /api/v1/chat/:guid/read` — marks the chat read (Private-API only).
     * Clears its unread state, which iMessage syncs to the account's other devices,
     * and sends a read receipt per the conversation's setting (so it just mirrors
     * reading on another device). Idempotent; callers fire it best-effort.
     */
    fun markRead(chatGuid: String) {
        val (code, _) = request("POST", "/api/v1/chat/${enc(chatGuid)}/read", null)
        if (code !in 200..299) throw ApiException(code, "mark read failed ($code)")
    }

    /**
     * `POST`/`DELETE /api/v1/chat/:guid/typing` — show or clear your typing bubble
     * on the other party's device (Private-API only). Best-effort; callers ignore
     * failures and gate on the Private API being live.
     */
    fun startTyping(chatGuid: String) {
        request("POST", "/api/v1/chat/${enc(chatGuid)}/typing", null)
    }

    fun stopTyping(chatGuid: String) {
        request("DELETE", "/api/v1/chat/${enc(chatGuid)}/typing", null)
    }

    /**
     * `POST /api/v1/message/attachment` — sends a file into a chat as multipart
     * form-data (the one call that isn't JSON, so it's built by hand rather than
     * via [request]). Like [send] we pass a `tempGuid` to correlate the echo and
     * force `apple-script` (Private API is off). Returns the created message parsed
     * from the response, falling back to a placeholder if the body is unexpected.
     */
    fun sendAttachment(
        chatGuid: String,
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        tempGuid: String,
    ): ChatMessage {
        val safeFile = filename.replace("\"", "").ifBlank { "image.jpg" }
        val boundary = "chatBoundary" + tempGuid.filter { it.isLetterOrDigit() }
        val crlf = "\r\n"
        fun field(name: String, value: String) =
            "--$boundary$crlf" +
                "Content-Disposition: form-data; name=\"$name\"$crlf$crlf" +
                "$value$crlf"
        val preamble = buildString {
            append(field("chatGuid", chatGuid))
            append(field("tempGuid", tempGuid))
            append(field("name", safeFile))
            append(field("method", "apple-script"))
            append("--$boundary$crlf")
            append("Content-Disposition: form-data; name=\"attachment\"; filename=\"$safeFile\"$crlf")
            append("Content-Type: $mimeType$crlf$crlf")
        }
        val epilogue = "$crlf--$boundary--$crlf"

        val url = URL("$baseUrl/api/v1/message/attachment?password=" + enc(password))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setChunkedStreamingMode(0) // stream the file, don't buffer it all in RAM
        }
        return try {
            conn.outputStream.use { out ->
                out.write(preamble.toByteArray(Charsets.UTF_8))
                out.write(bytes)
                out.write(epilogue.toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw ApiException(code, "send attachment failed ($code)")
            val data = runCatching { JSONObject(resp).optJSONObject("data") }.getOrNull()
            data?.let { parseMessage(it) }
                ?: ChatMessage(tempGuid, ChatMessage.ATTACHMENT_PLACEHOLDER, System.currentTimeMillis(), fromMe = true, sender = null)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * `GET /api/v1/attachment/:guid/download` — streams an attachment's raw bytes
     * to [dest]. Used by the inline image loader; deliberately not routed through
     * [request] (which buffers a text body) since these are binary and large.
     */
    fun downloadAttachment(guid: String, dest: File) {
        val url = URL(buildString {
            append(baseUrl).append("/api/v1/attachment/").append(enc(guid)).append("/download")
            append("?password=").append(enc(password))
        })
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw ApiException(code, "attachment download failed ($code)")
            conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * `POST /api/v1/chat/new` — starts a new chat by sending its first message.
     * macOS Big Sur+ requires a message (AppleScript can't create an empty chat),
     * so this both creates the chat and sends. Returns the new chat's guid.
     *
     * A single address goes over **AppleScript** (rock-solid, no Private API
     * needed). Two or more addresses form a **group**, which AppleScript can't do
     * reliably on modern macOS — that path requires `method:"private-api"`, so the
     * caller must gate group creation on the server's Private API being live. The
     * server assigns the group its own guid (`any;+;<hex>`, style 43), unguessable
     * client-side, so callers must use the returned guid rather than construct one.
     */
    fun newChat(addresses: List<String>, text: String, service: String = "iMessage"): String {
        val isGroup = addresses.size > 1
        val body = JSONObject()
            .put("addresses", JSONArray().apply { addresses.forEach { put(it) } })
            .put("message", text)
            .put("service", service)
            .put("method", if (isGroup) "private-api" else "apple-script")
        val (code, resp) = request("POST", "/api/v1/chat/new", body)
        if (code !in 200..299) throw ApiException(code, "new chat failed ($code)")
        val guid = JSONObject(resp).optJSONObject("data")?.optString("guid")
        return guid?.takeIf { it.isNotBlank() } ?: throw ApiException(code, "new chat: no guid returned")
    }

    /**
     * `GET /api/v1/contact` — the Mac's whole address book, flattened to
     * (address, name) pairs (every phone number and email maps to the contact's
     * display name). [com.craigeley.chat.Contacts.from] turns this into a lookup.
     */
    fun contacts(): List<Pair<String, String>> {
        val (code, text) = request("GET", "/api/v1/contact", null)
        if (code !in 200..299) throw ApiException(code, "contact failed ($code)")
        val data = JSONObject(text).optJSONArray("data") ?: JSONArray()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until data.length()) {
            val c = data.getJSONObject(i)
            val name = c.optString("displayName").ifBlank {
                listOf(c.optString("firstName"), c.optString("lastName"))
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
            }
            if (name.isBlank()) continue
            for (field in listOf("phoneNumbers", "emails")) {
                val arr = c.optJSONArray(field) ?: continue
                for (j in 0 until arr.length()) {
                    arr.getJSONObject(j).optString("address").takeIf { it.isNotBlank() }
                        ?.let { out.add(it to name) }
                }
            }
        }
        return out
    }

    // ---- parsing ----------------------------------------------------------

    private fun chatToConversation(
        chat: JSONObject,
        guid: String,
        lastText: String,
        lastDate: Long,
        lastFromMe: Boolean,
    ): Conversation {
        val participants = chat.optJSONArray("participants")?.let { arr ->
            (0 until arr.length()).mapNotNull {
                arr.getJSONObject(it).optString("address").takeIf { a -> a.isNotBlank() }
            }
        } ?: emptyList()
        // 1:1 chats (style 45) come back with an empty participants list, but the
        // chatIdentifier is the other party's address — use it so names resolve.
        val resolved = participants.ifEmpty {
            chat.optString("chatIdentifier")
                .takeIf { it.isNotBlank() && !it.startsWith("chat") }
                ?.let { listOf(it) }
                ?: emptyList()
        }
        return Conversation(
            guid = guid,
            displayName = chat.optString("displayName", ""),
            participants = resolved,
            isGroup = chat.optInt("style") == 43, // 43 = group, 45 = one-on-one
            lastText = lastText,
            lastDate = lastDate,
            lastFromMe = lastFromMe,
        )
    }

    // ---- transport --------------------------------------------------------

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun request(
        method: String,
        path: String,
        body: JSONObject?,
        extraQuery: String? = null,
    ): Pair<Int, String> {
        val url = buildString {
            append(baseUrl).append(path)
            append("?password=").append(enc(password))
            if (extraQuery != null) append("&").append(extraQuery)
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            code to text
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Shared message parsing. Lives in the companion so the Socket.IO service can
     * decode `new-message`/`updated-message` payloads with the same logic the REST
     * calls use — the socket emits the same message-object shape.
     */
    companion object {
        /** Body text, falling back to an attachment placeholder when null/blank. */
        fun messageText(o: JSONObject): String {
            val t = o.optString("text", "").trim()
            if (t.isNotEmpty()) return t
            val attachments = o.optJSONArray("attachments")?.length() ?: 0
            return if (attachments > 0) ChatMessage.ATTACHMENT_PLACEHOLDER else ""
        }

        fun parseMessage(o: JSONObject): ChatMessage {
            val handle = o.optJSONObject("handle")
            return ChatMessage(
                guid = o.optString("guid"),
                text = messageText(o),
                date = o.optLong("dateCreated", 0L),
                fromMe = o.optBoolean("isFromMe", false),
                sender = handle?.optString("address")?.takeIf { it.isNotBlank() },
                attachments = parseAttachments(o),
                // Present only on tapbacks; the ViewModel folds such messages onto
                // their target rather than rendering them. The server reports the
                // type as a word (`love`/`-love`), not the raw iMessage int.
                associatedMessageGuid = o.optString("associatedMessageGuid").takeIf { it.isNotBlank() },
                associatedMessageType = o.optString("associatedMessageType").takeIf { it.isNotBlank() && it != "null" },
            )
        }

        private fun parseAttachments(o: JSONObject): List<Attachment> {
            val arr = o.optJSONArray("attachments") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val a = arr.getJSONObject(i)
                val guid = a.optString("guid").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = a.optString("transferName").takeIf { it.isNotBlank() }
                // iMessage rich-link previews (sent for URLs — Instagram, etc.) ride
                // along as a `*.pluginPayloadAttachment` metadata blob, not a real
                // file. We can't render the preview and the URL is already in the
                // message text, so drop it rather than show a junk "File · <guid>" row.
                if (name?.endsWith(".pluginPayloadAttachment", ignoreCase = true) == true) {
                    return@mapNotNull null
                }
                Attachment(
                    guid = guid,
                    mimeType = a.optString("mimeType").takeIf { it.isNotBlank() },
                    transferName = name,
                    width = a.optInt("width", 0),
                    height = a.optInt("height", 0),
                )
            }
        }

        /**
         * Decodes a socket message event into an [IncomingMessage], pulling the
         * chat guid + display name from the embedded `chats` array. Returns null if
         * the payload has no chat (can't route it).
         */
        fun messageEvent(data: JSONObject, isNew: Boolean): IncomingMessage? {
            val chats = data.optJSONArray("chats") ?: return null
            if (chats.length() == 0) return null
            val chat = chats.getJSONObject(0)
            val guid = chat.optString("guid").takeIf { it.isNotBlank() } ?: return null
            return IncomingMessage(
                chatGuid = guid,
                message = parseMessage(data),
                isNew = isNew,
                chatDisplayName = chat.optString("displayName", ""),
            )
        }
    }
}
