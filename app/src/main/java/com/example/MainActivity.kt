package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.auth.authStateFlow
import com.example.auth.signOut
import com.example.data.ai.GeminiStudioService
import com.example.data.firebase.ClipPulseRepository
import com.example.data.firebase.configureAppCheck
import com.example.engine.AudioRecorderHelper
import com.example.engine.Media3RenderEngine
import com.example.engine.StreamResolver
import com.example.ui.components.ViralCoachSheet
import com.example.ui.screens.AnalyzeScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.CampaignsScreen
import com.example.ui.screens.InspectorScreen
import com.example.ui.screens.PublishScreen
import com.example.ui.screens.StudioScreen
import com.example.ui.theme.BroadcastSlate
import com.example.ui.theme.ClipPulseTheme
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.ElectricVolt
import com.example.ui.theme.ElevatedCard
import com.example.ui.theme.ObsidianInk
import com.example.ui.theme.SteelOutline
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.ClipPulseViewModel
import com.example.ui.viewmodel.DataUiState
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.auth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

@Serializable data object AnalyzeRoute
@Serializable data object StudioRoute
@Serializable data object PublishRoute
@Serializable data object CampaignsRoute
@Serializable data object InspectorRoute

private data class StudioDestination(
    val route: Any,
    val label: String,
    val icon: ImageVector,
    val testTag: String
)

private val StudioDestinations = listOf(
    StudioDestination(AnalyzeRoute, "Analyze", Icons.Default.Bolt, "nav_analyze"),
    StudioDestination(StudioRoute, "Studio", Icons.Default.MovieCreation, "nav_studio"),
    StudioDestination(PublishRoute, "Publish", Icons.Default.CloudUpload, "nav_publish"),
    StudioDestination(CampaignsRoute, "Campaigns", Icons.Default.AttachMoney, "nav_campaigns"),
    StudioDestination(InspectorRoute, "Pipeline", Icons.Default.Terminal, "nav_inspector")
)

class MainActivity : ComponentActivity() {

