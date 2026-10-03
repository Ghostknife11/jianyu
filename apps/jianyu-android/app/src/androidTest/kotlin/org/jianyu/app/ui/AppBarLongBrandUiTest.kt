package org.jianyu.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.jianyu.app.ui.theme.JianyuTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A long fork BrandConfig must not displace the always-visible local-storage boundary. */
@RunWith(AndroidJUnit4::class)
class AppBarLongBrandUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun longBrandStaysInsideTitleSpaceAtLargeText() {
        val longBrand = "很长的家庭机会项目名称也应该能在手机顶部被安全显示"
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                JianyuTheme {
                    Box(Modifier.width(360.dp).height(80.dp)) {
                        JianyuTopAppBar(longBrand)
                    }
                }
            }
        }

        val brand = composeRule.onNodeWithText(longBrand)
        val boundary = composeRule.onNodeWithText("本机加密保存")
        brand.assertIsDisplayed()
        boundary.assertIsDisplayed()
        val brandBounds = brand.fetchSemanticsNode().boundsInRoot
        val boundaryBounds = boundary.fetchSemanticsNode().boundsInRoot
        assertTrue("Long brand overlaps the encryption status", brandBounds.right <= boundaryBounds.left)

        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "app-bar-long-brand.png")
            .outputStream().use { stream ->
                check(composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
    }
}
