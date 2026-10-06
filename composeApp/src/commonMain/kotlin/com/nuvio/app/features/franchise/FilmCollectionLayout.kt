package com.nuvio.app.features.franchise

/** Keep collection cards readable on narrow phones and with larger accessibility text. */
internal fun filmCollectionColumnCount(widthDp: Float, fontScale: Float): Int =
    ((widthDp - 32f) / (160f * fontScale.coerceAtLeast(1f))).toInt().coerceIn(1, 3)
