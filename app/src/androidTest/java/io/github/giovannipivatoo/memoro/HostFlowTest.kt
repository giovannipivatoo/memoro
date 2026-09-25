package io.github.giovannipivatoo.memoro

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import io.github.giovannipivatoo.memoro.data.ArchiveSnapshot
import io.github.giovannipivatoo.memoro.data.RoomMemoroRepository
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual activity, settings and saved-state host rather than a test composition. */
@RunWith(AndroidJUnit4::class)
class HostFlowTest {
    val compose = createAndroidComposeRule<MainActivity>()
    private val cleanArchive = object : ExternalResource() {
        override fun before() = runBlocking {
            val repo = RoomMemoroRepository.open(ApplicationProvider.getApplicationContext())
            try { repo.restoreSnapshot(ArchiveSnapshot()) } finally { repo.close() }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(cleanArchive).around(compose)

    private fun capture(name: String) {
        compose.waitForIdle()
        // Native IME/dialog windows finish their own transitions outside Compose's clock.
        android.os.SystemClock.sleep(500)
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            instrumentation.targetContext.getExternalFilesDir(null)!!.resolve("$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    private fun waitTag(tag: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun actualHostRetainsDraftAndPetSettingAfterActivityRecreation() {
        val deckName = "Scoperte quotidiane"
        compose.onNodeWithText("Impostazioni").performClick()
        compose.onNodeWithTag("petSwitch").performScrollTo()
        if (compose.onAllNodes(hasTestTag("petSwitch") and isOff()).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("petSwitch").performClick()
        }
        capture("settings-verified")
        compose.onNodeWithText("Mazzi").performClick()
        compose.onNodeWithTag("addDeck").performScrollTo().performClick()
        compose.onNodeWithTag("deckName").performTextInput(deckName)
        compose.onNodeWithText("Salva").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(deckName).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(deckName).performScrollTo().performClick()
        compose.onNodeWithTag("addNote").performClick()
        waitTag("noteFront")
        compose.onNodeWithTag("noteFront").performTextInput("Quale pianeta abitiamo?")
        compose.onNodeWithTag("noteBack").performScrollTo().performTextInput("Terra")
        capture("editor-keyboard-verified")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            .performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitForIdle()
        capture("editor-verified")
        compose.onNodeWithTag("saveNote").performClick()
        waitTag("studyDeck")
        compose.onNodeWithContentDescription("Opzioni carta 1").performScrollTo().performClick()
        compose.onNodeWithText("Modalità risposta").performClick()
        compose.onNodeWithTag("mode-EXACT").performClick()
        compose.onNodeWithText("Salva modalità").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("studyDeck") and isEnabled() and hasText("Studia ora"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        capture("deck-verified")
        compose.onNodeWithContentDescription("Indietro").performClick()
        waitTag("startReview")
        capture("home-verified")
        compose.onNodeWithText(deckName).performClick()
        compose.onNodeWithTag("studyDeck").performScrollTo().performClick()
        waitTag("question")
        waitTag("answerInput")
        capture("question-verified")
        compose.onNodeWithTag("answerInput").performTextInput("Terra")
        compose.activityRule.scenario.recreate()
        waitTag("answerInput")
        compose.onNodeWithTag("answerInput").assertTextContains("Terra")
        compose.onNodeWithTag("submitAnswer").performScrollTo().performClick()
        waitTag("referenceAnswer")
        compose.onNodeWithText("Esito automatico: Corretta").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Gattino festeggia").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rate-EASY").performScrollTo()
        capture("study-verified")
        compose.onNodeWithTag("rate-GOOD").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Sessione completata").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Torna al mazzo").performClick()
        compose.onNodeWithText("Cronologia").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Ripassi recenti").fetchSemanticsNodes().isNotEmpty() }
        capture("history-verified")
    }
}
