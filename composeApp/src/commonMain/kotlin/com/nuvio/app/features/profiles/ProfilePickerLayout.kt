package com.nuvio.app.features.profiles

internal data class ProfilePickerLayout(val columns: Int, val cardWidthDp: Float)

/** Width excludes the parent's horizontal padding; no extra safe-area inset is added here. */
internal fun profilePickerLayout(availableWidthDp: Float, fontScale: Float): ProfilePickerLayout {
    val width = availableWidthDp.coerceAtLeast(0f)
    val columns = if (width >= 272f && fontScale < 1.4f) 2 else 1
    val cardWidth = if (columns == 2) ((width - 20f) / 2f).coerceAtMost(150f)
        else width.coerceAtMost(220f)
    return ProfilePickerLayout(columns, cardWidth)
}
