package com.filestech.pass_tech.core.security

import android.app.Activity
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi

/**
 * The selection toolbar does not hand an entry's text to another app.
 *
 * That toolbar opens over every selection, in a field or in a `SelectionContainer`: an entry's notes, user name
 * and address, the heir's view, the heir password typed in clear. Two doors led out of the app through it.
 *
 * 1. **Android's text classifier.** On a phone with Google's apps, a long press on a word offers **"Translate"**,
 *    first in the toolbar: one tap hands the selected text to the Google app, which looks it up online. Compose
 *    asks the classifier of the screen's context, this activity, through its [TextClassificationManager]; a
 *    dialog or a bottom sheet wraps that context and reads the same manager. [TextClassifier.NO_OP], set here,
 *    covers every field of every window of the activity; the application's own manager keeps the system
 *    classifier. Notes Tech measured it and closed it in 3.1.1.
 * 2. **Other apps' text actions** (`ACTION_PROCESS_TEXT`). Compose adds to the toolbar every VISIBLE app that
 *    declares one, and any app may, under any name. Package visibility does not keep them out: before Android
 *    11 it does not exist, and from 11 on it is granted per WHOLE app — any app the manifest's `<queries>` reach
 *    becomes visible with all its text actions. [ComposeFoundationFlags.isSmartSelectionEnabled] set to `false`
 *    takes those actions, and smart selection, out of every Compose toolbar of the process, on every version.
 *
 * What is lost: smart selection (a long press selecting a whole address), the classifier's actions (call, open a
 * map, translate) and reading aloud from the toolbar, still reachable through its own accessibility shortcut.
 * Cut, Copy, Paste and Select all remain: the toolbar keeps editing only, as Notes Tech's note body does. The
 * panic-disguise calculator shows no selectable text and does not call this.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Activity.keepSelectedTextInApp() {
    getSystemService(TextClassificationManager::class.java)?.setTextClassifier(TextClassifier.NO_OP)
    ComposeFoundationFlags.isSmartSelectionEnabled = false
}
