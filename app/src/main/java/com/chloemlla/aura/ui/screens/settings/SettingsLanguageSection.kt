package com.chloemlla.aura.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chloemlla.aura.MainActivity
import com.chloemlla.aura.R
import com.chloemlla.aura.util.LocaleHelper

@Composable
internal fun LanguageSettingsSection(
    viewModel: SettingsViewModel,
    context: Context,
) {
    val currentLocaleTag by viewModel.currentLocaleTag.collectAsStateWithLifecycle()
    val localeOptions by viewModel.localeOptions.collectAsStateWithLifecycle()
    // Immediate language switching: the ViewModel signals after persisting a new
    // locale; recreate the Activity so attachBaseContext re-wraps its resources.
    LaunchedEffect(viewModel) {
        viewModel.localeChanged.collect {
            (context as? MainActivity)?.recreate()
        }
    }

    var showLanguagePicker by remember { mutableStateOf(false) }
    val systemLabel = stringResource(R.string.settings_language_system)
    val englishLabel = stringResource(R.string.settings_language_english)
    val chineseLabel = stringResource(R.string.settings_language_chinese)

    SettingsSection(
        title = stringResource(R.string.settings_language_section_title),
        description = stringResource(R.string.settings_language_subtitle),
    ) {
        SettingsItem(
            icon = Icons.Default.Language,
            title = stringResource(R.string.settings_language_title),
            subtitle = languageOptionLabel(currentLocaleTag, systemLabel, englishLabel, chineseLabel),
            onClick = { showLanguagePicker = true },
        )
    }

    if (showLanguagePicker) {
        AlertDialog(
            onDismissRequest = { showLanguagePicker = false },
            title = { Text(stringResource(R.string.settings_language_dialog_title)) },
            text = {
                Column {
                    localeOptions.forEach { option ->
                        SettingsRadioOptionRow(
                            label = languageOptionLabel(option.tag, systemLabel, englishLabel, chineseLabel),
                            selected = currentLocaleTag == option.tag,
                            onClick = {
                                showLanguagePicker = false
                                viewModel.setAppLocale(option.tag)
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguagePicker = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

private fun languageOptionLabel(
    tag: String,
    systemLabel: String,
    englishLabel: String,
    chineseLabel: String,
): String = when (tag) {
    "" -> systemLabel
    "en" -> englishLabel
    "zh" -> chineseLabel
    else -> LocaleHelper.getDisplayName(tag)
}
