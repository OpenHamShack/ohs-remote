# Building and testing OpenHamShack Remote

## Toolchain (shack, no root)

Everything lives in `~/android-toolchain`:

| What | Where |
|---|---|
| JDK 17 (Temurin 17.0.20) | `~/android-toolchain/jdk17` |
| Android SDK (cmdline-tools, platform-tools, platforms 35 and 37.0, build-tools 35/36/37, emulator, system image android-34 google_apis x86_64) | `~/android-toolchain/sdk` |
| Gradle 9.8.1 (only used to make the wrapper) | `~/android-toolchain/gradle-9.8.1` |
| AVD `ohsr` (Pixel 6, Android 14, both cameras emulated) | `~/android-toolchain/avd` |
| How it was installed | `~/android-toolchain/install.sh` |

No NDK: nothing is compiled natively; libVLC and srtdroid come as AARs.

```sh
export JAVA_HOME=~/android-toolchain/jdk17 ANDROID_HOME=~/android-toolchain/sdk
cd ~/openhamshack-remote/android
./gradlew assembleDebug testDebugUnitTest
```

`android/local.properties` (not committed) says `sdk.dir=...`.

APKs: `android/app/build/outputs/apk/debug/` - `app-arm64-v8a-debug.apk`
(phones, ~44 MB), `app-universal-debug.apk` (~125 MB), and one per other ABI.

## Unit tests

`android/app/src/test`: address parsing, status/Larix parsing against real
fixtures, formatting, and the PTT lease on virtual time against a fake box
(one test per way a transmission can end), and ShackFormat against the
shared formatting vectors. Before any of them, `i18nCheck` runs the string
check over the whole repo and fails the build on a user-visible literal
outside the catalogs ([I18N.md](I18N.md)); it needs python3 3.11+.

Refresh the fixtures when the box's status changes shape (fakebox running):

```sh
cd android/app/src/test/resources
R=http://127.0.0.1:18081
curl -s -c jar -XPOST $R/api/login -d '{"pin":"1234"}'
curl -s $R/api/status | python3 -m json.tool > status-viewonly.json
curl -s -b jar -XPOST $R/api/ptt -d '{"on":true,"remote":"android-fixture"}' | python3 -m json.tool > status-onair.json
curl -s -b jar -XPOST $R/api/ptt -d '{"on":false}'
curl -s -b jar $R/api/stream/larix | python3 -m json.tool > larix-authorised.json
curl -s $R/api/stream/larix | python3 -m json.tool > larix-viewonly.json
```

## A box to test against: tools/fakebox.py

```sh
python3 tools/fakebox.py          # OpenHamShack checkout from $OPENHAMSHACK_SRC, else ~/openhamshack
```

The real `openhamshack.py` Handler with the radio replaced - nothing is encoded or
sent to a radio. `127.0.0.1:18080` answers as the box's own screen,
`:18081` as a remote browser (PIN `1234`); SRT stream source on `:19710`,
passphrase `fakebox-passphrase`. The emulator reaches them as `10.0.2.2`.
Modes: `FAKEBOX_PORT`, `FAKEBOX_CODEC=hevc`, `FAKEBOX_AUDIO=aac`,
`FAKEBOX_CORRUPT=0.02`, `FAKEBOX_CUT=15`, `FAKEBOX_NOSIGNAL=1`,
`FAKEBOX_DEBUG=1`. See the script's docstring.

Run more than one at once on different `FAKEBOX_PORT`s; only one should
have the Stream source selected (they share port 19710).

Never point the app at the workshop box or any Pluto for testing.

Discovery on the emulator (debug builds only): the emulator cannot see the
host's mDNS, so publish a pretend box from the device itself:

```sh
adb shell am broadcast -n com.openhamshack.remote/.DebugAdvertiser \
    --es name GM4BRI --es version 0.9.17 --ei port 8080
```

It advertises the emulator's own address, so opening it reaches nothing.

## A controller to test against: tools/fakeshack.py

`tools/fakeshack.py` is a controller's front door (CONTROLLER-CONTRACT 3-5)
in front of the real store servers, run on simulators and fakes. Nothing
behind it keys anything real. Each app is optional; one that is not
running is listed with `running: false`.

| App (kind) | Server behind it | Default port |
|---|---|---|
| `shack-qo100nb` (qo100nb) | `cd ~/openhamshack-nb; SHACK_PORT=18611 DOCK360_SHACK_PROXY_SECRET=fakeshack python3 -m ohsnb --server` | 18611 |
| `shack-radio` (radio) | same with `ohsradio`, `SHACK_PORT=18612` | 18612 |
| `shack-qo100wb` (qo100wb) | `FAKEBOX_PLATFORM=1 FAKEBOX_PORT=18621 python3 tools/fakebox.py` (the box daemon in platform mode) | 18621 |
| `shack-datv` (datv) | `cd ~/openhamshack-datv; DOCK360_SHACK_PROXY_SECRET=fakeshack python3 -m ohsdatv --server --port 18623` (the DATV simulator: a real H.264 picture) | 18623 |
| `shack-icom` (icom) | `FAKEFLOW_PREFIX= FAKEFLOW_PASSWORD= FAKEFLOW_PORT=18624 python3 ~/stationflow-tv/tools/fakeflow.py` | 18624 |
| `shack-flex` (flex) | the real Flex module (dock360-apps, flex line) in platform mode on its own pretend radio: see below | 18625 |

