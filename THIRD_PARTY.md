# Componenti di terze parti

Il codice originale di Memoro è BSD-2-Clause. Le licenze dei componenti rimangono le rispettive licenze originali; non viene incorporato il backend Anki/AnkiDroid.

| Componenti runtime | Licenza | Provenienza |
|---|---|---|
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | Apache-2.0 | https://github.com/JetBrains/kotlin / https://github.com/Kotlin |
| AndroidX, Compose, Room, Lifecycle | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| OkHttp e Okio | Apache-2.0 | https://github.com/square/okhttp / https://github.com/square/okio |
| zstd-jni | BSD-2-Clause | https://github.com/luben/zstd-jni/tree/v1.5.7-3 |
| Zstandard (in zstd-jni) | BSD-3-Clause | https://github.com/facebook/zstd/tree/v1.5.7 |

I testi delle licenze runtime sono inclusi negli asset `licenses/` dell'APK. Attribuzione e provenienza FSRS sono documentate separatamente insieme al codice dello scheduler.

## Asset del pet

`luoxiaohei_sprites.webp` proviene dal pet personale **罗小黑** (`luoxiaohei2d`) indicato dall'utente. Il file originale è riutilizzato senza modifiche; le animazioni sono riprodotte da codice Compose originale di Memoro. La descrizione del pet indica Luo Xiaohei come riferimento visivo. Provenienza e precedente asset della v0.2 sono documentati in [UI-UX.md](docs/UI-UX.md).
