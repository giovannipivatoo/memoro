package io.github.giovannipivatoo.memoro

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.giovannipivatoo.memoro.data.*
import io.github.giovannipivatoo.memoro.ui.AppActions
import io.github.giovannipivatoo.memoro.ui.MemoroApp
import io.github.giovannipivatoo.memoro.ui.MemoroTheme
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
        val content: @Composable () -> Unit = { MemoroTheme { MemoroApp(repo, actions) } }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
    }

    private fun launch(pet: Boolean = false, restoration: StateRestorationTester? = null) {
        setApp(restoration, pet)
        waitTag("deck-$deckId")
        compose.onNodeWithTag("deck-$deckId").performClick()
        compose.onNodeWithTag("studyDeck").performClick()
        waitTag("answerInput")
    }

    private fun waitTag(tag: String) {
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        } catch (error: ComposeTimeoutException) {
            val attempts = runBlocking { repo.snapshot().attempts }.map { "${it.mode}/${it.state}" }
            throw AssertionError("Missing $tag; attempts=$attempts\n${compose.onRoot().printToString()}", error)
        }
    }

    private fun capture(name: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("capture") != "true") return
        compose.waitForIdle()
        android.os.SystemClock.sleep(400)
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            instrumentation.targetContext.getExternalFilesDir(null)!!.resolve("$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
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
        compose.onNodeWithText("Modifica esito").performScrollTo().performClick()
        compose.onNodeWithText("Corretta").performClick()
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
        compose.onNodeWithTag("noteFront").performScrollTo().performTextInput("Quanto fa 2 + 2?")
        compose.onNodeWithTag("noteBack").performScrollTo().performTextInput("4")
        compose.onNodeWithTag("saveNote").performClick()
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
        compose.onNodeWithText("Modifica esito").performScrollTo().performClick()
        compose.onNodeWithText("Corretta").performClick()
        compose.waitUntil(10_000) { runBlocking { repo.snapshot().attempts.any { it.finalOutcome == Outcome.CORRECT } } }
        restoration.emulateSavedInstanceStateRestore()
        waitTag("referenceAnswer")
        compose.onNodeWithText("Esito automatico: Errata").performScrollTo().assertExists()
        compose.onNodeWithTag("rate-EASY").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { repo.snapshot().reviews.size == 1 } }
        assertEquals(Outcome.CORRECT, runBlocking { repo.snapshot().attempts.single().finalOutcome })
    }

    @Test fun homeReviewIncludesEveryDeckAndDoesNotCarryAnswersAcrossCards() {
        runBlocking {
            val secondDeck = repo.saveDeck(Deck(name = "Scienze di prova"))
            repo.saveNote(Note(deckId = secondDeck.id, fields = listOf("Pianeta che abitiamo?", "Terra")))
            repo.snapshot().cards.forEach { repo.saveCard(it.copy(modes = setOf(AnswerMode.EXACT))) }
        }
        setApp()
        waitTag("startReview")
        compose.onNodeWithTag("startReview").performClick()
        waitTag("answerInput")
        compose.onNodeWithText("Impostazioni").assertDoesNotExist()
        compose.onNodeWithTag("answerInput").performTextInput("Prima risposta")
        compose.onNodeWithTag("submitAnswer").performScrollTo().performClick()
        waitTag("referenceAnswer")
        compose.onNodeWithTag("rate-GOOD").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { repo.snapshot().reviews.size == 1 } }
        waitTag("answerInput")
        compose.onNodeWithTag("referenceAnswer").assertDoesNotExist()
        assertEquals("", compose.onNodeWithTag("answerInput").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("answerInput").performTextInput("Seconda risposta")
        compose.onNodeWithTag("submitAnswer").performScrollTo().performClick()
        waitTag("referenceAnswer")
        compose.onNodeWithTag("rate-GOOD").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { repo.snapshot().reviews.size == 2 } }
        val snapshot = runBlocking { repo.snapshot() }
        assertEquals(2, snapshot.reviews.map { it.cardId }.distinct().size)
        assertEquals(setOf("Prima risposta", "Seconda risposta"), snapshot.attempts.map { it.answer }.toSet())
    }

    @Test fun leavingUnsavedEditorRequiresAnExplicitChoice() {
        setApp()
        waitTag("deck-$deckId")
        compose.onNodeWithTag("deck-$deckId").performClick()
        compose.onNodeWithTag("addNote").performClick()
        waitTag("noteFront")
        compose.onNodeWithTag("noteFront").performScrollTo().performTextInput("Da conservare")
        compose.onNodeWithContentDescription("Indietro").performClick()
        compose.onNodeWithText("Scartare le modifiche?").assertIsDisplayed()
        compose.onNodeWithText("Continua a modificare").performClick()
        compose.onNodeWithTag("noteFront").assertTextContains("Da conservare")
        compose.onNodeWithContentDescription("Indietro").performClick()
        compose.onNodeWithText("Scarta").performClick()
        waitTag("studyDeck")
        assertEquals(1, runBlocking { repo.snapshot().notes.size })
    }
    @Test fun writtenAnswersAreAvailableOnClassicCardsWithoutAi() {
        runBlocking { repo.saveCard(repo.getCard(cardId)!!.copy(modes = setOf(AnswerMode.CLASSIC))) }
        setApp()
        waitTag("deck-$deckId")
        compose.onNodeWithTag("deck-$deckId").performClick()
        compose.onNodeWithTag("studyDeck").performClick()
        waitTag("question")
        compose.onNodeWithText("Come rispondere: Classica").performScrollTo().performClick()
        compose.onNodeWithText("Scritta").performClick()
        waitTag("answerInput")
        compose.onNodeWithTag("answerInput").performTextInput("La capitale è Roma")
        compose.onNodeWithTag("referenceAnswer").assertDoesNotExist()
        compose.onNodeWithTag("submitAnswer").performScrollTo().performClick()
        waitTag("referenceAnswer")
        compose.onNodeWithTag("referenceAnswer").assertTextContains("Roma")
        compose.onNodeWithText("Valuta la tua risposta").performScrollTo().performClick()
        compose.onNodeWithText("Corretta").performClick()
        compose.onNodeWithTag("rate-GOOD").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { repo.snapshot().reviews.size == 1 } }
        val attempt = runBlocking { repo.snapshot().attempts.single() }
        assertEquals(AnswerMode.WRITTEN, attempt.mode)
        assertEquals("La capitale è Roma", attempt.answer)
        assertNull(attempt.automaticOutcome)
        assertEquals(Outcome.CORRECT, attempt.finalOutcome)
    }

    @Test fun multipleChoiceEditorAndRepeatedFreePracticePreserveScheduleAndScheduledDraft() {
        runBlocking { repo.deleteNote(repo.getCard(cardId)!!.noteId) }
        val restoration = StateRestorationTester(compose)
        setApp(restoration, pet = true)
        waitTag("deck-$deckId")
        compose.onNodeWithTag("deck-$deckId").performClick()
        compose.onNodeWithTag("addNote").performClick()
        waitTag("editorMode-MULTIPLE_CHOICE")
        compose.onNodeWithTag("editorMode-MULTIPLE_CHOICE").performScrollTo().performClick()
        compose.onNodeWithTag("noteFront").performScrollTo().performTextInput("Quale pianeta abitiamo?")
        compose.onNodeWithTag("choiceOption-0").performScrollTo().performTextReplacement("Marte")
        compose.onNodeWithTag("choiceOption-1").performScrollTo().performTextReplacement("Terra")
        compose.onNodeWithTag("correctOption-1").performScrollTo().performClick()
        capture("multiple-choice-editor")
        compose.onNodeWithTag("saveNote").performClick()
        compose.waitUntil(10_000) { runBlocking { repo.snapshot().notes.size == 1 } }
        val before = runBlocking {
            val card = repo.snapshot().cards.single()
            val future = card.copy(scheduling = card.scheduling.copy(dueAtMillis = System.currentTimeMillis() + 604_800_000L))
            repo.saveCard(future)
            repo.saveAttempt(Attempt(cardId = card.id, mode = AnswerMode.MULTIPLE_CHOICE, answer = "Terra", createdAtMillis = System.currentTimeMillis()))
            repo.snapshot()
        }
        assertEquals(MultipleChoice(listOf("Marte", "Terra"), 1), before.notes.single().multipleChoice)
        assertEquals("Terra", before.notes.single().fields[1])
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("studyDeck") and isNotEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("practiceDeck").performClick()
        waitTag("choice-0")
        compose.onNodeWithTag("submitAnswer").assertIsNotEnabled()
        capture("multiple-choice-practice")
        compose.onNodeWithTag("choice-0").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { repo.snapshot().attempts.any { it.isPractice && it.answer == "Marte" } } }
        restoration.emulateSavedInstanceStateRestore()
        waitTag("choice-0")
        compose.onNodeWithTag("choice-0").assertIsSelected()
        compose.onNodeWithTag("referenceAnswer").assertDoesNotExist()
        compose.onNodeWithTag("submitAnswer").performScrollTo().performClick()
        waitTag("referenceAnswer")
        compose.onNodeWithTag("referenceAnswer").assertTextContains("Terra")
        compose.onNodeWithText("Esito automatico: Errata").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rate-GOOD").assertDoesNotExist()
        compose.onNodeWithTag("nextPractice").performScrollTo()
        capture("multiple-choice-feedback")
        compose.onNodeWithTag("nextPractice").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Sessione completata").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Torna al mazzo").performClick()
        compose.onNodeWithTag("practiceDeck").performClick()
        waitTag("choice-1")
        compose.onNodeWithTag("submitAnswer").assertIsNotEnabled()
        compose.onNodeWithTag("choice-1").performScrollTo().performClick()
        compose.onNodeWithTag("submitAnswer").performScrollTo().performClick()
        waitTag("referenceAnswer")
        compose.onNodeWithText("Esito automatico: Corretta").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("nextPractice").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { repo.snapshot().attempts.count { it.state == AttemptState.PRACTICED } == 2 } }
        val after = runBlocking { repo.snapshot() }
        assertEquals(before.cards.single().scheduling, after.cards.single().scheduling)
        assertTrue(after.reviews.isEmpty())
        assertEquals(before.attempts.single(), after.attempts.single { !it.isPractice })
        assertEquals(setOf(Outcome.WRONG, Outcome.CORRECT), after.attempts.filter { it.isPractice }.map { it.finalOutcome }.toSet())
    }

    @Test fun freePracticeKeepsItsPositionAfterStateRestoration() {
        runBlocking {
            repo.saveNote(Note(deckId = deckId, fields = listOf("Seconda domanda", "Seconda risposta")))
            repo.snapshot().cards.forEach { repo.saveCard(it.copy(scheduling = it.scheduling.copy(dueAtMillis = Long.MAX_VALUE))) }
        }
        val restoration = StateRestorationTester(compose)
        setApp(restoration)
        waitTag("deck-$deckId")
        compose.onNodeWithTag("deck-$deckId").performClick()
        compose.onNodeWithTag("practiceDeck").performClick()
        waitTag("answerInput")
        compose.onNodeWithTag("answerInput").performTextInput("Roma")
        compose.onNodeWithTag("submitAnswer").performScrollTo().performClick()
        waitTag("referenceAnswer")
        compose.onNodeWithTag("nextPractice").performScrollTo().performClick()
        waitTag("answerInput")
        compose.onNodeWithTag("question").assertTextContains("Seconda domanda")
        compose.onNodeWithTag("answerInput").performTextInput("Una bozza della seconda")
        restoration.emulateSavedInstanceStateRestore()
        waitTag("answerInput")
        compose.onNodeWithTag("question").assertTextContains("Seconda domanda")
        compose.onNodeWithTag("answerInput").assertTextContains("Una bozza della seconda")
        assertEquals(1, runBlocking { repo.snapshot().attempts.count { it.state == AttemptState.PRACTICED } })
        assertTrue(runBlocking { repo.snapshot().reviews.isEmpty() })
    }

}
