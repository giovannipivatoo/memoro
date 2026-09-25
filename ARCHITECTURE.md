# Architettura

## Decisioni
Un modulo Android `app`, Kotlin, Compose Material 3, minSdk 26, database Room. Package `io.github.giovannipivatoo.memoro`. Dipendenze stabili fissate in Gradle. FSRS puro Kotlin, rete HTTPS opzionale. Nessun backend Anki/AnkiDroid incorporato (licenze copyleft); adattatore APKG autonomo. Originali BSD-2-Clause, dipendenze con attribuzioni proprie.

## Confini e proprietari
- `data/`, `study/`: agente data. Entità Room, DAO/repository, scheduler, confronto esatto, backup, credenziali.
- `anki/`: agente anki. APKG codec, template/media sicuri, import/export e compatibilità.
- `ui/`, `ai/`, `MainActivity.kt`: agente ui. Compose, sessione di studio, pet, DeepSeek.
- Build, manifest, CI, documenti, integrazione: coordinatore.

## Contratto da fissare per primo
L'agente data pubblica subito `data/Models.kt` e API `MemoroRepository`, comunicando agli altri nomi e firme. Includere Deck, Note, Card, Review, Attempt; ID Long stabili per ponte Anki; modalità/classificazione/rating enum; informazioni fonte e campi originali Anki. Database interno Room, metadati Anki preservati separatamente.

L'interfaccia pubblica deve coprire osservazione collezione, CRUD note/mazzi, carte dovute, bozza tentativo, registrazione atomica e idempotente del ripasso e snapshot import/export. I client AI restituiscono risultati senza mutare repository o scadenze. Anki usa il contratto dati concordato; UI usa repository e codec. Owner cambia contratti solo dopo comunicazione ai consumatori.

## Rischi da provare
Semantica delle date Anki (epoch collezione/giorni vs timestamp), ordinale cloze/inverse, revlog, conversione FSRS, media zip e path traversal, restore atomico, process recreation, richieste concorrenti/doppi invii. Prima di dichiarare compatibilità: andata/ritorno con Anki reale e fixture legacy/moderne.
