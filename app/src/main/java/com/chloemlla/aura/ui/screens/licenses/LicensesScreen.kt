package com.chloemlla.aura.ui.screens.licenses

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chloemlla.aura.R
import com.chloemlla.aura.data.legal.ProviderBuild
import com.chloemlla.aura.data.legal.ProviderChannel
import com.chloemlla.aura.data.legal.disclosureStatus
import com.chloemlla.aura.data.legal.providerCapability
import com.chloemlla.aura.data.legal.providerDisclosures
import com.chloemlla.aura.ui.components.CompactSearchField
import com.chloemlla.aura.ui.util.openExternalUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class OssLicense(
    val name: String,
    val url: String,
    val license: String,
    val description: String,
)

private val licenses = listOf(
    OssLicense("Kotlin", "https://github.com/JetBrains/kotlin", "Apache 2.0", "Programming language"),
    OssLicense("Kotlin Coroutines", "https://github.com/Kotlin/kotlinx.coroutines", "Apache 2.0", "Async runtime"),
    OssLicense("Kotlin Serialization", "https://github.com/Kotlin/kotlinx.serialization", "Apache 2.0", "JSON serialization"),
    OssLicense("Jetpack Compose", "https://developer.android.com/jetpack/compose", "Apache 2.0", "Modern UI toolkit"),
    OssLicense("Material 3", "https://m3.material.io", "Apache 2.0", "Design system"),
    OssLicense("Hilt", "https://dagger.dev/hilt/", "Apache 2.0", "Dependency injection"),
    OssLicense("Room", "https://developer.android.com/training/data-storage/room", "Apache 2.0", "Local database"),
    OssLicense("Retrofit", "https://github.com/square/retrofit", "Apache 2.0", "HTTP client"),
    OssLicense("OkHttp", "https://github.com/square/okhttp", "Apache 2.0", "HTTP engine"),
    OssLicense("Moshi", "https://github.com/square/moshi", "Apache 2.0", "JSON parsing"),
    OssLicense("Coil", "https://github.com/coil-kt/coil", "Apache 2.0", "Image loading"),
    OssLicense("Media3", "https://github.com/androidx/media", "Apache 2.0", "Audio playback and platform audio export"),
    OssLicense("WorkManager", "https://developer.android.com/topic/libraries/architecture/workmanager", "Apache 2.0", "Background scheduling"),
    OssLicense("DataStore", "https://developer.android.com/topic/libraries/architecture/datastore", "Apache 2.0", "Persistent preferences"),
    OssLicense("Paging 3", "https://developer.android.com/topic/libraries/architecture/paging/v3-overview", "Apache 2.0", "Infinite scroll"),
    OssLicense("ProfileInstaller", "https://developer.android.com/topic/performance/baselineprofiles/overview", "Apache 2.0", "Baseline profile installation"),
    OssLicense("Palette", "https://developer.android.com/reference/kotlin/androidx/palette/graphics/package-summary", "Apache 2.0", "Color extraction"),
    OssLicense("ZXing", "https://github.com/zxing/zxing", "Apache 2.0", "QR code support"),
    OssLicense("Glance", "https://developer.android.com/jetpack/compose/glance", "Apache 2.0", "App widgets"),
    OssLicense("Navigation Compose", "https://developer.android.com/jetpack/compose/navigation", "Apache 2.0", "Screen navigation"),
    OssLicense("Firebase", "https://firebase.google.com/terms", "Google/Firebase terms", "Auth, Realtime Database, and Storage"),
    OssLicense("Google Play services", "https://developers.google.com/android/guides/overview", "Google APIs terms", "ML Kit module install and Play services base"),
    OssLicense("ML Kit Subject Segmentation", "https://developers.google.com/ml-kit", "Google APIs terms", "Parallax subject segmentation"),
    OssLicense("NewPipe Extractor", "https://github.com/TeamNewPipe/NewPipeExtractor", "GPL-3.0", "YouTube and streaming-site extraction"),
    OssLicense("youtubedl-android", "https://github.com/yausername/youtubedl-android", "GPL-3.0", "yt-dlp wrapper for Android"),
    OssLicense("yt-dlp", "https://github.com/yt-dlp/yt-dlp", "Unlicense", "YouTube stream extraction payload"),
    OssLicense("FFmpeg", "https://ffmpeg.org/legal.html", "LGPL/GPL depending on build", "Video crop, extractor runtime, and fallback audio codec payload"),
    OssLicense("Core library desugaring", "https://developer.android.com/studio/write/java8-support", "Apache 2.0", "Java API backports for Android"),
)

internal val releaseNoticeLinks = listOf(
    OssLicense(
        name = "Generated dependency notices",
        url = "https://github.com/SysAdminDoc/Aura/releases/latest",
        license = "Release artifact",
        description = "Open the latest GitHub Release and download THIRD-PARTY-NOTICES.md.",
    ),
    OssLicense(
        name = "Raw Google OSS inputs",
        url = "https://github.com/SysAdminDoc/Aura/releases/latest",
        license = "Release artifact",
        description = "Download GOOGLE-OSS-RAW-INPUTS.zip for dependencies.json and raw notice inputs.",
    ),
    OssLicense(
        name = "Native compliance packet",
        url = "https://github.com/SysAdminDoc/Aura/releases/latest",
        license = "Release artifact",
        description = "Download NATIVE-COMPLIANCE.md for native, extractor, Python, QuickJS, and FFmpeg evidence.",
    ),
)

