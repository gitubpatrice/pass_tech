package com.filestech.pass_tech.core.security

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.textclassifier.TextClassification
import android.view.textclassifier.TextClassificationContext
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.filestech.pass_tech.MainActivity
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The selection toolbar must not hand an entry's text to another app: see [keepSelectedTextInApp].
 *
 * On the real activity, where the fields live: the classifier manager Compose reads, a session made from
 * it as Compose makes one, the context a dialog or a sheet wraps, and the Compose flag that lists other
 * apps' text actions. The control, the application's own manager keeping the system
 * classifier, shows the first test can fail and that the setting stays local to the activity.
 */
@SdkSuppress(minSdkVersion = 28)
@RunWith(AndroidJUnit4::class)
class KeepSelectedTextInAppOnDeviceTest {

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun close() {
        scenario.close()
    }

    @Test
    fun theActivityTurnsTheTextClassifierOff() {
        assertThat(activityManager().textClassifier).isSameInstanceAs(TextClassifier.NO_OP)
    }

    /** Compose must list no other app's text action, on any version: package visibility is granted per app. */
    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun composeListsNoOtherAppsTextAction() {
        activityManager() // the activity has run its onCreate
        assertThat(ComposeFoundationFlags.isSmartSelectionEnabled).isFalse()
    }

    /** What Compose does before showing the toolbar: a session, then a classification. */
    @Test
    fun aSessionMadeFromTheActivityOffersNoAction() {
        val session = activityManager().createTextClassificationSession(
            TextClassificationContext.Builder(
                ApplicationProvider.getApplicationContext<Context>().packageName,
                TextClassifier.WIDGET_TYPE_TEXTVIEW,
            ).build(),
        )
        val classification = session.classifyText(TextClassification.Request.Builder(WORD, 0, WORD.length).build())
        session.destroy()

        assertThat(classification.actions).isEmpty()
    }

    /** A Compose dialog or bottom sheet wraps the activity's context: it reads the same manager. */
    @Test
    fun aWindowWrappingTheActivityReadsTheSameManager() {
        var ofTheWindow: TextClassificationManager? = null
        var ofTheActivity: TextClassificationManager? = null
        scenario.onActivity { activity ->
            ofTheActivity = activity.getSystemService(TextClassificationManager::class.java)
            ofTheWindow = ContextThemeWrapper(activity, android.R.style.Theme_DeviceDefault_Dialog)
                .getSystemService(TextClassificationManager::class.java)
        }
        assertThat(ofTheWindow).isSameInstanceAs(ofTheActivity)
    }

    @Test
    fun theControlTheApplicationManagerKeepsTheSystemClassifier() {
        val application = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(TextClassificationManager::class.java)
        assertThat(application.textClassifier).isNotSameInstanceAs(TextClassifier.NO_OP)
    }

    private fun activityManager(): TextClassificationManager {
        var manager: TextClassificationManager? = null
        scenario.onActivity { manager = it.getSystemService(TextClassificationManager::class.java) }
        return checkNotNull(manager)
    }

    private companion object {
        const val WORD = "Intensive"
    }
}
