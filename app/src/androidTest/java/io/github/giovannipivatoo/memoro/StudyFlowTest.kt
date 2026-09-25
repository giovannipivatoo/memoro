package io.github.giovannipivatoo.memoro

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.giovannipivatoo.memoro.data.*
import io.github.giovannipivatoo.memoro.ui.AppActions
import io.github.giovannipivatoo.memoro.ui.MemoroApp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudyFlowTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var repo: RoomMemoroRepository
    private var deckId = 0L
    private var cardId = 0L

    @Before fun prepare(): Unit = runBlocking {
        repo = RoomMemoroRepository.open(ApplicationProvider.getApplicationContext())
        repo.restoreSnapshot(ArchiveSnapshot())
        val deck = repo.saveDeck(Deck(name = "Geografia di prova"))
        deckId = deck.id
        repo.saveNote(Note(deckId = deck.id, fields = listOf("Capitale d'Italia?", "Roma"),
            source = SourceReference(excerpt = "Roma è la capitale d'Italia.", title = "Fonte di prova")))
        val card = repo.snapshot().cards.single()
        cardId = card.id
        repo.saveCard(card.copy(modes = setOf(AnswerMode.EXACT)))
    }

    @After fun cleanup() { repo.close() }

    private fun setApp(restoration: StateRestorationTester? = null, pet: Boolean = false) {
        val actions = AppActions(apiKey = { "" }, saveApiKey = {}, model = { "deepseek-flash" },
            saveModel = {}, petEnabled = { pet }, savePetEnabled = {}, importApkg = { "" },
            exportApkg = { "" }, backup = { "" }, restore = { "" })
        val content: @Composable () -> Unit = { MaterialTheme { MemoroApp(repo, actions) } }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
    }

    private fun launch(pet: Boolean = false, restoration: StateRestorationTester? = null) {
        setApp(restoration, pet)
        waitTag("deck-$deckId")
        compose.onNodeWithTag("deck-$deckId").performClick()
        compose.onNodeWithTag("studyDeck").performClick()
        waitTag("answerInput")
    }

    private fun waitTag(tag: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun exactAnswerCanBeOverriddenAndOnlyHumanRatingSchedules() {
        launch(pet = true)
        compose.onNodeWithTag("referenceAnswer").assertDoesNotExist()
        compose.onNodeWithText("Roma è la capitale d'Italia.").assertDoesNotExist()
        compose.onNodeWithTag("answerInput").performTextInput("Rmoa")
        compose.onNodeWithTag("submitAnswer").performScrollTo().performClick()
        waitTag("referenceAnswer")
        compose.onNodeWithTag("referenceAnswer").assertTextContains("Roma")
        compose.onNodeWithText("Esito automatico: Errata").performScrollTo().assertIsDisplayed()
        assertEquals(0, runBlocking { repo.snapshot().reviews.size })
        compose.onNodeWithText("Corretta", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { repo.snapshot().attempts.any { it.finalOutcome == Outcome.CORRECT } } }
        compose.onNodeWithTag("rate-GOOD").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { repo.snapshot().reviews.size == 1 } }
        val snapshot = runBlocking { repo.snapshot() }
        assertEquals(Outcome.WRONG, snapshot.attempts.single().automaticOutcome)
        assertEquals(Outcome.CORRECT, snapshot.attempts.single().finalOutcome)
        assertEquals(Rating.GOOD, snapshot.reviews.single().rating)
        assertTrue(snapshot.cards.single().scheduling.dueAtMillis > snapshot.reviews.single().reviewedAtMillis)
    }

    @Test fun draftSurvivesLeavingStudyWithoutRevealingAnswer() {
        launch()
        compose.onNodeWithTag("answerInput").performTextInput("Una bozza")
        compose.waitUntil(10_000) { runBlocking { repo.snapshot().attempts.any { it.answer == "Una bozza" } } }
        compose.onNodeWithContentDescription("Indietro").performClick()
        compose.onNodeWithTag("studyDeck").performClick()
        waitTag("answerInput")
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("answerInput") and hasText("Una bozza")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("referenceAnswer").assertDoesNotExist()
        assertTrue(runBlocking { repo.snapshot().reviews.isEmpty() })
    }

    @Test fun unavailableAiPreservesAnswerAndAllowsSelfAssessment() {
        runBlocking { val card = repo.getCard(cardId)!!; repo.saveCard(card.copy(modes = setOf(AnswerMode.AI))) }
        launch()
        compose.onNodeWithTag("answerInput").performTextInput("Roma")
        compose.onNodeWithTag("submitAnswer").performScrollTo().performClick()
        waitTag("technicalError")
        assertEquals("Roma", runBlocking { repo.snapshot().attempts.single().answer })
        assertTrue(runBlocking { repo.snapshot().reviews.isEmpty() })
        // The app must offer an explicit local fallback, even with the AI mode enabled.
        compose.onNodeWithTag("selfAssess").performScrollTo().performClick()
        waitTag("referenceAnswer")
        compose.onNodeWithTag("rate-HARD").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { repo.snapshot().reviews.size == 1 } }
    }

    @Test fun manualNoteEditorPersistsNewContent() {
        setApp()
        waitTag("deck-$deckId")
        compose.onNodeWithTag("deck-$deckId").performClick()
        compose.onNodeWithTag("addNote").performClick()
        waitTag("noteFront")
        compose.onNodeWithTag("noteFront").performTextInput("Quanto fa 2 + 2?")
        compose.onNodeWithTag("noteBack").performScrollTo().performTextInput("4")
        compose.onNodeWithTag("saveNote").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { repo.snapshot().notes.size == 2 } }
        val note = runBlocking { repo.snapshot().notes.last() }
        assertEquals(listOf("Quanto fa 2 + 2?", "4"), note.fields)
        assertEquals(2, runBlocking { repo.snapshot().cards.size })
    }

    @Test fun evaluatedAnswerAndHumanOverrideSurviveStateRestoration() {
        val restoration = StateRestorationTester(compose)
        launch(restoration = restoration)
        compose.onNodeWithTag("answerInput").performTextInput("Rmoa")
        compose.onNodeWithTag("submitAnswer").performScrollTo().performClick()
        waitTag("referenceAnswer")
        compose.onNodeWithText("Corretta", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { repo.snapshot().attempts.any { it.finalOutcome == Outcome.CORRECT } } }
        restoration.emulateSavedInstanceStateRestore()
        waitTag("referenceAnswer")
        compose.onNodeWithText("Esito automatico: Errata").performScrollTo().assertExists()
        compose.onNodeWithTag("rate-EASY").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { repo.snapshot().reviews.size == 1 } }
        assertEquals(Outcome.CORRECT, runBlocking { repo.snapshot().attempts.single().finalOutcome })
    }
}
