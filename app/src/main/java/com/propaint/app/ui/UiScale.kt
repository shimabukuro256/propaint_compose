package com.propaint.app.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * UI スケールファクター。タブレット向けに一括拡大。
 * 変更したい場合は FACTOR を調整するだけで全 UI に反映。
 */
object UiScale {
    const val FACTOR = 1.5f

    /** dp をスケーリング */
    val Int.sdp: Dp get() = (this * FACTOR).dp
    val Float.sdp: Dp get() = (this * FACTOR).dp
    val Double.sdp: Dp get() = (this.toFloat() * FACTOR).dp

    /** sp をスケーリング */
    val Int.ssp: TextUnit get() = (this * FACTOR).sp
    val Float.ssp: TextUnit get() = (this * FACTOR).sp
}
