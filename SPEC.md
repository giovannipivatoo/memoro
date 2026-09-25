# Memoro — specifica v1

App Android personale open source, Kotlin/Jetpack Compose, BSD-2-Clause. Dati locali, nessun account o server Memoro. Interfaccia italiana essenziale Material 3, tema di sistema. Questa specifica deriva dalle decisioni approvate dall'utente.

## Requisiti

- R01: Creare, modificare, eliminare mazzi e note manualmente; carte fronte/retro, inverse, cloze, immagini e audio. Distinguere nota e carte generate.
- R02: Modalità per carta: CLASSIC (rivela/autovaluta), EXACT (risposta scritta confrontata localmente), AI (risposta scritta valutata da DeepSeek), WRITTEN (risposta scritta con autovalutazione offline), MULTIPLE_CHOICE (scelta fra 2–6 opzioni distinte, una corretta). Le nuove note manuali partono in WRITTEN; scelta multipla per note Base con opzioni configurate. Importazioni inizialmente CLASSIC. La UI imposta una modalità preferita per carta, anche su più carte selezionate; CLASSIC e WRITTEN rimangono disponibili nello studio. Archivi precedenti con più modalità restano leggibili. EXACT/AI richiedono risposte testuali compatibili.
- R03: EXACT ignora maiuscole/minuscole e spazi esterni, distingue accenti e punteggiatura. Utente può rettificare ogni esito in corretta/parziale/errata, senza cambiare la carta.
- R04: AI confronta domanda, risposta di riferimento, risposta utente, eventuali punti essenziali ed estratto fonte. Mostra riferimento, esito, errori, omissioni e spiegazione. Esiti: corretta, parziale, errata, non valutabile. Conflitti fonte/risposta sono non valutabili; niente invenzione di fonti. Link/pagina sono metadati, non fonti lette.
- R05: Solo conferma umana Da rifare/Difficile/Buona/Facile aggiorna FSRS e registra ripasso, atomicamente e una volta per tentativo. AI e rettifica non programmano scadenze. Conservare testo, esito automatico e finale separati.
- R06: Studio offline; API opzionale con chiave personale protetta da Android Keystore. Invio esplicito del solo contenuto della carta/tentativo, nessun archivio completo. Errore di rete/API/JSON non equivale a risposta errata. Bozza persistente, retry esplicito, nessun reinvio automatico.
- R07: FSRS-6 con implementazione permissiva verificata, retention iniziale 90%. Preservare scadenze importate; dopo ripasso applicare FSRS. Nessuna promessa di identità con tutte le configurazioni Anki.
- R08: Import/export .apkg legacy e moderno: contenuti supportati, media, cronologia, scadenze e stato; identificativi stabili e reimportazione senza duplicati. Rapporto incompatibilità per template/JS non supportati; preservare pacchetto originale. Nessuna perdita silenziosa. Rendering isolato senza JS/rete. .colpkg e sincronizzazione AnkiWeb fuori v1.
- R09: Backup completo versionato di dati, tentativi, fonti, media e pacchetti originali; esclusa chiave API. Validare prima di ripristino, conferma esplicita, backup preventivo. Nessun backup cloud Android automatico.
- R10: Schermate mazzi, elenco/editor, studio, cronologia/statistiche, impostazioni. Statistiche scadenze/ripassi/esiti. Modalità scritte rivelano risposta solo dopo invio; classica su richiesta.
- R13: Ripasso libero di tutte le carte attive del mazzo, anche senza carte dovute. Tentativi e risultati sono persistiti separatamente; completare un ripasso libero non crea Review e non modifica FSRS o scadenze. Conservare le bozze del ripasso programmato.
- R11: Gattino nero con testa e occhi grandi, sprite animato del pet personale 罗小黑 (Luo Xiaohei) indicato dall’utente, opzionale disattivato inizialmente. Attesa/festa/incoraggiamento/pensieroso; segue esito rettificato; neutro su errore tecnico. Silenzioso, non sposta controlli, fotogrammi statici con movimento ridotto. Battito degli occhi e movimento di attesa; salto, saluto, incoraggiamento e riflessione secondo l’esito. Animazioni sospese in background.
- R12: Sorgenti, documenti, APK, build riproducibile e verifiche Android. Prova DeepSeek reale subordinata a chiave utente, indicata pendente se assente.

## Rimandato
Generazione AI delle carte, importazione testo/PDF come materiale da elaborare, OCR, chat/tutor, voce, sincronizzazione cloud, optimizer FSRS. Fonte v1: estratto e riferimento inseriti manualmente.
