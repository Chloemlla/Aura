package com.freevibe.ui.screens.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.freevibe.R
import com.freevibe.data.model.WallpaperHistoryEntity
import com.freevibe.ui.util.openExternalUrl

@Composable
internal fun SmartLiveWallpaperSettingsSection(
    context: Context,
    viewModel: SettingsViewModel,
    dailyWallpaperEnabled: Boolean,
    adaptiveTint: Boolean,
    adaptiveTintIntensity: Float,
    weatherEffects: Boolean,
    darkModeSwitch: Boolean,
    darkModeWallpaperId: String,
    lightModeWallpaperId: String,
    wallpaperHistory: List<WallpaperHistoryEntity>,
    reduceAnimations: Boolean,
    liveWallpaperDimEnabled: Boolean,
    onSetDailyWallpaperEnabled: (Boolean) -> Unit,
    onEnableWeatherEffects: () -> Unit,
    onDisableWeatherEffects: () -> Unit,
    onPermissionPrompt: (SettingsPermissionPrompt) -> Unit,
) {
    var showDarkModeWallpaperPicker by remember { mutableStateOf(false) }
    var showLightModeWallpaperPicker by remember { mutableStateOf(false) }
    var showVfxPicker by remember { mutableStateOf(false) }
    var showTouchEffectsPicker by remember { mutableStateOf(false) }
    var touchEffectStrength by remember {
        mutableStateOf(
            context.getSharedPreferences("freevibe_weather_wp", Context.MODE_PRIVATE)
                .getString("touch_effect_strength", "OFF") ?: "OFF",
        )
    }

    SettingsSection(
        title = stringResource(R.string.settings_smart_section_title),
        description = stringResource(R.string.settings_smart_section_description),
    ) {
        SettingsToggle(
            icon = Icons.Default.Today,
            title = stringResource(R.string.settings_smart_daily_wp_title),
            subtitle = stringResource(R.string.settings_smart_daily_wp_subtitle),
            checked = dailyWallpaperEnabled,
            onCheckedChange = { enabled ->
                if (!enabled) {
                    onSetDailyWallpaperEnabled(false)
                } else if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                    onSetDailyWallpaperEnabled(false)
                    onPermissionPrompt(SettingsPermissionPrompt.DAILY_NOTIFICATION_RECOVERY)
                } else if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    onSetDailyWallpaperEnabled(false)
                    onPermissionPrompt(SettingsPermissionPrompt.DAILY_NOTIFICATION_REQUEST)
                } else {
                    onSetDailyWallpaperEnabled(true)
                }
            },
        )
        SettingsToggle(
            icon = Icons.Default.WbSunny,
            title = stringResource(R.string.settings_smart_tint_title),
            subtitle = stringResource(R.string.settings_smart_tint_subtitle),
            checked = adaptiveTint,
            onCheckedChange = viewModel::setAdaptiveTint,
        )
        if (adaptiveTint) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    stringResource(R.string.settings_smart_tint_intensity),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = adaptiveTintIntensity,
                    onValueChange = viewModel::setAdaptiveTintIntensity,
                    valueRange = 0.1f..1f,
                    steps = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.settings_smart_tint_range),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SettingsToggle(
            icon = Icons.Default.Cloud,
            title = stringResource(R.string.settings_weather_effects_title),
            subtitle = stringResource(R.string.settings_weather_effects_subtitle),
            checked = weatherEffects,
            onCheckedChange = { enabled ->
                if (!enabled) {
                    onDisableWeatherEffects()
                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    onEnableWeatherEffects()
                } else {
                    onDisableWeatherEffects()
                    onPermissionPrompt(SettingsPermissionPrompt.WEATHER_LOCATION_REQUEST)
                }
            },
        )
        if (weatherEffects) {
            WeatherDataLicenseCard(context)
        }
        SettingsToggle(
            icon = Icons.Default.Brightness4,
            title = stringResource(R.string.settings_smart_dark_switch_title),
            subtitle = stringResource(R.string.settings_smart_dark_switch_subtitle),
            checked = darkModeSwitch,
            onCheckedChange = viewModel::setDarkModeSwitch,
        )
        if (darkModeSwitch) {
            DarkLightWallpaperSlots(
                lightModeWallpaperId = lightModeWallpaperId,
                darkModeWallpaperId = darkModeWallpaperId,
                onLightClick = { showLightModeWallpaperPicker = true },
                onDarkClick = { showDarkModeWallpaperPicker = true },
            )
        }
        SettingsItem(
            icon = Icons.Default.AutoFixHigh,
            title = stringResource(R.string.settings_smart_vfx_title),
            subtitle = stringResource(R.string.settings_smart_vfx_subtitle),
            onClick = { showVfxPicker = true },
        )
        SettingsItem(
            icon = Icons.Default.TouchApp,
            title = stringResource(R.string.settings_smart_touch_title),
            subtitle = touchEffectSummary(touchEffectStrength),
            onClick = { showTouchEffectsPicker = true },
        )
        SettingsToggle(
            icon = Icons.Default.Accessibility,
            title = stringResource(R.string.settings_smart_reduce_title),
            subtitle = stringResource(R.string.settings_smart_reduce_subtitle),
            checked = reduceAnimations,
            onCheckedChange = viewModel::setReduceAnimations,
        )
        SettingsToggle(
            icon = Icons.Default.Brightness6,
            title = stringResource(R.string.settings_smart_dim_title),
            subtitle = stringResource(R.string.settings_smart_dim_subtitle),
            checked = liveWallpaperDimEnabled,
            onCheckedChange = viewModel::setLiveWallpaperDimEnabled,
        )
    }

    if (showVfxPicker) {
        VfxPickerDialog(
            context = context,
            onDismiss = { showVfxPicker = false },
        )
    }
    if (showTouchEffectsPicker) {
        TouchEffectsPickerDialog(
            context = context,
            touchEffectStrength = touchEffectStrength,
            onSelect = { touchEffectStrength = it },
            onDismiss = { showTouchEffectsPicker = false },
        )
    }
    if (showDarkModeWallpaperPicker) {
        WallpaperSlotPickerDialog(
            title = stringResource(R.string.settings_smart_dark_wp_picker),
            history = wallpaperHistory,
            onPick = { entry ->
                val wallpaperId = "${entry.source}|${entry.wallpaperId}|${entry.fullUrl}"
                viewModel.setDarkModeWallpaperId(wallpaperId)
                showDarkModeWallpaperPicker = false
            },
            onDismiss = { showDarkModeWallpaperPicker = false },
        )
    }
    if (showLightModeWallpaperPicker) {
        WallpaperSlotPickerDialog(
            title = stringResource(R.string.settings_smart_light_wp_picker),
            history = wallpaperHistory,
            onPick = { entry ->
                val wallpaperId = "${entry.source}|${entry.wallpaperId}|${entry.fullUrl}"
                viewModel.setLightModeWallpaperId(wallpaperId)
                showLightModeWallpaperPicker = false
            },
            onDismiss = { showLightModeWallpaperPicker = false },
        )
    }
}

