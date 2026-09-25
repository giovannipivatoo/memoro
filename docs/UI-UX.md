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

Dalla v0.3 l'app usa lo [spritesheet del pet personale 罗小黑](../app/src/main/res/drawable-nodpi/luoxiaohei_sprites.webp), identificato dall'utente come riferimento. Il file è copiato senza modifiche dal pet `luoxiaohei2d` presente sul suo computer. Descrizione originale: “A tiny black cat Codex companion inspired by Luo Xiaohei, simplified into a cute polished Codex digital pet style.”

Compose mostra i fotogrammi di attesa e battito degli occhi, salto per un esito corretto, saluto per uno parziale, incoraggiamento dopo un errore e riflessione per un esito non valutabile. I controlli restano fermi. La scala animazioni Android a zero seleziona pose statiche; il passaggio in background interrompe l'animazione. Nessun suono o servizio in background.

La [precedente illustrazione generata e relativo prompt](https://github.com/giovannipivatoo/memoro/blob/v0.2.0/docs/UI-UX.md#gattino) rimangono documentati nella v0.2.

## Estensione risposte e ripasso libero (v0.3)

La modalità si sceglie già nell'editor. Scritta è l'impostazione iniziale delle note manuali e funziona offline; Esatta e AI restano selezioni esplicite. La scelta multipla ha 2–6 opzioni e una risposta corretta, senza mostrare l'indice durante lo studio. Riferimento e rettifica compaiono dopo l'invio.

Il dettaglio mazzo offre sempre Ripasso libero quando contiene carte attive. I risultati vengono conservati in una sezione dedicata della cronologia; le scadenze e i quattro voti del ripasso programmato rimangono separati.

## Verifica

I test coprono ripasso globale su due mazzi, isolamento della risposta tra carte, uscita protetta dall'editor, bozze, rettifica, ricreazione dell'Activity e impostazione del pet. Gli screenshot in [screenshots](screenshots/) provengono dall'app Android su emulatore con dati sintetici. Risultati e limiti sono registrati in [ACCEPTANCE.md](../ACCEPTANCE.md).

## Icona launcher (v0.3.1)

Il volto di 罗小黑 è ridisegnato come vettore Android: testa nera inclinata, grandi anelli color crema e interno dell’orecchio salvia. Lo sfondo usa il colore principale chiaro di Memoro. Il pet animato conserva i suoi fotogrammi originali.

Foreground e sfondo sono separati in un’icona adattiva 108dp; il soggetto rimane nella zona centrale protetta. Il livello monocromatico usa anelli trasparenti e pupille piene, così il volto resta riconoscibile con le icone a tema. Implementazione conforme alla [documentazione Android sulle icone adattive](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).

[Anteprima delle forme e dei temi](screenshots/icon-variants.png), render delle stesse geometrie vettoriali; [icona nel launcher Android](screenshots/launcher-icon.png), screenshot dall’APK su API 36.