```sh
FAKESHACK_AUTO_APPROVE=1 python3 ~/openhamshack-remote/tools/fakeshack.py   # :18600
```

Ports move with `FAKESHACK_PORT`, `FAKESHACK_NB_PORT`, `_RADIO_PORT`,
`_WB_PORT`, `_DATV_PORT`, `_ICOM_PORT`, `_FLEX_PORT` (fakebox's `FAKESHACK_PORT` must
match, for the profile). Give each server its own `HOME`/`XDG_*` if the
desktop app runs on the same account (they keep a station store).

- **Signing in:** add `10.0.2.2:18600` in the app. The TV pairs by code
  (auto-approved, or `curl -XPOST :18600/fake/approve -d '{"code":"123456"}'`);
  a phone signs in with any user and password `fake`. Tokens live in the
  fake's memory: restarting it, or `curl -XPOST :18600/fake/revoke`, is how
  to see the "pair again" path.
- **The gate is the real one's rules:** no credential is view on an app
  with anonymous viewing (QO-100 WB, as D3; `FAKESHACK_ANONYMOUS=id,id`
  changes the list) and 401 `credentials_required` on any other; a level
  below the route's minimum (each manifest's `levels`) is 403
  `level_too_low`. Phones are capped at transmit, TVs at control.
- **QO-100 WB's licence** is decided from the fake's station profile
  (`GET api/shack/v1/profile`, UK Full, `FAKESHACK_CALLSIGN`, default M0TST)
  and the shared dataset, as on a controller: fakebox's 437 MHz is refused
  (the satellites' segment), 2405.25 MHz at 333 kS is allowed. fakebox
  keys only its pretend radio; the lease (heartbeat, 5 s) is the daemon's.
- **SSTV:** the simulators send none, so fakeshack slips a Martin 1
  colour-bar picture (a white stripe that moves between pictures) into any
  NB/Radio events socket that asked for `events`, a line every 40 ms (~10 s
  a picture), every `FAKESHACK_SSTV_EVERY` seconds (60; 0 for none) -
  **only while the engine's mode is `sstv`**, as the real decoder: the
  first 3 s after the mode becomes sstv, cut off mid-picture when it
  changes (`FAKESHACK_SSTV_ANYMODE=1` for any mode, as before). FT8, CW,
  PSK and RTTY come from the simulators' own decoders and follow the mode
  already, so choosing a mode or bookmark on a Remote shows each panel
  open and close with real events. Its frames go between the server's,
  which it parses whole.
- **Radio's decoder sessions** (openhamshack-nb SERVER-API "Decoders",
  RADIO-SDR-PLAN §4.4): the simulator runs none, so with
  `FAKESHACK_DECODERS=1` fakeshack answers Radio's `api/decoders` and the
  routes under it itself (`tools/fakedecoders.py`): a catalogue of RTTY,
  FT8 (only as the mode: 409 `decoder_unavailable`) and DAB+ (needs the
  missing faad2), one RTTY session `ds_fake1` already decoding a CQ and an
  exchange on 14.0835 MHz, and sessions a client starts (RTTY: `starting`,
  then `synced` after 2 s) and stops, four at most, with the server's
  refusal codes. Radio's `api/status` gains `decoders`, and an events
  socket whose `kinds` include `decoder` gets each session's results,
  about seven characters a second, `rev` rising by one. Unset, the routes
  go to the server as any other. Nothing is decoded; the text is made up.
- **Radio's SDR antenna inputs** (openhamshack-nb SERVER-API: `soapyAntenna`,
  `soapyAntennaLabels`, `deviceStatus.antenna`/`antennas`): the simulator
  opens no SoapySDR device, so with `FAKESHACK_RADIO_ANTENNAS=1` fakeshack
  makes Radio look like an SDRplay RSPdx (`tools/fakeantennas.py`):
  inputs `Antenna A`, `Antenna B`, `Antenna C`, with C in use, labelled
  "Diamond X7000", "Unused" and "Loop Antenna". They appear in `GET
  api/state` (flat, as the server's: the settings and `deviceStatus` are
  top-level keys, the labels one line of JSON in a string) and in every
  events `state` message carrying `deviceStatus` or either setting.
  `POST api/set` with `soapyAntenna` or `soapyAntennaLabels` is applied by
  the fake and sent out as a `state` message, as the engine does; `""` is
  the driver's default (`Antenna A` on an RSPdx), and an input the radio
  lacks is kept as the setting but not applied, with the engine's
  "Antenna not changed: ..." toast. Any other key in the same set goes on
  to the server, and the antenna keys are applied only if the server takes
  the rest, so the set stays all or nothing. The gate makes `api/set`
  Control as ever. Remote's antenna line and picker (Radio's screen, the
  TV's controls row) need this to show at all against a simulator.
