package org.jianyu.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jianyu.core.model.Child
import org.jianyu.core.data.ActiveRecorderSettings
import org.jianyu.core.data.AiProviderSettings
import org.jianyu.core.data.AiProviderCapabilityProbe
import org.jianyu.core.data.AiCapabilityResult
import org.jianyu.core.data.AiCapabilityStatus
import org.jianyu.core.data.WorldBriefProviderSettings
import org.jianyu.core.data.GraduationAuthorization
import org.jianyu.core.data.DocumentTreeSyncProvider
import org.jianyu.core.data.FolderSyncSettings
import org.jianyu.core.model.CostBand
import org.jianyu.core.model.ContextStream
import org.jianyu.core.model.EnergyBand
import org.jianyu.core.model.Evidence
import org.jianyu.core.model.EvidenceKind
import org.jianyu.core.model.EvidenceVisibility
import org.jianyu.core.model.FamilyChoice
import org.jianyu.core.model.FamilyConstraints
import org.jianyu.core.model.FamilyEvent
import org.jianyu.core.model.FamilyGoals
import org.jianyu.core.model.FamilyMember
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.Household
import org.jianyu.core.model.LifecycleStage
import org.jianyu.core.model.MemberRole
import org.jianyu.core.model.Opportunity
import org.jianyu.core.model.OpportunitySet
import org.jianyu.core.domain.lifecycleAuthority
import org.jianyu.core.domain.lifecycleEvidenceVisibility
import org.jianyu.core.domain.lifecycleStage
import org.jianyu.core.domain.ageAt
import org.jianyu.core.domain.currentInterestHasNonRefusalClue
import org.jianyu.core.domain.normalizeAssessmentEntry
import org.jianyu.core.domain.canRecordScoredAssessment
import org.jianyu.core.domain.OpportunityDiscoveryRequest
import org.jianyu.core.domain.OpportunitySource
import org.jianyu.core.domain.LLMProvider
import org.jianyu.core.domain.WorldBriefProvider
import org.jianyu.core.domain.PublicWorldQuery
import org.jianyu.core.domain.DiscoverySourceIssue
import org.jianyu.core.domain.deleteEvidenceWithTombstone
import org.jianyu.core.domain.deleteChoiceWithTombstones
import org.jianyu.core.domain.projectRecentRecommendationContext
import org.jianyu.core.domain.countRecentShareableSelections
import org.jianyu.core.domain.FeedbackProvenance
import org.jianyu.core.domain.appendChoiceFeedback
import org.jianyu.core.domain.PublicAiProbeCapability
import org.jianyu.core.domain.toEventPayload
import org.jianyu.core.domain.GraduationRetentionAuthorization
import org.jianyu.core.domain.GraduationRetentionMode
import org.jianyu.core.domain.applyGraduationRetention
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.InputStream

enum class AppRoute { TODAY, CHILDREN, TIMELINE, SETTINGS }

enum class FamilyFormKind { CHILD, CAREGIVER, ASSESSMENT }

enum class SourceFormKind { AI, WORLD }

data class PortableImportPreview(
    val currentFamilyName: String,
    val incomingFamilyName: String,
)

private class PortableImportStaleException : IllegalStateException("本机资料已变化，请重新检查恢复包")

/** Unsent, in-memory UI draft. It never enters Family State, exports, sync, or provider calls by itself. */
data class DiscoveryComposerDraft(
    val childId: String,
    val stage: LifecycleStage,
    val expression: String = "",
    val caregiverGoal: String = "",
    val sharedGoal: String = "",
    val schoolWindow: String = "",
    val lifeContext: String = "",
    val region: String = "",
    val timeMinutes: Float = 90f,
    val travelMinutes: Float = 30f,
    val cost: CostBand = CostBand.LOW,
    val energy: EnergyBand = EnergyBand.MEDIUM,
    val persistContext: Boolean,
    val privateContext: Boolean = false,
)

data class MainUiState(
    val loading: Boolean = true,
    val vaultOpenFailed: Boolean = false,
    val onboardingSaving: Boolean = false,
    val family: FamilyState? = null,
    val route: AppRoute = AppRoute.TODAY,
    val selectedChildId: String? = null,
    val composerDraft: DiscoveryComposerDraft? = null,
    val composerDrafts: Map<String, DiscoveryComposerDraft> = emptyMap(),
    val activeMemberId: String? = null,
    val opportunities: OpportunitySet? = null,
    val sourceEventId: String? = null,
    val aiConfigured: Boolean = false,
    val aiProviderName: String? = null,
    val aiBaseUrl: String? = null,
    val aiModel: String? = null,
    val aiCapabilityChecking: Boolean = false,
    val aiCapabilityResult: AiCapabilityResult? = null,
    val worldBriefConfigured: Boolean = false,
    val worldBriefProviderName: String? = null,
    val worldBriefEndpoint: String? = null,
    val folderSyncConfigured: Boolean = false,
    val folderSyncName: String? = null,
    val folderSyncSettingsUnreadable: Boolean = false,
    val syncingFolder: Boolean = false,
    val discovering: Boolean = false,
    val choiceSaving: Boolean = false,
    val feedbackSavingChoiceId: String? = null,
    val choiceDeletingId: String? = null,
    val familyFormSaving: FamilyFormKind? = null,
    val familyFormSaveRevision: Long = 0,
    val lastSavedFamilyForm: FamilyFormKind? = null,
    val sourceFormSaving: SourceFormKind? = null,
    val sourceFormError: SourceFormKind? = null,
    val sourceFormSaveRevision: Long = 0,
    val lastSavedSourceForm: SourceFormKind? = null,
    val discoveryMode: String? = null,
    val discoverySourceIssues: List<DiscoverySourceIssue> = emptyList(),
    val disclosureIncluded: List<String> = emptyList(),
    val disclosureExcluded: List<String> = emptyList(),
    val contextPersisted: Boolean? = null,
    val contextRestricted: Boolean? = null,
    val choiceAcknowledgementId: String? = null,
    val incomingShareText: String? = null,
    val portableRecoveryCode: String? = null,
    val portableImportChecking: Boolean = false,
    val portableImportSaving: Boolean = false,
    val portableImportPreview: PortableImportPreview? = null,
    val portableImportError: String? = null,
    val graduationRecoveryCode: String? = null,
    val graduationSubjectName: String? = null,
    val notice: String? = null,
    val brandName: String = "",
    val brandTagline: String = "",
    val brandHero: String = "",
    val brandMission: String = "",
    val error: String? = null,
    val disclosureSaveRisk: Boolean = false,
)

internal fun MainUiState.withComposerDraft(
    childId: String,
    stage: LifecycleStage,
    persistByDefault: Boolean,
    change: (DiscoveryComposerDraft) -> DiscoveryComposerDraft,
): MainUiState {
    if (selectedChildId != childId) return this
    val existing = (composerDraft?.takeIf { it.childId == childId } ?: composerDrafts[childId])
        ?.takeIf { it.stage == stage }
        ?: DiscoveryComposerDraft(childId, stage, persistContext = persistByDefault)
    val updated = change(existing)
    require(updated.childId == childId && updated.stage == stage) { "Draft subject and stage cannot change" }
    val bounded = updated.copy(
        expression = updated.expression.take(800),
        caregiverGoal = updated.caregiverGoal.take(300),
        sharedGoal = updated.sharedGoal.take(300),
        schoolWindow = updated.schoolWindow.take(500),
        lifeContext = updated.lifeContext.take(500),
        region = updated.region.take(80),
    )
    return copy(composerDraft = bounded, composerDrafts = composerDrafts + (childId to bounded))
}

internal fun MainUiState.withSelectedChild(childId: String): MainUiState {
    if (discovering || choiceSaving || feedbackSavingChoiceId != null || choiceDeletingId != null || disclosureSaveRisk || childId == selectedChildId) return this
    val nextChild = family?.children?.firstOrNull { it.id == childId } ?: return this
    val currentId = selectedChildId
    val unsentDrafts = if (currentId != null && opportunities != null && discoverySourceIssues.isEmpty()) {
        composerDrafts - currentId
    } else {
        composerDraft?.takeIf { it.childId == currentId }?.let { composerDrafts + (it.childId to it) }
            ?: composerDrafts
    }
    val nextDraft = unsentDrafts[childId]?.takeIf { it.stage == lifecycleStage(nextChild) }
    return copy(
        selectedChildId = childId,
        composerDraft = nextDraft,
        composerDrafts = if (nextDraft == null) unsentDrafts - childId else unsentDrafts,
        opportunities = null,
        sourceEventId = null,
        discoveryMode = null,
        discoverySourceIssues = emptyList(),
        disclosureIncluded = emptyList(),
        disclosureExcluded = emptyList(),
        contextPersisted = null,
        contextRestricted = null,
        choiceAcknowledgementId = null,
        error = null,
        disclosureSaveRisk = false,
    )
}