@Composable
private fun WeatherDataLicenseCard(context: Context) {
    Surface(
        onClick = { openExternalUrl(context, OPEN_METEO_LICENCE_URL) },
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_smart_weather_data),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    stringResource(R.string.settings_smart_weather_license),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DarkLightWallpaperSlots(
    lightModeWallpaperId: String,
    darkModeWallpaperId: String,
    onLightClick: () -> Unit,
    onDarkClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            stringResource(R.string.settings_smart_wallpaper_slots),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WallpaperSlotCard(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.settings_smart_light_mode),
                isSet = lightModeWallpaperId.isNotEmpty(),
                onClick = onLightClick,
            )
            WallpaperSlotCard(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.settings_smart_dark_mode),
                isSet = darkModeWallpaperId.isNotEmpty(),
                onClick = onDarkClick,
            )
        }
        Text(
            stringResource(R.string.settings_smart_slot_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WallpaperSlotCard(
    modifier: Modifier,
    title: String,
    isSet: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelSmall)
        Text(
            if (isSet) stringResource(R.string.settings_smart_slot_set) else stringResource(R.string.settings_smart_slot_not_set),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VfxPickerDialog(
    context: Context,
    onDismiss: () -> Unit,
) {
    val effects = listOf(
        "NONE" to stringResource(R.string.settings_smart_vfx_none),
        "FIREFLIES" to stringResource(R.string.settings_smart_vfx_fireflies),
        "SAKURA" to stringResource(R.string.settings_smart_vfx_sakura),
        "EMBERS" to stringResource(R.string.settings_smart_vfx_embers),
        "BUBBLES" to stringResource(R.string.settings_smart_vfx_bubbles),
        "LEAVES" to stringResource(R.string.settings_smart_vfx_leaves),
        "SPARKLES" to stringResource(R.string.settings_smart_vfx_sparkles),
    )
    var currentVfx by remember {
        mutableStateOf(
            context.getSharedPreferences("freevibe_weather_wp", Context.MODE_PRIVATE)
                .getString("vfx_effect", "NONE") ?: "NONE",
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_smart_vfx_dialog_title)) },
        text = {
            Column {
                effects.forEach { (key, label) ->
                    SettingsRadioOptionRow(
                        label = label,
                        selected = currentVfx == key,
                        onClick = {
                            currentVfx = key
                            context.getSharedPreferences("freevibe_weather_wp", Context.MODE_PRIVATE)
                                .edit().putString("vfx_effect", key).apply()
                            onDismiss()
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

@Composable
private fun TouchEffectsPickerDialog(
    context: Context,
    touchEffectStrength: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val modes = listOf(
        "OFF" to stringResource(R.string.settings_smart_touch_off),
        "SUBTLE" to stringResource(R.string.settings_smart_touch_subtle),
        "STRONG" to stringResource(R.string.settings_smart_touch_strong),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_smart_touch_dialog_title)) },
        text = {
            Column {
                modes.forEach { (key, label) ->
                    SettingsRadioOptionRow(
                        label = label,
                        selected = touchEffectStrength == key,
                        onClick = {
                            onSelect(key)
                            context.getSharedPreferences("freevibe_weather_wp", Context.MODE_PRIVATE)
                                .edit().putString("touch_effect_strength", key).apply()
                            onDismiss()
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}
