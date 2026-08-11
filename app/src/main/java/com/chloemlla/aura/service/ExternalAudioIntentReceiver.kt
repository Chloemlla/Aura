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
 * This receiver is exported so any app can send intents. The receiver
 * validates that the intent has a sound ID before playing. For restricted
 * access, use [AudioContentProvider] with specific URI permissions.
 */
@AndroidEntryPoint
class ExternalAudioIntentReceiver : BroadcastReceiver() {

    @Inject lateinit var audioPlaybackManager: AudioPlaybackManager
    @Inject lateinit var bundledContentProvider: BundledContentProvider
    @Inject lateinit var soundUrlResolver: SoundUrlResolver

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        // Only allow trusted callers (com.chloemlla.* packages)
        val caller = goAsyncBinderIdentity(context) ?: run {
            Log.w(TAG, "Blocked intent from unknown caller")
            return
        }
        if (!isTrustedCaller(context, caller)) {
            Log.w(TAG, "Blocked intent from untrusted caller: $caller")
            return
        }

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

        val sound = allBundledSounds().find { it.stableKey() == soundId }
        if (sound == null) {
            Log.w(TAG, "Unknown sound: $soundId")
            return
        }

        scope.launch {
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
    }

    private fun handlePlayRandom(context: Context, intent: Intent) {
        val category = intent.getStringExtra(EXTRA_CATEGORY) ?: "all"
        val volume = intent.getFloatExtra(EXTRA_VOLUME, 1f).coerceIn(0f, 1f)

        val pool = when (category) {
            "ringtone" -> bundledContentProvider.getRingtones()
            "notification" -> bundledContentProvider.getNotifications()
            "alarm" -> bundledContentProvider.getAlarms()
            "all" -> allBundledSounds()
            else -> {
                Log.w(TAG, "Unknown category: $category")
                return
            }
        }
        if (pool.isEmpty()) return

        val sound = pool.random()
        scope.launch {
            val url = soundUrlResolver.resolve(sound)
            if (url != null) {
                audioPlaybackManager.play(sound, url, volume)
                if (com.chloemlla.aura.BuildConfig.DEBUG) {
                    Log.d(TAG, "Playing random sound: ${sound.name} from $url")
                }
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

    // --- caller validation ---

    private fun goAsyncBinderIdentity(context: Context): String? {
        val uid = android.os.Binder.getCallingUid()
        if (uid == android.os.Process.myUid()) return context.packageName
        return try {
            context.packageManager.getNameForUid(uid)
        } catch (_: Exception) {
            null
        }
    }

    private fun isTrustedCaller(context: Context, callerPkg: String): Boolean =
        callerPkg == context.packageName ||
            callerPkg == "com.chloemlla.projectlumen" ||
            callerPkg.startsWith("com.chloemlla.")

    companion object {
        private const val TAG = "AudioIntentReceiver"

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
