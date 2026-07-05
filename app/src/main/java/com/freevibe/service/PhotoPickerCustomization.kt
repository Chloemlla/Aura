package com.freevibe.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Parcelable
import android.os.ext.SdkExtensions
import android.util.Log
import android.view.Display
import android.view.SurfaceControlViewHost
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * Compile-safe bridge for newer platform Photo Picker features.
 *
 * Aura currently builds at compileSdk 35, while embedded Photo Picker and
 * portrait-grid customization are delivered through newer Android platform and
 * SDK-extension APIs. Keep all reflection in this reviewed bridge so callers
 * can use typed methods without broad storage permissions or toolchain bumps.
 */
object PhotoPickerCustomization {
    private const val TAG = "PhotoPickerCustomization"
    private const val EMBEDDED_PICKER_MIN_EXTENSION = 15
    private const val IMAGE_MIME_TYPE = "image/*"

    /**
     * Mutates [intent] to attach a 9:16 Photo Picker UI customization extra
     * when the runtime exposes it. No-op on older devices and extension levels.
     */
    fun apply9x16AspectRatio(intent: Intent) {
        if (Build.VERSION.SDK_INT < 34) return
        runCatching {
            val params = buildPortraitGridCustomization() ?: return
            val extraKey = photoPickerUiCustomizationExtraKey() ?: return
            Intent::class.java.getMethod(
                "putExtra",
                String::class.java,
                Parcelable::class.java,
            ).invoke(intent, extraKey, params)
        }.onFailure { e ->
            Log.d(TAG, "apply9x16 reflection skipped: ${e.message}")
        }
    }

    /**
     * Embedded picker availability from Android's documented gate:
     * Android 14/API 34 with SDK Extensions version 15 or newer.
     */
    fun isEmbeddedImagePickerAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val extension = runCatching {
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        }.getOrDefault(0)
        if (extension < EMBEDDED_PICKER_MIN_EXTENSION) return false

        return runCatching {
            val providerFactoryCls = Class.forName(
                "android.widget.photopicker.EmbeddedPhotoPickerProviderFactory",
            )
            val create = providerFactoryCls.getMethod("create", Context::class.java)
            create.invoke(null, context) != null
        }.getOrDefault(false)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun openEmbeddedImagePicker(
        context: Context,
        surfaceView: SurfaceView,
        widthPx: Int,
        heightPx: Int,
        accentColor: Long?,
        onUriPermissionGranted: (List<Uri>) -> Unit,
        onUriPermissionRevoked: (List<Uri>) -> Unit,
        onSelectionComplete: () -> Unit,
        onSessionError: (Throwable) -> Unit,
    ): EmbeddedPhotoPickerController? {
        if (!isEmbeddedImagePickerAvailable(context)) return null
        @Suppress("DEPRECATION")
        val hostToken: IBinder = surfaceView.hostToken ?: return null
        if (widthPx <= 0 || heightPx <= 0) return null

        return runCatching {
            val providerFactoryCls = Class.forName(
                "android.widget.photopicker.EmbeddedPhotoPickerProviderFactory",
            )
            val providerCls = Class.forName("android.widget.photopicker.EmbeddedPhotoPickerProvider")
            val clientCls = Class.forName("android.widget.photopicker.EmbeddedPhotoPickerClient")
            val featureInfoCls = Class.forName("android.widget.photopicker.EmbeddedPhotoPickerFeatureInfo")
            val sessionCls = Class.forName("android.widget.photopicker.EmbeddedPhotoPickerSession")
            val provider = providerFactoryCls.getMethod("create", Context::class.java)
                .invoke(null, context)
                ?: return null
            val controller = EmbeddedPhotoPickerController(sessionCls)

            val client = Proxy.newProxyInstance(
                clientCls.classLoader,
                arrayOf(clientCls),
            ) { _, method, args ->
                when (method.name) {
                    "onSessionOpened" -> {
                        val session = args?.firstOrNull()
                        if (session != null) {
                            attachSurfacePackage(surfaceView, session, sessionCls)
                            controller.attach(session)
                            controller.resize(widthPx, heightPx)
                            controller.setVisible(true)
                        }
                    }
                    "onUriPermissionGranted" -> onUriPermissionGranted(args.firstUriList())
                    "onUriPermissionRevoked" -> onUriPermissionRevoked(args.firstUriList())
                    "onSelectionComplete" -> onSelectionComplete()
                    "onSessionError" -> onSessionError(args?.firstOrNull() as? Throwable ?: RuntimeException("Embedded picker error"))
                }
                null
            }

            val featureInfo = buildEmbeddedFeatureInfo(accentColor)
            val openSession = providerCls.getMethod(
                "openSession",
                IBinder::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                featureInfoCls,
                Executor::class.java,
                clientCls,
            )
            openSession.invoke(
                provider,
                hostToken,
                surfaceView.display?.displayId ?: Display.DEFAULT_DISPLAY,
                widthPx,
                heightPx,
                featureInfo,
                context.mainExecutor,
                client,
            )
            controller
        }.onFailure { e ->
            Log.d(TAG, "embedded picker reflection skipped: ${e.message}")
            onSessionError(e)
        }.getOrNull()
    }

