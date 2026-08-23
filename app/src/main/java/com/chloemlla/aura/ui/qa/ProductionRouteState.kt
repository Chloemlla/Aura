package com.chloemlla.aura.ui.qa

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.chloemlla.aura.R
import com.chloemlla.aura.data.model.ContentSource
import com.chloemlla.aura.ui.components.AuraStatusAction
import com.chloemlla.aura.ui.components.AuraStatusBanner
import com.chloemlla.aura.ui.components.CompactSearchField
import com.chloemlla.aura.ui.components.ShimmerWallpaperGrid
import com.chloemlla.aura.ui.preview.PREVIEW_SOUNDS
import com.chloemlla.aura.ui.preview.PREVIEW_WALLPAPERS
import com.chloemlla.aura.ui.screens.editor.WallpaperEditorPreview
import com.chloemlla.aura.ui.screens.settings.SettingsMetric
import com.chloemlla.aura.ui.screens.settings.SettingsSection
import com.chloemlla.aura.ui.screens.settings.SettingsToggle
import com.chloemlla.aura.ui.screens.sounds.ApplyButton
import com.chloemlla.aura.ui.screens.sounds.DetailWaveform
import com.chloemlla.aura.ui.screens.videowallpapers.VideoCard
import com.chloemlla.aura.ui.screens.videowallpapers.VideoWallpaperItem
import com.chloemlla.aura.ui.screens.wallpapers.WallpaperGrid
import com.chloemlla.aura.ui.screens.wallpapers.WallpaperStateAction
import com.chloemlla.aura.ui.screens.wallpapers.WallpaperStateCard

/**
 * Release-build route scenarios used by screenshot and accessibility checks.
 *
 * Each branch is made from the same production renderers that the live screens
 * call. The scenario data is deterministic, but the UI is not a debug-only
 * drawing of the route.
 */
enum class ProductionRouteScenario(
    val screenshotName: String,
    @StringRes val assertionResource: Int,
) {
    WallpapersGridSuccess("wallpapers_grid_success", R.string.nav_wallpapers),
    WallpapersOfflineEmpty("wallpapers_offline_empty", R.string.wallpapers_empty_default_title),
    SoundDetailReady("sound_detail_ready", R.string.nav_sounds),
    SettingsProviderDisabled("settings_provider_disabled", R.string.nav_settings),
    VideoWallpapersError("video_wallpapers_error", R.string.nav_videos),
    WallpaperEditorLoading("wallpaper_editor_loading", R.string.editor_wallpaper_title),
}

@Composable
fun ProductionRouteState(
    scenario: ProductionRouteScenario,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (scenario) {
            ProductionRouteScenario.WallpapersGridSuccess -> WallpapersGridState()
            ProductionRouteScenario.WallpapersOfflineEmpty -> WallpapersOfflineState()
            ProductionRouteScenario.SoundDetailReady -> SoundDetailState()
            ProductionRouteScenario.SettingsProviderDisabled -> SettingsState()
            ProductionRouteScenario.VideoWallpapersError -> VideoWallpapersState()
            ProductionRouteScenario.WallpaperEditorLoading -> WallpaperEditorState()
        }
    }
}

@Composable
private fun RouteColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun WallpapersGridState() {
    Column(modifier = Modifier.fillMaxSize()) {
        RouteColumn {
            Text(stringResource(R.string.nav_wallpapers), style = MaterialTheme.typography.headlineSmall)
            CompactSearchField(
                value = "",
                onValueChange = {},
                placeholder = stringResource(R.string.wallpapers_search_placeholder),
                leadingIcon = Icons.Default.Wallpaper,
            )
            AuraStatusBanner(
                icon = Icons.Default.CheckCircle,
                title = stringResource(R.string.settings_storage_section_title),
                message = stringResource(R.string.wallpapers_subtitle_search_default),
                tone = MaterialTheme.colorScheme.secondary,
            )
        }
        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            WallpaperGrid(
                wallpapers = PREVIEW_WALLPAPERS,
                isLoadingMore = false,
                columns = 2,
                onWallpaperClick = {},
                favoriteIdentities = emptySet(),
                onLoadMore = {},
            )
        }
    }
}

