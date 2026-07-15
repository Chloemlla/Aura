package com.freevibe.ui.components

import android.net.Uri
import android.os.Build
import android.view.SurfaceView
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.freevibe.service.PhotoPickerCustomization

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmbeddedImagePickerSheet(
    title: String,
    body: String,
    fallbackLabel: String,
    onDismiss: () -> Unit,
    onFallback: () -> Unit,
    onImagePicked: (Uri) -> Unit,
) {
    val selectedUri = remember { mutableStateOf<Uri?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Default.ImageSearch,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp),
            ) {
                EmbeddedImagePickerSurface(
                    onUriPermissionGranted = { uris ->
                        selectedUri.value = uris.lastOrNull()
                    },
                    onUriPermissionRevoked = { uris ->
                        if (selectedUri.value in uris) selectedUri.value = null
                    },
                    onSelectionComplete = {
                        val uri = selectedUri.value
                        if (uri != null) onImagePicked(uri) else onDismiss()
                    },
                    onSessionError = { onFallback() },
                )
            }
            OutlinedButton(
                onClick = onFallback,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(fallbackLabel)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun EmbeddedImagePickerSurface(
    onUriPermissionGranted: (List<Uri>) -> Unit,
    onUriPermissionRevoked: (List<Uri>) -> Unit,
    onSelectionComplete: () -> Unit,
    onSessionError: (Throwable) -> Unit,
) {
    val context = LocalContext.current
    val accentColor = MaterialTheme.colorScheme.primary.toArgb().toLong() and 0xffffffffL
    val controller = remember { mutableStateOf<com.freevibe.service.EmbeddedPhotoPickerController?>(null) }
    val hasOpened = remember { mutableStateOf(false) }
    val hasFailed = remember { mutableStateOf(false) }
    val currentOnGranted = rememberUpdatedState(onUriPermissionGranted)
    val currentOnRevoked = rememberUpdatedState(onUriPermissionRevoked)
    val currentOnComplete = rememberUpdatedState(onSelectionComplete)
    val currentOnError = rememberUpdatedState(onSessionError)

    fun failOnce(error: Throwable) {
        if (!hasFailed.value) {
            hasFailed.value = true
            currentOnError.value(error)
        }
    }

    fun openIfReady(surfaceView: SurfaceView) {
        if (hasOpened.value || hasFailed.value) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            failOnce(IllegalStateException("Embedded photo picker unavailable"))
            return
        }
        @Suppress("DEPRECATION")
        val hostToken = surfaceView.hostToken
        if (!surfaceView.isAttachedToWindow || hostToken == null) return
        if (surfaceView.width <= 0 || surfaceView.height <= 0) return
        val opened = PhotoPickerCustomization.openEmbeddedImagePicker(
            context = context,
            surfaceView = surfaceView,
            widthPx = surfaceView.width,
            heightPx = surfaceView.height,
            accentColor = accentColor,
            onUriPermissionGranted = { currentOnGranted.value(it) },
            onUriPermissionRevoked = { currentOnRevoked.value(it) },
            onSelectionComplete = { currentOnComplete.value() },
            onSessionError = { failOnce(it) },
        )
        if (opened == null) {
            failOnce(IllegalStateException("Embedded photo picker unavailable"))
        } else {
            controller.value = opened
            hasOpened.value = true
        }
    }

    AndroidView(
        factory = { viewContext ->
            SurfaceView(viewContext).apply {
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        openIfReady(v as SurfaceView)
                    }

                    override fun onViewDetachedFromWindow(v: View) = Unit
                })
                addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                    val surfaceView = v as SurfaceView
                    openIfReady(surfaceView)
                    controller.value?.resize(surfaceView.width, surfaceView.height)
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { surfaceView ->
            openIfReady(surfaceView)
            controller.value?.resize(surfaceView.width, surfaceView.height)
        },
    )

    DisposableEffect(Unit) {
        onDispose { controller.value?.close() }
    }
}
