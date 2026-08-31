package com.chloemlla.aura.service

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi

const val LIVE_WALLPAPER_SHADER_PRESET_PREF = "live_wallpaper_shader_preset"

/**
 * SharedPreferences file the live-wallpaper engines read synchronously.
 *
 * A `WallpaperService` cannot practically subscribe to DataStore, so these keys are the
 * runtime source of truth and every writer must set them before the suspending DataStore
 * write. See [com.chloemlla.aura.data.local.PreferencesManager].
 */
const val WEATHER_WALLPAPER_PREFS_NAME = "freevibe_weather_wp"

const val REDUCE_ANIMATIONS_PREF = "reduce_animations"
const val ADAPTIVE_TINT_ENABLED_PREF = "adaptive_tint_enabled"
const val ADAPTIVE_TINT_INTENSITY_PREF = "adaptive_tint_intensity"
const val LIVE_WALLPAPER_DIM_ENABLED_PREF = "live_wallpaper_dim_enabled"
const val DAILY_WALLPAPER_ENABLED_PREF = "daily_wallpaper_enabled"
const val WEATHER_VFX_EFFECT_PREF = "vfx_effect"
const val TOUCH_EFFECT_STRENGTH_PREF = "touch_effect_strength"

data class AgslShaderPreset(
    val id: String,
    internal val agsl: String,
    @ColorInt val fallbackStartColor: Int,
    @ColorInt val fallbackEndColor: Int,
    @ColorInt val fallbackAccentColor: Int,
)

object AgslShaderGallery {
    const val NONE_ID = "none"
    const val AURORA_RIBBONS = "aurora_ribbons"
    const val CHROMA_MIST = "chroma_mist"
    const val NEON_DUSK = "neon_dusk"
    const val SOLAR_DRIFT = "solar_drift"
    const val DEEP_OCEAN = "deep_ocean"
    const val MONOCHROME_RAIN = "monochrome_rain"

