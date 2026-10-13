# Third-party components

OpenHamShack Remote is GPL-3.0. The Android app includes:

| Component | Licence | Used for |
|---|---|---|
| libVLC for Android (`org.videolan.android:libvlc-all` 3.7.7), VideoLAN | LGPL-2.1-or-later | playing the received transport stream |
| StreamPack 3.2.1 (`io.github.thibaultbee.streampack`), Thibault B. | Apache-2.0 | camera and microphone capture, encoding, MPEG-TS, SRT |
| srtdroid 1.10.1 (`io.github.thibaultbee.srtdroid`), Thibault B. | Apache-2.0 | Android binding of libsrt, used by StreamPack |
| libsrt (inside srtdroid), Haivision | MPL-2.0 | SRT; its native library also carries a crypto library for SRT encryption (OpenSSL strings are present in the binary) |
| AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines | Apache-2.0 | the app itself |
| cty.dat (Big CTY, 15 September 2026), Jim Reisert AD1C, country-files.com | MIT (its notice ships beside it as `cty.dat-copyright.txt`) | the Country column of a decode list (`core-candidates/src/main/assets`, the same pinned file as openhamshack-nb `ohscore/data`) |

Apache-2.0, LGPL-2.1+ and MPL-2.0 components may be combined into a GPL-3.0
work. libVLC is linked dynamically (its own shared libraries in the APK), so
it can be replaced as the LGPL requires.

Test-only: JUnit 4 (EPL-1.0), org.json (public domain / JSON licence) - not
shipped in the app.
