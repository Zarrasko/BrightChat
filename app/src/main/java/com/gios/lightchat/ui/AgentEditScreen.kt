package com.gios.lightchat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gios.lightchat.AgentProvider
import com.gios.lightchat.ChatViewModel
import com.gios.lightchat.api.AgentApi
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatType
import org.json.JSONObject

/**
 * Create or edit an agent. An agent is a name plus a provider ([AgentProvider]: an
 * OpenAI-compatible endpoint, or Anthropic's own Claude API), the matching
 * base URL/key/model, and an optional system prompt.
 */
@Composable
fun AgentEditScreen(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsState()
    val target = state.agentEditorTarget
    var name by rememberSaveable { mutableStateOf(target?.name.orEmpty()) }
    var provider by rememberSaveable { mutableStateOf(target?.provider ?: AgentProvider.OPENAI_COMPATIBLE) }
    var baseUrl by rememberSaveable { mutableStateOf(target?.baseUrl.orEmpty()) }
    var apiKey by rememberSaveable { mutableStateOf(target?.apiKey.orEmpty()) }
    var model by rememberSaveable { mutableStateOf(target?.model.orEmpty()) }
    var systemPrompt by rememberSaveable { mutableStateOf(target?.systemPrompt.orEmpty()) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var scanError by rememberSaveable { mutableStateOf<String?>(null) }

    // The QR scanner is a full-screen sub-mode of the editor. On a successful decode the
    // fields are filled in place and the form comes back for review before Create/Save.
    if (scanning) {
        QrScanScreen(
            onResult = { text ->
                val fields = parseAgentQr(text)
                if (fields == null) {
                    scanError = "That QR code isn’t a LightChat agent."
                } else {
                    name = fields.name
                    fields.provider?.let { provider = it }
                    baseUrl = fields.baseUrl
                    apiKey = fields.apiKey
                    model = fields.model
                    systemPrompt = fields.systemPrompt
                    scanError = null
                }
                scanning = false
            },
            onClose = { scanning = false },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(
            title = if (target == null) "New Agent" else target.name,
            onBack = viewModel::closeAgentEditor,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )

        FieldLabel("Name")
        FieldInput(name, KeyboardType.Text) { name = it }

        FieldLabel("Provider")
        HapticText(
            text = when (provider) {
                AgentProvider.OPENAI_COMPATIBLE -> "OpenAI-compatible"
                AgentProvider.ANTHROPIC -> "Anthropic (Claude)"
            },
            style = ChatType.body,
            color = ChatColors.onSurface,
            onClick = {
                provider = when (provider) {
                    AgentProvider.OPENAI_COMPATIBLE -> AgentProvider.ANTHROPIC
                    AgentProvider.ANTHROPIC -> AgentProvider.OPENAI_COMPATIBLE
                }
                // Only when the field is still whatever the previous provider defaulted to —
                // never overwrite a URL the user actually typed in themselves.
                if (provider == AgentProvider.ANTHROPIC && baseUrl.isBlank()) {
                    baseUrl = AgentApi.ANTHROPIC_DEFAULT_BASE_URL
                }
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)

        FieldLabel(if (provider == AgentProvider.ANTHROPIC) "Base URL" else "Base URL (OpenAI-compatible)")
        FieldInput(baseUrl, KeyboardType.Uri) { baseUrl = it }
        FieldLabel(if (provider == AgentProvider.ANTHROPIC) "API key (x-api-key)" else "API key")
        FieldInput(apiKey, KeyboardType.Password) { apiKey = it }
        FieldLabel("Model")
        FieldInput(model, KeyboardType.Text) { model = it }
        FieldLabel("System prompt (optional)")
        BasicTextField(
            value = systemPrompt,
            onValueChange = { systemPrompt = it },
            textStyle = ChatType.body.copy(color = ChatColors.onSurface),
            cursorBrush = SolidColor(ChatColors.onSurface),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)

        Spacer(modifier = Modifier.height(24.dp))
        HapticText(
            text = "Scan QR code",
            style = ChatType.body,
            color = ChatColors.onSurface,
            onClick = { scanError = null; scanning = true },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))
        HapticText(
            text = if (target == null) "Create" else "Save",
            style = ChatType.body,
            color = if (name.isBlank() || baseUrl.isBlank() || model.isBlank()) {
                ChatColors.onSurfaceDisabled
            } else {
                ChatColors.onSurface
            },
            onClick = { viewModel.saveAgent(name, provider, baseUrl, apiKey, model, systemPrompt) },
            modifier = Modifier.fillMaxWidth(),
        )
        scanError?.let { error ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = error,
                style = ChatType.hint,
                color = ChatColors.onSurfaceDisabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = ChatType.hint,
        color = ChatColors.onSurfaceDisabled,
        modifier = Modifier.padding(top = 14.dp),
    )
}

@Composable
private fun FieldInput(value: String, keyboardType: KeyboardType, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = ChatType.body.copy(color = ChatColors.onSurface),
        cursorBrush = SolidColor(ChatColors.onSurface),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
    HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)
}

/**
 * A decoded agent QR: the six editable fields. The editor fills whatever is present
 * and leaves the rest as the user left them. [provider] is null (rather than
 * defaulting to one) when the code doesn't say — an older QR minted before
 * [AgentProvider] existed shouldn't silently flip a Claude agent back to
 * OpenAI-compatible.
 */
private data class AgentQrFields(
    val name: String,
    val provider: AgentProvider?,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String,
)

/**
 * Parses a `lightchat-agent` QR payload (JSON). Returns null when [text] isn't a
 * LightChat agent code — a plain URL, a Wi-Fi code, or an unrelated QR.
 */
private fun parseAgentQr(text: String): AgentQrFields? {
    val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
    if (root.optString("type") != "lightchat-agent") return null
    return AgentQrFields(
        name = root.optString("name"),
        // "anthropic" (any case) -> ANTHROPIC; anything else present -> OPENAI_COMPATIBLE;
        // the key missing entirely -> null, so the editor leaves whatever was already picked.
        provider = if (root.has("provider")) {
            if (root.optString("provider").equals("anthropic", ignoreCase = true)) {
                AgentProvider.ANTHROPIC
            } else {
                AgentProvider.OPENAI_COMPATIBLE
            }
        } else {
            null
        },
        baseUrl = root.optString("base_url").ifBlank { root.optString("baseUrl") },
        apiKey = root.optString("api_key").ifBlank { root.optString("apiKey") },
        model = root.optString("model"),
        systemPrompt = root.optString("system_prompt").ifBlank { root.optString("systemPrompt") },
    )
}
