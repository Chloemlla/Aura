package com.chloemlla.aura.data.repository

import android.content.Context
import android.util.Log
import com.chloemlla.aura.data.local.PreferencesManager
import com.chloemlla.aura.service.CommunityIdentityProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private val FIREBASE_KEY_REGEX = Regex("[.#$\\[\\]/]")

internal fun sanitizeVoteKey(id: String): String =
    id.replace(FIREBASE_KEY_REGEX, "_")

/**
 * Pure-JVM admin-precedence rule. Tested by [com.chloemlla.aura.data.repository.AdminPrecedenceTest].
 * Roadmap N-2: server-side Custom Claim is always authoritative; legacy device-hash and
 * UID allowlists are migration fallbacks only.
 */
internal fun computeIsAdmin(
    adminFromClaims: Boolean,
    deviceIdHash: String,
    currentUserId: String,
    adminDeviceIdHashes: Set<String>,
    adminUserIds: Set<String>,
): Boolean =
    adminFromClaims ||
        deviceIdHash in adminDeviceIdHashes ||
        currentUserId in adminUserIds

internal fun matchesHiddenIds(hiddenIds: Set<String>, vararg candidateIds: String?): Boolean =
    candidateIds.asSequence()
        .filterNotNull()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .any { candidate ->
            candidate in hiddenIds || sanitizeVoteKey(candidate) in hiddenIds
        }

private fun expandHiddenIds(ids: Set<String>): Set<String> = buildSet(ids.size * 2) {
    ids.forEach { id ->
        val normalized = id.trim()
        if (normalized.isNotEmpty()) {
            add(normalized)
            add(sanitizeVoteKey(normalized))
        }
    }
}

/**
 * Community voting + admin moderation via Firebase Realtime Database.
 *
 * Firebase structure:
 *   /votes/{contentId}/upvotes = Int                 (community vote tally)
 *   /votes/{contentId}/voters/{deviceId} = true      (prevents double-voting, transactional)
 *   /voters/{contentId}/{deviceId} = true            (legacy path still read for compatibility)
 *   /moderation/{contentId} = true                   (admin global hide — removes for ALL users)
 *
 * Regular downvote = local-only hide (SharedPreferences).
 * Admin downvote = global hide via /moderation (visible to no one).
 */
