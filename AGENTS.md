# Team Memoro

Leggere SPEC.md prima di implementare comportamento. Leggere ARCHITECTURE.md prima di cambiare dati, API o dipendenze. ACCEPTANCE.md definisce le prove richieste per la consegna.

## Collaborazione
Usare agenti GPT-6 Sol, nominati data, anki, ui, coordinati dal root. Gli agenti adiacenti devono comunicare direttamente usando collaboration.send_message, scambiando firme, proposte e feedback; non limitarsi a riportare al root. data ↔ anki per archivio e scheduling; data ↔ ui per sessioni e persistenza; anki ↔ ui per rendering/importazione. Richiedere review concreta di un collega prima della consegna e risolverne i rilievi.

Un proprietario delle scritture per area come in ARCHITECTURE.md. Tutti condividono il worktree: non cambiare branch, fare checkout, commit o push autonomamente mentre lavorano altri agenti. Il coordinatore gestisce branch feature, stage esplicito per owner, commit coerenti, review e push; integra solo dopo i controlli. Non sovrascrivere cambi altrui. Proporre modifiche ai contratti prima di applicarle e notificare la versione finale.

## Consegna di ogni agente
Elencare requisiti implementati, file posseduti, test eseguiti con risultati, feedback ricevuto e limiti ancora reali. Niente stub spacciati per implementazione, downgrade di compatibilità non concordati, segreti o dati personali nel repository.

## Licenze
Codice nuovo BSD-2-Clause. Dipendenze permissive con attribuzioni registrate. Non copiare codice AGPL/GPL Anki o AnkiDroid. Dati/fixture creati da noi o redistribuibili.
