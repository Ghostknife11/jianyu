package org.jianyu.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.jianyu.app.MainUiState
import org.jianyu.app.PortableImportPreview
import org.jianyu.app.SourceFormKind
import org.jianyu.app.ui.theme.JianyuTheme
import org.jianyu.core.data.AiCapabilityResult
import org.jianyu.core.data.AiCapabilityStatus
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.Household
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic connected sources exercise the production Settings layout without credentials or a Vault. */
@RunWith(AndroidJUnit4::class)
class SettingsSourceActionsUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun portableImportDialogSeparatesVerificationFromDestructiveReplacementAtLargeText() {
        val source = Uri.parse("content://synthetic.invalid/recovery.foe")
        var state by mutableStateOf(MainUiState(portableImportError = "恢复码错误或恢复包已被修改"))
        var code by mutableStateOf("fictional-recovery-code")
        var previewCalls = 0
        var confirmCalls = 0
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                JianyuTheme {
                    PortableImportDialog(
                        source = source,
                        state = state,
                        recoveryCode = code,
                        onRecoveryCode = { code = it },
                        onPreview = { received, receivedCode ->
                            assertEquals(source, received)
                            assertEquals(code, receivedCode)
                            previewCalls++
                        },
                        onConfirm = { confirmCalls++ },
                        onDismiss = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("恢复码错误或恢复包已被修改").assertIsDisplayed()
        composeRule.onNodeWithText("fictional-recovery-code").assertDoesNotExist()
        composeRule.onNodeWithText("检查恢复包").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(1, previewCalls)
            assertEquals(0, confirmCalls)
            state = state.copy(
                portableImportError = null,
                portableImportPreview = PortableImportPreview("本机虚构家庭", "恢复包虚构家庭"),
            )
        }
        composeRule.onNodeWithText("这台设备：本机虚构家庭").assertIsDisplayed()
        composeRule.onNodeWithText("恢复包：恢复包虚构家庭").assertIsDisplayed()
        composeRule.onNodeWithText("不会合并", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("确认替换本机资料").assertIsDisplayed()
        saveScreenshot("settings-portable-import-preview-large.png", dialogRoot = true)
        composeRule.onNodeWithText("确认替换本机资料").performClick()
        composeRule.runOnIdle { assertEquals(1, confirmCalls) }
    }

    @Test
    fun longServiceAndModelNamesWrapWithoutHidingMaintenanceActions() {
        val longService = "虚构的第三方家庭机会发现服务名称，用于检查替换服务后普通家庭是否仍能读清连接状态"
        val longModel = "synthetic-model-with-a-long-provider-specific-identifier-for-layout-testing"
        val state = MainUiState(
            loading = false,
            family = FamilyState(
                household = Household("synthetic-household", "虚构家庭", "2026-09-27T00:00:00Z"),
                members = emptyList(),
                children = emptyList(),
            ),
            aiConfigured = true,
            aiProviderName = longService,
            aiModel = longModel,
        )
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                JianyuTheme {
                    Box(Modifier.width(360.dp).height(720.dp)) {
                        SettingsScreen(
                            state = state,
                            onSaveAi = { _, _, _, _ -> error("No credential should be saved") },
                            onCheckAi = { error("No network sample should run") },
                            onRemoveAi = { error("Synthetic source should not be disconnected") },
                            onSaveWorldBrief = { _, _, _ -> error("No source should be saved") },
                            onRemoveWorldBrief = {},
                            onConnectSyncFolder = {},
                            onRetrySyncFolderSettings = {},
                            onSyncFolder = {},
                            onDisconnectSyncFolder = {},
                            onExportPortable = {},
                            onPreviewPortable = { _, _ -> },
                            onConfirmPortableImport = {},
                            onCancelPortableImport = {},
                            onDismissRecoveryCode = {},
                            onErase = {},
                        )
                    }
                }
            }
        }