@Singleton
class VoteRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identityProvider: CommunityIdentityProvider,
    private val callableClient: CommunityCallableClient,
    private val prefs: PreferencesManager,
) {
    private val db by lazy {
        try { FirebaseDatabase.getInstance().reference } catch (_: Exception) { null }
    }
    private val votesRef get() = db?.child("votes")
    private val votersRef get() = db?.child("voters")
    private val moderationRef get() = db?.child("moderation")

    /**
     * Admin device IDs stored as SHA-256 hashes so plaintext IDs aren't in the APK.
     *
     * Roadmap N-2: server-side `admin: true` Firebase Custom Claim is the canonical
     * source of truth — checked first by [refreshAdminFromClaims] and cached in
     * [_adminFromClaims]. This hash list remains as a one-cycle migration fallback
     * so existing admin devices keep working until the matching ID token refreshes
     * with the claim attached. Remove the hash list and `adminUserIds` once every
     * admin has rotated through a Custom-Claim-bearing ID token (typical: 1 hour
     * after backend deploys the claim).
     */
    private val adminDeviceIdHashes = setOf(
        "70221777b62eabc52f5d0625fe7fd27f6a96f1a314231f0a33e7db98cb7da49b",
        "8d5c02d2bc8767d04eb1cdc9a662a16a735fb130374d6c98b189ff787b78f80c",
    )

    /** Admin Firebase UIDs can be added here as a legacy fallback alongside custom claims. */
    private val adminUserIds = emptySet<String>()

    private val auth: FirebaseAuth? by lazy {
        try { FirebaseAuth.getInstance() } catch (_: Exception) { null }
    }

    /** Cached Custom Claim state from the user's most-recently-refreshed ID token. */
    private val _adminFromClaims = MutableStateFlow(false)

    private fun sha256(input: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Force a fresh ID token from Firebase Auth and read the `admin` Custom Claim.
     * Call from a coroutine after sign-in, after a known privilege change, or on
     * app startup. The RTDB security rules are the actual enforcement layer; this
     * client check just controls UI affordances (e.g. showing the moderation menu).
     */
    suspend fun refreshAdminFromClaims(): Boolean {
        val token = try {
            // forceRefresh=true asks Firebase Auth to round-trip a fresh token so newly-set
            // server-side claims become visible without waiting for the 1 h token lifetime.
            auth?.currentUser?.getIdToken(true)?.await()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (com.chloemlla.aura.BuildConfig.DEBUG) Log.w("VoteRepo", "refreshAdminFromClaims failed: ${e.message}")
            null
        }
        val isAdminClaim = token?.claims?.get("admin") == true
        _adminFromClaims.value = isAdminClaim
        return isAdminClaim
    }

    /**
     * Best-effort admin check. Order of precedence:
     *  1. Cached `admin` Custom Claim from the user's ID token (Firebase Auth, server-side).
     *  2. Legacy SHA-256 device-ID allowlist (one-cycle migration fallback).
     *  3. Legacy `adminUserIds` Firebase UID allowlist.
     *
     * Always pair with RTDB Security Rules — the client check is spoofable; rules are not.
     */
    val isAdmin: Boolean
        get() = computeIsAdmin(
            adminFromClaims = _adminFromClaims.value,
            deviceIdHash = sha256(identityProvider.legacyDeviceId),
            currentUserId = identityProvider.currentUserId(),
            adminDeviceIdHashes = adminDeviceIdHashes,
            adminUserIds = adminUserIds,
        )

    // ── Local hidden IDs (user's personal downvotes) ──

    private val _localHiddenIds = MutableStateFlow<Set<String>>(emptySet())

    init {
        val prefs = context.getSharedPreferences("aura_votes", Context.MODE_PRIVATE)
        _localHiddenIds.value = prefs.getStringSet("hidden_ids", emptySet()) ?: emptySet()
    }

    // ── Global moderation list (admin-hidden, synced from Firebase) ──

    private val _moderatedIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Guarded by [moderationLock]. Non-null exactly while a listener is attached, so the
     * consent collector below is idempotent on repeated emissions of the same value.
     */
    private var moderationListener: ValueEventListener? = null
    private val moderationLock = Any()

    /**
     * Singleton-scoped because the moderation listener outlives any one screen. Cancelled
     * only with the process; [detachModerationListener] is what releases the Firebase
     * listener when consent is withdrawn.
     */
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // The moderation listener opens a Realtime Database socket, so it must not attach
        // until the user has actually opted into community features. Both preferences
        // default to false, and this repository is a @Singleton constructed as soon as any
        // screen that injects it opens — attaching from init would put a non-consenting
        // user on the network for the lifetime of the process.
        repositoryScope.launch {
            combine(
                prefs.communityProviderEnabled,
                prefs.communityGuidelinesAccepted,
            ) { providerEnabled, guidelinesAccepted -> providerEnabled && guidelinesAccepted }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) attachModerationListener() else detachModerationListener()
                }
        }
    }

    private fun attachModerationListener() {
        synchronized(moderationLock) {
            if (moderationListener != null) return
            try {
                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        _moderatedIds.value = snapshot.children.mapNotNull { it.key }.toSet()
                    }

                    override fun onCancelled(error: DatabaseError) {
                        if (com.chloemlla.aura.BuildConfig.DEBUG) {
                            Log.w("VoteRepo", "Moderation listener cancelled: ${error.message}")
                        }
                    }
                }
                val ref = moderationRef ?: return
                ref.addValueEventListener(listener)
                moderationListener = listener
            } catch (e: Exception) {
                if (com.chloemlla.aura.BuildConfig.DEBUG) {
                    Log.w("VoteRepo", "Firebase init failed: ${e.message}")
                }
            }
        }
    }

    private fun detachModerationListener() {
        synchronized(moderationLock) {
            val listener = moderationListener ?: return
            moderationListener = null
            try {
                moderationRef?.removeEventListener(listener)
            } catch (e: Exception) {
                if (com.chloemlla.aura.BuildConfig.DEBUG) {
                    Log.w("VoteRepo", "Moderation listener detach failed: ${e.message}")
                }
            }
            // Moderation hides are a community-service signal; drop them with the socket so a
            // user who opts out does not keep filtering content from a source they left.
            _moderatedIds.value = emptySet()
        }
    }

    /** Visible for tests: whether a Firebase moderation listener is currently attached. */
    internal fun isModerationListenerAttached(): Boolean =
        synchronized(moderationLock) { moderationListener != null }

    /** Combined hidden IDs: local downvotes + global moderation */
    val hiddenIds: Flow<Set<String>> = combine(_localHiddenIds, _moderatedIds) { local, moderated ->
        expandHiddenIds(local) + expandHiddenIds(moderated)
    }

    // ── Voting ──

    fun getVoteCount(contentId: String): Flow<Int> = callbackFlow {
        if (!isCommunityAccessEnabled()) { trySend(0); awaitClose {}; return@callbackFlow }
        val votesRefInstance = votesRef
        if (votesRefInstance == null) { trySend(0); awaitClose {}; return@callbackFlow }
        val safeId = sanitizeKey(contentId)
        val ref = votesRefInstance.child(safeId).child("upvotes")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                trySend(snapshot.getValue(Int::class.java) ?: 0)
            }
            override fun onCancelled(error: DatabaseError) { trySend(0) }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    suspend fun hasVoted(contentId: String, alreadySanitized: Boolean = false): Boolean {
        if (!isCommunityAccessEnabled()) return false
        val safeId = if (alreadySanitized) contentId else sanitizeKey(contentId)
        return try {
            identityProvider.knownIdentityIds()
                .map(::sanitizeKey)
                .any { voterId ->
                    awaitFirebaseRead("Community vote status") {
                        votesRef?.child(safeId)?.child("voters")?.child(voterId)?.get()?.await()?.exists() == true ||
                            votersRef?.child(safeId)?.child(voterId)?.get()?.await()?.exists() == true
                    }
                }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }

    suspend fun upvote(contentId: String): Boolean {
        if (!isCommunityAccessEnabled()) return false
        val safeId = sanitizeKey(contentId)
        identityProvider.ensureSignedIn()
        if (hasVoted(safeId, alreadySanitized = true)) return false

        return upvoteWithCallable(contentId)
    }

    private suspend fun upvoteWithCallable(contentId: String): Boolean =
        try {
            callableClient.recordCommunityVote(contentId).status.equals("accepted", ignoreCase = true)
        } catch (e: CommunityCallableException) {
            if (com.chloemlla.aura.BuildConfig.DEBUG) {
                Log.w("VoteRepo", "recordCommunityVote failed: ${e.message}")
            }
            false
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (com.chloemlla.aura.BuildConfig.DEBUG) {
                Log.w("VoteRepo", "recordCommunityVote failed: ${e.message}")
            }
            false
        }

    // ── Downvote / Hide ──

    /** Regular user: hide locally. Admin: hide globally for everyone. */
    suspend fun downvote(contentId: String) {
        if (!isCommunityAccessEnabled()) return
        if (com.chloemlla.aura.BuildConfig.DEBUG) {
            Log.d("VoteRepo", "downvote($contentId) userId=${identityProvider.currentUserId()} isAdmin=$isAdmin")
        }
        if (isAdmin) {
            moderateHide(contentId)
        } else {
            hideLocally(contentId)
        }
    }

    /** Local-only hide (regular users) */
    fun hideLocally(contentId: String) {
        val updated = _localHiddenIds.updateAndGet { it + contentId }
        context.getSharedPreferences("aura_votes", Context.MODE_PRIVATE)
            .edit().putStringSet("hidden_ids", updated).apply()
    }

    /** Admin: globally hide content for ALL users via Firebase */
    suspend fun moderateHide(contentId: String) {
        if (!isCommunityAccessEnabled()) return
        val moderationRefInstance = moderationRef
        if (moderationRefInstance == null) { hideLocally(contentId); return }
        val safeId = sanitizeKey(contentId)
        if (com.chloemlla.aura.BuildConfig.DEBUG) Log.d("VoteRepo", "moderateHide: safeId=$safeId path=moderation/$safeId")
        try {
            moderationRefInstance.child(safeId).setValue(true).await()
            if (com.chloemlla.aura.BuildConfig.DEBUG) Log.d("VoteRepo", "Admin moderated OK: $contentId")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (com.chloemlla.aura.BuildConfig.DEBUG) Log.e("VoteRepo", "Moderation FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            hideLocally(contentId)
        }
    }

    /** Admin: remove global moderation (unhide for everyone) */
    suspend fun moderateUnhide(contentId: String) {
        if (!isCommunityAccessEnabled()) return
        val moderationRefInstance = moderationRef ?: return
        val safeId = sanitizeKey(contentId)
        try {
            moderationRefInstance.child(safeId).removeValue().await()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    /** Unhide locally */
    fun unhideLocally(contentId: String) {
        val updated = _localHiddenIds.updateAndGet { it - contentId }
        context.getSharedPreferences("aura_votes", Context.MODE_PRIVATE)
            .edit().putStringSet("hidden_ids", updated).apply()
    }

    /** Reverse a [downvote]: mirrors its admin/local branch so an accidental hide is undoable. */
    suspend fun undoDownvote(contentId: String) {
        if (!isCommunityAccessEnabled()) return
        if (isAdmin) moderateUnhide(contentId) else unhideLocally(contentId)
    }

    fun isHidden(contentId: String): Boolean =
        matchesHiddenIds(_localHiddenIds.value, contentId) ||
            matchesHiddenIds(_moderatedIds.value, contentId)

    // ── Batch ──

    fun getVoteCounts(contentIds: List<String>): Flow<Map<String, Int>> = callbackFlow {
        if (!isCommunityAccessEnabled()) { trySend(emptyMap()); awaitClose {}; return@callbackFlow }
        val votesRefInstance = votesRef
        if (votesRefInstance == null) { trySend(emptyMap()); awaitClose {}; return@callbackFlow }
        val counts = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val listeners = mutableListOf<Pair<String, ValueEventListener>>()

        contentIds.take(50).forEach { id ->
            val safeId = sanitizeKey(id)
            val ref = votesRefInstance.child(safeId).child("upvotes")
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    counts[id] = snapshot.getValue(Int::class.java) ?: 0
                    trySend(counts.toMap())
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            ref.addValueEventListener(listener)
            listeners.add(safeId to listener)
        }
        awaitClose {
            listeners.forEach { (safeId, listener) ->
                votesRefInstance.child(safeId).child("upvotes").removeEventListener(listener)
            }
        }
    }

    /** Get top upvoted content IDs globally, sorted by vote count descending */
    suspend fun getTopVotedIds(limit: Int = 50): List<Pair<String, Int>> {
        if (!isCommunityAccessEnabled()) return emptyList()
        val votesRefInstance = votesRef ?: return emptyList()
        return try {
            val snapshot = awaitFirebaseRead("Community vote leaderboard") { votesRefInstance.get().await() }
            snapshot.children.mapNotNull { child ->
                val key = child.key ?: return@mapNotNull null
                val upvotes = child.child("upvotes").getValue(Int::class.java) ?: 0
                if (upvotes > 0) key to upvotes else null
            }.sortedByDescending { it.second }.take(limit)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (com.chloemlla.aura.BuildConfig.DEBUG) android.util.Log.e("VoteRepo", "getTopVotedIds failed: ${e.message}")
            emptyList()
        }
    }

    fun sanitizeKey(id: String): String =
        sanitizeVoteKey(id)

    private suspend fun isCommunityAccessEnabled(): Boolean =
        prefs.communityProviderEnabled.first() && prefs.communityGuidelinesAccepted.first()
}
