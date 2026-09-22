package com.filestech.pass_tech.ui.components

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Every text field inside asks the keyboard not to learn what is typed (no personalised learning,
 * no suggestions): keyboards otherwise offer it again in other apps and may sync it to their
 * publisher's cloud. 2.7.1 turned suggestions off field by field and missed some (SEC 2026-08-03 and
 * 2026-08-04); here it holds for the whole app, fields to come included.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PrivateKeyboard(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, next ->
            next.startInputMethod(
                object : PlatformTextInputMethodRequest {
                    override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                        val connection = request.createInputConnection(outAttributes)
                        outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                        // A text flag: on a number field the same bit means something else.
                        if (outAttributes.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT) {
                            outAttributes.inputType = outAttributes.inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        }
                        return connection
                    }
                },
            )
        },
        content = content,
    )
}
