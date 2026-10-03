package org.jianyu.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.jianyu.app.ui.theme.JianyuTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises the first-run UI with synthetic text; it never opens the Family Vault. */
@RunWith(AndroidJUnit4::class)
class OnboardingUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun introductionKeepsPrimaryActionVisibleAndFormStartsAtItsHeading() {
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                JianyuTheme {
                    Box(Modifier.width(360.dp).height(720.dp)) {
                        OnboardingScreen(
                            brandName = "见隅",
                            hero = "从一隅，看见更多可能。",
                            mission = "用 AI 缩小家庭获得教育机会的差距，而不是缩小孩子之间的差异。",
                            onSetup = { _, _, _, _ -> },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("开始建立家庭资料").assertIsDisplayed()
        composeRule.onNodeWithText("资料先留在自己手里").performScrollTo()
        composeRule.onNodeWithText("开始建立家庭资料").performScrollTo().performClick()
        composeRule.onNodeWithText("先认识你们").assertIsDisplayed()
        composeRule.onNodeWithText("第 2 步，共 2 步").assertIsDisplayed()
        composeRule.onNodeWithText("保存家庭资料").assertExists()
        composeRule.onNodeWithText(
            "资料只在本机。\n两位家长需共用设备。\n恢复包不会自动同步。",
        ).performScrollTo().assertIsDisplayed()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "onboarding-local-only-large.png")
            .outputStream().use { stream ->
                check(composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
            }

        composeRule.onNodeWithText("← 返回上一页").performScrollTo().performClick()
        composeRule.onNodeWithText("见隅").assertIsDisplayed()
    }

    @Test
    fun failedVaultOpenNeverShowsNewFamilyAction() {
        var retryCount = 0
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                JianyuTheme {
                    Box(Modifier.width(360.dp).height(720.dp)) {
                        VaultOpenFailureScreen(
                            "本机家庭资料没有正常打开。为保护现有内容，暂时不能新建家庭。请勿卸载或清除应用；可以重试打开。",
                        ) { retryCount++ }
                    }
                }
            }
        }

        composeRule.onNodeWithText("家庭资料暂时打不开").assertIsDisplayed()
        composeRule.onNodeWithText("开始建立家庭资料").assertDoesNotExist()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "vault-open-failed-large.png")
            .outputStream().use { stream ->
                check(composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        composeRule.onNodeWithText("重试打开").performClick()
        org.junit.Assert.assertEquals(1, retryCount)
    }

    @Test
    fun onboardingShowsLocalSaveFailureAndDisablesSecondSubmissionWhileSaving() {
        var saving by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        composeRule.setContent {
            JianyuTheme {
                Box(Modifier.width(360.dp).height(720.dp)) {
                    OnboardingScreen("见隅", "从一隅，看见更多可能。", "用 AI 缩小机会差距。", saving, error) { _, _, _, _ -> }
                }
            }
        }
        composeRule.onNodeWithText("开始建立家庭资料").performClick()
        composeRule.runOnIdle {
            error = "本机保存没有完成。请检查设备存储状态后重试。"
        }
        composeRule.onNodeWithText(error!!).assertExists()
        composeRule.runOnIdle { saving = true }
        composeRule.onNodeWithText("正在保存…").assertIsNotEnabled()
        composeRule.onNodeWithText("← 返回上一页").assertIsNotEnabled()
    }
}
