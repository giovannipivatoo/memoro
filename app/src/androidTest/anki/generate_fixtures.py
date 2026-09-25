# SPDX-License-Identifier: BSD-2-Clause
"""Generate original-content APKG fixtures with external Anki 26.9.3.

Run in a disposable venv: pip install anki==26.9.3; python generate_fixtures.py
Anki is a test oracle only. No Anki source or library is bundled with Memoro.
"""

import base64
import wave
import tempfile
from pathlib import Path

from anki.collection import Collection, DeckIdLimit, ExportAnkiPackageOptions


DEST = Path(__file__).resolve().parent.parent / "assets" / "anki"
PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScL/nwAAAABJRU5ErkJggg=="
)


def add_note(col, model_name, front, back, deck_id):
    note = col.new_note(col.models.by_name(model_name))
    names = list(note.keys())
    note[names[0]] = front
    note[names[1]] = back
    col.add_note(note, deck_id)
    return note


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="memoro-anki-test-") as temp:
        source = Path(temp) / "source.anki2"
        col = Collection(str(source))
        deck_id = col.decks.id("Memoro fixture")
        media = source.with_suffix(".media")
        media.mkdir(exist_ok=True)
        (media / "dot.png").write_bytes(PNG)
        with wave.open(str(media / "tiny.wav"), "wb") as audio:
            audio.setnchannels(1)
            audio.setsampwidth(1)
            audio.setframerate(8000)
            audio.writeframes(bytes([128] * 80))
        add_note(col, "Basic", 'Front <img src="dot.png"> [sound:tiny.wav]', "Back", deck_id)
        add_note(col, "Basic (and reversed card)", "Rome", "Roma", deck_id)
        add_note(col, "Cloze", "The {{c1::cat}} sleeps by the {{c2::fire}}.", "Extra", deck_id)
        # Created data, with a reviewed card and one history entry. Day 9 relative to crt.
        card_id = col.db.scalar("SELECT id FROM cards WHERE did=? ORDER BY id LIMIT 1", deck_id)
        col.db.execute("UPDATE cards SET type=2, queue=2, due=9, ivl=5, factor=2500, reps=1, data=? WHERE id=?",
                       '{"s":5.0,"d":5.0}', card_id)
        col.db.execute("INSERT INTO revlog (id,cid,usn,ease,ivl,lastIvl,factor,time,type) VALUES (?,?,?,?,?,?,?,?,?)",
                       int(card_id) + 100_000, card_id, -1, 3, 5, 1, 2500, 1200, 1)
        for legacy, name in ((True, "legacy.apkg"), (False, "modern.apkg")):
            col.export_anki_package(
                out_path=str(DEST / name),
                options=ExportAnkiPackageOptions(with_scheduling=True, with_deck_configs=True, with_media=True, legacy=legacy),
                limit=DeckIdLimit(deck_id=deck_id),
            )
        col.close()


if __name__ == "__main__":
    main()
