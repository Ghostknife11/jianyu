package org.jianyu.app.ui

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import org.jianyu.app.R

/** Jianyu's open-corner mark. It is decorative when the adjacent brand name is visible. */
@Composable
internal fun JianyuMark(
    modifier: Modifier = Modifier,
    tint: Color,
) {
    Icon(
        painter = painterResource(R.drawable.ic_jianyu_mark),
        contentDescription = null,
        modifier = modifier,
        tint = tint,
    )
}