        val list = composeRule.onNodeWithTag("settings-list")
        list.performScrollToNode(hasText("服务：$longService"))
        composeRule.onNodeWithText("服务：$longService").performScrollTo().assertIsDisplayed()
        saveScreenshot("settings-long-service-name.png")
        composeRule.onNodeWithText("模型").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(longModel).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("更换连接").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("断开").assertIsDisplayed()
        saveScreenshot("settings-long-service-actions.png")
    }

    @Test
    fun connectedSourceActionsStayDistinctAtLargeFont() {
        var state by mutableStateOf(MainUiState(
            loading = false,
            family = FamilyState(
                household = Household("synthetic-household", "虚构家庭", "2026-09-27T00:00:00Z"),
                members = emptyList(),
                children = emptyList(),
            ),
            aiConfigured = true,
            aiProviderName = "合成 AI",
            aiBaseUrl = "https://example.invalid/v1",
            aiModel = "synthetic-model",
            aiCapabilityResult = AiCapabilityResult(AiCapabilityStatus.RESPONSE_INCOMPLETE),
            worldBriefConfigured = true,
            worldBriefProviderName = "合成世界信息",
            worldBriefEndpoint = "https://example.invalid/world",
        ))
        var sampleChecks = 0
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                JianyuTheme {
                    Box(Modifier.width(360.dp).height(720.dp)) {
                        SettingsScreen(
                            state = state,
                            onSaveAi = { _, _, _, _ -> error("No credential should be saved") },
                            onCheckAi = { sampleChecks++ },
                            onRemoveAi = { error("Synthetic source should not be disconnected") },
                            onSaveWorldBrief = { _, _, _ -> error("No source should be saved") },
                            onRemoveWorldBrief = { error("Synthetic source should not be disconnected") },
                            onConnectSyncFolder = { error("No folder should be opened") },
                            onRetrySyncFolderSettings = { error("No folder settings should be retried") },
                            onSyncFolder = { error("No sync should run") },
                            onDisconnectSyncFolder = { error("No folder should be disconnected") },
                            onExportPortable = { error("No export should run") },
                            onPreviewPortable = { _, _ -> error("No import should run") },
                            onConfirmPortableImport = { error("No import should run") },
                            onCancelPortableImport = {},
                            onDismissRecoveryCode = {},
                            onErase = { error("No Vault should be erased") },
                        )
                    }
                }
            }
        }

        val list = composeRule.onNodeWithTag("settings-list")
        list.performScrollToNode(hasText("查看连接检查"))
        composeRule.onNodeWithText("试一次虚构样例").assertDoesNotExist()
        saveScreenshot("settings-connected-ai-collapsed.png")
        composeRule.onNodeWithText("查看连接检查").assertIsDisplayed().performClick()
        list.performScrollToNode(hasText("服务商可能收费", substring = true))
        composeRule.onNodeWithText("服务商可能收费", substring = true).assertIsDisplayed()
        list.performScrollToNode(hasText("试一次虚构样例"))
        composeRule.onNodeWithText("试一次虚构样例").assertIsDisplayed().performClick()
        assertEquals(1, sampleChecks)
        list.performScrollToNode(hasText("服务返回的内容未完整结束；这次虚构样例不能判断模型是否合适，请稍后重试。"))
        composeRule.onNodeWithText("服务返回的内容未完整结束；这次虚构样例不能判断模型是否合适，请稍后重试。").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("更换连接").fetchSemanticsNodes().isNotEmpty())
        saveScreenshot("settings-connected-ai-actions.png")

        composeRule.runOnIdle { state = state.copy(aiCapabilityResult = AiCapabilityResult(AiCapabilityStatus.RESPONSE_TIMED_OUT)) }
        val timeoutCopy = "等待服务响应超时；这次虚构样例不能判断模型是否合适。重试可能再次计费。"
        list.performScrollToNode(hasText(timeoutCopy))
        composeRule.onNodeWithText(timeoutCopy).assertIsDisplayed()
        saveScreenshot("settings-connected-ai-timeout.png")

        composeRule.runOnIdle { state = state.copy(aiCapabilityResult = AiCapabilityResult(AiCapabilityStatus.NO_DISPLAYABLE_DOORS)) }
        val noDoorCopy = "样例返回了可读入口，但本机没有可展示的孩子入口；这次不能据此判断模型适合。"
        list.performScrollToNode(hasText(noDoorCopy))
        composeRule.onNodeWithText(noDoorCopy).assertIsDisplayed()
        saveScreenshot("settings-connected-ai-no-door.png")

        composeRule.runOnIdle { state = state.copy(aiCapabilityResult = AiCapabilityResult(AiCapabilityStatus.SAMPLE_PASSED, acceptedCount = 2)) }
        val passedCopy = "这次虚构样例有 2 个经过本机筛选、可展示的入口；仍需家庭判断。"
        list.performScrollToNode(hasText(passedCopy))
        composeRule.onNodeWithText(passedCopy).assertIsDisplayed()

        list.performScrollToNode(hasText("服务：合成世界信息"))
        list.performScrollToNode(hasText("地区可能原样发送", substring = true))
        composeRule.onNodeWithText("地区可能原样发送", substring = true).assertIsDisplayed()
        list.performScrollToNode(hasText("查看信息边界"))
        composeRule.onNodeWithText("兴趣匹配在本机", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("查看信息边界").assertIsDisplayed().performClick()
        list.performScrollToNode(hasText("兴趣匹配在本机", substring = true))
        composeRule.onNodeWithText("兴趣匹配在本机", substring = true).assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("更换连接").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("断开").fetchSemanticsNodes().isNotEmpty())
        saveScreenshot("settings-connected-world-actions.png")
    }

    @Test
    fun disconnectingEitherSavedSourceRequiresExplicitConfirmation() {
        var state by mutableStateOf(MainUiState(
            loading = false,
            family = FamilyState(
                household = Household("synthetic-household", "虚构家庭", "2026-09-27T00:00:00Z"),
                members = emptyList(),
                children = emptyList(),
            ),
            aiConfigured = true,
            aiProviderName = "合成 AI",
            aiBaseUrl = "https://example.invalid/v1",
            aiModel = "synthetic-model",
            worldBriefConfigured = true,
            worldBriefProviderName = "合成世界信息",
            worldBriefEndpoint = "https://example.invalid/world",
        ))
        var aiRemovals = 0
        var worldRemovals = 0
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                JianyuTheme {
                    Box(Modifier.width(360.dp).height(720.dp)) {
                        SettingsScreen(
                            state = state,
                            onSaveAi = { _, _, _, _ -> error("No settings should be saved") },
                            onCheckAi = { error("No network request should run") },
                            onRemoveAi = { aiRemovals++; state = state.copy(aiConfigured = false) },
                            onSaveWorldBrief = { _, _, _ -> error("No settings should be saved") },
                            onRemoveWorldBrief = { worldRemovals++; state = state.copy(worldBriefConfigured = false) },
                            onConnectSyncFolder = {},
                            onRetrySyncFolderSettings = {},
                            onSyncFolder = {},
                            onDisconnectSyncFolder = {},
                            onExportPortable = {},
                            onPreviewPortable = { _, _ -> },
                            onConfirmPortableImport = {},
                            onCancelPortableImport = {},
                            onDismissRecoveryCode = {},
                            onErase = { error("No Vault should be erased") },
                        )
                    }
                }
            }
        }

        val list = composeRule.onNodeWithTag("settings-list")
        list.performScrollToNode(hasText("服务：合成 AI"))
        list.performScrollToNode(hasText("断开"))
        composeRule.onNodeWithText("断开").performClick()
        composeRule.onNodeWithText("断开 AI 服务？").assertIsDisplayed()
        composeRule.onNodeWithText("家庭保险箱不受影响", substring = true).assertIsDisplayed()
        assertEquals(0, aiRemovals)
        Thread.sleep(400) // Let the Android dialog window's dim/enter animation settle before the screenshot.
        saveScreenshot("settings-ai-disconnect-confirm-large.png", dialogRoot = true)
        composeRule.onNodeWithText("保留连接").performClick()
        assertEquals(0, aiRemovals)
        composeRule.onNodeWithText("断开").performClick()
        composeRule.onNodeWithText("确认断开").performClick()
        assertEquals(1, aiRemovals)

        list.performScrollToNode(hasText("服务：合成世界信息"))
        list.performScrollToNode(hasText("断开"))
        composeRule.onNodeWithText("断开").performClick()
        composeRule.onNodeWithText("断开世界信息服务？").assertIsDisplayed()
        composeRule.onNodeWithText("已发送的查询无法撤回", substring = true).assertIsDisplayed()
        assertEquals(0, worldRemovals)
        Thread.sleep(400)
        saveScreenshot("settings-world-disconnect-confirm-large.png", dialogRoot = true)
        composeRule.onNodeWithText("确认断开").performClick()
        assertEquals(1, worldRemovals)
        Unit
    }

    @Test
    fun failedLocalConnectionSaveStaysVisibleInsideTheMatchingForm() {
        val failureText = "本机保存没有完成。请检查设备存储状态后重试。"
        var state by mutableStateOf(MainUiState(
            loading = false,
            family = FamilyState(
                household = Household("synthetic-household", "虚构家庭", "2026-09-27T00:00:00Z"),
                members = emptyList(),
                children = emptyList(),
            ),
        ))
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                JianyuTheme {
                    Box(Modifier.width(360.dp).height(720.dp)) {
                        SettingsScreen(
                            state = state,
                            onSaveAi = { _, _, _, _ -> error("No credential should be saved") },
                            onClearSourceFormError = { kind ->
                                if (state.sourceFormError == kind) state = state.copy(sourceFormError = null, error = null)
                            },
                            onCheckAi = {},
                            onRemoveAi = {},
                            onSaveWorldBrief = { _, _, _ -> error("No source should be saved") },
                            onRemoveWorldBrief = {},
                            onConnectSyncFolder = {},
                            onRetrySyncFolderSettings = {},
                            onSyncFolder = {},
                            onDisconnectSyncFolder = {},
                            onExportPortable = {},
                            onPreviewPortable = { _, _ -> },
                            onConfirmPortableImport = {},
                            onCancelPortableImport = {},
                            onDismissRecoveryCode = {},
                            onErase = {},
                        )
                    }
                }
            }
        }

        val list = composeRule.onNodeWithTag("settings-list")
        list.performScrollToNode(hasText("连接 AI 服务"))
        composeRule.onNodeWithText("连接 AI 服务").performClick()
        composeRule.runOnIdle { state = state.copy(sourceFormError = SourceFormKind.AI, error = failureText) }
        list.performScrollToNode(hasText(failureText))
        composeRule.onNodeWithText(failureText).assertIsDisplayed()
        composeRule.onNodeWithText("保存连接").performScrollTo().assertIsDisplayed()
        saveScreenshot("settings-ai-local-save-error-large.png")

        list.performScrollToNode(hasText("取消"))
        composeRule.onNodeWithText("取消").performClick()
        list.performScrollToNode(hasText("连接世界信息服务"))
        composeRule.onNodeWithText("连接世界信息服务").performClick()
        composeRule.runOnIdle { state = state.copy(sourceFormError = SourceFormKind.WORLD, error = failureText) }
        list.performScrollToNode(hasText(failureText))
        composeRule.onNodeWithText(failureText).assertIsDisplayed()
    }

    @Test
    fun selectedFolderIsNotPresentedAsAutomaticSync() {
        val state = MainUiState(
            loading = false,
            family = FamilyState(
                household = Household("synthetic-household", "虚构家庭", "2026-09-27T00:00:00Z"),
                members = emptyList(),
                children = emptyList(),
            ),
            folderSyncConfigured = true,
            folderSyncName = "虚构文件夹",
        )
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                JianyuTheme {
                    Box(Modifier.width(360.dp).height(720.dp)) {
                        SettingsScreen(
                            state = state,
                            onSaveAi = { _, _, _, _ -> error("No source should be saved") },
                            onCheckAi = {},
                            onRemoveAi = {},
                            onSaveWorldBrief = { _, _, _ -> error("No source should be saved") },
                            onRemoveWorldBrief = {},
                            onConnectSyncFolder = { error("No folder should be opened") },
                            onRetrySyncFolderSettings = { error("No folder settings should be retried") },
                            onSyncFolder = { error("No transfer should start") },
                            onDisconnectSyncFolder = { error("No folder should be disconnected") },
                            onExportPortable = { error("No export should start") },
                            onPreviewPortable = { _, _ -> error("No import should start") },
                            onConfirmPortableImport = { error("No import should start") },
                            onCancelPortableImport = {},
                            onDismissRecoveryCode = {},
                            onErase = { error("No Vault should be erased") },
                        )
                    }
                }
            }
        }

        val list = composeRule.onNodeWithTag("settings-list")
        list.performScrollToNode(hasText("已选择文件夹；不会自动同步", substring = true))
        composeRule.onNodeWithText("已选择文件夹；不会自动同步", substring = true).assertIsDisplayed()
        list.performScrollToNode(hasText("已选择文件夹：虚构文件夹"))
        composeRule.onNodeWithText("已选择文件夹：虚构文件夹").assertIsDisplayed()
        composeRule.onNodeWithText("已连接：虚构文件夹").assertDoesNotExist()
        list.performScrollToNode(hasText("它不是完整同步", substring = true))
        composeRule.onNodeWithText("它不是完整同步", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("文件夹服务看不到家庭内容", substring = true).assertExists()
        list.performScrollToNode(hasText("立即传输一次"))
        composeRule.onNodeWithText("立即传输一次").assertIsDisplayed()
        saveScreenshot("settings-selected-folder-preview-large.png")
    }

    @Test
    fun unreadableFolderSettingsShowRetryInsteadOfNewKeySetup() {
        var retries = 0
        val state = MainUiState(
            loading = false,
            family = FamilyState(
                household = Household("synthetic-household", "虚构家庭", "2026-09-27T00:00:00Z"),
                members = emptyList(),
                children = emptyList(),
            ),
            folderSyncSettingsUnreadable = true,
        )
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                JianyuTheme {
                    Box(Modifier.width(360.dp).height(720.dp)) {
                        SettingsScreen(
                            state = state,
                            onSaveAi = { _, _, _, _ -> error("No source should be saved") },
                            onCheckAi = {},
                            onRemoveAi = {},
                            onSaveWorldBrief = { _, _, _ -> error("No source should be saved") },
                            onRemoveWorldBrief = {},
                            onConnectSyncFolder = { error("Unreadable settings must not create a new key") },
                            onRetrySyncFolderSettings = { retries++ },
                            onSyncFolder = { error("No transfer should start") },
                            onDisconnectSyncFolder = { error("No folder should be disconnected") },
                            onExportPortable = {},
                            onPreviewPortable = { _, _ -> },
                            onConfirmPortableImport = {},
                            onCancelPortableImport = {},
                            onDismissRecoveryCode = {},
                            onErase = { error("No Vault should be erased") },
                        )
                    }
                }
            }
        }

        val list = composeRule.onNodeWithTag("settings-list")
        list.performScrollToNode(hasText("先前的文件夹连接信息暂时无法读取", substring = true))
        composeRule.onNodeWithText("先前的文件夹连接信息暂时无法读取", substring = true).assertIsDisplayed()
        list.performScrollToNode(hasText("重试读取"))
        composeRule.onNodeWithText("重试读取").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
        composeRule.onNodeWithText("选择文件夹").assertDoesNotExist()
        composeRule.onNodeWithText("立即传输一次").assertDoesNotExist()
        list.performScrollToNode(hasText("放弃旧连接"))
        composeRule.onNodeWithText("放弃旧连接").performClick()
        composeRule.onNodeWithText("旧文件夹里的密文不会删除", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("保留连接").performClick()
        saveScreenshot("settings-unreadable-folder-large.png")
    }

    private fun saveScreenshot(name: String, dialogRoot: Boolean = false) {
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name)
        output.outputStream().use { stream ->
            val image = if (dialogRoot) {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            } else {
                composeRule.onRoot().captureToImage().asAndroidBitmap()
            }
            check(image.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }
}
