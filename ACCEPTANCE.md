# Accettazione v0.3

Verifiche del 25 settembre 2026. Risposte, scelta multipla, ripasso libero e pet sviluppati sul branch `feat/answer-modes-practice-pet`. Tutti gli oracle Anki sotto riportati sono stati rieseguiti sui pacchetti prodotti dalla v0.3. Android 16/API 36, emulatore arm64 Pixel 7 dedicato; JDK 17. CI aggiunge Android API 35 x86_64.

## Esito

- [x] R01/R10: creazione manuale, generazione inversa/cloze, modifica atomica delle note importate e persistenza Room; scambio dei media verificato tramite Anki.
- [x] R02/R03: classica, scritta, esatta, scelta multipla, correzione AI simulata e fallback senza chiave; rettifica indipendente dal voto; abilitazione modalità su selezione di carte.
- [x] R04/R06: server simulato per protocollo AI, conflitto fonte, errori HTTP/JSON/timeout e assenza di retry automatici; bozza conservata.
- [ ] R04/R06: chiamata a DeepSeek reale con chiave utente. **Non eseguita: chiave non fornita**, come previsto da R12.
- [x] R05/R07: FSRS contro py-fsrs 6.3.2; doppio commit concorrente produce un solo ripasso; rettifica non modifica lo scheduling.
- [x] R08: fixture Anki reali legacy/moderne, esportazione mista e nativa, reimportazione senza duplicati; oracle esterno Anki 26.9.3 sui file prodotti dall'APK.
- [x] R09: backup/ripristino, copia preventiva, ZIP troncato, digest errato e path traversal; esclusione della chiave; file e ripassi conservati dopo riapertura.
- [x] R10: bozza e risposta valutata conservate alla ricreazione; cronologia e statistiche implementate, cancellazione dati personali del tentativo verificata.
- [x] R11: pet originale 罗小黑, battito degli occhi e reazioni a fotogrammi; verifica visiva chiaro/scuro. Animazioni sospese in background e pose statiche espressive con animazioni Android disattivate; nessun esito negativo attribuito agli errori tecnici.
- [x] R13: ripasso libero anche senza carte in scadenza, ripetibile, cronologia separata, nessuna modifica FSRS; bozza del ripasso programmato isolata e posizione conservata alla ricreazione.
- [x] R12: compilazione APK installabile, **8 test unitari e 23 test strumentali**, lint senza errori; documentazione di uso, build e limiti.

## Prove riproducibili

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
```

Rapporti generati in `app/build/reports/tests/testDebugUnitTest/`, `app/build/reports/androidTests/connected/debug/` e `app/build/reports/lint-results-debug.html`. Lint segnala 17 warning informativi: target SDK/dipendenze più recenti disponibili e suggerimenti KTX; nessun errore. Versioni fissate per questa preview, senza una promessa di pubblicazione Play Store.

| Suite | Evidenza |
|---|---|
| `ExactGradeTest` | Maiuscole/spazi, accenti, punteggiatura e refusi |
| `Fsrs6Test` | Quattro voti, primo ripasso, stesso giorno, ritardo anche con frazione di giorno, bootstrap stato Anki |
| `DeepSeekClientTest` | Contenuto richiesta, risposte JSON, conflitto fonte, malformed/vuoto/troncato, 401/429/500, timeout e nessun reinvio |
| `RepositoryPersistenceTest` | Room, cloze Android, revisione concorrente/idempotente, backup, Keystore, riapertura, GUID e precedenza modifiche locali/remote; scelta multipla, pratica idempotente, backup v2 e lettura v1 |
| `AnkiInteropTest` | Due fixture reali, media, cinque carte importate, cloze esatto, export misto e legacy nativo, scelta multipla con recupero interattivo, modifica esterna, rimozione opzioni e reimportazione |
| `StudyFlowTest` | Editor e uscita protetta, rettifica esito/voto, rete indisponibile, bozza non rivelata, stato valutato ripristinato, coda globale su due mazzi e risposte isolate fra carte; risposta scritta su carte classiche, editor scelta multipla, ripassi liberi ripetuti e ripristino della posizione |
| `HostFlowTest` | Activity reale: impostazioni pet, creazione mazzo/nota, modalità, ricreazione Activity con bozza, esito corretto e voto |

Screenshot della v0.3 dal vero host: [home](docs/screenshots/home-light.png), [editor](docs/screenshots/editor-light.png), [impostazioni](docs/screenshots/settings-light.png), [cronologia](docs/screenshots/history-light.png), studio [chiaro](docs/screenshots/study-light.png) e [scuro senza animazioni](docs/screenshots/study-dark.png). Il nuovo flusso è documentato con [editor a scelta multipla](docs/screenshots/multiple-choice-editor.png), [ripasso libero](docs/screenshots/multiple-choice-practice.png) e [feedback](docs/screenshots/multiple-choice-feedback.png). Animazione osservata anche fuori dal runner: [occhi aperti](docs/screenshots/pet-idle.png) e [battito degli occhi](docs/screenshots/pet-blink.png) nella stessa Activity. Verificati scorrimento e [Salva sopra la tastiera](docs/screenshots/editor-keyboard-light.png). La prova al [130% di testo](docs/screenshots/study-large-font.png) appartiene alla v0.2; non è stata ripetuta per i nuovi controlli. Ricerca, decisioni e provenienza del pet sono in [UI-UX.md](docs/UI-UX.md). Non è una certificazione su ogni schermo o dispositivo fisico. L'interruzione forzata nel mezzo del ripristino non è stata iniettata automaticamente: la protezione deriva dal puntatore alla generazione file aggiornato nella stessa transazione Room, verificato con ripristino e riapertura.

## Oracle Anki esterno

Fixture sintetiche nostre, generate con `anki==26.9.3` da [generate_fixtures.py](app/src/androidTest/anki/generate_fixtures.py). Nessun contenuto personale e nessun backend Anki incorporato nell'APK.

Per trattenere gli export sul dispositivo, installare APK e test APK ed eseguire direttamente il runner; il task Gradle connected può disinstallarli al termine:

```sh
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class io.github.giovannipivatoo.memoro.anki.AnkiInteropTest \
  io.github.giovannipivatoo.memoro.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/io.github.giovannipivatoo.memoro/files/memoro-mixed-oracle.apkg
