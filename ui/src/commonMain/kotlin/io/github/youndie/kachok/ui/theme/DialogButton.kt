package io.github.youndie.kachok.ui.theme

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable

/**
 * The button both dialogs end with, drawn by the current kit.
 *
 * Shared rather than copied. Two dialogs whose buttons are two private functions are two dialogs
 * that drift, and the drift is invisible until they are on screen together — which they never are.
 *
 * [destructive] is a third appearance and not a colour argument: what makes *Remove* different from
 * *Add* is that it cannot be undone, and a caller that had to remember to pass the error colour is
 * a caller that will one day forget.
 *
 * The defaults live here and not on [KachokKit.DialogButton], for the reason `Text`'s do (B-160).
 */
@Composable
internal fun RowScope.DialogButton(
    label: String,
    primary: Boolean,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    KachokTheme.kit.DialogButton(label, primary, enabled, destructive, onClick)
}
