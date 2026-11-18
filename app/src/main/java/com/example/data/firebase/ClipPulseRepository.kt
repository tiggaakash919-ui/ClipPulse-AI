package com.example.data.firebase

import android.content.Context
import com.example.R
import com.example.data.model.ClipProject
import com.example.data.model.ClipStatus
import com.example.data.model.ClippingCampaign
import com.example.data.model.HookBannerStyle
import com.example.data.model.LayoutPreset
import com.example.data.model.PrivacyStatus
import com.example.data.model.PublishedShort
import com.example.data.model.ViralClip
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.tasks.await
import java.util.UUID

class ClipPulseRepository(
    private val db: FirebaseFirestore?,
    private val auth: FirebaseAuth?
) {
    private val localProjects = MutableStateFlow<List<ClipProject>>(emptyList())
    private val localClips = MutableStateFlow<List<ViralClip>>(emptyList())
    private val localCampaigns = MutableStateFlow<List<ClippingCampaign>>(emptyList())
    private val localPublications = MutableStateFlow<List<PublishedShort>>(emptyList())

    companion object {
        fun create(context: Context): ClipPulseRepository {
            val appCtx = context.applicationContext
            val hasFirebase = FirebaseApp.getApps(appCtx).isNotEmpty() ||
                runCatching { FirebaseApp.initializeApp(appCtx) != null }.getOrDefault(false)
            if (!hasFirebase) {
                return ClipPulseRepository(db = null, auth = null)
            }
            val dbIdRes = appCtx.resources.getIdentifier("firestore_database_id", "string", appCtx.packageName)
            val firestore = runCatching {
                if (dbIdRes != 0) {
                    val dbId = appCtx.getString(dbIdRes)
                    if (dbId.isNotBlank()) FirebaseFirestore.getInstance(dbId) else FirebaseFirestore.getInstance()
                } else {
                    FirebaseFirestore.getInstance()
                }
            }.getOrNull()
            val firebaseAuth = runCatching { Firebase.auth }.getOrNull()
            return ClipPulseRepository(db = firestore, auth = firebaseAuth)
        }
    }

    private fun requireUserId(): String {
        return auth?.currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun observeProjects(userId: String): Flow<List<ClipProject>> {
        val firestore = db ?: return localProjects
        return firestore.collection("users").document(userId).collection("projects")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    ClipProject::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
    }

    fun observeClips(userId: String): Flow<List<ViralClip>> {
        val firestore = db ?: return localClips
        return firestore.collection("users").document(userId).collection("clips")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    ViralClip::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
    }

    fun observeCampaigns(userId: String): Flow<List<ClippingCampaign>> {
        val firestore = db ?: return localCampaigns
        return firestore.collection("users").document(userId).collection("campaigns")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    ClippingCampaign::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
    }

    fun observePublications(userId: String): Flow<List<PublishedShort>> {
        val firestore = db ?: return localPublications
        return firestore.collection("users").document(userId).collection("publications")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    PublishedShort::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
    }

    suspend fun saveProject(project: ClipProject): Result<String> {
        val firestore = db
        if (firestore == null) {
            val id = "proj_${UUID.randomUUID().toString().take(8)}"
            localProjects.update { listOf(project.copy(id = id)) + it }
            return Result.success(id)
        }
        var docPath = "users/${auth?.currentUser?.uid ?: "unauth"}/projects"
        return try {
            val uid = requireUserId()
            val docRef = firestore.collection("users").document(uid).collection("projects").document()
            docPath = docRef.path
            docRef.set(project.toCreateMap(uid)).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun saveClip(clip: ViralClip): Result<String> {
        val firestore = db
        if (firestore == null) {
            val id = clip.id.ifBlank { "clip_${UUID.randomUUID().toString().take(8)}" }
            localClips.update { list -> listOf(clip.copy(id = id)) + list.filterNot { it.id == id } }
            return Result.success(id)
        }
        var docPath = "users/${auth?.currentUser?.uid ?: "unauth"}/clips"
        return try {
            val uid = requireUserId()
            val docRef = firestore.collection("users").document(uid).collection("clips").document()
            docPath = docRef.path
            docRef.set(clip.toCreateMap(uid)).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateClipCustomization(
        clipId: String,
        layoutPreset: LayoutPreset,
        hookBannerText: String,
        hookBannerStyle: HookBannerStyle,
        creditWatermark: String,
        chatOverlayEnabled: Boolean,
        startSec: Long,
        endSec: Long
    ): Result<Unit> {
        val firestore = db
        if (firestore == null) {
            val safeStart = startSec.coerceAtLeast(0L)
            val safeEnd = endSec.coerceAtLeast(safeStart + 1L)
            localClips.update { list ->
                list.map {
                    if (it.id == clipId) {
                        it.copy(
                            layoutPreset = layoutPreset.name,
                            hookBannerText = hookBannerText.take(80),
                            hookBannerStyle = hookBannerStyle.name,
                            creditWatermark = creditWatermark.take(80),
                            chatOverlayEnabled = chatOverlayEnabled,
                            startSec = safeStart,
                            endSec = safeEnd
                        )
                    } else it
                }
            }
            return Result.success(Unit)
        }
        var docPath = "users/${auth?.currentUser?.uid ?: "unauth"}/clips/$clipId"
        return try {
            val uid = requireUserId()
            require(clipId.isNotBlank()) { "Clip ID must not be blank" }
            val safeStart = startSec.coerceAtLeast(0L)
            val safeEnd = endSec.coerceAtLeast(safeStart + 1L)
            val docRef = firestore.collection("users").document(uid).collection("clips").document(clipId)
            docPath = docRef.path
            docRef.update(
                mapOf(
                    "layoutPreset" to layoutPreset.name,
                    "hookBannerText" to hookBannerText.take(80),
                    "hookBannerStyle" to hookBannerStyle.name,
                    "creditWatermark" to creditWatermark.take(80),
                    "chatOverlayEnabled" to chatOverlayEnabled,
                    "startSec" to safeStart,
                    "endSec" to safeEnd,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateClipAiAndSeo(
        clipId: String,
        seoTitle: String,
        seoDescription: String,
        seoTags: List<String>,
        pinnedComment: String,
        viralScore: Long? = null,
        aiRetentionSummary: String? = null,
        transcriptPreview: String? = null
    ): Result<Unit> {
        val firestore = db
        if (firestore == null) {
            localClips.update { list ->
                list.map {
                    if (it.id == clipId) {
                        it.copy(
                            seoTitle = seoTitle.take(100),
                            seoDescription = seoDescription.take(2500),
                            seoTags = seoTags.take(30),
                            pinnedComment = pinnedComment.take(500),
                            viralScore = viralScore?.coerceIn(0L, 100L) ?: it.viralScore,
                            aiRetentionSummary = aiRetentionSummary ?: it.aiRetentionSummary,
                            transcriptPreview = transcriptPreview ?: it.transcriptPreview
                        )
                    } else it
                }
            }
            return Result.success(Unit)
        }
        var docPath = "users/${auth?.currentUser?.uid ?: "unauth"}/clips/$clipId"
        return try {
            val uid = requireUserId()
            require(clipId.isNotBlank()) { "Clip ID must not be blank" }
            val docRef = firestore.collection("users").document(uid).collection("clips").document(clipId)
            docPath = docRef.path
            val payload = mutableMapOf<String, Any>(
                "seoTitle" to seoTitle.take(100),
                "seoDescription" to seoDescription.take(2500),
                "seoTags" to seoTags.take(30),
                "pinnedComment" to pinnedComment.take(500),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (viralScore != null) {
                payload["viralScore"] = viralScore.coerceIn(0L, 100L)
            }
            if (!aiRetentionSummary.isNullOrBlank()) {
                payload["aiRetentionSummary"] = aiRetentionSummary.take(2000)
            }
            if (!transcriptPreview.isNullOrBlank()) {
                payload["transcriptPreview"] = transcriptPreview.take(3000)
            }
            docRef.update(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateClipRenderStatus(
        clipId: String,
        localOutputPath: String,
        status: ClipStatus
    ): Result<Unit> {
        val firestore = db
        if (firestore == null) {
            localClips.update { list ->
                list.map {
                    if (it.id == clipId) it.copy(localOutputPath = localOutputPath, status = status.name) else it
                }
            }
            return Result.success(Unit)
        }
        var docPath = "users/${auth?.currentUser?.uid ?: "unauth"}/clips/$clipId"
        return try {
            val uid = requireUserId()
            require(clipId.isNotBlank()) { "Clip ID must not be blank" }
            val docRef = firestore.collection("users").document(uid).collection("clips").document(clipId)
            docPath = docRef.path
            docRef.update(
                mapOf(
                    "localOutputPath" to localOutputPath.take(500),
                    "status" to status.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun saveCampaign(campaign: ClippingCampaign): Result<String> {
        val firestore = db
        if (firestore == null) {
            val id = "camp_${UUID.randomUUID().toString().take(8)}"
            localCampaigns.update { listOf(campaign.copy(id = id)) + it }
            return Result.success(id)
        }
        var docPath = "users/${auth?.currentUser?.uid ?: "unauth"}/campaigns"
        return try {
            val uid = requireUserId()
            val docRef = firestore.collection("users").document(uid).collection("campaigns").document()
            docPath = docRef.path
            docRef.set(campaign.toCreateMap(uid)).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun toggleCampaignActive(campaignId: String, active: Boolean): Result<Unit> {
        val firestore = db
        if (firestore == null) {
            localCampaigns.update { list ->
                list.map { if (it.id == campaignId) it.copy(active = active) else it }
            }
            return Result.success(Unit)
        }
        var docPath = "users/${auth?.currentUser?.uid ?: "unauth"}/campaigns/$campaignId"
        return try {
            val uid = requireUserId()
            require(campaignId.isNotBlank()) { "Campaign ID must not be blank" }
            val docRef = firestore.collection("users").document(uid).collection("campaigns").document(campaignId)
            docPath = docRef.path
            docRef.update(
                mapOf(
                    "active" to active,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteCampaign(campaignId: String): Result<Unit> {
        val firestore = db
        if (firestore == null) {
            localCampaigns.update { list -> list.filterNot { it.id == campaignId } }
            return Result.success(Unit)
        }
        var docPath = "users/${auth?.currentUser?.uid ?: "unauth"}/campaigns/$campaignId"
        return try {
            val uid = requireUserId()
            require(campaignId.isNotBlank()) { "Campaign ID must not be blank" }
            val docRef = firestore.collection("users").document(uid).collection("campaigns").document(campaignId)
            docPath = docRef.path
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    suspend fun savePublication(publication: PublishedShort): Result<String> {
        val firestore = db
        if (firestore == null) {
            val id = "pub_${UUID.randomUUID().toString().take(8)}"
            localPublications.update { listOf(publication.copy(id = id)) + it }
            return Result.success(id)
        }
        var docPath = "users/${auth?.currentUser?.uid ?: "unauth"}/publications"
        return try {
            val uid = requireUserId()
            val docRef = firestore.collection("users").document(uid).collection("publications").document()
            docPath = docRef.path
            docRef.set(publication.toCreateMap(uid)).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updatePublicationViews(
        publicationId: String,
        viewsCount: Long,
        privacyStatus: PrivacyStatus? = null
    ): Result<Unit> {
        val firestore = db
        if (firestore == null) {
            localPublications.update { list ->
                list.map {
                    if (it.id == publicationId) {
                        it.copy(
                            viewsCount = viewsCount.coerceAtLeast(0L),
                            privacyStatus = privacyStatus?.name ?: it.privacyStatus
                        )
                    } else it
                }
            }
            return Result.success(Unit)
        }
        var docPath = "users/${auth?.currentUser?.uid ?: "unauth"}/publications/$publicationId"
        return try {
            val uid = requireUserId()
            require(publicationId.isNotBlank()) { "Publication ID must not be blank" }
            val docRef = firestore.collection("users").document(uid).collection("publications").document(publicationId)
            docPath = docRef.path
            val payload = mutableMapOf<String, Any>(
                "viewsCount" to viewsCount.coerceAtLeast(0L),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (privacyStatus != null) {
                payload["privacyStatus"] = privacyStatus.name
            }
            docRef.update(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }
}