@Composable
private fun WallpapersOfflineState() {
    Column(modifier = Modifier.fillMaxSize()) {
        RouteColumn {
            Text(stringResource(R.string.nav_wallpapers), style = MaterialTheme.typography.headlineSmall)
            AuraStatusBanner(
                icon = Icons.Default.CloudOff,
                title = stringResource(R.string.search_provider_offline),
                message = stringResource(R.string.wallpapers_empty_default_description),
                tone = MaterialTheme.colorScheme.tertiary,
            )
            WallpaperStateCard(
                icon = Icons.Default.Folder,
                title = stringResource(R.string.wallpapers_empty_default_title),
                description = stringResource(R.string.wallpapers_empty_default_description),
                primaryAction = WallpaperStateAction(
                    label = stringResource(R.string.wallpapers_empty_back_to_discover_action),
                    icon = Icons.Default.Download,
                    onClick = {},
                ),
            )
        }
        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            ShimmerWallpaperGrid(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SoundDetailState() {
    val sound = PREVIEW_SOUNDS.first()
    RouteColumn {
        Text(stringResource(R.string.nav_sounds), style = MaterialTheme.typography.headlineSmall)
        Text(sound.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            stringResource(R.string.sound_detail_by_creator, sound.uploaderName),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DetailWaveform(
            duration = sound.duration,
            isPlaying = true,
            progress = 0.42f,
            modifier = Modifier.fillMaxWidth().height(156.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ApplyButton(
                text = stringResource(R.string.editor_sound_apply_ringtone),
                icon = Icons.Default.PlayArrow,
                enabled = true,
                isLoading = false,
                modifier = Modifier.weight(1f),
                onClick = {},
            )
            OutlinedButton(onClick = {}, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.sound_detail_edit_sound_title))
            }
        }
        AuraStatusBanner(
            icon = Icons.Default.Info,
            title = stringResource(R.string.sound_detail_source_policy),
            message = stringResource(R.string.sound_detail_source_unavailable_body),
            tone = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SettingsState() {
    RouteColumn {
        Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.headlineSmall)
        SettingsSection(
            title = stringResource(R.string.settings_services_section_title),
            description = stringResource(R.string.settings_services_section_description),
        ) {
            SettingsToggle(
                icon = Icons.Default.CloudOff,
                title = stringResource(R.string.settings_wp_reddit_title),
                subtitle = stringResource(R.string.settings_wp_reddit_off_subtitle),
                checked = false,
                onCheckedChange = {},
            )
            SettingsToggle(
                icon = Icons.Default.BatteryChargingFull,
                title = stringResource(R.string.settings_video_battery_saver_title),
                subtitle = stringResource(R.string.settings_video_battery_saver_on_subtitle),
                checked = true,
                onCheckedChange = {},
            )
        }
        SettingsSection(
            title = stringResource(R.string.settings_diagnostics_section_title),
            description = stringResource(R.string.settings_diagnostics_section_description),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsMetric(
                    label = stringResource(R.string.settings_diag_crash_title),
                    value = stringResource(R.string.settings_diagnostics_section_description),
                    icon = Icons.Default.Info,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                SettingsMetric(
                    label = stringResource(R.string.settings_diag_source_title),
                    value = stringResource(R.string.settings_diag_source_empty_subtitle),
                    icon = Icons.Default.Settings,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        AuraStatusBanner(
            icon = Icons.Default.DarkMode,
            title = stringResource(R.string.settings_wp_night_variant_title),
            message = stringResource(R.string.settings_wp_night_variant_on_subtitle),
            tone = MaterialTheme.colorScheme.primary,
        )
    }
}

@androidx.annotation.OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.media3.common.util.ExperimentalApi::class,
)
@Composable
private fun VideoWallpapersState() {
    val context = LocalContext.current
    val mediaSourceFactory = remember(context) { DefaultMediaSourceFactory(context) }
    RouteColumn {
        Text(stringResource(R.string.nav_videos), style = MaterialTheme.typography.headlineSmall)
        AuraStatusBanner(
            icon = Icons.Default.CloudOff,
            title = stringResource(R.string.video_wp_degraded_title),
            message = stringResource(R.string.video_wp_degraded_refresh),
            tone = MaterialTheme.colorScheme.tertiary,
            primaryAction = AuraStatusAction(
                label = stringResource(R.string.video_wp_degraded_gallery),
                icon = Icons.Default.Folder,
                onClick = {},
            ),
        )
        VideoCard(
            item = VideoWallpaperItem(
                id = "production-route-video",
                title = stringResource(R.string.video_wp_loading_title),
                thumbnailUrl = "",
                source = ContentSource.PIXABAY.name,
                duration = 14,
                videoWidth = 1080,
                videoHeight = 1920,
                contentSource = ContentSource.PIXABAY,
            ),
            streamUrl = null,
            mediaSourceFactory = mediaSourceFactory,
            shouldPreview = false,
            isApplying = false,
            onApply = {},
            onOpen = {},
        )
    }
}

@Composable
private fun WallpaperEditorState() {
    val editorBitmap = remember {
        Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    RouteColumn {
        Text(stringResource(R.string.editor_wallpaper_title), style = MaterialTheme.typography.headlineSmall)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .background(Color.Black, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            WallpaperEditorPreview(
                bitmap = editorBitmap,
                contentDescription = stringResource(R.string.editor_wallpaper_edited_cd),
                modifier = Modifier.fillMaxSize(),
                bitmapWidth = 720,
                bitmapHeight = 1280,
                overlays = emptyList(),
                selectedOverlayId = null,
                onSelectOverlay = {},
                onMoveOverlay = { _, _, _ -> },
            )
            Surface(
                color = Color.Black.copy(alpha = 0.62f),
                shape = RoundedCornerShape(8.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.editor_wallpaper_loading_image), color = Color.White)
                }
            }
        }
        Text(
            stringResource(R.string.editor_wallpaper_quality_warning_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}
