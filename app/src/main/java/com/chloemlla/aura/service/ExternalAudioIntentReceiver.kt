package com.chloemlla.aura.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.chloemlla.aura.data.model.Sound
import com.chloemlla.aura.data.model.stableKey
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/**
 * Broadcast receiver that allows third-party apps to trigger audio playback
 * in Aura via simple intents.
 *
 * ## Intent Protocol
 *
 * ### Play a sound by ID
 * - Action: `com.chloemlla.aura.action.PLAY_SOUND`
 * - Extra `com.chloemlla.aura.extra.SOUND_ID` (String) — the stable key of the sound to play
 *   (e.g. `"SOUND::BUNDLED::bundled_ringtone_01"`)
 * - Extra `com.chloemlla.aura.extra.VOLUME` (Float, optional) — volume 0.0–1.0, default 1.0
 *
 * Example (Tasker / adb shell):
 * ```
 * am broadcast -a com.chloemlla.aura.action.PLAY_SOUND \
 *   --es com.chloemlla.aura.extra.SOUND_ID "SOUND::BUNDLED::bundled_ringtone_01"
 * ```
 *
 * ### Play a random sound from a category
 * - Action: `com.chloemlla.aura.action.PLAY_RANDOM`
 * - Extra `com.chloemlla.aura.extra.CATEGORY` (String, optional) — "ringtone", "notification", "alarm", or "all" (default)
 *
 * ### Stop playback
 * - Action: `com.chloemlla.aura.action.STOP_PLAYBACK`
 *
 * ### Set volume
 * - Action: `com.chloemlla.aura.action.SET_VOLUME`
 * - Extra `com.chloemlla.aura.extra.VOLUME` (Float) — 0.0–1.0
 *
 * ## Security
 * Callers are gated by the signature-level permission
 * `com.chloemlla.aura.permission.EXTERNAL_AUDIO`, declared on the receiver in the
 * manifest, so only apps signed with the same key can send these intents. The check
 * cannot be done in code: broadcasts are dispatched by system_server, so inside
 * [onReceive] `Binder.getCallingUid()` returns this process's own uid and carries no
 * information about the sender.
 */
@AndroidEntryPoint
class ExternalAudioIntentReceiver : BroadcastReceiver() {

    @Inject lateinit var audioPlaybackManager: AudioPlaybackManager
    @Inject lateinit var bundledContentProvider: BundledContentProvider
    @Inject lateinit var soundUrlResolver: SoundUrlResolver

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PLAY_SOUND -> handlePlaySound(context, intent)
            ACTION_PLAY_RANDOM -> handlePlayRandom(context, intent)
            ACTION_STOP_PLAYBACK -> handleStopPlayback()
            ACTION_SET_VOLUME -> handleSetVolume(intent)
            else -> {
                if (com.chloemlla.aura.BuildConfig.DEBUG) {
                    Log.d(TAG, "Ignored unknown action: ${intent.action}")
                }
            }
        }
    }

    private fun handlePlaySound(context: Context, intent: Intent) {
        val soundId = intent.getStringExtra(EXTRA_SOUND_ID) ?: run {
            Log.w(TAG, "PLAY_SOUND without SOUND_ID extra")
            return
        }
        val volume = intent.getFloatExtra(EXTRA_VOLUME, 1f).coerceIn(0f, 1f)

        // A receiver's process can be reaped the instant onReceive returns, so the
        // async resolution must run inside goAsync's window or the broadcast is
        // silently dropped on a busy device (AURA-G2-17). Resolution is bounded so a
        // stuck resolver can't outlive goAsync's ~10s ANR accounting.
        val pending = goAsync()
        scope.launch {
            try {
                withTimeout(RESOLVE_TIMEOUT_MS) {
                    val sound = allBundledSounds().find { it.stableKey() == soundId }
                    if (sound == null) {
                        Log.w(TAG, "Unknown sound: $soundId")
                        return@withTimeout
                    }
                    val url = soundUrlResolver.resolve(sound)
                    if (url != null) {
                        audioPlaybackManager.play(sound, url, volume)
                        if (com.chloemlla.aura.BuildConfig.DEBUG) {
                            Log.d(TAG, "Playing sound: ${sound.name} from $url")
                        }
                    } else {
                        Log.w(TAG, "No URL resolved for sound: ${sound.stableKey()}")
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun handlePlayRandom(context: Context, intent: Intent) {
        val category = intent.getStringExtra(EXTRA_CATEGORY) ?: "all"
        val volume = intent.getFloatExtra(EXTRA_VOLUME, 1f).coerceIn(0f, 1f)

        val pending = goAsync()
        scope.launch {
            try {
                withTimeout(RESOLVE_TIMEOUT_MS) {
                    val pool = when (category) {
                        "ringtone" -> bundledContentProvider.getRingtones()
                        "notification" -> bundledContentProvider.getNotifications()
                        "alarm" -> bundledContentProvider.getAlarms()
                        "all" -> allBundledSounds()
                        else -> {
                            Log.w(TAG, "Unknown category: $category")
                            return@withTimeout
                        }
                    }
                    if (pool.isEmpty()) return@withTimeout
                    val sound = pool.random()
                    val url = soundUrlResolver.resolve(sound)
                    if (url != null) {
                        audioPlaybackManager.play(sound, url, volume)
                        if (com.chloemlla.aura.BuildConfig.DEBUG) {
                            Log.d(TAG, "Playing random sound: ${sound.name} from $url")
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun handleStopPlayback() {
        audioPlaybackManager.stop()
    }

    private fun handleSetVolume(intent: Intent) {
        val volume = intent.getFloatExtra(EXTRA_VOLUME, 1f).coerceIn(0f, 1f)
        audioPlaybackManager.setVolume(volume)
    }

    private fun allBundledSounds(): List<Sound> =
        bundledContentProvider.getRingtones() +
            bundledContentProvider.getNotifications() +
            bundledContentProvider.getAlarms()

    companion object {
        private const val TAG = "AudioIntentReceiver"

        /** Bounds async resolution to a fraction of goAsync's ~10s ANR window. */
        private const val RESOLVE_TIMEOUT_MS = 8_000L

        // One scope for all broadcasts: the old per-instance job pool was never
        // cancelled and leaked a SupervisorJob (and its children) per broadcast.
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        /** Actions */
        const val ACTION_PLAY_SOUND = "com.chloemlla.aura.action.PLAY_SOUND"
        const val ACTION_PLAY_RANDOM = "com.chloemlla.aura.action.PLAY_RANDOM"
        const val ACTION_STOP_PLAYBACK = "com.chloemlla.aura.action.STOP_PLAYBACK"
        const val ACTION_SET_VOLUME = "com.chloemlla.aura.action.SET_VOLUME"

        /** Extras */
        const val EXTRA_SOUND_ID = "com.chloemlla.aura.extra.SOUND_ID"
        const val EXTRA_CATEGORY = "com.chloemlla.aura.extra.CATEGORY"
        const val EXTRA_VOLUME = "com.chloemlla.aura.extra.VOLUME"
    }
}