private val contentSources = providerDisclosures.map { disclosure ->
    // The lifecycle label comes from the capability registry rather than the
    // disclosure's own copy, so what users read here cannot drift away from what
    // the runtime is actually allowed to fetch.
    val capability = providerCapability(disclosure.source)
    val availability = buildList {
        if (!capability.availableIn(ProviderBuild.FOSS)) add("full builds only")
        if (!capability.availableOn(ProviderChannel.PLAY)) add("not shipped on Play")
    }.joinToString(", ")
    OssLicense(
        name = disclosure.displayName,
        url = disclosure.termsUrl,
        license = disclosure.licenseSummary,
        description = buildString {
            append(capability.lifecycle.disclosureStatus().label)
            append(" - ")
            append(disclosure.content)
            append(". ")
            append(disclosure.storeDisclosure)
            if (availability.isNotEmpty()) append(" ($availability)")
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var generatedNotices by remember { mutableStateOf<List<GeneratedDependencyNotice>>(emptyList()) }
    var generatedNoticeQuery by rememberSaveable { mutableStateOf("") }
    var selectedGeneratedNoticeName by rememberSaveable { mutableStateOf<String?>(null) }

    // Parse the raw OSS notice resources off the main thread, once. The loaded
    // list keeps only the fields the list UI needs; the dialog fetches the full
    // license text of the single selected notice on demand.
    LaunchedEffect(context) {
        val loaded = withContext(Dispatchers.IO) {
            GoogleOssNoticeReader.load(context.resources)
        }
        generatedNotices = loaded
    }

    val selectedGeneratedNotice = remember(generatedNotices, selectedGeneratedNoticeName) {
        selectedGeneratedNoticeName?.let { name ->
            generatedNotices.firstOrNull { it.name == name }
        }
    }

    val reviewNotices = remember(generatedNotices, generatedNoticeQuery) {
        GoogleOssNoticeReader.filter(
            notices = generatedNotices,
            query = generatedNoticeQuery,
            reviewOnly = true,
        )
    }
    val visibleGeneratedNotices = remember(generatedNotices, generatedNoticeQuery) {
        GoogleOssNoticeReader.filter(
            notices = generatedNotices,
            query = generatedNoticeQuery,
        )
    }

    selectedGeneratedNotice?.let { notice ->
        GeneratedNoticeDialog(
            notice = notice,
            onDismiss = { selectedGeneratedNoticeName = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.licenses_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            item {
                Text(
                    stringResource(R.string.licenses_release_notices),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            items(releaseNoticeLinks) { lic ->
                LicenseCard(lic)
            }
            if (generatedNotices.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    SectionHeader(
                        title = stringResource(R.string.licenses_generated_dependencies),
                        detail = stringResource(
                            R.string.licenses_generated_notice_count,
                            visibleGeneratedNotices.size,
                            generatedNotices.size,
                        ),
                    )
                }
                item {
                    CompactSearchField(
                        value = generatedNoticeQuery,
                        onValueChange = { generatedNoticeQuery = it },
                        placeholder = stringResource(R.string.licenses_filter_generated_notices),
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = Icons.Default.Search,
                        onClear = { generatedNoticeQuery = "" },
                    )
                }
                if (reviewNotices.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = stringResource(R.string.licenses_review_watchlist),
                            detail = "${reviewNotices.size}",
                        )
                    }
                    items(reviewNotices) { notice ->
                        GeneratedNoticeCard(
                            notice = notice,
                            onClick = { selectedGeneratedNoticeName = notice.name },
                        )
                    }
                }
                item {
                    SectionHeader(title = stringResource(R.string.licenses_all_generated_notices))
                }
                if (visibleGeneratedNotices.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.licenses_no_generated_notices_match),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                        )
                    }
                } else {
                    items(visibleGeneratedNotices) { notice ->
                        GeneratedNoticeCard(
                            notice = notice,
                            onClick = { selectedGeneratedNoticeName = notice.name },
                        )
                    }
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                SectionHeader(title = stringResource(R.string.licenses_libraries))
            }
            items(licenses) { lic ->
                LicenseCard(lic)
            }
            item {
                Spacer(Modifier.height(8.dp))
                SectionHeader(title = stringResource(R.string.licenses_content_sources))
            }
            items(contentSources) { lic ->
                LicenseCard(lic)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, detail: String? = null) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LicenseCard(lic: OssLicense) {
    val context = LocalContext.current
    Surface(
        onClick = { openExternalUrl(context, lic.url) },
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(lic.name, style = MaterialTheme.typography.titleSmall)
                Text(lic.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(lic.license, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GeneratedNoticeCard(
    notice: GeneratedDependencyNotice,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(notice.name, style = MaterialTheme.typography.titleSmall)
            Text(
                notice.licenseLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            notice.reviewLabelRes?.let { labelRes ->
                ReviewLabel(stringResource(labelRes))
            }
        }
    }
}

@Composable
private fun ReviewLabel(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun GeneratedNoticeDialog(
    notice: GeneratedDependencyNotice,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var licenseText by remember(notice.name) { mutableStateOf<String?>(null) }
    LaunchedEffect(notice.name) {
        licenseText = withContext(Dispatchers.IO) {
            GoogleOssNoticeReader.licenseText(context.resources, notice.name)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_done))
            }
        },
        title = {
            Text(notice.name)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    notice.licenseLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                val text = licenseText
                if (text != null) {
                    SelectionContainer {
                        Text(
                            text,
                            modifier = Modifier
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp, max = 360.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }
        },
    )
}
