# Accettazione

Registrare qui evidenze reali; una casella non spuntata è lavoro pendente.

- [ ] R01/R10 CRUD, inversa/cloze/media e persistenza dopo riavvio su Android.
- [ ] R02/R03 classica, esatta (case/spazi/accenti/refuso), AI e rettifiche; selezione multipla.
- [ ] R04/R06 API simulata: corretto/parziale/errato/conflitto, 401/429/timeout, JSON malformato/vuoto; bozza preservata e nessun retry automatico.
- [ ] R04/R06 prova DeepSeek reale con chiave utente (non disponibile inizialmente).
- [ ] R05/R07 FSRS contro riferimenti e orologio controllato; idempotenza del commit; AI non modifica scadenze.
- [ ] R08 Anki → Memoro → Anki legacy e moderno, media, scheduling, revlog e reimportazione senza duplicati; incompatibilità esplicite.
- [ ] R09 backup/ripristino, corruzione, esclusione chiavi e copia preventiva.
- [ ] R10 rotazione/ricreazione durante risposta; statistiche e cronologia.
- [ ] R11 pet off/on, esiti/rettifica, chiaro/scuro, movimento ridotto.
- [ ] R12 build, unit test, lint, test strumentali e APK installabile.

## Consegna
README con comandi e uso; rapporto delle verifiche con limiti documentati. Mai chiamare un controllo simulato prova API reale, o un test di round-trip interno prova interoperabilità Anki.
