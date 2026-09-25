# SPDX-License-Identifier: BSD-2-Clause
"""Verify a Memoro mixed APKG with external Anki 26.9.3.

Requires a disposable venv with `anki==26.9.3` and the `zstd` executable.
Usage: python verify_export.py path/to/memoro-mixed-oracle.apkg
No Anki library is bundled into the Android app.
"""

import hashlib
import argparse
import json
import sqlite3
import subprocess
import tempfile
import zipfile
from pathlib import Path

from anki.collection import Collection, ImportAnkiPackageOptions, ImportAnkiPackageRequest
from anki.import_export_pb2 import MediaEntries


def decode_zstd(data):
    return subprocess.check_output(["zstd", "-d", "-q", "-c"], input=data)


def rows(db, sql):
    return db.execute(sql).fetchall()


def card_semantics(db, deck_name):
    sql = """SELECT n.guid,c.ord,c.did,c.type,c.queue,c.due,c.ivl,c.factor,c.reps,c.lapses,c.left,c.odue,c.odid
             FROM cards c JOIN notes n ON n.id=c.nid"""
    return sorted((guid, ordinal, deck_name(did), *state) for guid, ordinal, did, *state in rows(db, sql))


def review_semantics(db):
    sql = """SELECT r.id,n.guid,c.ord,r.usn,r.ease,r.ivl,r.lastIvl,r.factor,r.time,r.type
             FROM revlog r JOIN cards c ON c.id=r.cid JOIN notes n ON n.id=c.nid"""
    return sorted(rows(db, sql))


def verify(package, native_legacy=False):
    with tempfile.TemporaryDirectory(prefix="memoro-oracle-") as folder:
        folder = Path(folder)
        with zipfile.ZipFile(package) as archive:
            modern = "collection.anki21b" in archive.namelist()
            name = "collection.anki21b" if modern else "collection.anki21"
            payload = archive.read(name)
            (folder / "source.db").write_bytes(decode_zstd(payload) if modern else payload)
            if modern:
                names = [entry.name for entry in MediaEntries.FromString(decode_zstd(archive.read("media"))).entries]
            else:
                names_by_index = json.loads(archive.read("media"))
                names = [names_by_index[str(i)] for i in range(len(names_by_index))]
            media = {}
            for index, media_name in enumerate(names):
                contents = archive.read(str(index))
                media[media_name] = decode_zstd(contents) if modern else contents

        source = sqlite3.connect(folder / "source.db")
        expected_notes = rows(source, "SELECT guid,mid,flds,tags FROM notes ORDER BY guid")
        source_decks = json.loads(source.execute("SELECT decks FROM col").fetchone()[0])
        expected_cards = card_semantics(source, lambda did: source_decks[str(did)]["name"])
        expected_reviews = review_semantics(source)
        expected_counts = (3, 4, 0) if native_legacy else (6, 9, 2)
        assert (len(expected_notes), len(expected_cards), len(expected_reviews)) == expected_counts
        assert len({row[0] for row in expected_notes}) == expected_counts[0]
        assert len(media) == (0 if native_legacy else 2)
        models = json.loads(source.execute("SELECT models FROM col").fetchone()[0])
        used = {row[1] for row in expected_notes}
        assert any(models[str(mid)]["type"] == 1 for mid in used), "cloze model absent"
        assert any(len(models[str(mid)]["tmpls"]) == 2 for mid in used), "reverse model absent"
        assert any(len(models[str(mid)]["tmpls"]) == 1 and models[str(mid)]["type"] == 0 for mid in used), "basic model absent"

        target = Collection(str(folder / "imported.anki2"))
        try:
            target.import_anki_package(ImportAnkiPackageRequest(
                package_path=str(package.resolve()),
                options=ImportAnkiPackageOptions(with_scheduling=True, with_deck_configs=True),
            ))
            db = sqlite3.connect(folder / "imported.anki2")
            assert rows(db, "SELECT guid,mid,flds,tags FROM notes ORDER BY guid") == expected_notes
            assert card_semantics(db, lambda did: target.decks.get(did)["name"]) == expected_cards
            assert review_semantics(db) == expected_reviews
            for name, original in media.items():
                assert hashlib.sha256(Path(target.media.dir(), name).read_bytes()).digest() == hashlib.sha256(original).digest(), name
            db.close()
        finally:
            target.close()
        source.close()
        print(f"Anki 26.9.3 import OK: {len(expected_notes)} notes, {len(expected_cards)} cards, "
              f"{len(expected_reviews)} reviews, {len(media)} media; GUID, fields, scheduling, revlog and media bytes match")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("package", type=Path)
    parser.add_argument("--native-legacy", action="store_true", help="expect the native-only 3-note, 4-card legacy fixture")
    args = parser.parse_args()
    verify(args.package, args.native_legacy)
