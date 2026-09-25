// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.giovannipivatoo.memoro.ai.DeepSeekClient
import io.github.giovannipivatoo.memoro.data.*
import io.github.giovannipivatoo.memoro.ui.AppActions
import io.github.giovannipivatoo.memoro.ui.MemoroApp
import io.github.giovannipivatoo.memoro.ui.MemoroTheme
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class DeepSeekStudyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var repo: RoomMemoroRepository
    private var deckId = 0L

    @Before fun prepare(): Unit = runBlocking {
        compose.activityRule.scenario.onActivity {
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        repo = RoomMemoroRepository.open(ApplicationProvider.getApplicationContext())
        repo.restoreSnapshot(ArchiveSnapshot())
        deckId = repo.saveDeck(Deck(name = "Prova correzione AI")).id
        repo.saveNote(Note(deckId = deckId, fields = listOf("Qual è la capitale d'Italia?", "Roma")))
        repo.snapshot().cards.single().let { repo.saveCard(it.copy(modes = setOf(AnswerMode.AI))) }
    }

    @After fun cleanup() { repo.close() }

    private fun waitTag(tag: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun hideKeyboard() {
        compose.runOnUiThread {
            val window = compose.activity.window
            WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(5_000) {
            compose.runOnUiThread {
                ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == false
            }
        }
    }

    @Test fun aiSuccessPersistsFeedbackAndOverrideBeforeOnlyHumanRatingSchedules() {
        val calls = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            calls.incrementAndGet()
            val content = """{"verdict":"CORRECT","explanation":"Roma è la capitale.","errors":[],"omissions":[],"source_conflict":false}"""
            val json = """{"choices":[{"finish_reason":"stop","message":{"content":${kotlinx.serialization.json.JsonPrimitive(content)}}}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(json.toResponseBody()).build()
        }).build()
        val actions = AppActions(apiKey = { "fixture-key" }, saveApiKey = {}, model = { "deepseek-flash" }, saveModel = {},
            petEnabled = { false }, savePetEnabled = {}, importApkg = { "" }, exportApkg = { "" }, backup = { "" }, restore = { "" })
        val before = runBlocking { repo.snapshot().cards.single().scheduling }
        compose.setContent { MemoroTheme { MemoroApp(repo, actions, DeepSeekClient(http)) } }
        waitTag("deck-$deckId")
        compose.onNodeWithTag("deck-$deckId").performClick()
        compose.onNodeWithTag("studyDeck").performClick()
        waitTag("answerInput")
        compose.onNodeWithTag("answerInput").performTextInput("Roma")
        hideKeyboard()
        compose.onNodeWithTag("submitAnswer").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        waitTag("referenceAnswer")
        compose.onNodeWithText("Esito automatico: Corretta").performScrollTo().assertExists()
        compose.onNodeWithText("Roma è la capitale.").performScrollTo().assertExists()
        val evaluated = runBlocking { repo.snapshot() }
        assertEquals(1, calls.get())
        assertEquals(AttemptState.EVALUATED, evaluated.attempts.single().state)
        assertEquals(Outcome.CORRECT, evaluated.attempts.single().automaticOutcome)
        assertEquals(Outcome.CORRECT, evaluated.attempts.single().finalOutcome)
        assertTrue(evaluated.attempts.single().feedback.contains("Roma è la capitale."))
        assertTrue(evaluated.reviews.isEmpty())
        assertEquals(before, evaluated.cards.single().scheduling)

        compose.onNodeWithText("Modifica esito").performScrollTo().performClick()
        compose.onNodeWithText("Parziale").performClick()
        compose.waitUntil(10_000) {
            runBlocking { repo.snapshot().attempts.single().finalOutcome == Outcome.PARTIAL }
        }
        val overridden = runBlocking { repo.snapshot() }
        assertEquals(Outcome.CORRECT, overridden.attempts.single().automaticOutcome)
        assertEquals(Outcome.PARTIAL, overridden.attempts.single().finalOutcome)
        assertEquals(before, overridden.cards.single().scheduling)
        assertTrue(overridden.reviews.isEmpty())

        compose.onNodeWithTag("rate-GOOD").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { repo.snapshot().reviews.size == 1 } }
        val reviewed = runBlocking { repo.snapshot() }
        assertEquals(Rating.GOOD, reviewed.reviews.single().rating)
        assertEquals(Outcome.PARTIAL, reviewed.attempts.single().finalOutcome)
        assertNotEquals(before, reviewed.cards.single().scheduling)
        assertEquals(1, calls.get())
    }
}
