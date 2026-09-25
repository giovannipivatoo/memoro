# UI e UX di Memoro 0.2

Ricerca e implementazione del 25 settembre 2026. L'obiettivo è ridurre la confusione nell'uso quotidiano di una raccolta locale. Le app qui sotto sono riferimenti di interazione, non una classifica assoluta di qualità rispetto ad Anki.

## Riferimenti consultati

| Fonte ufficiale | Spunto adottato | Applicazione in Memoro |
|---|---|---|
| [Mochi](https://mochi.cards/) e [ripasso](https://mochi.cards/docs/reviewing/) | Layout sobrio, contenuti in primo piano, strumenti discreti | Tema salvia/avorio, note leggibili, ricerca e menu contestuali |
| [RemNote: spaced repetition](https://help.remnote.com/en/articles/6022755-getting-started-with-spaced-repetition) | Conteggio delle carte dovute e azione principale; domanda isolata, progresso e intervalli | Home con coda globale; scheda domanda; quattro voti con prossimi intervalli calcolati dal nostro FSRS |
| [Quizlet: flashcards](https://quizlet.com/features/flashcards) | Creazione basata su due campi e modalità di studio esplicite | Domanda e risposta come campi principali; scelta della modalità in un dialogo dedicato |
| [Knowt: flashcards mode](https://help.knowt.com/en/articles/10298062-how-can-i-use-flashcards-mode) e [editor](https://help.knowt.com/en/articles/10716239-how-do-i-create-and-edit-flashcards) | Un'azione primaria chiara e impostazioni secondarie raccolte | Opzioni su richiesta, editor con salvataggio visibile, gestione carte tramite menu |

Le pagine e le immagini ufficiali sono state consultate come riferimento. Nessuno screenshot, marchio o codice delle app concorrenti è incluso nell'app o redistribuito nel repository. Memoro conserva archivio locale, import/export Anki, FSRS-6 e AI limitata alla correzione facoltativa.

## Decisioni implementate

- **Mazzi:** il conteggio principale e «Inizia ripasso» coprono tutti i mazzi. Le azioni «Nuovo» e importazione Anki sono accessibili dalla home. Il dettaglio separa lo studio dalla gestione delle note.
- **Navigazione:** Mazzi, Cronologia e Impostazioni hanno icone e testo. Durante editor e studio la barra inferiore scompare. Il titolo del mazzo dà contesto e l'uscita da un editor modificato richiede una scelta esplicita.
- **Editor:** domanda e riferimento sono i primi campi. Tipologia, allegati e fonte restano disponibili; la fonte si espande solo quando serve. Il pulsante Salva rimane visibile. Le note importate conservano l'aggiornamento atomico dei campi.
- **Modalità:** una preferenza per carta, applicabile anche a una selezione: Classica, Esatta oppure AI. Classica rimane disponibile nella sessione. Vecchi archivi con più modalità sono ancora leggibili. Le opzioni incompatibili spiegano perché non sono selezionabili.
- **Studio:** una domanda per volta, avanzamento visibile, risposta scritta separata dal riferimento. Dopo invio compaiono esito, risposta corretta e fonte facoltativa; «Modifica esito» consente la rettifica umana. Solo il voto di memoria registra il ripasso. I quattro voti mostrano l'intervallo previsto.
- **Cronologia:** riepilogo, ricerca e dettagli espandibili. La cancellazione dei dati personali richiede conferma e mantiene il ripasso già registrato.
- **Impostazioni:** pet con anteprima, AI in una sezione espandibile e operazioni archivio descritte. La chiave già salvata non viene riversata automaticamente nel campo di testo.
- **Aspetto:** colori e tipografia condivisi, tema chiaro/scuro di sistema, aree di tocco Material e controlli che vanno a capo nell'editor. Animazione del pet disattivata quando la scala animazioni Android è zero.

## Gattino

[Asset PNG trasparente](../app/src/main/res/drawable-nodpi/memoro_cat.png), generato con lo strumento ImageGen a partire dallo screenshot del pet fornito dall'utente. È un'illustrazione raster nuova, non un ritaglio dello screenshot. Testa nera molto grande, occhi ovali color crema, orecchio interno verde menta, corpo piccolo e coda ricurva. L'utente ha richiesto esplicitamente questa somiglianza.

Il pet rimane facoltativo e disattivato inizialmente. Un piccolo salto e una scintilla indicano una risposta corretta; una lieve inclinazione e un cuore accompagnano un errore o una risposta parziale. Tre puntini indicano il giudizio non valutabile; una coda vuota mantiene il pet in attesa. Non cambia la posizione dei controlli. Il giudizio rettificato dall'utente determina la reazione.

Prompt di generazione:

> Use case: identity-preserve. Asset type: transparent mascot sprite for an Android flashcard app. Attached screenshot character reference, not background. Recreate same little black kitten, hugely oversized round black head, enormous pale butter-yellow oval eyes with black oval pupils, three-quarter facing left, asymmetric ears with pale mint right inner ear, tiny seated black body/four paws, thick hooked tail right. Deep black with warm brown outlines. Faithful proportions/silhouette, curious friendly, not owl/rabbit/realistic. Crisp hand-drawn 2D game pet, subtle pixel-art inspiration, flat colors, no glossy 3D/fur detail. Center fully visible 8% transparent padding. True alpha, no checkerboard/floor/shadow/UI/text/watermark.

## Verifica

I test coprono ripasso globale su due mazzi, isolamento della risposta tra carte, uscita protetta dall'editor, bozze, rettifica, ricreazione dell'Activity e impostazione del pet. Gli screenshot in [screenshots](screenshots/) provengono dall'app Android su emulatore con dati sintetici. Risultati e limiti sono registrati in [ACCEPTANCE.md](../ACCEPTANCE.md).
