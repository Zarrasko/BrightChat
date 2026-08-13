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
import com.gios.lightchat.ChatViewModel
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatType

/**
 * Create or edit an agent. An agent is a name plus an OpenAI-compatible endpoint
 * (base URL, bearer key, model) and an optional system prompt — June's Hermes server
 * is one such endpoint, but so is OpenRouter, OpenAI, or a local LM Studio box.
 */
@Composable
fun AgentEditScreen(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsState()
    val target = state.agentEditorTarget
    var name by rememberSaveable { mutableStateOf(target?.name.orEmpty()) }
    var baseUrl by rememberSaveable { mutableStateOf(target?.baseUrl.orEmpty()) }
    var apiKey by rememberSaveable { mutableStateOf(target?.apiKey.orEmpty()) }
    var model by rememberSaveable { mutableStateOf(target?.model.orEmpty()) }
    var systemPrompt by rememberSaveable { mutableStateOf(target?.systemPrompt.orEmpty()) }

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
        FieldLabel("Base URL (OpenAI-compatible)")
        FieldInput(baseUrl, KeyboardType.Uri) { baseUrl = it }
        FieldLabel("API key")
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
            text = if (target == null) "Create" else "Save",
            style = ChatType.body,
            color = if (name.isBlank() || baseUrl.isBlank() || model.isBlank()) {
                ChatColors.onSurfaceDisabled
            } else {
                ChatColors.onSurface
            },
            onClick = { viewModel.saveAgent(name, baseUrl, apiKey, model, systemPrompt) },
            modifier = Modifier.fillMaxWidth(),
        )
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