- **Radio's listening bands, wide FM and DAB** (RADIO-LISTEN-CONTRACT.md):
  the simulator has none of them, so with `FAKESHACK_RADIO_LISTEN=1`
  fakeshack makes Radio look like the RSPdx on ohs-box
  (`tools/fakelisten.py`). `api/status` carries `rxRange: [1000,
  2000000000]` (`FAKESHACK_RADIO_RXRANGE` still wins), `api/spec`'s dial
  maximum is 2 GHz and its modes include `wfm`. A set to `wfm` goes on to
  the simulator as `fm`, and while it is in force the fm the server reports
  (`api/state`, the set's `changed`, events `state` messages) reads `wfm`.
  The audio is the simulator's FM. The simulator's own 100 kHz floor
  stays, below LW. `api/decoders` lists a `dab` decoder (with
  `FAKESHACK_DECODERS=1`, after fakedecoders' own). A session on a block
  (`{"decoder":"dab","freqHz":222064000,"params":{"block":"11D"}}`,
  `rfHz` taken too) reports `sync:false` at once and, about 2 s later,
  `sync:true`, a made-up ensemble ("Fake Mux 11D") and five to eight
  services, the same for a block every time. They alternate `dab`
  (playable) and `dab+` (with a `why`: no faad2, as on ohs-box until it
  arrives). Block 5A never syncs and says why. `POST .../sessions/{id}
  {"params":{"service":sid}}` sets `playing` on a dab service and is 409
  `decoder_unavailable` on a dab+ one. Results go to the `?since=` poll
  and to events sockets that asked for `decoder`. With
  `FAKESHACK_RADIO_LISTEN_DAB=missing` the decoder is listed unavailable,
  and starting it is 409 `decoder_unavailable` with `needs: ["faad2"]` and
  the server's sentence. Nothing is decoded; no station audio plays.
  The same switch gives Radio a calibrated S-meter: each events `meter`
  item gains `dbm` (`rx` less 50.4 dB) and `s` by the IARU rule at the
  last reported dial (S9 = -93 dBm above 30 MHz, -73 dBm below, 6 dB an
  S-unit, "S9+N" above). `FAKESHACK_RADIO_LISTEN_SMETER=uncalibrated` sends
  a `why` sentence instead, for the screens' uncalibrated path.
- **Band Activity (FT8 with countries):** the NB simulator's FT8 at
  10489.540 MHz has stations in eight countries (G4XYZ, DL1ABC, EA4QRP,
  F5KLM, PA3QOS, OH8ZZ, SV1QO, ON4BB). An NB from openhamshack-nb with the
  engine's `_with_country` names each on the event (`country`, `dxcc`,
  `continent`); an older one sends none, and the app's own cty.dat fills
  the column - run each once to see both paths. Behind fakeshack the
  station's callsign is the NB server's own setting, not the fake's
  profile: put `"callsign": "G4XYZ"` in its `config.json` (under the
  `HOME` you gave it, `.config/openhamshack-nb/`) before it starts and the
  messages to G4XYZ show in amber as "to you". A tablet's layout on the
  phone AVD: `adb shell wm size 2960x1848; adb shell wm density 340`
  (the Tab S9 Ultra), `wm size reset` after.
- NB and Radio: transmit stays locked on the simulator (no station profile
  reaches them), so only the refusals and the lease's 409/403 answers are
  testable there.

`~/build/m4-wb/fakes.sh start|stop|status` on shack started all of these on
187xx ports with pid files for the M4 WB work.

**Flex behind fakeshack** is three processes from the flex line's `flex/`
folder, none of which reaches a radio: Flex's own pretend FLEX-6000 (which
must have the radio's fixed control port, so one at a time per host), its
fake controller services (station profile and broker, which the module
needs before it will claim the radio), and the module itself, with the
shared libraries' wheels (`flex/tools/vendor/*.whl`) unpacked onto
`PYTHONPATH` (aiohttp and libopus from the host):

```sh
python3 tools/fakeflex.py --host 127.0.0.1 --port 4992
python3 tools/fakecontroller.py --app http://127.0.0.1:18625 --secret fakeshack --services-port 18631
PYTHONPATH=<unpacked wheels> SHACK_PLATFORM=dock360 FLEX_HTTP_PORT=18625 FLEX_DATA_DIR=<dir> \
    RADIO_HOST=127.0.0.1 DOCK360_SHACK_PROXY_SECRET=fakeshack \
    DOCK360_SHACK_URL=http://127.0.0.1:18631 DOCK360_SHACK_TOKEN=shackapp_test python3 -m app.main
```

Transmit stays off (`ALLOW_TRANSMIT` unset) and fakeflex keys nothing real
anyway; `fakeflex.py --log FILE` records any PTT it is asked for.
`~/build/m4-icom-flex/fakes.sh start|stop|status` on shack runs fakeshack
(:19400), fakeflow (:19424) and the three Flex pieces (:19425, :19431,
:4992) with pid files.

### Verified on the emulators (9 October 2026, controller at 10.0.2.2:18700)

Phone (`ohsr`), signed in as a phone (Transmit cap): the app list with
QO-100 WB and DATV openable, Icom "not in this version"; QO-100 WB's
picture (fakebox's test card, through the relay with the bearer token),
`brian · Transmit`, no Camera tab; a 1.6 s hold keyed fakebox's pretend
radio under the lease (KEYED with the install's remote id), one tap
unkeyed; keyed again then Home: unkeyed at once; `/fake/revoke` while open:
back to sign-in. DATV: the simulator's H.264 picture, lock/MER/service, the
simulator line, band 23cm (dial to 1255 MHz, searching).
NB on the controller (`ohsnb --server` on :18711, simulator): FT8 at
10489.540 MHz gives real decodes each slot (CQs from the simulated
cluster) and CW on the lower beacon (10489.5004, mode cw) a stream of
characters (garbled: the beacon is F1A FSK, not on-off keying); the TV
showed both and toggled NR from the OK row (state `nr` true, then put back).
SSTV (fakeshack's picture every 20 s): the TV's panel opened by itself
and painted line by line (Line 60 of 256), the previous picture in the
gallery.
TV (`ohsr-tv`): paired by code, the app list by D-pad, QO-100 WB's picture
and overlay (Back: overlay, then apps), DATV's overlay, its Tune panel
(70cm chosen by D-pad: locked, the simulator's picture). Screens:
`~/build/m4-wb/screens/` on ohs-dev.

### Modes, bookmarks and decoder panels (10 October 2026, controller at 10.0.2.2:18800)

`~/build/m4-owrx/fakes.sh` on shack: fakeshack :18800, `ohsnb --server`
:18811 and `ohsradio --server` :18812 (openhamshack-nb 8498fe9, simulators).
TV (`ohsr-tv`), D-pad only: NB opened on the waterfall with all twelve of
NB's bookmarks named along the spectrum; Up to the bookmark under the
dial, Left to "FT8", OK: dial 10489.540, mode FT8, the list docked beside
the waterfall with the simulator's decodes each slot; Up to the modes,
LSB: the panel closed; the "Lower beacon" bookmark: CW, the text line
under the waterfall (garbled: the beacon is FSK); SSTV from the mode row:
the picture beside the waterfall at 5:4 from line 1, filling downward,
Down then OK full screen, BACK out of that, BACK to the apps. One BACK
leaves the screen from the waterfall with a panel docked. Radio on 2 m:
its two bookmarks on the scope, the FM channel shaded. Phone (`ohsr`):
the same rows as chips, the FT8 bookmark opening the list under the
waterfall, USB closing it, the SSTV picture filling at 5:4. Screens:
`~/build/m4-owrx/screens/` on ohs-dev (`tv-*`, `phone-*`; `base-*` are the
build before, used to look for the BACK bug).

Not seen: Radio on HF (the HF bookmarks are in the fixture and tested,
not exercised on a screen); the passband shading is a few pixels wide at
NB's wide span and only reads in zoom; the auto-open switch's off path on
a screen (tested in `DecoderPanelTest`).

### Icom and Flex (10 October 2026, controller at 10.0.2.2:19400)

`~/build/m4-icom-flex/fakes.sh` on shack (fakeshack, fakeflow, and the
real Flex module at dock360-apps flex a95e508 on fakeflex). TV (`ohsr-tv`,
port 5640, read-only), D-pad only: paired by code; the app list shows Icom
and Flex openable. **Icom**: the IC-9700's own face (radio-shaped display)
through the controller with the token; Right x3 tuned 144.174.000 to
.300 (fakeflow's state agreed, three `/api/freq` posts, `tx_attempts`
empty, audio/scope/state sockets 1/1/1); one BACK to the apps closed all
three at fakeflow (0/0/0). **Flex**: 14201.00 kHz USB 20 m, S8, the
panadapter and the radio's waterfall with fakeflex's three carriers, the
USB passband shaded; Right x3, Up (step 100 to 500), Right: 14201.50 on
the module; OK opened modes/bands/mute, AM and 60 m chosen (the module
agreed: 5335.5 AM; a band move restores that band's mode, as on the
page); Down past Mute stayed in the panel; BACK closed it, Left tuned
(focus back on the display), BACK left (the module logged the page
going). Receive audio: an AudioTrack for the app at 24 kHz mono,
active, frames advancing (`dumpsys media.audio_flinger`); the 700 Hz
tone itself was not measured. Phone (`ohsr`, port 5642): signed in with
password `fake`; Flex laid out as the TV's plus buttons and mode chips,
a tap on a carrier tuned to it (5.348 MHz on the module); Icom's own
compact face. fakeflex's PTT log stayed empty throughout.
Screens: `~/build/m4-icom-flex/screens/` on ohs-dev (`tv-*`, `phone-*`).

Not seen: the display following the dial off an edge (unit-tested only),
a token revoked while Icom or Flex is open, Icom's other models' faces in
Remote, a real controller, the real radios, the Fire TV.

### Flex receive controls (10 October 2026, controller at 10.0.2.2:19600)

`~/build/flex-controls/fakes.sh start|stop|radio` on shack: fakeshack
:19600 and the real Flex module (flex 5b9a5d4) :19625 on
`tools/fakeflex_rx.py` (this repo: the module's fakeflex plus antennas,
AGC, RF gain and five FT8 stations a slot; `AUDIO=ft8|ft4|sstv|tone`,
`EXT=0` for a radio without the newer DSP; `radio` restarts only the
radio, which resets its slice). TV emulator `ohsr-tv` on -port 5680,
`wm size 1920x1080` + `wm density 320` (the Fire TV's 960x540 dp, captured
at 1080p); phone `ohsr` on 5684. Seen, D-pad only on the TV: Menu toggled
NR (the module's state agreed); the extras' Receive row; Noise with all
eleven functions on an extended radio, NR level 50 to 60 by Right, RNN
with no level; AGC-T 65 to 55; RF gain Preamp +16 and antenna RX_A held
on screen while the module's state still said 0 dB / ANT1 (as on a real
radio); Decoder FT8 with "FT8 is on 14.074 MHz DIGU", Tune to it, then
the docked list with every period's decodes (GM4BRI's call in amber, CQs
in the accent) and the full list; SSTV on: Robot 36 bars painting line by
line beside the display, a reopened app getting the picture from line 1
(late join), the kept pictures and one full screen. Phone: the NR chip,
the Controls sheet (switches, sliders, chips), the SSTV picture under the
display. fakeflex's PTT log stayed empty. Screens:
`~/build/gate6/flex-controls-*.png` on ohs-dev.

Not seen: the real radio (in particular whether `slice set <n> rfgain=`
works there: FlexRadio's API puts RF gain on the panadapter), a View-only
TV token on these controls (unit-tested), JS8/Q65 decodes (fakeflex sends
FT8/FT4 only), the Fire TV.

### The calm screen (10 October 2026, controller at 10.0.2.2:18900)

`~/build/m4-calm/fakes.sh` on shack: fakeshack :18900, `ohsnb --server`
:18911, `ohsradio --server` :18912; emulators on their own ports (`ohsr-tv`
-port 5580, `ohsr` -port 5582, both `-read-only`), driven by
`~/build/m4-calm/do.sh` run on shack itself (keys, taps and screencaps in
one ssh: under load, one ssh per key let the picker time out between
keys). TV, D-pad only: NB opens to one header line, the subtitle, the
waterfall with ticks and one name, and the key hints for 6 s; Up opens the
picker over the foot of the waterfall on the bookmark by the dial, the
waterfall's name following the highlight; OK on FT8: the list docked
beside the waterfall, time once per slot; Lower beacon: CW, the text line
under it; BACK to the apps and NB again: the hints back for a moment.
Screens: `~/build/m4-calm/screens/` on ohs-dev, beside the old ones in
`~/build/m4-owrx/screens/`.

### Faces fill the screen; mode grid, SET, zoom, reference level, NR2, D-STAR, band plan (11 October 2026, controller at 10.0.2.2:19500)

`~/build/face-overhaul/rig.sh start|stop|status` on shack: fakeshack
:19500, Flex's module at dock360-apps `dd561ab` (the merged NR2/zoom
line) on Flex's own fakeflex and fake services; the Icom app either
`tools/fakeflow_face.py` (fakeflow plus the module's new fields) :19524
or, as finally checked, the real Icom module at `02e00f6` on shack_rig's
FakeIcom, run on ohs-dev by `~/build/face-overhaul/serve_icom.py 19534
[MODE]` and tunnelled to shack (`ssh -N -R 19534:127.0.0.1:19534
shack`). `~/build/face-overhaul/emu.sh start|stop|keys|shot` drives
`ohsr-tv` (or `AVD=ohsr`) on -port 5690, adb to it alone.

TV, D-pad only: the 9700's face fills the 1080p screen bar a 20 x 12 dp
margin; Up from the frequency to the mode badge, OK, the MODE grid
(LSB...DD, then the DATA row) on the current mode; CW-R chosen reached
the module and the badge printed CW-R; the scope, OK: amber adjust mode,
Up Up moved the reference to +2.0 dB and Right zoomed to the next span on
the module and on FakeIcom's own scope; OK again opened the Band scope
panel; SET opened Settings (P.AMP/ATT, NR2, CW pitch, mute, step) and
Back returned focus to SET; DECODE opened on the CW tab in CW-R, on
D-STAR in DV with FakeIcom's caller, repeater and message. Flex: the
band plan strip under the spectrum (PHONE, ALL MODES), OK held for adjust
mode (REF -30 dBm, 100 kHz after two Ups and a Right), its Panadapter
page with black level and colour gain, the extras with wrapped modes, NR2
and Scope. Phone (`ohsr`): the compact Icom face upright, the wide one on
its side, a tap on the mode badge opened the grid, holding the scope the
Band scope panel with minus and plus; Flex's mode under the frequency
opened its grid (rows wrap by screen width) and CW reached the module.
Nothing keyed: fakeflex's PTT log empty, no transmit path in any POST.
Screens: `~/build/face-overhaul/screens/` on shack.

Found on the way, not Remote's: the Icom module answered `{"ok": true}`
to a mode change against FakeIcom without the radio changing mode (USB
to CW or DV; started in DV, it reported DV and D-STAR correctly).

### Remote keys and Enable TX (12 October 2026, controller at 10.0.2.2:19700)

`~/build/remote-keys/fakes.sh start|stop|status` on shack: fakeshack
:19700, openhamshack-nb main (2b73306 and after) `ohsnb`/`ohsradio --server` on
:19711/:19712 with `DOCK360_SHACK_URL`/`DOCK360_SHACK_TOKEN` pointing at
the fake, fakeflow_face :19724, the Flex module on fakeflex :19725; the
platform-mode fakebox :19721 and `ohsdatv --server` :19723 were started by
hand. `emu.sh tv|phone` drives `ohsr-tv` (5700) and `ohsr` (5702);
`tap.sh` taps a node by its text from a uiautomator dump.

- **Enable TX needs a station callsign**, and the store servers get the
  profile from the controller's shared store, which fakeshack does not
  sync. Seed it into each engine's own store instead (the running engine
  picks it up):
  `PYTHONPATH=<shack_client wheel dir> python3 -c "from shack_client.station import Station; Station.open(app='t', path='<HOME>/.local/share/openhamshack/station.db', background=False).profile.update({'callsign':'M0TST','locator':'IO75RL','country':'GB'})"`.
  Without it the arm is refused `no_callsign`, which is also worth seeing.
- **adb cannot hold a key for a second.** `input keyevent --longpress`
  is about half a second (adb's long press), there is no `--duration` on
  API 30 or 34, `sendevent` needs root (refused on these images), and the
  emulator console's `event send` answers OK but nothing reaches the app.
  So Play/Pause held for 1 s is tested only by the JVM tests
  (`KeyDispatcherTest`) and on a real remote. The D-pad path (Transmit…
  in the OK row) and OK on a decode arm the same overlay and are testable.
- **Phone, Transmit level:** Call CQ armed the red confirm (FT8, the dial,
  `CQ M0TST IO75`, 5 s ring); left alone it disarmed; Start armed it on
  the server, which held it well past 5 s on the heartbeat and showed the
  next over; Stop, and separately a hardware Left (`input keyevent`),
  disarmed it ("switched off by brian") without tuning.
- **TV, Control level:** Transmit… in the OK row armed the overlay and OK
  confirmed; the server's 403 `level_too_low` was shown with the Devices
  page explanation. OK on a decode in the full list armed a reply naming
  the station; Left cancelled it without tuning. Tune, step on FF, the OK
  row, Info's legend, Menu's settings, Up's picker on NB; Up's MODE grid,
  Menu's SET, Down to the scope and back, FF to 1 kHz on the IC-9700
  face; Up's mode-and-band grid, Down to adjust mode, Menu to the Noise
  page on Flex; Right to the next channel and Up's channel grid on WB;
  FF to 23 cm, a channel grid and Muted on DATV.
- Nothing keyed a radio: the NB simulator's own pretend radio keyed one
  FT8 over during the phone test; fakeflex's PTT log stayed empty.

### Radio: band plan strip, band buttons, Flex-style layout (13 October 2026, controller at 10.0.2.2:29600)

`~/build/radio-bands-run/fakes.sh start|stop|status` on shack: fakeshack
:29600 in front of openhamshack-nb main (8c169b4) `ohsnb`/`ohsradio --server` on
:29611/:29612 (vendored ohs-libs wheels and ohsdigi in `~/build/radio-bands-run/site`);
`RXRANGE=lo-hi fakes.sh start` gives Radio's status an `rxRange`
(`FAKESHACK_RADIO_RXRANGE`). A fakeshack restart forgets its tokens, so
every device signs in or pairs again. `emu.sh phone|tv` drives `ohsr`
(5742) and `ohsr-tv` (5740); the tablet is `ohsr` with `wm size 2960x1848`
/ `1848x2960` and `wm density 340`. Screens: `screens/` there.

- **Phone (`ohsr`):** Radio opened on 2 m with the FM strip and the row
  scrolled to 2 m lit. 40 first visit in FM (no station country, so IARU
  R1): 7.130 LSB at the time (now 7.133 with the 3 kHz rule, by test); ▶
  three times to 7.133, 20 (14.125 USB), 40 again: back on 7.133 LSB. FT8
  from the Controls sheet, then 17: 18.100 FT8 with Band Activity under
  the scope. Scope/Controls sheets open and close.
- **Tablet, both orientations:** Radio's header row, big dial, full-width
  scope with CW / ALL MODES words on the strip, all fourteen bands in one
  row; FT8 on 17 m splits 50/50 with Band Activity (country column) in
  landscape. 20 pressed again: 14.030 CW, then 14.074 FT8. FM from the
  sheet, then 70cm: 433.500 FM. With `RXRANGE=1000-60000000` the row was
  160 to 6. **NB** (same build, tablet): its own screen as before, Back
  top right, FT8 Band Activity 50/50 in landscape and under the scope
  upright. Back from Radio returned to the app list.
- **TV (`ohsr-tv`), D-pad only:** paired by auto-approve, Radio opened on
  70cm with the band strip under the header (70cm lit) and the plan strip
  on the waterfall; CH−: 2 m on 145.500 FM (the TV's own first visit);
  CH− again: 4 m, which has no rows in IARU R1 and no FM calling channel,
  landed on the server's FT8 memory (70.154 FT8), the strip blank there.

Not seen: a real Radio (the RSPdx on ohs-box), a station profile with a
country (so RSGB 2026 in force) on a screen - RadioBandsTest covers its
answers; the Fire TV's own remote's channel keys (adb sends them).

## Emulator

`/dev/kvm` is usable on shack (the user is in `kvm`).

```sh
export ANDROID_HOME=~/android-toolchain/sdk ANDROID_AVD_HOME=~/android-toolchain/avd
$ANDROID_HOME/emulator/emulator -avd ohsr -no-window -no-audio -gpu swiftshader_indirect \
    -no-boot-anim -no-snapshot -camera-back emulated -camera-front emulated &
echo $! > emulator.pid        # stop it with: kill $(cat emulator.pid) - never pkill -f
adb install -r android/app/build/outputs/apk/debug/app-x86_64-debug.apk
adb shell am start -n com.openhamshack.remote/.MainActivity
```

Boots in about 20 s. Drive it with `adb shell input` and `uiautomator dump`
(the app's text is in the dump; Compose test tags are not exposed as ids).

## What was verified on the emulator (8 October 2026)

Against fakebox (OpenHamShack 0.9.16's Handler, with the lease and Larix fields
of the box's REMOTE-API.md):

- Add a box, open it; view only without the PIN, with the refusal said on
  a tap; wrong PIN refused with the box's words; PIN accepted; a stale
  session noticed after the box forgot it.
- Receive: H.264+MP2 and HEVC+AAC with 2% packet loss play; a stream cut
  every 15 s reconnects each time; a no-signal stream stays connected with
  no reconnects and says so; recovery after airplane mode.
- Tune: presets (incl. QO-100 with the 9750 offset), symbol-rate chip,
  typed frequency (offset back to 0).
- Transmit: a tap and a 0.4 s hold do not key; a 1.5 s hold keys with
  heartbeats every second; one tap stops; stop from full screen. Home and
  screen-off unkey at once; force-stop and airplane mode are ended by the
  box's lease 4.7 s later.
- Camera: permissions, the Stream source selected, SRT with passphrase into
  the daemon's real ingest ffmpeg, "receiving" H.264 1280x720 + AAC, front/
  back switch without dropping, Home stops sending.
- Layouts: phone portrait (stacked) and landscape (side panel).

Round 2 (box at 670c469 through fakebox): the transmit settings panel
(modulation moves the FEC list, power steps, a refused 0 kS shown as "Not
saved: ...", a typed out-of-licence frequency hinted, then txBlocked above
TRANSMIT and the button not keyable; on air, frequency reported as waiting
for the key-up and power applied at once); Sign out; a PIN changed at the
box returning the app to view only with the message; Bonjour listing two
advertised boxes with name, version and port; no-signal stream stable at
ten null packets a second.

Not verified: connecting to a box found by Bonjour (the emulator cannot
see a real one); a real phone or tablet; a real box (the box-side endpoints
were not yet released); real tuner streams, including a signal locking on
an open no-signal stream; a real hardware encoder's SRT output (the
emulator's camera is synthetic); keying with the Stream source on a real
box (the harness does not run the encoder); PTT under a real 30 s LibreSDR
key-up.

## TV mode (9 October 2026)

The same APK shows a receive-only TV UI on Android TV, Google TV and Fire
TV (ui/TvUi.kt, ui/TvTune.kt). Emulator: `system-images;android-30;android-tv;x86`
(Android 11, as Fire OS 8), AVD `ohsr-tv` (`tv_1080p`), started as above with
`-avd ohsr-tv`; its launcher's app list is
`adb shell am start -n com.google.android.tvlauncher/.appsview.AppsViewActivity`.
Drive it with `adb shell input keyevent DPAD_UP|DPAD_DOWN|DPAD_LEFT|DPAD_RIGHT|DPAD_CENTER|BACK|MENU`.

Verified against fakebox with D-pad keys only: the banner in the TV
launcher's app list and its app picker; launch from there; Bonjour boxes
(DebugAdvertiser) listed first with focus on the first; an address and a
PIN typed on the on-screen keyboards (Next / Done from the keyboard);
picture plays; OK shows the overlay, Back hides it, Back again returns to
the boxes, Back on the boxes leaves the app; view only up/down says why;
Tune panel: PIN entered on the numeric keypad, band preset, frequency
nudged in 0.025 and 1 MHz steps and sent once on OK, typed 10491.5 (LNB
offset 9750 set, QO-100 rates shown), symbol-rate chip, Back dropping an
unsent nudge; signed in, up/down stepping presets with wrap. Phone UI
checked unchanged on `ohsr`.

Brian's Fire TV (AFTKAUK001, Fire OS 7.7.1.7 = Android 9, armeabi-v7a
only) takes `app-armeabi-v7a-debug.apk`. Sideload: TV Settings › My Fire
TV › Developer options › ADB debugging on (and "Apps from unknown
sources"), then `adb connect <tv-ip>:5555`, accept the prompt on the TV,
`adb -s <tv-ip>:5555 install -r app-armeabi-v7a-debug.apk`. It appears
under Apps / Your Apps & Channels.

**For using it rather than debugging it, install the fast build.** The
debug APK starts in 7.3 s on the Fire TV, 0.46 s for the fast one (cold
start, `am start -S -W` TotalTime, 23 October 2026). Fire OS does not
compile a debuggable APK at install (`dumpsys package dexopt` says
run-from-apk), so each start reads 41 MB of dex and runs it interpreted.
The fast build is the release code (R8, uncompressed dex, a baseline
profile) signed with the same debug key, so it replaces a debug install
without losing pairing:

```sh
cd android && ./gradlew assembleFast
tools/install-fast.sh <tv-ip>:5555            # armeabi-v7a; checks the signer, sends the .dm
```

It is not debuggable: no `run-as`, no RxPlayer stats line or
vlc-options.txt, no DebugAdvertiser. Go back to the debug APK for those
(the same `install -r`; the signer is the same). Timing a start:
`adb shell am start -S -W -n com.openhamshack.remote/.MainActivity`.

## The standing test fakebox on shack

Real devices on the LAN (the Fire TV first) test against a fakebox kept
running on shack as two systemd user services, so it survives reboots
(linger is on for user):

- `openhamshack-fakebox.service`: `tools/fakebox.py` on 127.0.0.1:18080/18081,
  PIN 1234.
- `openhamshack-fakebox-lan.service`: `tools/lanforward.py` exposes only the
  PIN-gated 18081 on 198.51.100.208:18181. ufw lets in only listed test
  devices (`sudo ufw status | grep 18181`); add a device with
  `sudo ufw allow in on eno1 from <ip> to any port 18181 proto tcp comment "OpenHamShack Remote test fakebox - <device>"`.

Settings are in `~/.config/openhamshack-fakebox/env` (FAKEBOX_FILE, FAKEBOX_CODEC,
FAKEBOX_CORRUPT, FAKEBOX_CUT, FAKEBOX_NOSIGNAL; clips in
`~/build/fakebox-media/`). Change one, then
`systemctl --user restart openhamshack-fakebox`. A restart clears sessions, so
apps need the PIN again to tune. Install on a fresh host with
`tools/systemd/install.sh`.

## Weak signals on a real TV (9 October 2026)

Brian's Fire TV against fakebox looping a 720p25 film (`FAKEBOX_FILE`,
H.264 or `FAKEBOX_CODEC=hevc`, MP2 sound) with `FAKEBOX_CORRUPT` dropping
that share of TS packets. Each cell is 60 s of the debug build's `RxStats`
lines (below). 1500 pictures a minute is the full 25 fps; a "freeze" is the
longest time the displayed-picture count stood still, sampled every 250 ms
(stats arrive in batches, so ~0.75 s is the floor, not a freeze). CPU is
`top` for the app process, of 400 %.

| Codec | Loss | Decoder | Before: pictures/min, longest freeze | After: pictures/min, longest freeze | After: audio buffers/min, longest gap | After: CPU |
|---|---|---|---|---|---|---|
| H.264 | 0 % | hardware | 1471, 0.8 s | 1475, 0.8 s | 2491, 0.8 s | 23 % |
| H.264 | 0 % | software | 1475, 0.8 s | 1492, 0.5 s | 2498, 0.5 s | 39 % |
| H.264 | 0.5 % | hardware | 901, 1.8 s | 1462, 0.8 s | 2467, 0.8 s | 21 % |
| H.264 | 0.5 % | software | 867, 1.5 s | 1473, 0.8 s | 2444, 0.8 s | 50 % |
| H.264 | 2 % | hardware | 768, 2.8 s | 1444, 0.8 s | 2334, 0.5 s | 25 % |
| H.264 | 2 % | software | 336, 35.8 s | 1465, 0.8 s | 2282, 0.5 s | 49 % |
| H.264 | 5 % | hardware | 809, 2.8 s | 1365, 1.8 s | 2053, 0.8 s | 20 % |
| H.264 | 5 % | software | 335, 35.8 s | 1442, 0.8 s | 2033, 0.8 s | 86 % |
| H.265 | 0 % | hardware | 1483, 0.8 s | 1484, 0.8 s | 2498, 0.8 s | 21 % |
| H.265 | 0 % | software | 1504, 0.8 s | 1502, 0.8 s | 2499, 0.8 s | 104 % |
| H.265 | 0.5 % | hardware | 865, 5.5 s | 1477, 0.8 s | 2427, 0.8 s | 21 % |
| H.265 | 0.5 % | software | 837, 4.8 s | 1496, 0.8 s | 2463, 0.8 s | 83 % |
| H.265 | 2 % | hardware | 810, 10.5 s | 1446, 0.8 s | 2297, 1.0 s | 19 % |
| H.265 | 2 % | software | 890, 6.5 s | 1467, 0.5 s | 2296, 0.8 s | 69 % |
| H.265 | 5 % | hardware | 0, 70.1 s | 1412, 0.8 s | 2075, 1.0 s | 26 % |
| H.265 | 5 % | software | 0, 70.1 s | 1413, 0.8 s | 2078, 0.8 s | 53 % |

"Before" is the player as merged on 8 October (the 5 % rows: the new build
with `--ts-cc-check` and `:codec=all` put back through the options file).
Before, sound also thinned to 1600-2200 buffers a minute at 0.5-2 % loss
and 700-900 at 5 %; the drop that remains after (to ~2050 at 5 %) is the
MP2 frames actually lost. Why the fix is what it is: RxPlayer.kt and
REMOTE-CONTRACT.md §6.

Screenshots (`adb exec-out screencap -p`, strip up to `\x89PNG`) a few
hundred ms apart: both decoders now keep the clock and the film moving,
but the TV's hardware decoders paint a damaged frame white or grey until
the next I-frame, while libavcodec keeps the film recognisable under the
blocks - hence software by default on a TV.

On the phone emulator (`ohsr`, H.265 2 %): before 282 pictures a minute
with a 51 s freeze, after 1468; clean-stream lag unchanged within noise.

### Trying player options without rebuilding (debug builds)

```sh
printf '%s\n' --no-ts-cc-check ':codec=avcodec,all' 'hw=0' > opts.txt
adb push opts.txt /sdcard/Android/data/com.openhamshack.remote/files/vlc-options.txt
adb logcat -s RxStats:I
```

`--x` lines go to the libVLC instance (rebuilt), `:x` lines to the media,
`hw=` overrides the setting; read every 2 s, reopening the stream on a
change; added after the built-ins, so they win. Delete the file to go
back. `RxStats` logs every 10 s: pictures decoded/displayed/lost, audio
buffers played/lost, the longest video and audio gaps, and `lag` (wall
time since opening minus stream time - compare it between option sets,
it means nothing alone). Read logcat as a stream, not `-d`: a TV's
256 KB buffer turns over in seconds when a decoder is complaining.
