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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.Firebase
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class ClipPulseRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun observeProjects(userId: String): Flow<List<ClipProject>> =
        db.collection("users").document(userId).collection("projects")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    ClipProject::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }

    fun observeClips(userId: String): Flow<List<ViralClip>> =
        db.collection("users").document(userId).collection("clips")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    ViralClip::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }

    fun observeCampaigns(userId: String): Flow<List<ClippingCampaign>> =
        db.collection("users").document(userId).collection("campaigns")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    ClippingCampaign::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }

    fun observePublications(userId: String): Flow<List<PublishedShort>> =
        db.collection("users").document(userId).collection("publications")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    PublishedShort::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }

    suspend fun saveProject(project: ClipProject): Result<String> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauth"}/projects"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("projects").document()
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
        var docPath = "users/${auth.currentUser?.uid ?: "unauth"}/clips"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("clips").document()
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
        var docPath = "users/${auth.currentUser?.uid ?: "unauth"}/clips/$clipId"
        return try {
            val uid = requireUserId()
            require(clipId.isNotBlank()) { "Clip ID must not be blank" }
            val safeStart = startSec.coerceAtLeast(0L)
            val safeEnd = endSec.coerceAtLeast(safeStart + 1L)
            val docRef = db.collection("users").document(uid).collection("clips").document(clipId)
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
        var docPath = "users/${auth.currentUser?.uid ?: "unauth"}/clips/$clipId"
        return try {
            val uid = requireUserId()
            require(clipId.isNotBlank()) { "Clip ID must not be blank" }
            val docRef = db.collection("users").document(uid).collection("clips").document(clipId)
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
        var docPath = "users/${auth.currentUser?.uid ?: "unauth"}/clips/$clipId"
        return try {
            val uid = requireUserId()
            require(clipId.isNotBlank()) { "Clip ID must not be blank" }
            val docRef = db.collection("users").document(uid).collection("clips").document(clipId)
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
        var docPath = "users/${auth.currentUser?.uid ?: "unauth"}/campaigns"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("campaigns").document()
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
        var docPath = "users/${auth.currentUser?.uid ?: "unauth"}/campaigns/$campaignId"
        return try {
            val uid = requireUserId()
            require(campaignId.isNotBlank()) { "Campaign ID must not be blank" }
            val docRef = db.collection("users").document(uid).collection("campaigns").document(campaignId)
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
        var docPath = "users/${auth.currentUser?.uid ?: "unauth"}/campaigns/$campaignId"
        return try {
            val uid = requireUserId()
            require(campaignId.isNotBlank()) { "Campaign ID must not be blank" }
            val docRef = db.collection("users").document(uid).collection("campaigns").document(campaignId)
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
        var docPath = "users/${auth.currentUser?.uid ?: "unauth"}/publications"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("publications").document()
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
        var docPath = "users/${auth.currentUser?.uid ?: "unauth"}/publications/$publicationId"
        return try {
            val uid = requireUserId()
            require(publicationId.isNotBlank()) { "Publication ID must not be blank" }
            val docRef = db.collection("users").document(uid).collection("publications").document(publicationId)
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
