package com.filestech.pass_tech.core.security

import android.app.Activity
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier

/**
 * No text classifier in this activity: the selection toolbar must not hand an entry to another app.
 *
 * Android's text classifier adds its own actions to the toolbar of every selection, in a field or in
 * a `SelectionContainer`. On a phone with Google's apps, a long press on a word offers **"Translate"**,
 * first in the toolbar: one tap hands the selected text to the Google app, which looks it up online.
 * Notes Tech measured it and closed it in 3.1.1; here the same toolbar opens over an entry's notes,
 * user name and address, and over the heir's view.
 *
 * Compose asks the classifier of the screen's context, this activity, through its
 * [TextClassificationManager]; a dialog or a bottom sheet wraps that context and reads the same
 * manager. Set once here, [TextClassifier.NO_OP] covers every field of every window of the activity,
 * where a setting on each field would miss the next one anyone writes. The application's own
 * manager keeps the system classifier.
 *
 * What is lost: smart selection (a long press selecting a whole address) and the classifier's actions
 * (call, open a map, translate). Cut, Copy, Paste and Select all remain.
 */
fun Activity.turnOffTextClassifier() {
    getSystemService(TextClassificationManager::class.java)?.setTextClassifier(TextClassifier.NO_OP)
}