    private fun buildEmbeddedFeatureInfo(accentColor: Long?): Any {
        val builderCls = Class.forName("android.widget.photopicker.EmbeddedPhotoPickerFeatureInfo\$Builder")
        val builder = builderCls.getDeclaredConstructor().newInstance()
        invokeOptional(builder, "setMaxSelectionLimit", intArray(), 1)
        invokeOptional(builder, "setMimeTypes", arrayOf(List::class.java), listOf(IMAGE_MIME_TYPE))
        invokeOptional(builder, "setOrderedSelection", booleanArray(), false)
        invokeOptional(builder, "setPickerLaunchedInExpandedState", booleanArray(), true)
        accentColor?.let {
            invokeOptional(builder, "setAccentColor", arrayOf(Long::class.javaPrimitiveType!!), it)
        }

        buildSelectionParams()?.let { selectionParams ->
            invokeOptional(
                builder,
                "setSelectionParams",
                arrayOf(selectionParams.javaClass),
                selectionParams,
            )
        }
        buildPortraitGridCustomization()?.let { customizationParams ->
            invokeOptional(
                builder,
                "setUiCustomizationParams",
                arrayOf(customizationParams.javaClass),
                customizationParams,
            )
        }
        return builderCls.getMethod("build").invoke(builder)
    }

    private fun buildSelectionParams(): Any? = runCatching {
        val builderCls = Class.forName("android.widget.photopicker.PhotoPickerSelectionParams\$Builder")
        val builder = builderCls.getDeclaredConstructor().newInstance()
        invokeOptional(builder, "setMimeTypes", arrayOf(List::class.java), listOf(IMAGE_MIME_TYPE))
        builderCls.getMethod("build").invoke(builder)
    }.getOrNull()

    private fun buildPortraitGridCustomization(): Any? {
        buildOfficialPortraitGridCustomization()?.let { return it }
        return buildLegacyPortraitGridCustomization()
    }

    private fun buildOfficialPortraitGridCustomization(): Any? = runCatching {
        val builderCls = Class.forName("android.widget.photopicker.PhotoPickerUiCustomizationParams\$Builder")
        val paramsCls = Class.forName("android.widget.photopicker.PhotoPickerUiCustomizationParams")
        val builder = builderCls.getDeclaredConstructor().newInstance()
        val aspectRatio = paramsCls.getField("ASPECT_RATIO_PORTRAIT_9_16").getInt(null)
        builderCls.getMethod("setAspectRatio", Int::class.javaPrimitiveType!!).invoke(builder, aspectRatio)
        builderCls.getMethod("build").invoke(builder)
    }.getOrNull()

    private fun buildLegacyPortraitGridCustomization(): Any? = runCatching {
        val builderCls = Class.forName(
            "android.provider.MediaStore\$PhotoPickerUiCustomizationParams\$Builder",
        )
        val builder = builderCls.getDeclaredConstructor().newInstance()
        builderCls.getMethod(
            "setGridAspectRatio",
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        ).invoke(builder, 9, 16)
        builderCls.getMethod("build").invoke(builder)
    }.getOrNull()

    private fun photoPickerUiCustomizationExtraKey(): String? = runCatching {
        val mediaStoreCls = Class.forName("android.provider.MediaStore")
        listOf(
            "EXTRA_PICK_IMAGES_UI_CUSTOMIZATION_PARAMS",
            "EXTRA_PHOTO_PICKER_UI_CUSTOMIZATION_PARAMS",
        ).firstNotNullOfOrNull { fieldName ->
            runCatching { mediaStoreCls.getField(fieldName).get(null) as? String }.getOrNull()
        }
    }.getOrNull()

    @RequiresApi(Build.VERSION_CODES.R)
    private fun attachSurfacePackage(
        surfaceView: SurfaceView,
        session: Any,
        sessionCls: Class<*>,
    ) {
        val surfacePackage = sessionCls.getMethod("getSurfacePackage").invoke(session)
        if (surfacePackage is SurfaceControlViewHost.SurfacePackage) {
            surfaceView.setChildSurfacePackage(surfacePackage)
        }
    }

    private fun invokeOptional(
        target: Any,
        methodName: String,
        parameterTypes: Array<Class<*>>,
        vararg args: Any?,
    ) {
        runCatching {
            target.javaClass.getMethod(methodName, *parameterTypes).invoke(target, *args)
        }
    }

    private fun intArray(): Array<Class<*>> = arrayOf(Int::class.javaPrimitiveType!!)

    private fun booleanArray(): Array<Class<*>> = arrayOf(Boolean::class.javaPrimitiveType!!)

    @Suppress("UNCHECKED_CAST")
    private fun Array<Any?>?.firstUriList(): List<Uri> =
        this?.firstOrNull() as? List<Uri> ?: emptyList()
}

class EmbeddedPhotoPickerController internal constructor(
    private val sessionCls: Class<*>,
) {
    private var session: Any? = null
    private var closed = false
    private var pendingWidth = 0
    private var pendingHeight = 0

    internal fun attach(session: Any) {
        this.session = session
        if (closed) {
            close()
        } else if (pendingWidth > 0 && pendingHeight > 0) {
            resize(pendingWidth, pendingHeight)
        }
    }

    fun resize(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        pendingWidth = widthPx
        pendingHeight = heightPx
        invokeSession(
            "notifyResized",
            arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            widthPx,
            heightPx,
        )
    }

    fun setVisible(visible: Boolean) {
        invokeSession(
            "notifyVisibilityChanged",
            arrayOf(Boolean::class.javaPrimitiveType!!),
            visible,
        )
    }

    fun close() {
        closed = true
        invokeSession("close", emptyArray())
        session = null
    }

    private fun invokeSession(
        methodName: String,
        parameterTypes: Array<Class<*>>,
        vararg args: Any?,
    ) {
        val activeSession = session ?: return
        runCatching {
            sessionCls.getMethod(methodName, *parameterTypes).invoke(activeSession, *args)
        }
    }
}