adb pull /sdcard/Android/data/io.github.giovannipivatoo.memoro/files/memoro-native-legacy.apkg
adb pull /sdcard/Android/data/io.github.giovannipivatoo.memoro/files/memoro-multiple-choice-oracle.apkg
adb pull /sdcard/Android/data/io.github.giovannipivatoo.memoro/files/memoro-multiple-choice-removed-oracle.apkg
```

In un ambiente Python separato con `anki==26.9.3` e il comando `zstd` disponibile:

```sh
python app/src/androidTest/anki/verify_export.py memoro-mixed-oracle.apkg
python app/src/androidTest/anki/verify_export.py --native-legacy memoro-native-legacy.apkg
python app/src/androidTest/anki/verify_export.py --multiple-choice memoro-multiple-choice-oracle.apkg
python app/src/androidTest/anki/verify_export.py --multiple-choice-removed memoro-multiple-choice-removed-oracle.apkg
```

Risultati dei file effettivamente estratti dall'emulatore, senza modificarli:

| Pacchetto | Importato da Anki 26.9.3 | SHA-256 |
|---|---|---|
| Moderno misto | 6 note, 9 carte, 2 ripassi, 2 media | `2410e1d9f5e1935313ac0bddfc04b9bdffc96b104b3390918b32c0772b83ecf9` |
| Legacy nativo | 3 note, 4 carte, 0 ripassi, 0 media | `4334815f14762cbf6b03f3397bde8b76f84156ccc7bc136a2d209fc43281fed5` |
| Scelta multipla | 1 nota, 1 carta, 0 ripassi, 0 media | `0226755d563a0d00ed002461a7cd9d9864e4d8bc948d1e4e239c20f851c38a67` |
| Opzioni rimosse | 1 nota, 1 carta, 0 ripassi, 0 media | `a38ffb76116b6261f91e56e91303bfc270cf820a395e38c904fe9e6e260d53b7` |

L'oracle confronta GUID e campi, identità carta per GUID+ordinale+mazzo, coda/tipo/scadenza/intervallo/ripetizioni/lapsi, timestamp e voti revlog, hash dei byte multimediali. I conteggi da soli non costituiscono la verifica. Nuove esecuzioni generano ID, GUID e timestamp nuovi: gli hash della tabella identificano questa specifica esecuzione.

I limiti supportati sono in [README.md](README.md): template semplificati, preset avanzati non riprodotti integralmente, stima FSRS quando lo stato importato manca, orologi dei dispositivi, spazio di generazioni/orfani, chiave e preferenze escluse dal backup. Nessuna prova simulata è presentata come chiamata DeepSeek reale.

## Patch icona v0.3.1

Modifica limitata a icona, manifest e numero di versione. `lintDebug assembleDebug` completati, senza errori e con gli stessi 17 warning della v0.3. Verifica visiva delle geometrie rotonde/arrotondate e monocromatiche; APK installato come aggiornamento e [icona verificata nel launcher API 36](docs/screenshots/launcher-icon.png). Nessuna modifica a studio, dati o compatibilità Anki.
