package io.github.superthom196.matv.ui

import android.os.SystemClock

/**
 * When Compose loses its focused node (a list re-populates, a dialog closes) it hands focus to the
 * first focusable in the tree, which is a tab; tabs must not treat that as the user choosing them.
 * The activity stamps every D-pad press here and tabs only switch on focus within that window.
 */
object DpadTracker {
    @Volatile var lastPressAt: Long = 0L
    fun stamp() { lastPressAt = SystemClock.uptimeMillis() }
    fun userNavigatedRecently(windowMs: Long = 250): Boolean = SystemClock.uptimeMillis() - lastPressAt < windowMs
}
