package io.github.giovannipivatoo.memoro

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual activity, settings and saved-state host rather than a test composition. */
@RunWith(AndroidJUnit4::class)
class HostFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun waitTag(tag: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun actualHostRetainsDraftAndPetSettingAfterActivityRecreation() {
        val deckName = "Memoro dispositivo ${System.currentTimeMillis()}"
        compose.onNodeWithText("Impostazioni").performClick()
        compose.onNodeWithTag("petSwitch").performScrollTo()
        if (compose.onAllNodes(hasTestTag("petSwitch") and isOff()).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("petSwitch").performClick()
        }
        compose.onNodeWithText("Mazzi").performClick()
        compose.onNodeWithTag("addDeck").performClick()
        compose.onNodeWithTag("deckName").performTextInput(deckName)
        compose.onNodeWithText("Salva").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(deckName).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(deckName).performScrollTo().performClick()
        compose.onNodeWithTag("addNote").performClick()
        waitTag("noteFront")
        compose.onNodeWithTag("noteFront").performTextInput("Quale pianeta abitiamo?")
        compose.onNodeWithTag("noteBack").performScrollTo().performTextInput("Terra")
        compose.onNodeWithTag("saveNote").performScrollTo().performClick()
        waitTag("studyDeck")
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.onNodeWithText("Esatta").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("studyDeck") and isEnabled() and hasText("Studia"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("studyDeck").performClick()
        waitTag("question")
        compose.onNodeWithText("Esatta").performClick()
        waitTag("answerInput")
        compose.onNodeWithTag("answerInput").performTextInput("Terra")
        compose.activityRule.scenario.recreate()
        waitTag("answerInput")
        compose.onNodeWithTag("answerInput").assertTextContains("Terra")
        compose.onNodeWithTag("submitAnswer").performScrollTo().performClick()
        waitTag("referenceAnswer")
        compose.onNodeWithText("Esito automatico: Corretta").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Gattino festeggia").performScrollTo().assertIsDisplayed()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            instrumentation.targetContext.getExternalFilesDir(null)!!.resolve("study-verified.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        compose.onNodeWithTag("rate-GOOD").performScrollTo().performClick()
    }
}
