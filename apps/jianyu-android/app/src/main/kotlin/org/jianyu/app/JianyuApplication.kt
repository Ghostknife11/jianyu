package org.jianyu.app

import android.app.Application
import org.jianyu.core.data.ActiveRecorderSettingsStore
import org.jianyu.core.data.AiProviderSettingsStore
import org.jianyu.core.data.AndroidKeystoreVaultRepository
import org.jianyu.core.data.OpenAiCompatibleOpportunitySource
import org.jianyu.core.data.BundledOpportunityPackStore
import org.jianyu.core.data.BundledBrandConfigStore
import org.jianyu.core.data.PortableFamilyBundleCodec
import org.jianyu.core.data.GraduationBundleCodec
import org.jianyu.core.data.HttpWorldBriefProvider
import org.jianyu.core.data.WorldBriefProviderSettingsStore
import org.jianyu.core.data.FolderSyncSettingsStore
import org.jianyu.core.data.FamilySyncCoordinator
import org.jianyu.core.domain.DefaultOpportunityPolicy
import org.jianyu.core.domain.FamilyOpportunityEngine
import org.jianyu.core.domain.LocalDemoOpportunitySource
import org.jianyu.core.domain.DeclarativePackOpportunitySource
import org.jianyu.core.domain.SyntheticWorldBriefProvider
import org.jianyu.core.domain.WorldBriefOpportunitySource

class JianyuApplication : Application() {
    val brandConfigStore by lazy { BundledBrandConfigStore(this) }
    val brandConfig by lazy { brandConfigStore.load("brand/jianyu.json") }
    val vaultRepository by lazy { AndroidKeystoreVaultRepository(this) }
    val portableFamilyBundleCodec by lazy { PortableFamilyBundleCodec() }
    val graduationBundleCodec by lazy { GraduationBundleCodec() }
    val activeRecorderSettingsStore by lazy { ActiveRecorderSettingsStore(this) }
    val aiSettingsStore by lazy { AiProviderSettingsStore(this) }
    val aiOpportunitySource by lazy { OpenAiCompatibleOpportunitySource(aiSettingsStore) }
    val worldBriefSettingsStore by lazy { WorldBriefProviderSettingsStore(this) }
    val folderSyncSettingsStore by lazy { FolderSyncSettingsStore(this) }
    val familySyncCoordinator by lazy { FamilySyncCoordinator() }
    val demoOpportunitySource by lazy { LocalDemoOpportunitySource() }
    val bundledPackStore by lazy { BundledOpportunityPackStore(this) }
    val packOpportunitySource by lazy {
        DeclarativePackOpportunitySource(listOf(bundledPackStore.load("packs/starter-motorsport.json")))
    }
    val worldBriefOpportunitySource by lazy { WorldBriefOpportunitySource() }
    val worldBriefProvider by lazy { HttpWorldBriefProvider(worldBriefSettingsStore) }
    val syntheticWorldBriefProvider by lazy { SyntheticWorldBriefProvider() }
    val opportunityEngine by lazy {
        FamilyOpportunityEngine(policy = DefaultOpportunityPolicy())
    }
}
