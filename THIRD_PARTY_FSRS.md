# FSRS-6 provenance

`app/src/main/java/io/github/giovannipivatoo/memoro/study/Fsrs6.kt` is an original BSD-2-Clause Kotlin implementation of the FSRS-6 equations. The 21 default parameters and equations come from [Open Spaced Repetition's algorithm reference](https://github.com/open-spaced-repetition/awesome-fsrs/wiki/The-Algorithm). No Anki or AnkiDroid source was copied.

The independent behavior reference for tests is [py-fsrs v6.3.2](https://github.com/open-spaced-repetition/py-fsrs/tree/v6.3.2), tag SHA `9446cb06605c597a063aeee49f7d188d42e34dc2`, MIT license, copyright 2022 Open Spaced Repetition. The PyPI package metadata includes the MIT license and copyright notice. The Python package is used only to produce test vectors and is not shipped in the Android app.

Reference vector command (UTC, fuzz disabled):

```python
from fsrs import Card, Scheduler, Rating
from datetime import datetime, timedelta, timezone
s = Scheduler(enable_fuzzing=False)
t = datetime(2026, 1, 1, tzinfo=timezone.utc)
c, _ = s.review_card(Card(), Rating.Good, t)
print(c.stability, c.difficulty, c.due)
c, _ = s.review_card(c, Rating.Good, t + timedelta(days=1))
print(c.stability, c.difficulty, c.due)
```

The Kotlin scheduler uses the same default parameters and 90% retention. For cards imported without FSRS memory state, it derives initial stability from their existing interval only at the first Memoro review; this is a compatibility estimate. Existing Anki due dates remain intact until that review. It is not a promise of identical scheduling under custom Anki presets, fuzzing, or custom learning steps.