    val presets: List<AgslShaderPreset> = listOf(
        AgslShaderPreset(
            id = AURORA_RIBBONS,
            agsl = """
                uniform float2 resolution;
                uniform float time;
                half4 main(float2 fragCoord) {
                    float2 uv = fragCoord / resolution;
                    float band = sin(uv.y * 9.0 + uv.x * 3.0 + time * 0.35);
                    float glow = smoothstep(0.15, 1.0, band * 0.5 + 0.5);
                    float3 base = mix(float3(0.02, 0.05, 0.12), float3(0.04, 0.18, 0.28), uv.y);
                    float3 accent = mix(float3(0.12, 0.72, 0.82), float3(0.72, 0.36, 0.95), uv.x);
                    return half4(base + accent * glow * 0.65, 1.0);
                }
            """.trimIndent(),
            fallbackStartColor = 0xFF061020.toInt(),
            fallbackEndColor = 0xFF123044.toInt(),
            fallbackAccentColor = 0xFF4DE1D4.toInt(),
        ),
        AgslShaderPreset(
            id = CHROMA_MIST,
            agsl = """
                uniform float2 resolution;
                uniform float time;
                half4 main(float2 fragCoord) {
                    float2 uv = fragCoord / resolution;
                    float2 p = uv - 0.5;
                    float r = length(p);
                    float mist = sin((p.x - p.y) * 12.0 + time * 0.22) * 0.5 + 0.5;
                    float halo = smoothstep(0.85, 0.05, r);
                    float3 base = mix(float3(0.05, 0.04, 0.08), float3(0.18, 0.10, 0.16), uv.y);
                    float3 accent = mix(float3(0.90, 0.34, 0.56), float3(0.28, 0.62, 0.95), mist);
                    return half4(base + accent * halo * 0.55, 1.0);
                }
            """.trimIndent(),
            fallbackStartColor = 0xFF100A17.toInt(),
            fallbackEndColor = 0xFF2B2034.toInt(),
            fallbackAccentColor = 0xFFE65D8C.toInt(),
        ),
        AgslShaderPreset(
            id = NEON_DUSK,
            agsl = """
                uniform float2 resolution;
                uniform float time;
                half4 main(float2 fragCoord) {
                    float2 uv = fragCoord / resolution;
                    float horizon = smoothstep(0.62, 0.18, abs(uv.y - 0.58));
                    float grid = smoothstep(0.985, 1.0, sin((uv.x + time * 0.015) * 56.0))
                        + smoothstep(0.982, 1.0, sin((uv.y + time * 0.025) * 42.0));
                    float3 sky = mix(float3(0.04, 0.03, 0.10), float3(0.18, 0.06, 0.18), uv.y);
                    float3 neon = float3(0.93, 0.22, 0.72) * horizon + float3(0.16, 0.78, 0.92) * min(grid, 1.0) * 0.42;
                    return half4(sky + neon, 1.0);
                }
            """.trimIndent(),
            fallbackStartColor = 0xFF090817.toInt(),
            fallbackEndColor = 0xFF311036.toInt(),
            fallbackAccentColor = 0xFF2CD6F2.toInt(),
        ),
        AgslShaderPreset(
            id = SOLAR_DRIFT,
            agsl = """
                uniform float2 resolution;
                uniform float time;
                half4 main(float2 fragCoord) {
                    float2 uv = fragCoord / resolution;
                    float2 sun = uv - float2(0.74 + sin(time * 0.08) * 0.06, 0.30 + cos(time * 0.06) * 0.04);
                    float glow = smoothstep(0.72, 0.0, length(sun));
                    float wave = sin((uv.x + uv.y) * 10.0 - time * 0.18) * 0.5 + 0.5;
                    float3 base = mix(float3(0.05, 0.07, 0.09), float3(0.34, 0.17, 0.09), uv.y);
                    float3 heat = mix(float3(0.98, 0.42, 0.17), float3(1.0, 0.78, 0.28), wave);
                    return half4(base + heat * glow * 0.72, 1.0);
                }
            """.trimIndent(),
            fallbackStartColor = 0xFF0E1114.toInt(),
            fallbackEndColor = 0xFF5A2B15.toInt(),
            fallbackAccentColor = 0xFFFFA23E.toInt(),
        ),
        AgslShaderPreset(
            id = DEEP_OCEAN,
            agsl = """
                uniform float2 resolution;
                uniform float time;
                half4 main(float2 fragCoord) {
                    float2 uv = fragCoord / resolution;
                    float swell = sin(uv.x * 7.0 + time * 0.18) + sin((uv.x + uv.y) * 13.0 - time * 0.12);
                    float caustic = smoothstep(1.15, 1.95, swell + uv.y);
                    float3 base = mix(float3(0.01, 0.03, 0.07), float3(0.02, 0.18, 0.24), uv.y);
                    float3 light = float3(0.24, 0.84, 0.76) * caustic * 0.38;
                    return half4(base + light, 1.0);
                }
            """.trimIndent(),
            fallbackStartColor = 0xFF020914.toInt(),
            fallbackEndColor = 0xFF063442.toInt(),
            fallbackAccentColor = 0xFF42D4C7.toInt(),
        ),
        AgslShaderPreset(
            id = MONOCHROME_RAIN,
            agsl = """
                uniform float2 resolution;
                uniform float time;
                half4 main(float2 fragCoord) {
                    float2 uv = fragCoord / resolution;
                    float streak = smoothstep(0.92, 1.0, sin((uv.x * 46.0) + (uv.y * 120.0) - time * 3.2));
                    float haze = smoothstep(0.0, 1.0, uv.y) * 0.18;
                    float value = 0.05 + haze + streak * 0.22;
                    return half4(float3(value), 1.0);
                }
            """.trimIndent(),
            fallbackStartColor = 0xFF050608.toInt(),
            fallbackEndColor = 0xFF24272C.toInt(),
            fallbackAccentColor = 0xFFB8C0C8.toInt(),
        ),
    )

    fun find(id: String?): AgslShaderPreset? = presets.firstOrNull { it.id == id }

    fun sanitizeId(id: String?): String = when {
        id == NONE_ID -> NONE_ID
        find(id) != null -> id.orEmpty()
        else -> NONE_ID
    }
}

class AgslShaderBackgroundRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cachedPresetId: String? = null
    private var cachedRuntimeShader: Any? = null
    private var failedRuntimePresetId: String? = null
    private var cachedFallbackKey: String? = null
    private var cachedFallbackGradient: Shader? = null
    private var cachedFallbackAccent: Shader? = null

    /**
     * Continuous time base for the shader's `time` uniform. Rebasing to the engine's
     * lifetime (instead of `elapsedRealtimeMs % 600_000`) removes the once-every-10-min
     * discontinuity where `time` snapped from 600 back to 0 and every preset's
     * sin/cos drift jumped. Float precision stays well below a frame for the day-scale
     * uptimes a wallpaper process sees.
     */
    private val startElapsedMs = SystemClock.elapsedRealtime()

    fun draw(
        canvas: Canvas,
        preset: AgslShaderPreset,
        elapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
    ) {
        if (canvas.width <= 0 || canvas.height <= 0) return
        // RuntimeShader needs a hardware-accelerated RenderNode pipeline. A software
        // canvas — e.g. the SurfaceHolder.lockCanvas() the weather engine draws on —
        // either throws (permanently degrading to the static fallback) or evaluates
        // the program per-pixel on the CPU, which 30 FPS cannot sustain. Skip the
        // attempt outright on a software canvas so the degraded path is deterministic
        // and cheap instead of an exception/CPU churn every frame.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            canvas.isHardwareAccelerated &&
            failedRuntimePresetId != preset.id
        ) {
            try {
                drawRuntimeShader(canvas, preset, elapsedRealtimeMs)
                return
            } catch (_: Throwable) {
                failedRuntimePresetId = preset.id
                cachedRuntimeShader = null
                cachedPresetId = null
                paint.shader = null
            }
        }
        drawStaticFallback(canvas, preset)
    }

    fun clear() {
        cachedPresetId = null
        cachedRuntimeShader = null
        failedRuntimePresetId = null
        cachedFallbackKey = null
        cachedFallbackGradient = null
        cachedFallbackAccent = null
        paint.shader = null
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun drawRuntimeShader(
        canvas: Canvas,
        preset: AgslShaderPreset,
        elapsedRealtimeMs: Long,
    ) {
        val runtimeShader = if (cachedPresetId == preset.id) {
            cachedRuntimeShader as? RuntimeShader ?: RuntimeShader(preset.agsl).also {
                cachedRuntimeShader = it
                cachedPresetId = preset.id
            }
        } else {
            RuntimeShader(preset.agsl).also {
                cachedRuntimeShader = it
                cachedPresetId = preset.id
            }
        }
        runtimeShader.setFloatUniform("resolution", canvas.width.toFloat(), canvas.height.toFloat())
        // Continuous since the engine started; see startElapsedMs. The old
        // `elapsedRealtimeMs % 600_000L` wrap made the animation visibly jump
        // once every 10 minutes.
        runtimeShader.setFloatUniform("time", (elapsedRealtimeMs - startElapsedMs) / 1000f)
        paint.shader = runtimeShader
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), paint)
    }

    private fun drawStaticFallback(canvas: Canvas, preset: AgslShaderPreset) {
        val key = "${preset.id}:${canvas.width}x${canvas.height}"
        if (cachedFallbackKey != key) {
            cachedFallbackKey = key
            cachedFallbackGradient = LinearGradient(
                0f,
                0f,
                canvas.width.toFloat(),
                canvas.height.toFloat(),
                intArrayOf(preset.fallbackStartColor, preset.fallbackEndColor, preset.fallbackAccentColor),
                floatArrayOf(0f, 0.72f, 1f),
                Shader.TileMode.CLAMP,
            )
            cachedFallbackAccent = RadialGradient(
                canvas.width * 0.78f,
                canvas.height * 0.24f,
                minOf(canvas.width, canvas.height) * 0.62f,
                withAlpha(preset.fallbackAccentColor, 112),
                withAlpha(preset.fallbackAccentColor, 0),
                Shader.TileMode.CLAMP,
            )
        }
        paint.shader = cachedFallbackGradient
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), paint)
        paint.shader = cachedFallbackAccent
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), paint)
        paint.shader = null
    }

    @ColorInt
    private fun withAlpha(@ColorInt color: Int, alpha: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)
}