internal fun MainUiState.afterDiscoveryReturn(keepDraft: Boolean): MainUiState {
    val retainedDraft = composerDraft?.takeIf { keepDraft && discoverySourceIssues.isNotEmpty() }
    val updatedDrafts = selectedChildId?.let { childId ->
        if (retainedDraft == null) composerDrafts - childId else composerDrafts + (childId to retainedDraft)
    } ?: composerDrafts
    return copy(
        opportunities = null,
        composerDraft = retainedDraft,
        composerDrafts = updatedDrafts,
        sourceEventId = null,
        discoveryMode = null,
        discoverySourceIssues = emptyList(),
        disclosureIncluded = emptyList(),
        disclosureExcluded = emptyList(),
        contextPersisted = null,
        contextRestricted = null,
        choiceAcknowledgementId = null,
        error = null,
        disclosureSaveRisk = false,
    )
}

private data class GraduationUiExport(
    val subjectName: String,
    val recoveryCode: String,
    val stateWithReceipt: FamilyState,
    val receiptSaved: Boolean,
)

/** Production sources are composed at the App boundary; tests may replace only the public interfaces. */
internal data class FormalDiscoverySources(
    val ai: LLMProvider,
    val others: List<OpportunitySource>,
    val worldBrief: WorldBriefProvider?,
)

