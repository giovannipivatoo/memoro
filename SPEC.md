# Memoro — specifica v1

App Android personale open source, Kotlin/Jetpack Compose, BSD-2-Clause. Dati locali, nessun account o server Memoro. Interfaccia italiana essenziale Material 3, tema di sistema. Questa specifica deriva dalle decisioni approvate dall'utente.

## Requisiti

- R01: Creare, modificare, eliminare mazzi e note manualmente; carte fronte/retro, inverse, cloze, immagini e audio. Distinguere nota e carte generate.
- R02: Modalità per carta: CLASSIC (rivela/autovaluta), EXACT (risposta scritta confrontata localmente), AI (risposta scritta valutata da DeepSeek). Importazioni inizialmente CLASSIC; selezione multipla delle modalità su risposte testuali compatibili.
- R03: EXACT ignora maiuscole/minuscole e spazi esterni, distingue accenti e punteggiatura. Utente può rettificare ogni esito in corretta/parziale/errata, senza cambiare la carta.
- R04: AI confronta domanda, risposta di riferimento, risposta utente, eventuali punti essenziali ed estratto fonte. Mostra riferimento, esito, errori, omissioni e spiegazione. Esiti: corretta, parziale, errata, non valutabile. Conflitti fonte/risposta sono non valutabili; niente invenzione di fonti. Link/pagina sono metadati, non fonti lette.
- R05: Solo conferma umana Da rifare/Difficile/Buona/Facile aggiorna FSRS e registra ripasso, atomicamente e una volta per tentativo. AI e rettifica non programmano scadenze. Conservare testo, esito automatico e finale separati.
- R06: Studio offline; API opzionale con chiave personale protetta da Android Keystore. Invio esplicito del solo contenuto della carta/tentativo, nessun archivio completo. Errore di rete/API/JSON non equivale a risposta errata. Bozza persistente, retry esplicito, nessun reinvio automatico.
- R07: FSRS-6 con implementazione permissiva verificata, retention iniziale 90%. Preservare scadenze importate; dopo ripasso applicare FSRS. Nessuna promessa di identità con tutte le configurazioni Anki.
- R08: Import/export .apkg legacy e moderno: contenuti supportati, media, cronologia, scadenze e stato; identificativi stabili e reimportazione senza duplicati. Rapporto incompatibilità per template/JS non supportati; preservare pacchetto originale. Nessuna perdita silenziosa. Rendering isolato senza JS/rete. .colpkg e sincronizzazione AnkiWeb fuori v1.
- R09: Backup completo versionato di dati, tentativi, fonti, media e pacchetti originali; esclusa chiave API. Validare prima di ripristino, conferma esplicita, backup preventivo. Nessun backup cloud Android automatico.
- R10: Schermate mazzi, elenco/editor, studio, cronologia/statistiche, impostazioni. Statistiche scadenze/ripassi/esiti. Modalità scritte rivelano risposta solo dopo invio; classica su richiesta.
- R11: Gattino vettoriale nero, testa e occhi grandi, opzionale disattivato inizialmente. Attesa/festa/incoraggiamento/pensieroso; segue esito rettificato; neutro su errore tecnico. Silenzioso, non sposta controlli, pose statiche con movimento ridotto.
- R12: Sorgenti, documenti, APK, build riproducibile e verifiche Android. Prova DeepSeek reale subordinata a chiave utente, indicata pendente se assente.

## Rimandato
Generazione AI delle carte, importazione testo/PDF come materiale da elaborare, OCR, chat/tutor, voce, sincronizzazione cloud, optimizer FSRS. Fonte v1: estratto e riferimento inseriti manualmente.
