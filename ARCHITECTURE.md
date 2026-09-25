# Architettura

## Decisioni
Un modulo Android `app`, Kotlin, Compose Material 3, minSdk 26, database Room. Package `io.github.giovannipivatoo.memoro`. Dipendenze stabili fissate in Gradle. FSRS puro Kotlin, rete HTTPS opzionale. Nessun backend Anki/AnkiDroid incorporato (licenze copyleft); adattatore APKG autonomo. Originali BSD-2-Clause, dipendenze con attribuzioni proprie.

## Confini e proprietari
- `data/`, `study/`: agente data. Entità Room, DAO/repository, scheduler, confronto esatto, backup, credenziali.
- `anki/`: agente anki. APKG codec, template/media sicuri, import/export e compatibilità.
- `ui/`, `ai/`, `MainActivity.kt`: agente ui. Compose, sessione di studio, pet, DeepSeek.
- Build, manifest, CI, documenti, integrazione: coordinatore.

## Modello e contratti

`data/Models.kt` definisce Deck, Note, Card, Attempt, Review e ArchiveSnapshot. Una nota contiene campi e fonte facoltativa; genera una o più carte con ordinale, modalità e scheduling indipendenti. Il tentativo conserva bozza, esito automatico, esito umano e feedback. `Note.multipleChoice` contiene opzioni e indice corretto per una nota Base; il riferimento deve coincidere con l’opzione corretta. `saveNote(note, preferredMode)` applica la modalità alle carte native nella stessa transazione. Il ripasso conserva il voto FSRS: correttezza e voto non sono intercambiabili.

`MemoroRepository` espone osservazione, CRUD, carte dovute, tentativi, commit del ripasso e snapshot. Room salva corpi JSON versionabili e colonne indicizzate per selezioni e identità. `commitReview` aggiorna scheduling, tentativo e review nella stessa transazione; l'indice univoco del tentativo impedisce doppi ripassi. `saveNoteAndCards` mantiene atomica la modifica di una nota importata e di tutte le sue carte, anche distribuite su mazzi diversi. Modificare i contratti richiede comunicazione ai consumatori prima dell'implementazione.

I client AI restituiscono risultati senza accedere al repository o alle scadenze. `DeepSeekClient` usa HTTPS, timeout e limiti di risposta, nessun redirect o retry automatico. Il prompt separa le istruzioni dai contenuti da valutare. I risultati JSON sono validati; conflitti ed errori tecnici rimangono non valutabili.

La correzione usa JSON Output e disabilita esplicitamente la modalità thinking, per riservare il limite di output al feedback. Le impostazioni riusano lo stesso client per una prova esplicita su un esempio fisso: salvare le credenziali o aprire la schermata non avvia richieste. Riferimenti del protocollo: [Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/) e [JSON Output](https://api-docs.deepseek.com/guides/json_mode/), verificati il 25 settembre 2026.

## Ciclo di vita e studio

`MemoroHost`, un AndroidViewModel, mantiene repository e servizi durante la ricreazione dell'Activity. Compose salva pagina e dati transitori con `rememberSaveable`; le bozze e i risultati già valutati vengono ricaricati da Room prima di abilitare l'input. La risposta di riferimento e la fonte restano nascoste fino all'invio. L'autovalutazione è sempre disponibile senza rete. `Attempt.isPractice` separa bozze e risultati del ripasso libero. `finishPractice` porta un tentativo valutato a PRACTICED in modo idempotente; non crea Review. `commitReview` rifiuta tentativi liberi. Il cursore della sessione libera sopravvive alla ricreazione.

Le modifiche multiple alla modalità disabilitano temporaneamente l'avvio dello studio fino al salvataggio.

FSRS-6 è Kotlin puro, con parametri standard, retention 90% e fuzzing disabilitato. Passi iniziali 1 e 10 minuti, riapprendimento 10 minuti. Il bootstrap delle carte Anki senza stato di memoria usa l'intervallo esistente al primo ripasso. Le formule sono verificate contro py-fsrs; provenienza e comando di riferimento in THIRD_PARTY_FSRS.md.

## Confine Anki

L'adattatore legge ZIP, SQLite, zstd e metadati protobuf necessari senza incorporare codice Anki. Riconosce lo schema dalle tabelle, non solo dal nome del file nel pacchetto. Mantiene metadati originali per esportare modelli, carte e revlog. La compatibilità esterna è verificata con Anki 26.9.3.

Il digest del pacchetto identifica la provenienza, non la nota. La reimportazione riconcilia note per GUID persistente, carte per nota+ordinale, mazzi per nome e review per identità/timestamp della carta. I GUID delle note native sono UUID persistenti. Un orologio di modifica preserva modifiche locali più recenti; fonti Memoro e tentativi locali sono mantenuti. Gli identificativi numerici esportati non coincidono necessariamente con quelli riassegnati da Anki: gli oracle confrontano identità semantiche.

Rendering con componenti Android nativi, senza WebView/JavaScript/rete; HTML/template complessi sono semplificati con avviso. Media locali con percorsi validati e limiti dimensionali. Preset avanzati e personalizzazioni non riproducibili sono segnalati e l'originale `.apkg` viene conservato. La versione moderna esporta un database schema 11 dentro il contenitore zstd accettato da Anki.

Le note Memoro a scelta multipla diventano carte Anki Base con opzioni statiche e un terzo campo `MemoroMC` ignorato dal template. Metadati versionati e hash dei due fronti permettono di ricostruire la modalità interattiva solo se il contenuto è coerente. Una modifica esterna dei fronti invalida il recupero e produce un avviso, mantenendo il contenuto Anki visibile.

## Backup e credenziali

Lo ZIP include snapshot versione 2 (il lettore accetta anche la versione 1), file con dimensione/digest, fonti, tentativi, media e originali Anki. Prima del ripristino vengono validati percorsi, dimensioni, digest, identità e riferimenti; viene creato un backup preventivo. I nuovi file sono completati in una directory di generazione separata, poi Room sostituisce i dati e il puntatore alla generazione nella stessa transazione. Una terminazione del processo lascia referenziata la generazione completa precedente o successiva. Le vecchie generazioni sono conservate: nessuna pulizia automatica delle generazioni nella preview attuale.

La chiave API è cifrata con Android Keystore, esterna allo ZIP; preferenze UI in SharedPreferences. Il manifest esclude backup e trasferimento automatici Android. I file restano nello spazio privato dell'app; l'esportazione è una scelta esplicita attraverso il selettore documenti di Android.

## Verifica

Unit test per FSRS, confronto esatto e protocollo AI; test Android per Room/Keystore/backup, Anki e flussi Compose; test del vero host per ricreazione Activity e gattino. L'oracle Python Anki è esterno all'APK. ACCEPTANCE.md registra evidenze e limiti; non equiparare round-trip interno e interoperabilità esterna.