class MainViewModel private constructor(
    application: Application,
    private val formalSourcesOverride: FormalDiscoverySources?,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, null)
    internal constructor(application: JianyuApplication, formalSources: FormalDiscoverySources) :
        this(application as Application, formalSources)

    private val choiceSubmissionInFlight = AtomicBoolean(false)
    private val discoverySubmissionInFlight = AtomicBoolean(false)
    private val onboardingSubmissionInFlight = AtomicBoolean(false)
    private val initialLoadInFlight = AtomicBoolean(false)
    private val feedbackSubmissionInFlight = AtomicBoolean(false)
    private val choiceDeletionInFlight = AtomicBoolean(false)
    private val familyFormSubmissionInFlight = AtomicBoolean(false)
    private val sourceFormSubmissionInFlight = AtomicBoolean(false)
    private val portableImportSubmissionInFlight = AtomicBoolean(false)
    private val portableImportGeneration = AtomicLong(0)
    private val portableImportLock = Any()
    private var pendingPortableImport: Pair<FamilyState, FamilyState>? = null
    private val vaultOperationGate = VaultOperationGate()
    private val container = application as JianyuApplication
    private val repository = container.vaultRepository
    private val engine = container.opportunityEngine
    private val formalSources by lazy {
        formalSourcesOverride ?: FormalDiscoverySources(
            ai = container.aiOpportunitySource,
            others = listOf(container.packOpportunitySource, container.worldBriefOpportunitySource),
            worldBrief = container.worldBriefProvider,
        )
    }
    private val mutableState = MutableStateFlow(
        MainUiState(
            brandName = container.brandConfig.displayName,
            brandTagline = container.brandConfig.tagline,
            brandHero = container.brandConfig.hero,
            brandMission = container.brandConfig.mission,
        ),
    )
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()

    private fun launchVaultOperation(onFinished: () -> Unit = {}, block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                vaultOperationGate.run(block)
            } finally {
                onFinished()
            }
        }
    }

    init { loadInitialState() }

    fun retryOpenVault() {
        if (!mutableState.value.loading && mutableState.value.vaultOpenFailed && mutableState.value.family == null) {
            loadInitialState()
        }
    }

    private fun loadInitialState() {
        if (!initialLoadInFlight.compareAndSet(false, true)) return
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = runCatching { repository.load() }
                initialLoadInFlight.set(false)
                result
                .onSuccess { family ->
                    val aiSettings = runCatching { container.aiSettingsStore.load() }.getOrNull()
                    val worldBriefSettings = runCatching { container.worldBriefSettingsStore.load() }.getOrNull()
                    val folderSyncSettingsResult = runCatching { container.folderSyncSettingsStore.load() }
                    val folderSyncSettings = folderSyncSettingsResult.getOrNull()
                    val preferredRecorderId = runCatching {
                        container.activeRecorderSettingsStore.load()?.memberId
                    }.getOrNull()
                    mutableState.update { current -> MainUiState(
                        loading = false,
                        vaultOpenFailed = false,
                        family = family,
                        route = current.route,
                        selectedChildId = family?.children?.firstOrNull()?.id,
                        activeMemberId = family?.let {
                            runCatching { it.resolveCaregiverAuthor(preferredRecorderId).id }.getOrNull()
                        },
                        aiConfigured = aiSettings != null,
                        aiProviderName = aiSettings?.providerName,
                        aiBaseUrl = aiSettings?.baseUrl,
                        aiModel = aiSettings?.model,
                        worldBriefConfigured = worldBriefSettings != null,
                        worldBriefProviderName = worldBriefSettings?.providerName,
                        worldBriefEndpoint = worldBriefSettings?.endpoint,
                        folderSyncConfigured = folderSyncSettings != null,
                        folderSyncName = folderSyncSettings?.displayName,
                        folderSyncSettingsUnreadable = folderSyncSettingsResult.isFailure,
                        brandName = container.brandConfig.displayName,
                        brandTagline = container.brandConfig.tagline,
                        brandHero = container.brandConfig.hero,
                        brandMission = container.brandConfig.mission,
                        incomingShareText = current.incomingShareText,
                    ) }
                }
                .onFailure { error ->
                    mutableState.update {
                        current -> current.copy(
                            loading = false,
                            vaultOpenFailed = true,
                            error = error.asUserFacingMessage(ErrorContext.STARTUP),
                        )
                    }
                }
            } finally {
                initialLoadInFlight.set(false)
            }
        }
    }

    fun navigate(route: AppRoute) {
        if (mutableState.value.portableImportChecking || mutableState.value.portableImportSaving) return
        if (route != AppRoute.SETTINGS) cancelPortableImport()
        mutableState.update {
            if (it.feedbackSavingChoiceId != null || it.choiceDeletingId != null || it.portableImportChecking || it.portableImportSaving) {
                it
            } else {
                it.copy(route = route)
            }
        }
    }

    fun selectActiveMember(memberId: String) {
        val family = mutableState.value.family ?: return
        val member = family.members.firstOrNull { it.id == memberId && it.canAuthorCaregiverActions() } ?: return
        mutableState.update { current ->
            current.copy(
                activeMemberId = member.id,
                notice = "之后的新记录将署名为 ${member.displayName}。这不是共享设备上的身份认证。",
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                container.activeRecorderSettingsStore.save(ActiveRecorderSettings(member.id))
            }.onFailure(::report)
        }
    }

    fun receiveIncomingShare(text: String) {
        val draft = text.trim().take(800)
        if (draft.isEmpty()) return
        mutableState.update {
            it.copy(
                route = AppRoute.TODAY,
                incomingShareText = draft,
                error = null,
            )
        }
    }

    fun consumeIncomingShare() = mutableState.update { it.copy(incomingShareText = null) }

    fun updateComposerDraft(
        childId: String,
        stage: LifecycleStage,
        persistByDefault: Boolean,
        change: (DiscoveryComposerDraft) -> DiscoveryComposerDraft,
    ) = mutableState.update { it.withComposerDraft(childId, stage, persistByDefault, change) }

    fun selectChild(childId: String) {
        if (discoverySubmissionInFlight.get() || choiceSubmissionInFlight.get()) return
        mutableState.update { it.withSelectedChild(childId) }
    }

    fun dismissDisclosureSaveRisk() = mutableState.update {
        if (it.disclosureSaveRisk) it.copy(error = null, disclosureSaveRisk = false) else it
    }

    fun startNewDiscovery(keepDraft: Boolean = false) = mutableState.update {
        it.afterDiscoveryReturn(keepDraft)
    }

    fun saveAiProvider(providerName: String, baseUrl: String, model: String, apiKey: String): Boolean {
        val settings = AiProviderSettings(providerName.trim(), baseUrl.trim(), model.trim(), apiKey.trim())
        return saveSourceForm(
            kind = SourceFormKind.AI,
            write = { container.aiSettingsStore.save(settings) },
            afterSave = { state -> state.copy(
                aiConfigured = true,
                aiProviderName = settings.providerName,
                aiBaseUrl = settings.baseUrl,
                aiModel = settings.model,
                aiCapabilityResult = null,
                aiCapabilityChecking = false,
            ) },
        )
    }

    fun clearSourceFormError(kind: SourceFormKind) = mutableState.update {
        if (it.sourceFormError == kind) it.copy(sourceFormError = null, error = null) else it
    }

    fun checkAiCapability() {
        if (!mutableState.value.aiConfigured || mutableState.value.aiCapabilityChecking) return
        mutableState.update { it.copy(aiCapabilityChecking = true, aiCapabilityResult = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val settings = runCatching { container.aiSettingsStore.load() }.getOrNull()
            if (settings == null) {
                mutableState.update { it.copy(
                    aiCapabilityChecking = false,
                    aiCapabilityResult = AiCapabilityResult(AiCapabilityStatus.SETTINGS_UNAVAILABLE),
                ) }
                return@launch
            }
            val result = AiProviderCapabilityProbe().check(settings, PublicAiProbeCapability.issueForExplicitCheck())
            val stillCurrent = runCatching { container.aiSettingsStore.load() }.getOrNull() == settings
            mutableState.update { current ->
                if (!stillCurrent) current.copy(aiCapabilityChecking = false)
                else current.copy(aiCapabilityChecking = false, aiCapabilityResult = result)
            }
        }
    }

    fun removeAiProvider() {
        if (sourceFormSubmissionInFlight.get()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.aiSettingsStore.erase() }
                .onSuccess {
                    mutableState.update {
                        it.copy(
                            aiConfigured = false,
                            aiProviderName = null,
                            aiBaseUrl = null,
                            aiModel = null,
                            aiCapabilityChecking = false,
                            aiCapabilityResult = null,
                            opportunities = null,
                            error = null,
                        )
                    }
                }
                .onFailure(::report)
        }
    }

    fun saveWorldBriefProvider(providerName: String, endpoint: String, apiKey: String): Boolean {
        val normalizedName = providerName.trim()
        val normalizedEndpoint = endpoint.trim()
        val normalizedKey = apiKey.trim()
        return saveSourceForm(
            kind = SourceFormKind.WORLD,
            write = {
                val existing = container.worldBriefSettingsStore.load()
                container.worldBriefSettingsStore.save(
                    WorldBriefProviderSettings(
                        normalizedName,
                        normalizedEndpoint,
                        normalizedKey.ifEmpty { existing?.apiKey.orEmpty() },
                    ),
                )
            },
            afterSave = { state -> state.copy(
                worldBriefConfigured = true,
                worldBriefProviderName = normalizedName,
                worldBriefEndpoint = normalizedEndpoint,
                notice = "世界信息的连接信息已保存在本机。正式寻找入口时才会尝试访问；地区如有填写，可能原样发送，请勿填个人信息。",
            ) },
        )
    }

    fun removeWorldBriefProvider() {
        if (sourceFormSubmissionInFlight.get()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.worldBriefSettingsStore.erase() }
                .onSuccess {
                    mutableState.update {
                        it.copy(
                            worldBriefConfigured = false,
                            worldBriefProviderName = null,
                            worldBriefEndpoint = null,
                            notice = "世界信息服务已断开",
                            error = null,
                        )
                    }
                }
                .onFailure(::report)
        }
    }

    fun connectSyncFolder(treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            var permissionTaken = false
            runCatching {
                require(!mutableState.value.folderSyncSettingsUnreadable) {
                    "先重新读取原有的加密文件夹连接信息"
                }
                resolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                permissionTaken = true
                val documentId = DocumentsContract.getTreeDocumentId(treeUri)
                val displayName = documentId.substringAfterLast('/').substringAfterLast(':')
                    .trim().take(120).ifBlank { "已选择的文件夹" }
                val settings = container.folderSyncSettingsStore.load()?.copy(
                    treeUri = treeUri.toString(),
                    displayName = displayName,
                )?.also(FolderSyncSettings::validate)
                    ?: FolderSyncSettings.create(treeUri.toString(), displayName)
                DocumentTreeSyncProvider(resolver, treeUri).validateAccess()
                container.folderSyncSettingsStore.save(settings)
                settings
            }.onSuccess { settings ->
                mutableState.update {
                    it.copy(
                        folderSyncConfigured = true,
                        folderSyncName = settings.displayName,
                        folderSyncSettingsUnreadable = false,
                        notice = "已选择加密文件夹；尚未传输家庭资料。",
                        error = null,
                    )
                }
            }.onFailure { error ->
                if (permissionTaken) runCatching { resolver.releasePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                ) }
                report(error)
            }
        }
    }

    fun retryOpenSyncFolderSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { container.folderSyncSettingsStore.load() }
            val settings = result.getOrNull()
            mutableState.update {
                it.copy(
                    folderSyncConfigured = settings != null,
                    folderSyncName = settings?.displayName,
                    folderSyncSettingsUnreadable = result.isFailure,
                    notice = if (result.isSuccess) "已重新检查文件夹连接信息。" else null,
                    error = null,
                )
            }
        }
    }

    fun synchronizeFolderNow() {
        launchVaultOperation {
            val family = mutableState.value.family ?: return@launchVaultOperation
            mutableState.update { it.copy(syncingFolder = true, error = null) }
            runCatching {
                val settings = requireNotNull(container.folderSyncSettingsStore.load()) { "尚未连接加密文件夹" }
                val provider = DocumentTreeSyncProvider(
                    getApplication<Application>().contentResolver,
                    Uri.parse(settings.treeUri),
                )
                val report = container.familySyncCoordinator.synchronize(
                    local = family,
                    provider = provider,
                    key = settings.householdKey(),
                    deviceId = settings.deviceId,
                )
                repository.save(report.state)
                report
            }.onSuccess { report ->
                mutableState.update {
                    it.copy(
                        family = report.state,
                        syncingFolder = false,
                        notice = if (report.uploadedObjectId == null) {
                            "已检查 ${report.inspectedObjectCount} 个密文对象，合并 ${report.appliedFrameCount} 个新变化；内容未变，没有新增密文。"
                        } else {
                            "已检查 ${report.inspectedObjectCount} 个密文对象，合并 ${report.appliedFrameCount} 个新变化，并写入一份新密文。"
                        },
                        error = null,
                    )
                }
            }.onFailure { error ->
                mutableState.update { it.copy(syncingFolder = false) }
                report(error)
            }
        }
    }

    fun disconnectSyncFolder() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val settingsResult = runCatching { container.folderSyncSettingsStore.load() }
                val settings = settingsResult.getOrNull()
                container.folderSyncSettingsStore.erase()
                settings?.let {
                    runCatching {
                        getApplication<Application>().contentResolver.releasePersistableUriPermission(
                            Uri.parse(it.treeUri),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }
                }
                settingsResult.isFailure
            }.onSuccess { unreadable ->
                mutableState.update {
                    it.copy(
                        folderSyncConfigured = false,
                        folderSyncName = null,
                        folderSyncSettingsUnreadable = false,
                        syncingFolder = false,
                        notice = if (unreadable) {
                            "已清除本机无法读取的旧连接信息；既有密文没有删除。原文件夹授权可能需在系统设置中移除。"
                        } else {
                            "已断开文件夹并销毁本机传输密钥；文件夹中的既有密文没有被删除。"
                        },
                        error = null,
                    )
                }
            }.onFailure(::report)
        }
    }

    fun exportPortableBundle(destination: Uri) {
        launchVaultOperation {
            val family = mutableState.value.family ?: return@launchVaultOperation
            runCatching {
                val exported = container.portableFamilyBundleCodec.create(family)
                val descriptor = requireNotNull(getApplication<Application>().contentResolver.openFileDescriptor(destination, "w")) {
                    "无法打开导出位置"
                }
                descriptor.use {
                    FileOutputStream(it.fileDescriptor).use { stream ->
                        stream.write(exported.bytes)
                        stream.fd.sync()
                    }
                }
                exported.recoveryCode
            }.onSuccess { recoveryCode ->
                mutableState.update {
                    it.copy(
                        portableRecoveryCode = recoveryCode,
                        notice = "加密恢复包已保存。恢复码不会写入文件，请单独保存。",
                        error = null,
                    )
                }
            }.onFailure(::report)
        }
    }

    fun previewPortableBundle(source: Uri, recoveryCode: String) {
        if (!portableImportSubmissionInFlight.compareAndSet(false, true)) return
        val generation = portableImportGeneration.incrementAndGet()
        synchronized(portableImportLock) { pendingPortableImport = null }
        mutableState.update { it.copy(portableImportChecking = true, portableImportPreview = null, portableImportError = null) }
        launchVaultOperation {
            val result = runCatching {
                val current = requireNotNull(repository.load()) { "本机家庭资料没有正常打开" }
                val stream = requireNotNull(getApplication<Application>().contentResolver.openInputStream(source)) {
                    "无法读取恢复包"
                }
                val bytes = stream.use { it.readLimited(MAX_PORTABLE_BUNDLE_BYTES) }
                val restored = container.portableFamilyBundleCodec.open(bytes, recoveryCode)
                restored.resolveCaregiverAuthor(null)
                current to restored
            }
            portableImportSubmissionInFlight.set(false)
            synchronized(portableImportLock) {
                if (generation != portableImportGeneration.get()) return@synchronized
                result.onSuccess { (current, restored) ->
                    pendingPortableImport = current to restored
                    mutableState.update { it.copy(
                        portableImportChecking = false,
                        portableImportPreview = PortableImportPreview(
                            current.household.name,
                            restored.household.name,
                        ),
                        portableImportError = null,
                    ) }
                }.onFailure { failure ->
                    mutableState.update { it.copy(
                        portableImportChecking = false,
                        portableImportError = failure.asUserFacingMessage(),
                    ) }
                }
            }
        }
    }

    fun cancelPortableImport() {
        if (mutableState.value.portableImportSaving) return
        synchronized(portableImportLock) {
            portableImportGeneration.incrementAndGet()
            pendingPortableImport = null
            mutableState.update { it.copy(
                portableImportChecking = false,
                portableImportPreview = null,
                portableImportError = null,
            ) }
        }
    }

    fun confirmPortableImport() {
        if (mutableState.value.portableImportPreview == null ||
            !portableImportSubmissionInFlight.compareAndSet(false, true)
        ) return
        mutableState.update { it.copy(portableImportSaving = true, portableImportError = null) }
        launchVaultOperation(onFinished = { portableImportSubmissionInFlight.set(false) }) {
            runCatching {
                val (original, restored) = synchronized(portableImportLock) {
                    requireNotNull(pendingPortableImport) { "请重新检查恢复包" }
                }
                if (repository.load() != original) throw PortableImportStaleException()
                val recorderId = restored.resolveCaregiverAuthor(null).id
                repository.save(restored)
                val recorderPersisted = runCatching {
                    container.activeRecorderSettingsStore.save(ActiveRecorderSettings(recorderId))
                }.isSuccess
                Triple(restored, recorderId, recorderPersisted)
            }.onSuccess { (restored, recorderId, recorderPersisted) ->
                synchronized(portableImportLock) {
                    pendingPortableImport = null
                    portableImportGeneration.incrementAndGet()
                }
                mutableState.update { it.copy(
                    family = restored,
                    route = AppRoute.TODAY,
                    selectedChildId = restored.children.firstOrNull()?.id,
                    composerDraft = null,
                    composerDrafts = emptyMap(),
                    activeMemberId = recorderId,
                    opportunities = null,
                    sourceEventId = null,
                    contextPersisted = null,
                    contextRestricted = null,
                    portableImportSaving = false,
                    portableImportPreview = null,
                    portableImportError = null,
                    notice = if (recorderPersisted) {
                        "恢复包已导入，本机保险箱已重新加密。"
                    } else {
                        "恢复包已导入，但本机未能记住当前记录者；下次启动会使用第一位监护人。"
                    },
                    error = null,
                ) }
            }.onFailure { failure ->
                if (failure is PortableImportStaleException) {
                    synchronized(portableImportLock) {
                        pendingPortableImport = null
                        portableImportGeneration.incrementAndGet()
                    }
                }
                mutableState.update { it.copy(
                    portableImportSaving = false,
                    portableImportPreview = if (failure is PortableImportStaleException) null else it.portableImportPreview,
                    portableImportError = failure.asUserFacingMessage(),
                ) }
            }
        }
    }

    fun dismissRecoveryCode() = mutableState.update { it.copy(portableRecoveryCode = null) }
    fun dismissGraduationRecoveryCode() = mutableState.update {
        it.copy(graduationRecoveryCode = null, graduationSubjectName = null)
    }
    fun consumeNotice() = mutableState.update { it.copy(notice = null) }

    fun exportGraduationBundle(destination: Uri, childId: String, confirmedBySubject: Boolean) {
        launchVaultOperation {
            val family = mutableState.value.family ?: return@launchVaultOperation
            runCatching {
                require(confirmedBySubject) { "需要由本人确认这次成年交接导出" }
                val child = requireNotNull(family.children.firstOrNull { it.id == childId }) { "找不到要导出的本人资料" }
                val confirmedAt = now()
                val exported = container.graduationBundleCodec.create(
                    family,
                    childId,
                    GraduationAuthorization(childId, confirmedAt),
                )
                val descriptor = requireNotNull(getApplication<Application>().contentResolver.openFileDescriptor(destination, "w")) {
                    "无法打开成年交接资料包的导出位置"
                }
                descriptor.use {
                    FileOutputStream(it.fileDescriptor).use { stream ->
                        stream.write(exported.bytes)
                        stream.fd.sync()
                    }
                }
                val receiptTime = now()
                val receipt = FamilyEvent(
                    eventId = id(),
                    eventType = "graduation.archive-exported",
                    householdId = family.household.id,
                    authorId = child.memberId,
                    actorRole = MemberRole.CHILD,
                    subjectId = child.id,
                    deviceId = "android-local",
                    occurredAt = receiptTime,
                    recordedAt = receiptTime,
                    visibility = "child-private",
                    payload = mapOf(
                        "format" to "org.foe.encrypted-graduation-bundle/v1",
                        "scope" to "subject-only",
                    ),
                )
                val stateWithReceipt = family.copy(events = family.events + receipt)
                val receiptSaved = runCatching { repository.save(stateWithReceipt) }.isSuccess
                GraduationUiExport(child.displayName, exported.recoveryCode, stateWithReceipt, receiptSaved)
            }.onSuccess { exported ->
                mutableState.update {
                    it.copy(
                        family = if (exported.receiptSaved) exported.stateWithReceipt else it.family,
                        graduationRecoveryCode = exported.recoveryCode,
                        graduationSubjectName = exported.subjectName,
                        notice = if (exported.receiptSaved) {
                            "本人资料包已保存。恢复码不会写入文件，请由本人单独保管。"
                        } else {
                            "资料包已保存，但本机未能写入导出记录。请先保存恢复码。"
                        },
                        error = null,
                    )
                }
            }.onFailure(::report)
        }
    }

    fun applyGraduationRetentionChoice(
        childId: String,
        mode: GraduationRetentionMode,
        confirmedBySubject: Boolean,
    ) {
        launchVaultOperation {
            val family = mutableState.value.family ?: return@launchVaultOperation
            runCatching {
                require(confirmedBySubject) { "需要由本人确认这次成年交接留存选择" }
                val actionAt = Instant.now()
                val next = applyGraduationRetention(
                    state = family,
                    childId = childId,
                    mode = mode,
                    authorization = GraduationRetentionAuthorization(childId, actionAt.toString()),
                    actionAt = actionAt,
                    tombstoneId = id(),
                    deviceId = "android-local",
                    currentYear = currentYear,
                    referenceDate = LocalDate.now(),
                )
                repository.save(next)
                next
            }.onSuccess { next ->
                mutableState.update { current ->
                    current.copy(
                        family = next,
                        selectedChildId = next.children.firstOrNull { it.id == current.selectedChildId }?.id
                            ?: next.children.firstOrNull()?.id,
                        composerDraft = null,
                        composerDrafts = emptyMap(),
                        opportunities = null,
                        sourceEventId = null,
                        contextPersisted = null,
                        contextRestricted = null,
                        choiceAcknowledgementId = null,
                        notice = when (mode) {
                            GraduationRetentionMode.RELATIONSHIP_ONLY -> "本人经历已清空；家庭副本只保留最小成员关系和防止旧副本复活的删除标记。"
                            GraduationRetentionMode.ERASE_SUBJECT -> "本人资料与家庭成员关联已从活动家庭副本删除；只保留防止旧副本复活的最小删除标记。"
                        },
                        error = null,
                    )
                }
            }.onFailure(::report)
        }
    }

    fun setup(familyName: String, caregiverName: String, childName: String, birthDate: String) {
        if (mutableState.value.loading || mutableState.value.vaultOpenFailed || mutableState.value.family != null) return
        val parsedBirthDate = runCatching {
            require(familyName.isNotBlank()) { "请填写家庭称呼" }
            require(caregiverName.isNotBlank()) { "请填写你的称呼" }
            require(childName.isNotBlank()) { "请填写孩子的称呼" }
            validateFamilyBirthDate(birthDate)
        }.getOrElse { report(it); return }
        if (!onboardingSubmissionInFlight.compareAndSet(false, true)) return
        mutableState.update { it.copy(onboardingSaving = true, error = null) }
        createFamily {
            val timestamp = now()
            val childId = id()
            val childMemberId = id()
            FamilyState(
                household = Household(id(), familyName.trim(), timestamp),
                members = listOf(
                    FamilyMember(id(), caregiverName.trim(), MemberRole.CAREGIVER, createdAt = timestamp),
                    FamilyMember(childMemberId, childName.trim(), MemberRole.CHILD, childId, timestamp),
                ),
                children = listOf(
                    Child(
                        id = childId,
                        memberId = childMemberId,
                        displayName = childName.trim(),
                        birthYear = parsedBirthDate.year,
                        createdAt = timestamp,
                        birthDate = parsedBirthDate.toString(),
                    ),
                ),
            )
        }
    }

    fun addChild(displayName: String, birthDate: String): Boolean {
        val normalizedName = displayName.trim().take(80)
        if (normalizedName.isBlank()) {
            report(IllegalArgumentException("请填写孩子的称呼"))
            return false
        }
        val parsedBirthDate = runCatching { validateFamilyBirthDate(birthDate) }.getOrElse {
            report(it)
            return false
        }
        val childId = id()
        val memberId = id()
        return saveFamilyForm(
            kind = FamilyFormKind.CHILD,
            afterSave = { state, _ -> state.copy(
                selectedChildId = childId,
                composerDraft = null,
                notice = "已添加孩子。",
            ) },
        ) { family ->
            val timestamp = now()
            val child = Child(
                id = childId,
                memberId = memberId,
                displayName = normalizedName,
                birthYear = parsedBirthDate.year,
                createdAt = timestamp,
                birthDate = parsedBirthDate.toString(),
            )
            val member = FamilyMember(child.memberId, child.displayName, MemberRole.CHILD, child.id, timestamp)
            val next = family.copy(
                children = family.children + child,
                members = family.members + member,
                events = family.events + event(
                    family,
                    "child.added",
                    child.id,
                    mapOf(
                        "displayName" to child.displayName,
                        "birthDate" to parsedBirthDate.toString(),
                        "birthYear" to parsedBirthDate.year.toString(),
                    ),
                ),
            )
            next
        }
    }

    fun addCaregiver(displayName: String): Boolean {
        val normalizedName = displayName.trim().take(80)
        if (normalizedName.isBlank()) {
            report(IllegalArgumentException("请填写家长或监护人的称呼"))
            return false
        }
        return saveFamilyForm(
            kind = FamilyFormKind.CAREGIVER,
            afterSave = { state, _ -> state.copy(notice = "已添加家长或监护人。") },
        ) { family ->
            val timestamp = now()
            val member = FamilyMember(
                id = id(),
                displayName = normalizedName,
                role = MemberRole.CAREGIVER,
                createdAt = timestamp,
            )
            family.copy(
                members = family.members + member,
                events = family.events + event(
                    family,
                    "family.member-added",
                    null,
                    mapOf("memberId" to member.id, "role" to member.role.name),
                ),
            )
        }
    }

    fun recordAssessment(
        childId: String,
        subject: String,
        assessmentKind: String,
        score: String,
        maximum: String,
        occurredOn: String,
        classAverage: String,
        percentile: String,
        topics: String,
        notes: String,
        childConfirmed: Boolean,
    ): Boolean {
        val family = mutableState.value.family ?: return false
        val child = family.children.firstOrNull { it.id == childId } ?: return false
        val stage = lifecycleStage(child)
        if (stage == LifecycleStage.GRADUATION) {
            report(IllegalStateException("成年交接后不再新增家长侧学校记录"))
            return false
        }
        if (!canRecordScoredAssessment(stage)) {
            report(IllegalStateException("共玩阶段不记录考试分数；可在机会发现中补充老师的观察"))
            return false
        }
        if (stage == LifecycleStage.HAND_OVER && !childConfirmed) {
            report(IllegalStateException("放权阶段需要孩子本人同意保存这条学校记录"))
            return false
        }
        val earliestDate = child.birthDate
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: LocalDate.of(child.birthYear, 1, 1)
        val entry = runCatching {
            normalizeAssessmentEntry(
                subject = subject,
                assessmentKind = assessmentKind,
                score = score,
                maximum = maximum,
                occurredOn = occurredOn,
                classAverage = classAverage,
                percentile = percentile,
                topics = topics,
                notes = notes,
                earliestDate = earliestDate,
            )
        }.getOrElse {
            report(it)
            return false
        }
        val authorId = family.resolveCaregiverAuthor(mutableState.value.activeMemberId).id

        return saveFamilyForm(
            kind = FamilyFormKind.ASSESSMENT,
            afterSave = { state, _ -> state.copy(notice = "已保存这次学校记录；它不会被换算成孩子总分。") },
        ) { current ->
            val author = current.resolveCaregiverAuthor(authorId)
            val timestamp = now()
            val evidenceId = id()
            val visibility = lifecycleEvidenceVisibility(stage, childPrivateRequested = false)
            val occurredAt = entry.occurredOn.atStartOfDay(ZoneId.systemDefault()).toInstant().toString()
            val assessmentEvidence = Evidence(
                id = evidenceId,
                childId = child.id,
                authorId = author.id,
                stream = ContextStream.SCHOOL,
                kind = EvidenceKind.ASSESSMENT,
                expression = entry.summary(),
                occurredAt = occurredAt,
                recordedAt = timestamp,
                confidence = 1.0,
                ownerId = child.memberId,
                visibility = visibility,
                source = "caregiver-assessment",
            )
            val assessmentEvent = FamilyEvent(
                eventId = id(),
                eventType = "assessment.recorded",
                householdId = current.household.id,
                authorId = author.id,
                actorRole = author.role,
                subjectId = child.id,
                deviceId = "android-local",
                occurredAt = occurredAt,
                recordedAt = timestamp,
                visibility = when (visibility) {
                    EvidenceVisibility.GUARDIANS -> "guardians"
                    EvidenceVisibility.SHARED_WITH_CHILD -> "shared-with-child"
                    EvidenceVisibility.CHILD_PRIVATE -> "child-private"
                },
                payload = mapOf(
                    "evidenceId" to evidenceId,
                    "subject" to entry.subject,
                    "assessmentKind" to entry.assessmentKind,
                    "score" to entry.score.toString(),
                    "maximum" to entry.maximum.toString(),
                    "classAverage" to (entry.classAverage?.toString() ?: ""),
                    "percentile" to (entry.percentile?.toString() ?: ""),
                    "topics" to entry.topics.joinToString("｜"),
                    "source" to "caregiver-entered",
                    "notes" to (entry.notes ?: ""),
                    "childConfirmed" to childConfirmed.toString(),
                ),
            )
            current.copy(
                evidence = current.evidence + assessmentEvidence,
                events = current.events + assessmentEvent,
            )
        }
    }

    fun discover(
        childId: String,
        expression: String,
        caregiverGoal: String?,
        sharedGoal: String?,
        schoolWindow: String?,
        lifeContext: String?,
        region: String?,
        constraints: FamilyConstraints,
        childConfirmed: Boolean,
        persistContext: Boolean,
        includeRecentContext: Boolean,
        providerDisclosureApproved: Boolean,
        useOfflineDemo: Boolean,
        contextVisibility: EvidenceVisibility? = null,
    ) {
        if (!discoverySubmissionInFlight.compareAndSet(false, true)) return
        launchVaultOperation(onFinished = { discoverySubmissionInFlight.set(false) }) {
            val family = mutableState.value.family ?: return@launchVaultOperation
            val child = family.children.firstOrNull { it.id == childId } ?: return@launchVaultOperation
            if (child.ageAt() < 4) {
                report(IllegalStateException("当前版本支持 4 岁起的家庭成员；现在不寻找入口或新建观察"))
                return@launchVaultOperation
            }
            val stage = lifecycleStage(child)
            val authority = lifecycleAuthority(stage)
            if (!authority.allowsNewObservation) {
                report(IllegalStateException("16 岁起进入成年交接阶段，不再创建新的家长侧记录"))
                return@launchVaultOperation
            }
            if (authority.requiresChildConfirmation && !childConfirmed) {
                report(IllegalStateException(discoveryMissingChildConfirmationMessage))
                return@launchVaultOperation
            }
            if (!useOfflineDemo && !mutableState.value.aiConfigured) {
                report(IllegalStateException(discoveryMissingAiMessage(stage)))
                return@launchVaultOperation
            }
            if (!useOfflineDemo && !providerDisclosureApproved) {
                report(IllegalStateException("请先确认本次发送给 AI 的最小上下文"))
                return@launchVaultOperation
            }
            val normalizedExpression = expression.trim().take(800)
            if (normalizedExpression.isBlank()) {
                report(IllegalArgumentException(discoveryBlankInterestMessage(stage)))
                return@launchVaultOperation
            }
            if (!currentInterestHasNonRefusalClue(normalizedExpression)) {
                report(IllegalArgumentException(currentInterestClueMessage(stage)))
                return@launchVaultOperation
            }
            val normalizedCaregiverGoal = caregiverGoal?.trim()?.take(300).nullIfBlank()
            val normalizedSharedGoal = sharedGoal?.trim()?.take(300).nullIfBlank()
            val normalizedSchoolWindow = schoolWindow?.trim()?.take(500).nullIfBlank()
            val normalizedLifeContext = lifeContext?.trim()?.take(500).nullIfBlank()
            val normalizedRegion = region?.trim()?.take(80).nullIfBlank()
            val effectivePersistContext = discoveryContextMayPersist(useOfflineDemo, persistContext)
            val effectiveIncludeRecentContext = includeRecentContext && !useOfflineDemo
            val goals = FamilyGoals(normalizedExpression, normalizedCaregiverGoal, normalizedSharedGoal)
            val timestamp = now()
            if (contextVisibility == EvidenceVisibility.CHILD_PRIVATE && stage != LifecycleStage.HAND_OVER) {
                report(IllegalArgumentException("“只留给自己”只适用于放权阶段"))
                return@launchVaultOperation
            }
            val author = runCatching {
                family.resolveDiscoveryAuthor(child, stage, mutableState.value.activeMemberId)
            }.getOrElse { failure ->
                report(failure)
                return@launchVaultOperation
            }
            mutableState.update { it.copy(discovering = true, error = null, disclosureSaveRisk = false) }
            val visibility = lifecycleEvidenceVisibility(
                stage = stage,
                childPrivateRequested = contextVisibility == EvidenceVisibility.CHILD_PRIVATE,
            )
            val newEvidence = buildList {
                add(
                    Evidence(
                        id = id(),
                        childId = childId,
                        authorId = author.id,
                        stream = ContextStream.CHILD,
                        kind = if (stage == LifecycleStage.HAND_OVER) EvidenceKind.CHILD_STATED else EvidenceKind.DIRECT_OBSERVATION,
                        expression = normalizedExpression,
                        occurredAt = timestamp,
                        recordedAt = timestamp,
                        ownerId = child.memberId,
                        visibility = visibility,
                    ),
                )
                normalizedSchoolWindow?.let {
                    add(
                        Evidence(
                            id = id(), childId = childId, authorId = author.id,
                            stream = ContextStream.SCHOOL, kind = EvidenceKind.IMPORTED_CLAIM,
                            expression = it, occurredAt = timestamp, recordedAt = timestamp,
                            ownerId = child.memberId, visibility = visibility,
                        ),
                    )
                }
                normalizedLifeContext?.let {
                    add(
                        Evidence(
                            id = id(), childId = childId, authorId = author.id,
                            stream = ContextStream.LIFE, kind = EvidenceKind.DIRECT_OBSERVATION,
                            expression = it, occurredAt = timestamp, recordedAt = timestamp,
                            ownerId = child.memberId, visibility = visibility,
                        ),
                    )
                }
            }
            val interestEvent = FamilyEvent(
                eventId = id(),
                eventType = "interest.observed",
                householdId = family.household.id,
                authorId = author.id,
                actorRole = author.role,
                subjectId = childId,
                deviceId = "android-local",
                occurredAt = timestamp,
                recordedAt = timestamp,
                visibility = when (visibility) {
                    EvidenceVisibility.GUARDIANS -> "guardians"
                    EvidenceVisibility.SHARED_WITH_CHILD -> "shared-with-child"
                    EvidenceVisibility.CHILD_PRIVATE -> "child-private"
                },
                payload = mapOf(
                    "expression" to normalizedExpression,
                    "caregiverGoal" to (goals.caregiver ?: ""),
                    "sharedGoal" to (goals.shared ?: ""),
                    "schoolWindow" to (normalizedSchoolWindow ?: ""),
                    "lifeContext" to (normalizedLifeContext ?: ""),
                    "region" to (normalizedRegion ?: ""),
                    "childConfirmed" to childConfirmed.toString(),
                    "persistContext" to effectivePersistContext.toString(),
                    "includeRecentContext" to effectiveIncludeRecentContext.toString(),
                    "discoveryMode" to if (useOfflineDemo) "offline-demo" else "byok-ai",
                ),
            )
            val disclosureVisibility = if (stage == LifecycleStage.HAND_OVER) "shared-with-child" else "guardians"
            val nextFamily = if (effectivePersistContext) {
                family.copy(evidence = family.evidence + newEvidence, events = family.events + interestEvent)
            } else {
                family
            }
            val discoveryRequest = OpportunityDiscoveryRequest(
                currentInterest = normalizedExpression,
                age = child.ageAt(),
                lifecycleStage = stage,
                goals = goals,
                constraints = constraints,
                schoolWindow = normalizedSchoolWindow,
                lifeContext = normalizedLifeContext,
                region = normalizedRegion,
                recentEvidence = if (effectiveIncludeRecentContext) {
                    projectRecentRecommendationContext(
                        evidence = family.evidence,
                        choices = family.choices,
                        childId = childId,
                        events = family.events,
                    ).summaries
                } else {
                    emptyList()
                },
                recentInterventionCount = if (useOfflineDemo) 0 else countRecentShareableSelections(
                    choices = family.choices, events = family.events, childId = childId,
                ),
                includeRecentSelectionCountInProviderContext = effectiveIncludeRecentContext,
            )
            val formalOpportunitySources = listOf(formalSources.ai) + formalSources.others
            val worldBriefConfigured = mutableState.value.worldBriefConfigured
            val aiProviderName = mutableState.value.aiProviderName ?: "configured-provider"
            val aiModel = mutableState.value.aiModel ?: "configured-model"
            val worldBriefProviderName = mutableState.value.worldBriefProviderName ?: "configured-world-brief-provider"
            val confirmedVault = ConfirmedVaultSnapshot(family, repository)
            var externalRequestMayHaveStarted = false
            runCatching {
                if (effectivePersistContext) confirmedVault.save(nextFamily)
                val approvedSourceScopes = if (useOfflineDemo) null else {
                    formalOpportunitySources.associate { source ->
                        source.id to engine.previewSourceDisclosure(
                            discoveryRequest,
                            source,
                            worldBriefConfigured,
                        ).includedCategories.toSet()
                    }
                }
                val familyAfterApproval = if (useOfflineDemo) nextFamily else {
                    val aiApproval = engine.previewSourceDisclosure(
                        discoveryRequest,
                        formalSources.ai,
                        worldBriefConfigured,
                    )
                    val approvalEvents = buildList {
                        add(event(
                            nextFamily,
                            "provider.disclosure-approved",
                            childId,
                            aiApproval.toEventPayload(
                                providerId = aiProviderName,
                                modelId = aiModel,
                                contextPersisted = effectivePersistContext,
                            ) + mapOf(
                                "requestId" to interestEvent.eventId,
                                "deliveryStatus" to "not-confirmed",
                            ),
                            author = author,
                            visibility = disclosureVisibility,
                        ))
                        if (worldBriefConfigured) {
                            add(event(
                                nextFamily,
                                "provider.disclosure-approved",
                                childId,
                                publicWorldQueryDisclosure(normalizedRegion).toEventPayload(
                                    providerId = worldBriefProviderName,
                                    modelId = "not-applicable",
                                    contextPersisted = false,
                                ) + mapOf(
                                    "requestId" to interestEvent.eventId,
                                    "deliveryStatus" to "not-confirmed",
                                ),
                                author = author,
                                visibility = disclosureVisibility,
                            ))
                        }
                    }
                    val approvedFamily = nextFamily.copy(events = nextFamily.events + approvalEvents)
                    confirmedVault.save(approvedFamily)
                    approvedFamily
                }
                if (!useOfflineDemo) externalRequestMayHaveStarted = true
                val result = engine.discover(
                    request = discoveryRequest,
                    sources = if (useOfflineDemo) {
                        listOf(container.demoOpportunitySource)
                    } else {
                        formalOpportunitySources
                    },
                    worldBriefProvider = if (useOfflineDemo || !worldBriefConfigured) null else formalSources.worldBrief,
                    approvedCategoriesBySource = approvedSourceScopes,
                    approvedWorldQuery = if (useOfflineDemo || !worldBriefConfigured) null else {
                        PublicWorldQuery(region = normalizedRegion, timeWindow = "next-14-days")
                    },
                )
                val effectiveDisclosure = if (useOfflineDemo) {
                    result.disclosure
                } else {
                    result.sourceDisclosures[formalSources.ai.id] ?: result.disclosure
                }
                val disclosureEvents = if (useOfflineDemo) {
                    emptyList()
                } else {
                    buildList {
                        add(event(
                            familyAfterApproval,
                            "provider.context-disclosed",
                            childId,
                            effectiveDisclosure.toEventPayload(
                                providerId = aiProviderName,
                                modelId = aiModel,
                                contextPersisted = effectivePersistContext,
                            ),
                            author = author,
                            visibility = disclosureVisibility,
                        ))
                        if (worldBriefConfigured) {
                            add(event(
                                familyAfterApproval,
                                "provider.context-disclosed",
                                childId,
                                publicWorldQueryDisclosure(normalizedRegion).toEventPayload(
                                    providerId = worldBriefProviderName,
                                    modelId = "not-applicable",
                                    contextPersisted = false,
                                ),
                                author = author,
                                visibility = disclosureVisibility,
                            ))
                        }
                    }
                }
                val familyWithReceipt = if (disclosureEvents.isEmpty()) familyAfterApproval else {
                    familyAfterApproval.copy(events = familyAfterApproval.events + disclosureEvents)
                }
                if (disclosureEvents.isNotEmpty()) confirmedVault.save(familyWithReceipt)
                Triple(result, familyWithReceipt, effectiveDisclosure)
            }.onSuccess { (result, familyWithReceipt, effectiveDisclosure) ->
                mutableState.update {
                    it.copy(
                        family = familyWithReceipt,
                        selectedChildId = childId,
                        opportunities = result.opportunities,
                        sourceEventId = if (effectivePersistContext) interestEvent.eventId else "ephemeral:${interestEvent.eventId}",
                        discovering = false,
                        // The family's chosen mode is authoritative even if a source returns no items or fails.
                        discoveryMode = if (useOfflineDemo) "offline-demo" else "byok-ai",
                        discoverySourceIssues = result.sourceIssues,
                        disclosureIncluded = effectiveDisclosure.includedCategories,
                        disclosureExcluded = effectiveDisclosure.excludedCategories,
                        contextPersisted = effectivePersistContext,
                        contextRestricted = effectivePersistContext && visibility == EvidenceVisibility.CHILD_PRIVATE,
                        error = null,
                        disclosureSaveRisk = false,
                    )
                }
            }.onFailure { error ->
                mutableState.update { it.copy(family = confirmedVault.family, discovering = false) }
                if (confirmedVault.saveFailed && externalRequestMayHaveStarted) {
                    mutableState.update { it.copy(
                        error = postRequestLocalSaveMessage(effectivePersistContext),
                        disclosureSaveRisk = true,
                    ) }
                } else if (confirmedVault.saveFailed) {
                    reportLocalSave(error)
                } else {
                    report(error)
                }
            }
        }
    }

    fun choose(opportunity: Opportunity, vetoed: Boolean = false) {
        val snapshot = mutableState.value
        if (!discoveryChoiceMayPersist(snapshot.discoveryMode)) {
            report(IllegalStateException("离线演示只供预览，不会保存正式选择"))
            return
        }
        snapshot.family ?: return
        val childId = snapshot.selectedChildId ?: return
        val sourceEventId = snapshot.sourceEventId ?: return
        if (!choiceSubmissionInFlight.compareAndSet(false, true)) return
        mutableState.update { it.copy(choiceSaving = true) }
        mutate(transform = { current ->
            require(current.choices.none { it.sourceEventId == sourceEventId }) { "这次已经作出选择" }
            val child = current.children.firstOrNull { it.id == childId }
                ?: error("选择关联的孩子资料已不存在")
            val stage = lifecycleStage(child)
            val author = current.resolveDiscoveryAuthor(child, stage, mutableState.value.activeMemberId)
            val timestamp = now()
            val choice = FamilyChoice(
                id = id(),
                childId = childId,
                opportunity = opportunity,
                sourceEventId = sourceEventId,
                status = when {
                    vetoed -> "child-vetoed"
                    opportunity.isNothing -> "nothing"
                    else -> "chosen"
                },
                chosenAt = timestamp,
            )
            val eventType = when {
                vetoed -> "opportunity.child-vetoed"
                opportunity.isNothing -> "opportunity.nothing-chosen"
                else -> "opportunity.chosen"
            }
            current.copy(
                choices = listOf(choice) + current.choices,
                events = current.events + event(
                    current,
                    eventType,
                    childId,
                    mapOf("choiceId" to choice.id, "title" to opportunity.title),
                    author = author,
                    visibility = if (snapshot.contextRestricted == true) {
                        "child-private"
                    } else if (stage == LifecycleStage.CO_SELECT || stage == LifecycleStage.HAND_OVER) {
                        "shared-with-child"
                    } else {
                        "guardians"
                    },
                ),
            )
        }, onSaved = { next ->
            val choiceId = next.choices.first().id
            mutableState.update { state ->
                if (state.selectedChildId == childId && state.sourceEventId == sourceEventId) {
                    state.copy(
                        opportunities = null,
                        sourceEventId = null,
                        composerDraft = null,
                        composerDrafts = state.composerDrafts - childId,
                        contextPersisted = null,
                        contextRestricted = null,
                        choiceAcknowledgementId = choiceId,
                    )
                } else state
            }
        }, onFinished = {
            choiceSubmissionInFlight.set(false)
            mutableState.update { it.copy(choiceSaving = false) }
        })
    }

    fun feedback(choiceId: String, value: String) = recordFeedback(choiceId, value, childAuthored = false)

    fun feedbackFromChild(choiceId: String, value: String) = recordFeedback(choiceId, value, childAuthored = true)

    private fun recordFeedback(choiceId: String, value: String, childAuthored: Boolean) {
        if (value !in KNOWN_FEEDBACK) return
        if (!feedbackSubmissionInFlight.compareAndSet(false, true)) return
        val preferredAuthorId = mutableState.value.activeMemberId
        mutableState.update { it.copy(feedbackSavingChoiceId = choiceId, error = null) }
        mutate(transform = { family ->
            val choice = family.choices.firstOrNull { it.id == choiceId } ?: return@mutate family
            val child = family.children.firstOrNull { it.id == choice.childId }
                ?: error("反馈关联的孩子资料已不存在")
            val author = if (childAuthored) {
                require(lifecycleStage(child) == LifecycleStage.HAND_OVER) { "这次反馈已不在放权阶段" }
                family.resolveDiscoveryAuthor(child, LifecycleStage.HAND_OVER, preferredAuthorId)
            } else {
                family.resolveCaregiverAuthor(preferredAuthorId)
            }
            appendChoiceFeedback(
                family = family,
                choiceId = choiceId,
                value = value,
                author = author,
                provenance = if (childAuthored) FeedbackProvenance.CHILD_SIGNED
                    else FeedbackProvenance.CAREGIVER_RELAYED_CHILD_VIEW,
                eventId = id(),
                recordedAt = Instant.parse(now()),
                deviceId = "android-local",
            )
        }, onSaved = {
            mutableState.update { state ->
                if (state.choiceAcknowledgementId == choiceId) state.copy(choiceAcknowledgementId = null) else state
            }
        }, onFinished = {
            feedbackSubmissionInFlight.set(false)
            mutableState.update { it.copy(feedbackSavingChoiceId = null) }
        }, onFailure = ::reportLocalSave)
    }

    fun dismissChoiceAcknowledgement() = mutableState.update { it.copy(choiceAcknowledgementId = null) }

    fun deleteChoice(choiceId: String, subjectConfirmed: Boolean) {
        if (feedbackSubmissionInFlight.get() || choiceSubmissionInFlight.get() ||
            !choiceDeletionInFlight.compareAndSet(false, true)) return
        mutableState.update { it.copy(choiceDeletingId = choiceId, error = null) }
        launchVaultOperation(onFinished = {
            choiceDeletionInFlight.set(false)
            mutableState.update { it.copy(choiceDeletingId = null) }
        }) {
            val current = mutableState.value.family ?: return@launchVaultOperation
            val next = runCatching {
                val choice = current.choices.firstOrNull { it.id == choiceId } ?: return@runCatching current
                val child = requireNotNull(current.children.firstOrNull { it.id == choice.childId }) {
                    "选择关联的成员已不存在"
                }
                val stage = lifecycleStage(child)
                val author = if (stage == LifecycleStage.HAND_OVER || stage == LifecycleStage.GRADUATION) {
                    require(subjectConfirmed) { "请由本人确认删除这条选择" }
                    requireNotNull(current.members.firstOrNull {
                        it.id == child.memberId && it.role == MemberRole.CHILD && it.subjectId == child.id
                    }) { "当前无法确认本人署名" }
                } else {
                    current.resolveCaregiverAuthor(mutableState.value.activeMemberId)
                }
                deleteChoiceWithTombstones(
                    state = current, choiceId = choiceId, authorId = author.id,
                    actorRole = author.role, subjectConfirmed = subjectConfirmed,
                    deviceId = "android-local", deletedAt = now(), nextId = ::id,
                )
            }.getOrElse { error ->
                report(error)
                return@launchVaultOperation
            }
            if (next == current) return@launchVaultOperation
            runCatching { repository.save(next) }
                .onSuccess {
                    mutableState.update { state -> state.copy(
                        family = next,
                        choiceAcknowledgementId = state.choiceAcknowledgementId.takeUnless { it == choiceId },
                        error = null,
                    ) }
                }
                .onFailure(::reportLocalSave)
        }
    }

    fun deleteEvidence(evidenceId: String) {
        mutate { family ->
            val author = family.resolveCaregiverAuthor(mutableState.value.activeMemberId)
            val timestamp = now()
            deleteEvidenceWithTombstone(
                state = family,
                evidenceId = evidenceId,
                authorId = author.id,
                actorRole = author.role,
                deviceId = "android-local",
                deletedAt = timestamp,
                tombstoneId = id(),
                eventId = id(),
            )
        }
    }

    /** Adds a child-authored correction. The original evidence remains visible as history. */
    fun correctEvidence(evidenceId: String, correction: String) {
        val normalized = correction.trim()
        if (normalized.isEmpty()) return
        mutate { family ->
            val original = family.evidence.firstOrNull { it.id == evidenceId } ?: return@mutate family
            val child = family.children.firstOrNull { it.id == original.childId } ?: return@mutate family
            val timestamp = now()
            val correctionEvidence = Evidence(
                id = id(),
                childId = child.id,
                authorId = child.memberId,
                stream = ContextStream.CHILD,
                kind = EvidenceKind.CHILD_STATED,
                expression = normalized,
                occurredAt = timestamp,
                recordedAt = timestamp,
                confidence = 1.0,
                ownerId = child.memberId,
                visibility = EvidenceVisibility.SHARED_WITH_CHILD,
                source = "child-correction:$evidenceId",
            )
            val correctionEvent = FamilyEvent(
                eventId = id(),
                eventType = "evidence.child-corrected",
                householdId = family.household.id,
                authorId = child.memberId,
                actorRole = MemberRole.CHILD,
                subjectId = child.id,
                deviceId = "android-local",
                occurredAt = timestamp,
                recordedAt = timestamp,
                visibility = "shared-with-child",
                payload = mapOf("correctsEvidenceId" to evidenceId, "correctionEvidenceId" to correctionEvidence.id),
            )
            family.copy(
                evidence = family.evidence + correctionEvidence,
                events = family.events + correctionEvent,
            )
        }
    }

    fun eraseVault() {
        if (sourceFormSubmissionInFlight.get()) {
            report(IllegalStateException("正在保存连接，请稍后再删除家庭资料"))
            return
        }
        launchVaultOperation {
            runCatching {
                val folderSyncSettings = runCatching { container.folderSyncSettingsStore.load() }.getOrNull()
                repository.erase()
                container.activeRecorderSettingsStore.erase()
                container.aiSettingsStore.erase()
                container.worldBriefSettingsStore.erase()
                container.folderSyncSettingsStore.erase()
                folderSyncSettings?.let {
                    runCatching {
                        getApplication<Application>().contentResolver.releasePersistableUriPermission(
                            Uri.parse(it.treeUri),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }
                }
            }
                .onSuccess {
                    mutableState.value = MainUiState(
                        loading = false,
                        brandName = container.brandConfig.displayName,
                        brandTagline = container.brandConfig.tagline,
                        brandHero = container.brandConfig.hero,
                        brandMission = container.brandConfig.mission,
                    )
                }
                .onFailure(::report)
        }
    }

    private fun saveSourceForm(
        kind: SourceFormKind,
        write: () -> Unit,
        afterSave: (MainUiState) -> MainUiState,
    ): Boolean {
        if (mutableState.value.family == null) return false
        if (!sourceFormSubmissionInFlight.compareAndSet(false, true)) return false
        mutableState.update { it.copy(sourceFormSaving = kind, sourceFormError = null, error = null) }
        launchVaultOperation(onFinished = {
            sourceFormSubmissionInFlight.set(false)
            mutableState.update { it.copy(sourceFormSaving = null) }
        }) {
            if (mutableState.value.family == null) return@launchVaultOperation
            runCatching(write)
                .onSuccess {
                    mutableState.update { state ->
                        afterSave(state).copy(
                            sourceFormSaveRevision = state.sourceFormSaveRevision + 1,
                            lastSavedSourceForm = kind,
                            sourceFormError = null,
                            error = null,
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(
                        loading = false,
                        sourceFormError = kind,
                        error = error.asUserFacingMessage(ErrorContext.LOCAL_SAVE),
                    ) }
                }
        }
        return true
    }

    private fun saveFamilyForm(
        kind: FamilyFormKind,
        afterSave: (MainUiState, FamilyState) -> MainUiState,
        transform: (FamilyState) -> FamilyState,
    ): Boolean {
        if (mutableState.value.family == null) return false
        if (!familyFormSubmissionInFlight.compareAndSet(false, true)) return false
        mutableState.update { it.copy(familyFormSaving = kind, error = null) }
        mutate(
            transform = transform,
            onSaved = { next ->
                mutableState.update { state ->
                    afterSave(state, next).copy(
                        familyFormSaveRevision = state.familyFormSaveRevision + 1,
                        lastSavedFamilyForm = kind,
                    )
                }
            },
            onFinished = {
                familyFormSubmissionInFlight.set(false)
                mutableState.update { it.copy(familyFormSaving = null) }
            },
            onFailure = ::reportLocalSave,
        )
        return true
    }

    private fun mutate(
        onSaved: (FamilyState) -> Unit = {},
        onFinished: () -> Unit = {},
        onFailure: (Throwable) -> Unit = ::report,
        transform: (FamilyState) -> FamilyState,
    ) {
        launchVaultOperation(onFinished = onFinished) {
            val current = mutableState.value.family ?: return@launchVaultOperation
            runCatching {
                val next = transform(current)
                repository.save(next)
                next
            }
                .onSuccess { next ->
                    mutableState.update { it.copy(family = next, error = null) }
                    onSaved(next)
                }
                .onFailure(onFailure)
        }
    }

    private fun createFamily(create: () -> FamilyState) {
        launchVaultOperation(onFinished = {
            onboardingSubmissionInFlight.set(false)
            mutableState.update { it.copy(onboardingSaving = false) }
        }) {
            runCatching {
                val next = create()
                val recorderId = next.resolveCaregiverAuthor(null).id
                repository.save(next)
                val recorderPersisted = runCatching {
                    container.activeRecorderSettingsStore.save(ActiveRecorderSettings(recorderId))
                }.isSuccess
                Triple(next, recorderId, recorderPersisted)
            }
                .onSuccess { (next, recorderId, recorderPersisted) -> mutableState.update { it.copy(
                    family = next,
                    loading = false,
                    selectedChildId = next.children.firstOrNull()?.id,
                    composerDraft = null,
                    composerDrafts = emptyMap(),
                    activeMemberId = recorderId,
                    notice = if (recorderPersisted) null else "家庭已创建，但本机未能记住当前记录者。",
                    error = null,
                ) } }
                .onFailure { error ->
                    if (runCatching { repository.load() }.isFailure) {
                        mutableState.update { it.copy(
                            vaultOpenFailed = true,
                            error = error.asUserFacingMessage(ErrorContext.STARTUP),
                        ) }
                    } else {
                        reportLocalSave(error)
                    }
                }
        }
    }

    private fun event(
        family: FamilyState,
        type: String,
        subjectId: String?,
        payload: Map<String, String>,
        author: FamilyMember = family.resolveCaregiverAuthor(mutableState.value.activeMemberId),
        visibility: String = "guardians",
    ): FamilyEvent {
        val timestamp = now()
        return FamilyEvent(
            eventId = id(),
            eventType = type,
            householdId = family.household.id,
            authorId = author.id,
            actorRole = author.role,
            subjectId = subjectId,
            deviceId = "android-local",
            occurredAt = timestamp,
            recordedAt = timestamp,
            visibility = visibility,
            payload = payload,
        )
    }

    private fun report(error: Throwable) = mutableState.update {
        it.copy(loading = false, error = error.asUserFacingMessage())
    }
    private fun reportLocalSave(error: Throwable) = mutableState.update {
        it.copy(loading = false, error = error.asUserFacingMessage(ErrorContext.LOCAL_SAVE))
    }
    private fun id() = UUID.randomUUID().toString()
    private fun now() = Instant.now().toString()
    private fun String?.nullIfBlank() = this?.trim()?.takeIf { it.isNotEmpty() }
    private fun InputStream.readLimited(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "恢复包超过大小限制" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        val currentYear: Int get() = Year.now().value
        private const val MAX_PORTABLE_BUNDLE_BYTES = 16 * 1024 * 1024
        private val KNOWN_FEEDBACK = setOf("喜欢", "一般", "不合适")
    }
}