    private val sharedUrlFlow = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)
        extractSharedUrl(intent)?.let { sharedUrlFlow.value = it }

        enableEdgeToEdge()
        setContent {
            ClipPulseTheme {
                AppNavigation(
                    sharedUrlFlow = sharedUrlFlow,
                    onConsumeSharedUrl = { sharedUrlFlow.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractSharedUrl(intent)?.let { sharedUrlFlow.value = it }
    }

    private fun extractSharedUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return null
        val raw = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        val urlMatch = Regex("https?://\\S+").find(raw)
        return (urlMatch?.value ?: raw).trim().takeIf { it.isNotBlank() }
    }
}

@Composable
private fun AppNavigation(
    sharedUrlFlow: MutableStateFlow<String?>,
    onConsumeSharedUrl: () -> Unit
) {
    val context = LocalContext.current
    val firebaseReady = remember(context) {
        FirebaseApp.getApps(context).isNotEmpty() ||
            runCatching { FirebaseApp.initializeApp(context) != null }.getOrDefault(false)
    }
    var offlineStudioMode by remember { mutableStateOf(!firebaseReady) }

    if (!firebaseReady) {
        if (!offlineStudioMode) {
            AuthScreen(
                firebaseReady = false,
                onContinueOffline = { offlineStudioMode = true },
                onAuthSuccess = {}
            )
            return
        }
        key("offline_studio") {
            AuthenticatedStudioShell(
                uid = "local_studio",
                userLabel = "On-Device Studio",
                isOfflineMode = true,
                onExitOfflineMode = { offlineStudioMode = false },
                sharedUrlFlow = sharedUrlFlow,
                onConsumeSharedUrl = onConsumeSharedUrl
            )
        }
        return
    }

    val currentUser by Firebase.auth.authStateFlow()
        .collectAsStateWithLifecycle(initialValue = Firebase.auth.currentUser)

    val user = currentUser
    if (user == null) {
        AuthScreen(
            firebaseReady = true,
            onContinueOffline = {},
            onAuthSuccess = {}
        )
        return
    }

    key(user.uid) {
        AuthenticatedStudioShell(
            uid = user.uid,
            userLabel = user.displayName ?: user.email ?: "Creator",
            isOfflineMode = false,
            onExitOfflineMode = {},
            sharedUrlFlow = sharedUrlFlow,
            onConsumeSharedUrl = onConsumeSharedUrl
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthenticatedStudioShell(
    uid: String,
    userLabel: String,
    isOfflineMode: Boolean,
    onExitOfflineMode: () -> Unit,
    sharedUrlFlow: MutableStateFlow<String?>,
    onConsumeSharedUrl: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val viewModel: ClipPulseViewModel = viewModel(
        key = uid,
        factory = viewModelFactory {
            initializer {
                ClipPulseViewModel(
                    repository = ClipPulseRepository.create(appContext),
                    currentUserId = uid,
                    streamResolver = StreamResolver(appContext),
                    renderEngine = Media3RenderEngine(appContext),
                    geminiService = GeminiStudioService(),
                    audioRecorder = AudioRecorderHelper()
                )
            }
        }
    )

    val analyzerState by viewModel.analyzer.collectAsStateWithLifecycle()
    val pipelineState by viewModel.pipeline.collectAsStateWithLifecycle()
    val aiState by viewModel.aiStudio.collectAsStateWithLifecycle()
    val publishState by viewModel.publish.collectAsStateWithLifecycle()
    val autoPilotState by viewModel.autoPilot.collectAsStateWithLifecycle()
    val clipsState by viewModel.clipsState.collectAsStateWithLifecycle()
    val campaignsState by viewModel.campaignsState.collectAsStateWithLifecycle()
    val publicationsState by viewModel.publicationsState.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()
    val sharedUrl by sharedUrlFlow.collectAsStateWithLifecycle()

    val cloudClips = (clipsState as? DataUiState.Success)?.data.orEmpty()
    val campaigns = (campaignsState as? DataUiState.Success)?.data.orEmpty()
    val selectedClip = viewModel.currentSelectedClip(cloudClips)
    val activeCampaign = campaigns.firstOrNull { it.id == publishState.activeCampaignId }
        ?: campaigns.firstOrNull { it.active }
    val complianceChecks = viewModel.evaluateCompliance(selectedClip, activeCampaign)

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val snackbarHostState = remember { SnackbarHostState() }
    var showCoachSheet by remember { mutableStateOf(false) }

    fun navigateTo(route: Any) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(sharedUrl) {
        val url = sharedUrl
        if (!url.isNullOrBlank()) {
            viewModel.onSharedUrlReceived(url)
            onConsumeSharedUrl()
            navigateTo(AnalyzeRoute)
        }
    }

    LaunchedEffect(selectedClip?.id) {
        viewModel.ensureSelectedClipSynced(selectedClip)
    }

    LaunchedEffect(statusBanner) {
        val msg = statusBanner
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioObsidian)
    ) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            containerColor = StudioObsidian,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = BroadcastSlate,
                        titleContentColor = TextPrimary
                    ),
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_clippulse_logo_1795006679042),
                                contentDescription = "ClipPulse AI icon",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .border(1.dp, ElectricVolt.copy(alpha = 0.55f), RoundedCornerShape(9.dp))
                            )
                            Column {
                                Text(
                                    text = "CLIPPULSE AI",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = ElectricVolt
                                )
                                Text(
                                    text = userLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    actions = {
                        FilledTonalIconButton(
                            onClick = { showCoachSheet = true },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = ElectricVolt.copy(alpha = 0.16f),
                                contentColor = ElectricVolt
                            ),
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("open_coach_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "Open Viral Growth Coach"
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                if (isOfflineMode) {
                                    onExitOfflineMode()
                                } else {
                                    signOut(credentialManager, scope)
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("sign_out_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Logout,
                                contentDescription = "Sign out of Google account",
                                tint = CyberCyan
                            )
                        }
                    }
                )
            },
            bottomBar = {
                if (!useRail) {
                    NavigationBar(
                        containerColor = BroadcastSlate,
                        tonalElevation = 0.dp
                    ) {
                        StudioDestinations.forEach { dest ->
                            val selected = currentDestination?.hasRoute(dest.route::class) == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTo(dest.route) },
                                icon = { Icon(dest.icon, contentDescription = dest.label) },
                                label = {
                                    Text(
                                        text = dest.label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = ObsidianInk,
                                    selectedTextColor = ElectricVolt,
                                    indicatorColor = ElectricVolt,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(
                        containerColor = BroadcastSlate,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        StudioDestinations.forEach { dest ->
                            val selected = currentDestination?.hasRoute(dest.route::class) == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTo(dest.route) },
                                icon = { Icon(dest.icon, contentDescription = dest.label) },
                                label = { Text(dest.label, maxLines = 1) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = ObsidianInk,
                                    selectedTextColor = ElectricVolt,
                                    indicatorColor = ElectricVolt,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    NavHost(
                        navController = navController,
                        startDestination = AnalyzeRoute,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        composable<AnalyzeRoute> {
                            AnalyzeScreen(
                                state = analyzerState,
                                cloudClips = cloudClips,
                                aiState = aiState,
                                autoPilotState = autoPilotState,
                                captionHighlight = pipelineState.captionHighlight,
                                youtubeAccessToken = publishState.youtubeAccessToken,
                                onUrlChange = viewModel::updateSourceUrl,
                                onModeChange = viewModel::updateIngestMode,
                                onAnalyzeClick = viewModel::analyzeCurrentSource,
                                onToggleLiveMonitor = viewModel::startOrStopLiveMonitor,
                                onLocalVideoPicked = viewModel::onLocalVideoPicked,
                                onUpdateAutoPilotCount = { count ->
                                    viewModel.updateAutoPilotConfig(clipsTargetCount = count)
                                },
                                onToggleAutoUpload = { enabled ->
                                    viewModel.updateAutoPilotConfig(autoUploadYouTube = enabled)
                                },
                                onUpdateCaptionHighlight = { hl ->
                                    viewModel.updateCaptionHighlight(hl, selectedClip)
                                },
                                onSetYouTubeToken = viewModel::setYouTubeAccessToken,
                                onRunAutoPilot = {
                                    viewModel.runFullAutoPilot(activeCampaign)
                                },
                                onUploadStagedClips = {
                                    viewModel.uploadStagedAutoPilotClips(activeCampaign)
                                },
                                onUpdateBucketSec = { v ->
                                    viewModel.updateDetectionConfig { it.copy(bucketSec = v) }
                                },
                                onUpdateBeforeSec = { v ->
                                    viewModel.updateDetectionConfig { it.copy(beforeSec = v) }
                                },
                                onUpdateAfterSec = { v ->
                                    viewModel.updateDetectionConfig { it.copy(afterSec = v) }
                                },
                                onUpdateGapSec = { v ->
                                    viewModel.updateDetectionConfig { it.copy(gapSec = v) }
                                },
                                onUpdateTopCount = { v ->
                                    viewModel.updateDetectionConfig { it.copy(topCount = v) }
                                },
                                onSelectClip = viewModel::selectClip,
                                onOpenClipInStudio = { clip ->
                                    viewModel.selectClip(clip)
                                    navigateTo(StudioRoute)
                                },
                                onRunDeepRetentionAudit = viewModel::runDeepRetentionAudit
                            )
                        }

                        composable<StudioRoute> {
                            val matchingSliceUri = if (
                                selectedClip != null &&
                                pipelineState.downloadedSegmentClipId == selectedClip.id
                            ) {
                                pipelineState.downloadedSegmentFile?.let { android.net.Uri.fromFile(java.io.File(it)) }
                            } else null

                            val playbackUri = selectedClip?.localOutputPath?.let { path ->
                                java.io.File(path).takeIf { it.exists() }?.let { android.net.Uri.fromFile(it) }
                            }
                                ?: analyzerState.localVideoUri
                                ?: analyzerState.resolvedInfo?.directPlayableUri?.let { android.net.Uri.parse(it) }
                                ?: matchingSliceUri

                            StudioScreen(
                                clip = selectedClip,
                                playbackUri = playbackUri,
                                pipeline = pipelineState,
                                aiState = aiState,
                                onUpdateClip = { clip, layout, hook, style, credit, chatOverlay, transcript ->
                                    viewModel.updateClipInEditor(
                                        clip = clip,
                                        layoutPreset = layout,
                                        hookText = hook,
                                        hookStyle = style,
                                        creditWatermark = credit,
                                        chatOverlayEnabled = chatOverlay,
                                        transcript = transcript
                                    )
                                },
                                onUpdateCaptionHighlight = { color ->
                                    viewModel.updateCaptionHighlight(color, selectedClip)
                                },
                                onAttachLocalVideo = viewModel::onLocalVideoPicked,
                                onExportClip = viewModel::exportSelectedClipOnDevice,
                                onGoToPublish = { navigateTo(PublishRoute) },
                                onGenerateFastHooks = viewModel::generateFastHooks,
                                onStartMicRecording = viewModel::startMicRecording,
                                onStopMicRecording = viewModel::stopMicRecording,
                                onAnalyzeClipVideo = viewModel::analyzeClipVideoBytes,
                                onAnalyzeImage = viewModel::analyzeThumbnailImage,
                                onUpdateImageGenOptions = { model, aspect, size ->
                                    viewModel.updateImageGenOptions(model, aspect, size)
                                },
                                onGenerateCoverImage = viewModel::generateCoverImage
                            )
                        }

                        composable<PublishRoute> {
                            PublishScreen(
                                clip = selectedClip,
                                publishState = publishState,
                                aiState = aiState,
                                campaigns = campaigns,
                                complianceChecks = complianceChecks,
                                onGenerateSeo = viewModel::generateViralSeo,
                                onUpdatePublish = viewModel::updatePublishField,
                                onSetYouTubeToken = viewModel::setYouTubeAccessToken,
                                onUploadYouTube = viewModel::uploadClipToYouTube,
                                onLogExternalShare = viewModel::logExternalSharePublication
                            )
                        }

                        composable<CampaignsRoute> {
                            CampaignsScreen(
                                campaignsState = campaignsState,
                                publicationsState = publicationsState,
                                onCreateCampaign = viewModel::createCampaign,
                                onToggleCampaign = viewModel::toggleCampaign,
                                onDeleteCampaign = viewModel::deleteCampaign,
                                onUpdatePublicationViews = viewModel::updatePublicationViews
                            )
                        }

                        composable<InspectorRoute> {
                            InspectorScreen(
                                clip = selectedClip,
                                pipeline = pipelineState
                            )
                        }
                    }
                }
            }
        }

        if (showCoachSheet) {
            ViralCoachSheet(
                aiState = aiState,
                onSelectMode = viewModel::setCoachMode,
                onSendMessage = viewModel::sendCoachMessage,
                onStartVoice = viewModel::startMicRecording,
                onStopVoice = viewModel::stopMicRecording,
                onDismiss = { showCoachSheet = false }
            )
        }
    }
}
