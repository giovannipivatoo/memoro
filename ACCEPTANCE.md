# Accettazione v0.2

Verifiche del 25 settembre 2026. Redesign sviluppato sul branch `feat/ui-ux-refresh`; la verifica Anki esterna sotto riportata proviene dalla v0.1 (`afc7b95`) e il relativo motore non è stato modificato dal redesign. Android 16/API 36, emulatore arm64 Pixel 7 dedicato; JDK 17. CI aggiunge Android API 35 x86_64.

## Esito

- [x] R01/R10: creazione manuale, generazione inversa/cloze, modifica atomica delle note importate e persistenza Room; scambio dei media verificato tramite Anki.
- [x] R02/R03: classica, esatta, correzione AI simulata e fallback senza chiave; rettifica indipendente dal voto; abilitazione modalità su selezione di carte.
- [x] R04/R06: server simulato per protocollo AI, conflitto fonte, errori HTTP/JSON/timeout e assenza di retry automatici; bozza conservata.
- [ ] R04/R06: chiamata a DeepSeek reale con chiave utente. **Non eseguita: chiave non fornita**, come previsto da R12.
- [x] R05/R07: FSRS contro py-fsrs 6.3.2; doppio commit concorrente produce un solo ripasso; rettifica non modifica lo scheduling.
- [x] R08: fixture Anki reali legacy/moderne, esportazione mista e nativa, reimportazione senza duplicati; oracle esterno Anki 26.9.3 sui file prodotti dall'APK.
- [x] R09: backup/ripristino, copia preventiva, ZIP troncato, digest errato e path traversal; esclusione della chiave; file e ripassi conservati dopo riapertura.
- [x] R10: bozza e risposta valutata conservate alla ricreazione; cronologia e statistiche implementate, cancellazione dati personali del tentativo verificata.
- [x] R11: opzione gattino, reazione all'esito e verifica visiva chiaro/scuro. Il codice rispetta la scala animazioni di Android e mantiene neutro il pet su errori tecnici.
- [x] R12: compilazione APK installabile, **8 test unitari e 16 test strumentali**, lint senza errori; documentazione di uso, build e limiti.

## Prove riproducibili

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
```

Rapporti generati in `app/build/reports/tests/testDebugUnitTest/`, `app/build/reports/androidTests/connected/debug/` e `app/build/reports/lint-results-debug.html`. Lint segnala 15 warning informativi: target SDK/dipendenze più recenti disponibili e suggerimenti KTX; nessun errore. Versioni fissate per questa preview, senza una promessa di pubblicazione Play Store.

| Suite | Evidenza |
|---|---|
| `ExactGradeTest` | Maiuscole/spazi, accenti, punteggiatura e refusi |
| `Fsrs6Test` | Quattro voti, primo ripasso, stesso giorno, ritardo anche con frazione di giorno, bootstrap stato Anki |
| `DeepSeekClientTest` | Contenuto richiesta, risposte JSON, conflitto fonte, malformed/vuoto/troncato, 401/429/500, timeout e nessun reinvio |
| `RepositoryPersistenceTest` | Room, cloze Android, revisione concorrente/idempotente, backup, Keystore, riapertura, GUID e precedenza modifiche locali/remote |
| `AnkiInteropTest` | Due fixture reali, media, cinque carte importate, cloze esatto, export misto e legacy nativo, reimportazione |
| `StudyFlowTest` | Editor e uscita protetta, rettifica esito/voto, rete indisponibile, bozza non rivelata, stato valutato ripristinato, coda globale su due mazzi e risposte isolate fra carte |
| `HostFlowTest` | Activity reale: impostazioni pet, creazione mazzo/nota, modalità, ricreazione Activity con bozza, esito corretto e voto |

Screenshot del vero host: [home](docs/screenshots/home-light.png), [editor](docs/screenshots/editor-light.png), [impostazioni](docs/screenshots/settings-light.png), [cronologia](docs/screenshots/history-light.png), studio [chiaro](docs/screenshots/study-light.png) e [scuro](docs/screenshots/study-dark.png). La verifica visiva ha corretto la sovrapposizione della tastiera al salvataggio, gli esiti duplicati e i conteggi al singolare. Il percorso reale completo è passato anche in tema scuro con [testo al 130%](docs/screenshots/study-large-font.png); verificati scorrimento e [Salva sopra la tastiera](docs/screenshots/editor-keyboard-light.png). Ricerca, decisioni e provenienza del nuovo pet sono in [UI-UX.md](docs/UI-UX.md). Non è una certificazione su ogni dimensione dello schermo o dispositivo fisico. L'interruzione forzata nel mezzo del ripristino non è stata iniettata automaticamente: la protezione deriva dal puntatore alla generazione file aggiornato nella stessa transazione Room, verificato con ripristino e riapertura.

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
```

In un ambiente Python separato con `anki==26.9.3` e il comando `zstd` disponibile:

```sh
python app/src/androidTest/anki/verify_export.py memoro-mixed-oracle.apkg
python app/src/androidTest/anki/verify_export.py --native-legacy memoro-native-legacy.apkg
```

Risultati dei file effettivamente estratti dall'emulatore, senza modificarli:

| Pacchetto | Importato da Anki 26.9.3 | SHA-256 |
|---|---|---|
| Moderno misto | 6 note, 9 carte, 2 ripassi, 2 media | `57af7bc73f27e9234250eb179e540b8ee2c079246aad16727c8ccd8b9ebc3014` |
| Legacy nativo | 3 note, 4 carte, 0 ripassi, 0 media | `3c1e4ef00f2f52843466a276f18db17585ef0de88bb87782b08998c31f32c571` |

L'oracle confronta GUID e campi, identità carta per GUID+ordinale+mazzo, coda/tipo/scadenza/intervallo/ripetizioni/lapsi, timestamp e voti revlog, hash dei byte multimediali. I conteggi da soli non costituiscono la verifica. Nuove esecuzioni generano ID, GUID e timestamp nuovi: gli hash della tabella identificano questa specifica esecuzione.

I limiti supportati sono in [README.md](README.md): template semplificati, preset avanzati non riprodotti integralmente, stima FSRS quando lo stato importato manca, orologi dei dispositivi, spazio di generazioni/orfani, chiave e preferenze escluse dal backup. Nessuna prova simulata è presentata come chiamata DeepSeek reale.
