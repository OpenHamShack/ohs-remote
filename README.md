# OpenHamShack Remote

A native phone and tablet remote for the
[OpenHamShack](https://openhamshack.brian-henderson.co.uk) DATV appliance (called
OpenHamShack until October 2026; the repository, package and bundle ids still
are): watch what
the box receives, tune it, transmit with a safety lease, and send the
phone's camera to the box as its picture.

- `android/` - the Android app (Kotlin, Jetpack Compose, minSdk 26; libVLC
  for the picture, StreamPack for camera-to-SRT).
- `docs/REMOTE-CONTRACT.md` - how a client uses the box's API, platform
  neutral: the rules the iPad build follows too.
- `docs/TESTING.md` - toolchain, build, the fake box, the emulator, and what
  has been verified.
- `tools/fakebox.py` - the real OpenHamShack console API with the radio replaced,
  to test against without a box.

The box's own description of its API is `docs/REMOTE-API.md` in the OpenHamShack
repository.

Transmit only where your licence allows. The app keys only on a deliberate
one-second hold, and every way it can lose touch with the box ends with the
box off air.

GPL-3.0 (see `LICENSE`); third-party components in `NOTICES.md`.
