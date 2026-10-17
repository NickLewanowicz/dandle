package net.babybuddy.next.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/**
 * P3-8: dialog and bottom-sheet WINDOWS have their own semantics root — the
 * `testTagsAsResourceId` flag set on the activity root does not reach them, so test tags inside
 * dialogs never appear as resource-ids for UiAutomator. Apply this modifier alongside `.testTag`
 * on every AlertDialog / ModalBottomSheet / Dialog container.
 */
fun Modifier.dialogTestTags(): Modifier =
    this
        .semantics { testTagsAsResourceId = true }
