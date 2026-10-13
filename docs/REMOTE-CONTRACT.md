# OpenHamShack Remote: how a client uses the box

The box's own description of its API is **`docs/REMOTE-API.md` in the
OpenHamShack repository** (`~/openhamshack` main from 670c469, the first release
after 0.9.16). That document says what the box does. This one says
what a *client* does with it: the rules the Android app follows, the
numbers it uses, and the things learned by running it, so the iPad
(Swift/SwiftUI) build can follow the same rules without rediscovering them.
Nothing here is Android-specific unless it says so.

Where the two documents disagree, the box's document is right about the
box; tell whoever maintains this one.

## 1. The address is the operator's, always

- The operator types `host` or `host:port` (default port **8080**). Accept a
  pasted `http://` and a trailing `/`; refuse `https://` (the box has no
  certificate) rather than downgrading it silently. IPv6 literals go in
  brackets in URLs.
- **Every URL is built from that address**: the API, the receive stream,
  and the SRT destination for the camera. The box reports addresses of its
  own (`status.stream.urls`, `links[].server` in `/api/stream/larix`,
  `network`), but those are what it sees on its interfaces; the phone may be
  coming in over the tailnet, through NAT, or by a name. Never turn one of
  them into a URL.
- Plain HTTP on the LAN and the tailnet. On iOS that means an App Transport
  Security exception (`NSAllowsArbitraryLoads`, or `NSAllowsLocalNetworking`
  plus the tailnet's 100.64/10 range, which ATS does not treat as local -
  test with a tailnet address before choosing the narrower one). iOS 14+
  also needs `NSLocalNetworkUsageDescription` or LAN requests silently fail.

Android: `api/BoxAddress.kt`, tests in `BoxAddressTest.kt` (port the cases).

### Finding boxes: Bonjour

- Browse `_openhamshack._tcp` (DNS-SD, `local.`) while the box list is on
  screen; stop when a box is opened or the app leaves the screen. Resolve
  each, take an **IPv4** address first (a link-local IPv6 would need its
  scope in the URL) and the SRV port; TXT `name`, `version`, `port`.
- Show found boxes beside the saved ones ("On this network"); a saved box
  whose address was found is marked so. Tapping a found box saves it (by
  its resolved address) and opens it. Typing an address stays: mDNS does
  not cross the tailnet or a router, and older boxes get avahi a day late.
- Android: `NsdManager` resolves one service at a time below API 34, so
  resolves are queued (`data/Discovery.kt`). iOS: `NWBrowser` for
  `_openhamshack._tcp`, and `NSBonjourServices` must list `_openhamshack._tcp` in
  Info.plist or browsing silently finds nothing.
- An emulator never sees the host's mDNS (NAT). Debug builds carry
  `DebugAdvertiser`, which publishes a pretend box from the device itself
  so the list can be seen (see TESTING.md).

## 2. The PIN session

- `POST /api/login {"pin"}` sets `ohssession=<token>` (a year). Keep the
  token **per box** and send `Cookie: ohssession=<token>` yourself. Do not
  use a shared cookie store: two boxes reached through one name would share
  a session.
- Store the token, never the PIN. (Android keeps it in app-private
  DataStore with backup disabled; on iOS use the Keychain.)
- Every status a POST answers with now carries `auth` (and `tx.remote`),
  as `GET /api/status` does. Boxes of 0.9.16 left it out of POST answers;
  the client still keeps the last known `auth` when an answer lacks it
  (Android: `Status.authReported`, `BoxSession.adopt`).
- **Sign out** (`POST /api/logout`, then forget the token) ends the session
  at the box; **Forget PIN** only forgets it on the phone. Both are offered.
- **A PIN changed or cleared at the box ends every remote session.** The
  next poll says `authorised: false` (or a POST answers 403/401): drop the
  token, release PTT, go back to view only and say so - *"Signed out by
  the box - the PIN was probably changed there. View only until the PIN is
  entered again."*
- **The login race.** A status poll in flight when the PIN is entered
  answers `authorised: false`. If the client treats "I have a token but the
  box says not authorised" as "the session ended" (it must, to notice a PIN
  cleared at the box), it must judge only a poll *that carried the token it
  now holds*. The Android app threw away every fresh session until this was
  fixed.

## 3. Polling

`GET /api/status` once a second while the app is in the foreground, as the
console's `refresh()` does; nothing while in the background. HTTP timeout
5 s. A failed poll marks the box "not answering" but changes nothing else.

Fields read, by the box's names (everything else is ignored, so a field
added to the box never breaks a client):

| Field | Used for |
|---|---|
| `auth.local`, `.authorised`, `.pinSet` | view-only rule (section 4) |
| `onAir`, `onAirSeconds`, `txError`, `txBlocked`, `txCheck`, `txTimeout` | transmit panel (section 5) |
| `tx.remote` | the lease; its *presence* (even null) says the box has the lease at all |
| `config.callsign`, `frequencyMHz`, `symbolRateKs`, `mode`, `constellation`, `fec`, `gainDb`, `source`, `streamProtocol`, `rx*`, `qo100LnbMHz` | summaries, tuning |
| `rx`, `receivers[]` (`id`, `tuner`, `heard`, `locked`, `label`, `mer`, `marginDb`, `name`, `modcod`, `video`, `frequencyMHz`, `symbolRateKs`, `offsetMHz`) | overlay, tuning |
| `rxView.enlarged` | passed back unchanged on `/api/rx/select` so a phone does not rearrange the box's TV |
| `stream` (`state`, `port`, `latencyMs`, `secretSet`, `video{}`, `audio{}`, `kbps`, `onAir`, `note`, `problem`, `message`) | camera page |
| `version` | shown |

JSON `null` means *unknown*: keep it distinct from 0 (`rx.locked` null is a
network PicoTuner with no status; a null MER is not 0 dB). `stream.video`
and `stream.audio` are objects (`{codec, width, height, fps}`,
`{codec, rate, channels}`), not strings. `rx.band` is the margin's colour,
not a radio band.

Fixtures: `android/app/src/test/resources/*.json` are real answers from
openhamshack.py's Handler. Use them for the iPad's parser tests too.

## 4. View only

The console's rule, which the app mirrors exactly:

- `auth.authorised` false: everything is visible, nothing changes. Controls
  are **dimmed, not disabled**, and a tap says why in the console's words:
  *"View only: enter the PIN to change this."*, or with no PIN set at the
  box *"View only: set a remote PIN on the box (Settings › Security) to
  control it from here."* A disabled control that says nothing was the
  console's old fault and is not repeated.
- A banner while view only: *"View only. Enter the PIN to control this
  OpenHamShack."* / *"View only. Set a remote PIN on the box's touchscreen
  (Settings › Security) to allow control from here."*
- Watching the picture needs no PIN (`/api/rx/stream.ts` is open).
- `auth.local` is the box's own screen (only seen when running against a
  daemon on the same machine).

## 5. Transmitting

The rule throughout: **the safe direction is off.** Every rule below is a
reason to stop; none is a reason to stay on air.

Keying:

- Deliberate: hold the button for **1 s** (a bar fills; letting go early
  does nothing). The console uses two taps within 4 s; a phone lives in a
  pocket. Stopping is **one tap**, whoever keyed, in every state, including
  full screen.
- Refuse to key a box whose status has **no `tx.remote` key at all**: such a
  box (0.9.16 and earlier) accepts `{"remote"}` and ignores it, so there
  would be no dead-man. Say "Update OpenHamShack to transmit from here".
- Refuse (greyed, with the reason) while `txBlocked` is non-empty, view
  only, or not connected.
- `POST /api/ptt {"on": true, "remote": "<client id>"}` with a **30 s**
  timeout (a key-up can take seconds). The client id is random per install
  (`android-<uuid>`), made once, stored; never derived from the device.
- Keyed means `onAir` true **and** `tx.remote.id` is ours. A refusal is HTTP
  200 with `onAir` false and `txError`. On air under some other id (should
  not happen) is unkeyed at once: it would have no dead-man.
- `txCheck` set after a refusal: the next hold sends `"force": true` and the
  button says *"In use — hold to transmit anyway"*.

Holding (Android: `ptt/PttController.kt`, tests `PttControllerTest.kt`):

| Rule | Number |
|---|---|
| Heartbeat `POST /api/ptt/heartbeat {"remote"}` | every 1 s, starting **after** the key-up answer |
| Heartbeat HTTP timeout | 2 s |
| Heartbeat 409 | the box ended it: idle at once, nothing sent |
| Heartbeats failing | give up after **3 s** without a 200: idle, say so, send a key-down (the box's own 5 s lease is the backstop) |
| Status says off air | ignore for 3 s after key-up, then 2 polls in a row = ended |

Stopping (`release`, only if *this app* keyed - backgrounding a phone must
never stop a transmission started at the box):

| Trigger | Android | iPad equivalent |
|---|---|---|
| App leaves the screen | `onStop` | `scenePhase == .background` (not `.inactive`, which Control Centre causes) |
| Screen locked | `ACTION_SCREEN_OFF` broadcast (before `onStop`) | `protectedDataWillBecomeUnavailable`, and the scene going to background |
| Network lost | `ConnectivityManager` default network `onLost` | `NWPathMonitor` path unsatisfied |
| Box closed in the app | session close | same |

Order on stopping: cancel the heartbeat loop first, then send `{"on":
false}` (which also clears the box's `txError`; 3 tries, 400 ms apart), on a task that **outlives the screen
being closed** (Android: an application-scope coroutine). If the operator
lets go while the key-up is still in flight, send the key-down at once *and*
again when the key-up's answer arrives - correct whichever the box sees
last. On iOS, wrap the key-down in `beginBackgroundTask` so it is sent after
the app is backgrounded.

Shown: ON AIR in red with `onAirSeconds` and `txTimeout.secondsLeft`;
while `txTimeout.warning`, a red bar with **Keep transmitting**
(`POST /api/ptt/keep`). `txError` (the box's reason) is shown in preference
to the app's own. Keep the screen on while the box screen is open.

Measured against the harness on the emulator: Home and screen-off unkey
immediately; a force-stopped app and airplane mode are unkeyed by the box's
lease 4.7 s after the last heartbeat, with the box's *"Transmit stopped:
remote controller lost..."* shown when the app returns.

## 5a. Transmit settings

`GET /api/tx/options` (open) gives the choices; `POST /api/tx/tune` (PIN)
sets any of `frequencyMHz`, `symbolRateKs`, `mode`, `constellation`, `fec`,
`gainDb`. The panel:

- Offers only the box's choices: presets; the band's symbol rates
  (`qo100` while the frequency is in `qo100.uplinkMHz`, else
  `terrestrial`) plus the one in use plus a typed one; on QO-100 the band
  plan's channels for the current rate, preferred first; modes; the
  modulations of the mode; the FECs of mode+modulation (8PSK has no 1/4);
  power in steps of -5/-1/+1/+5 within `gainDb.min..max`.
- Sends each change at once, one key, as a console chip does. Typed numbers
  go to the box as typed (only a non-number is caught locally), so the
  box's own sentence is what the operator reads.
- **400 = refused, nothing saved**: shown in a red line, *"Not saved:
  <the box's sentence>"*.
- On air, `txTune.nextKeyUp` lists what waits for the next key-up: *"On
  air: frequency will apply at the next key-up"*; power alone: *"Power
  changed on air"*; and a standing line that power is immediate and the
  rest waits.
- **txBlocked** (licence/band) is shown as a red *"Can't transmit"* block
  with the box's reason both above TRANSMIT and at the top of the settings,
  and the TRANSMIT button is grey and cannot be held to key. While a
  frequency is being typed, the licence's `bands` give a hint first (each
  edge, at 1.35 x the symbol rate, inside one band; no hint when `bands` is
  null) - only a hint, the box's `txBlocked` is the verdict.
- View only: the same panel, dimmed, each tap saying why.

## 6. The received picture

- `GET /api/rx/stream.ts`, with `?rx=N` for the receiver the box has as
  `heard` (receiver 1 without the parameter). Open, no cookie needed. 8
  clients at most (503).
- Player options (libVLC; MobileVLCKit 3.x takes the same strings):
  media `:network-caching=<ms>` (setting, default **250**, 100-1500),
  `:demux=ts`, `:clock-jitter=0`, `:clock-synchro=0`, and a codec list
  that decodes audio with libavcodec - `:codec=avcodec,all` in software,
  `:codec=mediacodec_ndk,mediacodec_jni,avcodec,all` in hardware (on Apple,
  VideoToolbox first instead); instance **`--no-ts-cc-check`**,
  `--avcodec-corrupted` (show damaged frames, do not freeze),
  `--drop-late-frames`, `--skip-frames`, `--quiet` (libavcodec's
  per-macroblock errors off); stats **on** (the watchdog needs them).
  A "hardware decoding" switch: off by default on a TV, on elsewhere.
- **Weak signals (measured on a Fire TV, October 2026):** the picture must
  keep moving, blocky or not, as mpv's does on the box. Two things decide
  it, neither of them the clock:
  - With TS continuity checking on (VLC's default) every lost packet marks
    the next PES as a discontinuity, and VLC 3's H.264/HEVC packetizers
    then drop everything until a clean I-frame: at 2 % loss the picture
    froze for 5-70 s at a time, hardware or software. `--no-ts-cc-check`
    passes the damaged frames to the decoder to conceal. It must be an
    instance option: Android's libVLC ignores `:ts-cc-check=0`.
  - Unchecked, **mpg123** (VLC's first choice for MP2) drifts behind the
    clock on damaged audio and every buffer is dropped as late (10-38 s of
    silence). libavcodec's MP2 decoder resyncs per frame: the sound clicks.
  - Tried, no help: clock-jitter 1000/5000, clock-synchro default,
    ts-trust-pcr off, drop-late-frames/skip-frames/avcodec-hurry-up off.
- **Reopen only when the connection is gone**: an error opening, the box
  closing it (EndReached), or - only while a picture is coming - no bytes
  for 15 s. Never because there is no picture: with nothing received the
  box keeps the connection open with one null packet a second, and the
  picture appears by itself when a signal locks. Backoff 1 s, 2 s, then
  every 3 s; reset after 10 s of playing. The box now sends ten null
  packets a second while idle; the client still does not judge an idle
  stream by bytes (unchanged, and still stable on the emulator). Also reopen when the status poll
  recovers after the box was unreachable (the TCP connection may be dead
  without knowing it).
- Traps found on Android's libVLC 3.7 (check them on VLCKit):
  - `stop()` posts a **Stopped** event that arrives *after* the reopen has
    begun. Taking it as "ended" made a no-signal stream reopen every few
    seconds for ever. Ignore Stopped; only EndReached/EncounteredError.
  - `readBytes` moves in large steps (block reads), so the 188 B/s null
    trickle looks like silence for a minute or more.
  - The Vout event is not repeated after a reopen; the media stats'
    picture counters and `currentVideoTrack` size stay 0 for a network
    stream. "Is there a picture" = a selected video ES (`videoTrack >= 0`).
  - Rotation blanks the picture for 1-2 s until the next key frame; no
    reconnect.
- Overlay, like the box TV's label: `437.000 MHz · 333 kS`, lock line
  (`Locked DVB-S2 · MER 9.4 dB · +6.3 dB margin`, green / amber / grey for
  locked / not / unknown), station · MODCOD · size codec; ON AIR top right;
  player state and reconnect count bottom left (*"No picture: nothing
  received at the box"* when the stream is open but carries nothing).

## 7. Tuning

`POST /api/rx/tune {id, frequencyMHz?, symbolRateKs?, offsetMHz?}` for every
receiver, as the console's receiver window does. Presets (console
`#rxtpresets`): 146.50, 437.00, 1255.00, all offset 0; the console's fourth,
the QO-100 beacon, is on the QO-100 page (section 7a). A typed frequency
picks its LNB offset as `receivers.js` does:

```
offset = f >= 10000 ? (current > 0 ? current : qoLo) : (f <= current ? 0 : current)
qoLo   = config.qo100LnbMHz || 9750
```

Symbol-rate chips by band (console `SR_SETS`): QO-100 (offset > 0)
33 66 125 250 333 500 1000 1500; terrestrial 125 250 333 500 1000 1500 2000
3000 4000; plus the current rate if it is not among them, and a typed one.
A typed frequency also carries a rate when it lands in a band its current
rate is not in (Android `rateForFrequency`): 10491.5 ± 0.25 through the LNB
is always 1500 (a real tuner told 10491.5 at 333 never locks, and nothing
says why); onto the LNB from 2000/3000/4000, the nearest QO-100 rate; off
it from 33/66, the nearest terrestrial one. Otherwise the rate is left.
With more than one receiver: choose which to tune, and "Hear receiver N"
(`/api/rx/select {id, enlarge: <rxView.enlarged as it is>}`). Numbers are
formatted without locale (`437.000`, never `437,000`).

## 7a. QO-100

A page of its own (tablet tab, TV screen), separate from terrestrial tuning.

- `GET /api/qo100?wf=<seq>` (open, like status), **once a second, only while
  the page shows** and the app is in the foreground. Polling is what keeps
  the box's connection to BATC open (it closes 20 s after the last poll).
  **Never connect to BATC from the app**: one connection per box, the box's.
- Read: `spectrum` (461 points, dB above the floor, -3 bottom), `startMHz`/
  `stopMHz` (downlink), `uplinkOffsetMHz`, `signals[]` (`freqMHz`, `lowMHz`,
  `highMHz`, `symbolRateKs` measured class, `tuneSymbolRateKs`, `levelDb`,
  `beacon`, `overPower`), `outOfBand`, `live`, `age`, `error` (the box's
  words), `source`/`sourceUrl` (shown as the credit), `channels`.
  `waterfall`: `seq` (ask for it next), `full` (start again), `maxRows`,
  `rows` base64, one byte a point, dB = -3 + byte x 0.125, oldest first.
  Colours: qo100.js `WF_LUT`.
- **Tapping a signal** (list or graph) = one `rx/tune {id, frequencyMHz:
  freqMHz, symbolRateKs: tuneSymbolRateKs, offsetMHz}` - frequency and rate
  together, so the beacon goes at 1500. `offsetMHz` = the receiver's own if
  it is on QO-100 (offset > 0 **and** frequency >= 10000 - HamTV's
  downconverter is an offset too), else `config.qo100LnbMHz` (9750).
- A tap hits a signal within 0.1 MHz of `lowMHz..highMHz`. On the waterfall
  with nothing there: offer (never tune unasked) round(f x 80)/80 at the
  receiver's rate, or 333 if it is above 1000.
- A signal is "on" a receiver when offset > 0, |f - freqMHz| < 0.02 and the
  rate equals `tuneSymbolRateKs`. The receiver's frequency is a green line.
- View only watches; a tap says why it cannot tune. State line: console's
  words (`Live from BATC's monitor at Goonhilly · N signals`, the box's
  `error`, `No new spectrum for N s`, `Connecting to BATC…`).

Android: `api/Qo100.kt`, `ui/Qo100Tune.kt` (pure; `Qo100TuneTest` against
real answers `qo100-live.json`, `qo100-busy.json`), `ui/Qo100Page.kt`,
`ui/TvQo100.kt`. On a TV the list's focus is keyed by frequency: signals
come and go every second, and a row that vanishes must hand focus on.

## 8. Camera to the box

Order (the box refuses to key the Stream source with nothing connected):

1. `POST /api/config {"source": "stream"}` ("Use the phone camera as the
   box's picture"). If `config.streamProtocol` is not `srt`, offer
   `{"streamProtocol": "srt"}`.
2. `GET /api/stream/larix`: `port`, `latencyMs`, `passphrase` (null without
   a session; `secretSet` says whether one is needed - then let the
   operator type it, stored per box). Boxes of 0.9.16 give the passphrase
   only inside the Larix link (`conn[][srtpass]`); read that as a fallback.
3. Send SRT **caller** to `<operator's host>:<port>`, passphrase, latency;
   no streamid. MPEG-TS, H.264 1280x720 30 fps 2.5 Mb/s (1 s GOP), AAC.
4. Wait for `stream.state == "receiving"` (shown as *"The box is receiving:
   H264 1280×720 · AAC stereo · 255 kb/s"*), then key as usual.

- The camera page holds the device in **landscape**: the encoder follows
  the display, and an upright phone sent 720x1280.
- A dropped SRT connection is retried every 2 s while sending is wanted.
- Leaving the app stops sending and releases the camera; the receive
  player is stopped while the camera page shows (no speaker into the mic).
- Android uses StreamPack 3.2 (Apache-2.0). For iOS, HaishinKit with its
  SRT module (`SRTHaishinKit`, BSD-3-Clause, libsrt inside) is the
  equivalent; check its current licence and SRT caller support before
  committing to it.

Verified on the emulator into the daemon's own ingest ffmpeg: SRT with the
passphrase, `stream.state` "receiving", H.264 1280x720 + AAC, front/back
switch without dropping the connection, Home stops it.

## 9. Words

The app reuses the console's sentences where the console has one (view
only, the time-out bar, "Keep transmitting", "transmit anyway", the
receive labels) so that an operator moving between the box's screen and a
phone reads the same thing. `ui/Format.kt` holds the formatting; its tests
(`FormatTest.kt`) are the reference.

## 10. Questions for the box side

Answered by the box in round 2 (670c469): `auth` in POST answers,
`POST /api/logout`, a changed PIN ending sessions, ten null packets a
second, `txError` cleared by an unkey. Still open:

1. **Lease detection by key presence.** The app refuses to key a box whose
   `status.tx` has no `remote` key. Please keep `tx.remote` present (null)
   in every status for as long as the lease exists.
2. **`stream.video.fps`** was null for a phone's SRT stream in the harness;
   say if it is expected to be filled.
3. **Field names in refusals.** `/api/tx/tune`'s 400 sentences name the
   key ("symbolRateKs must be above zero"); an operator would read
   "Symbol rate must be above zero" more easily.

## 11. Video kinds on an OpenHamShack controller (QO-100 WB, DATV)

A controller (`/.well-known/openhamshack`, openhamshack
`docs/contract/CONTROLLER-CONTRACT.md`) lists its apps; Remote opens one by
its exact `kind`. Every URL is the controller's base (what the operator
typed, port 80 when none) plus the app's relative `base`
(`shack-qo100wb/`); never an address an app reports. The device token goes
as `Authorization: Bearer shackdev_…` on every request, never in a URL.

**QO-100 WB (`qo100wb`)** is the Classic box's API under the app's base
(`~/openhamshack` REMOTE-API 2, "On the OpenHamShack platform"), so the Classic
screens are reused (Android: `BoxSession` with a `PlatformApp`), with these
differences:

- No PIN, no login/logout. `status.auth` carries `platform: true`, `user`,
  `level` (`view`/`control`/`transmit`, the person's level capped by the
  device) and `canTransmit`. `authorised` is control or above. View only
  says the level, not the PIN: *"View only: your level for this app on the
  controller is View…"*.
- Transmitting **and the transmit settings** need the Transmit level (the
  app's own rule: a `POST /api/config` that changes what goes on air needs
  transmit). Below it the button reads *"Needs the Transmit level"* and a
  tap says the level. The lease is unchanged (§5): key with `remote`, 1 s
  heartbeats after the key-up answers, 5 s server lease, unkey on
  background/screen-off/network loss. TVs are capped at Control by the
  controller and have no transmit UI at all.
- A **401** from the app or the controller (`token_revoked`,
  `bad_credentials`) means the token is gone: release PTT, forget the
  token, pair or sign in again. A **403** (`level_too_low`) is shown in the
  controller's words and changes nothing else.
- **No camera:** a controller does not publish the app's SRT ingest port.
- Settings the controller owns answer `400/404 on_platform`; Remote offers
  none of them.

**DATV (`datv`)** is openhamshack-datv `docs/SERVER-API.md`: receive only (no
route keys; `status.receiveOnly` true). Remote polls `GET api/status` once
a second while in front, reads `GET api/bands` once, plays
`api/rx/stream.ts`, and tunes with `POST api/set {"changes": {...}}`
(Control). **A tune always names the band that holds the frequency**
(`{"band", "rxFreq", "rxSr"?}` together): the engine refuses an `rxFreq`
outside its current band, and a band alone moves the dial to that band's
default channel. A frequency in no band is refused in the app before
asking. `POST api/start` / `api/stop` start and stop the receiver.

**The picture: a loopback relay.** libVLC cannot send a header, and the
token never goes in a URL; stream tickets (CONTROLLER-CONTRACT 4.6) are not
built in the controller yet (Dock360 feature/ohs-edition, 09/10/2026), and
anonymous viewing is only on for QO-100 WB and can be switched off by an
admin. So the player opens `http://127.0.0.1:<port>/<128 random bits>/api/rx/stream.ts`,
and the relay (Android `shack/StreamRelay.kt`) requests the same path below
the app's base with the bearer token and copies the bytes untouched. It is
bound to **127.0.0.1 by number** (Android's `getLoopbackAddress()` can be
`::1`; the player was refused). Only paths under the app's `/api/` behind
the secret are served; an upstream refusal reaches the player as its HTTP
status (an open error: the player's backoff applies). On iOS the same
idea fits a local `NWListener`, or AVPlayer/VLCKit with a resource loader.

**Icom (`icom`)** is known and listed, not opened: its screens are
stationflow-tv's `:kind-stationflow`, not yet inside Remote.

## 12. NB and Radio: noise reduction and the decoders

Both from openhamshack-nb's store server (`ohscore/server/app.py`), nothing new
on the server side.

- **Noise reduction:** offered only where `GET api/spec` has a boolean
  `nr`; the state key `nr` is the switch, set with `api/set` (Control). It
  works on what is heard in USB, LSB, CW and AM, and `api/audio/rx` already
  carries the reduced audio. The store API does not say the person's level,
  so the first `403 level_too_low` marks the screen view only and the
  switch is shown disabled (phone) or explains itself (TV).
- **Decoders:** ask the events socket for `events` as well. Each decoder's
  output arrives as `{"kind": "events", "mode", ...}` (ohscore
  `engine._emit_event`); the engine runs the decoder for the current mode
  by itself (no switch: CW decodes in CW, FT8 in FT8).
  - Without `slot`: a character decoder (CW, RTTY, PSK) sending a letter,
    a space or an undecodable code as `[.-.-]`, with `freq` (or `center`)
    and, for CW, `wpm`. Shown as one rolling line (160 characters kept;
    a change of mode starts afresh), hidden while empty.
  - With `slot` (Unix seconds of the slot's start): a whole FT8/FT4/Q65/JS8
    message with `snr`, `dt`, `df` (audio Hz), `text`. Listed as WSJT-X
    does: UTC (24-hour, the locale's pattern), dB, DT, Hz, message; a rule
    between slots; a slot's decodes in frequency order; 200 kept; CQ in
    green, a message naming the station's own callsign (state `callsign`,
    the profile's on a controller; as a word, `<call>`, or the base of
    `CALL/P`, `EA8/CALL`) highlighted. A decoder's failure for a slot
    (`{"error"}`) is not a decode.
  - TV: the list sits beside the scope and never takes focus (the D-pad
    keeps tuning); **Decodes** in the OK row opens the full list to scroll,
    with everything beneath unfocusable. Since 12/10/2026 a decode can be
    answered, but only through Enable TX's arm and confirm (12c): OK on a
    decode in the full list (TV), or a tap on it (phone), arms a reply and
    names it; nothing is sent until it is confirmed.
- **Screen awake:** the NB/Radio screens keep the display on while receive
  audio plays unmuted, and on a phone while it holds the transmitter.

### 12c. Enable TX: automatic transmit in the digital modes

The store server's WSJT-X-style Enable TX (openhamshack-nb `docs/SERVER-API.md`
"Automatic transmit", main f3bbac2): this device arms it and chooses what
goes, and the server keys each over itself through PTT's own path (the
key-up check, the broker, the lease). Offered in FT8, FT4 and Q65 (CQ,
or a reply to a decode, sequenced by the server) and PSK31/63/125, RTTY
and CW (one typed line at a time).

- **Arm and choose:** `POST api/autotx {"on": true, "remote": <client id>,
  "cq": true | "reply": <the decode as api/events sent it: text, slot, snr,
  df, call/grid when present> | "text": <line>}`. Arming again with
  another choice while armed is allowed. A refusal is the server's
  `{"error", "code"}`, shown as it says it, with a line of ours for the
  codes a person needs explained (`level_too_low`: this device may not
  transmit; `armed_elsewhere`; `not_digital`; `no_callsign`).
- **The arm is a lease:** `POST api/ptt/heartbeat {"remote"}` every 1 s
  from the answer on, between overs as well as during them; a 409 means
  the server has disarmed (QSO logged, a refused over, the six-minute
  watchdog, someone at the station): stop at once and show the next
  status's `autoTx.why`. Heartbeats failing for 3 s: give up and send
  `{"on": false}`; the server's own 5 s ends it anyway. Two status polls
  saying `autoTx.armed` false or another `remote`: believe them.
- **Off:** `{"on": false}` on any stop: the operator's, the app going to
  the background, the screen going off, leaving the screen. Never re-armed
  by itself; nothing survives a restart of either side.
- **TV:** the remote's gesture (§14): hold Play/Pause 1 s (or Transmit…
  in the OK row, or OK on a decode) to arm; a red overlay names the
  frequency, the mode and the message; OK or Play/Pause within 5 s starts;
  while on, the overlay says ON AIR / Enable TX and the next over, and any
  key stops it and does nothing else. A text mode asks for the line first.
  **TVs are refused today:** the controller caps a TV's token at Control,
  so `api/autotx` answers 403 `level_too_low` and the screen says so; a
  per-TV opt-in on the controller (Brian, REMOTE-KEYS.md "Decided":
  digital modes only, never voice) is separate work, and this client
  needs no change for it.
- **Phone and tablet** (Transmit level): Call CQ, a tap on a decode, or a
  typed line arms the same red confirm with Start and Cancel; while on,
  Stop. Voice PTT (§5) is unchanged and separate.

Tested only against fakes and the simulator (`EnableTxTest`, the fake
controller): nothing here has keyed a radio.

### 12a. SSTV pictures

`api/events` messages with `"mode": "sstv"` (ohscore/modes/sstv.py):
`state: "start"` (`name`, `fmt` - `martinm1`, `scottie_s1`, `robot36`,
`pd120` - `width`, `height`), one `state: "line"` per line (`line` from 1,
`of`, `row` = base64 of width x 3 RGB bytes; **already RGB in every mode**:
the engine turns Robot's and PD's Y/R-Y/B-Y into RGB with `_yryby_to_rgb`
before sending, so lines are painted as they come), and `state: "done"`
(`path` is on the server; **there is no route to fetch a finished picture**,
so the device keeps what it painted: the current picture and the last 5 of
the session). A line for a picture that did not start on this device is
dropped. The picture is reserved at its full size from the start event (rows not
yet received dark grey) and drawn into the largest rectangle of its own
shape that fits, centred - never stretched to the panel (the Fire TV
showed a 4.7:1 strip growing downward when the shape was left to
Compose's `aspectRatio`). A start mid-picture (a mode guessed without a
VIS header) is a fresh picture like any other. The picture is the SSTV
decoder's panel (12b): docked beside the
waterfall, with the format ("Martin 1") and "Line n of N"; OK on it shows
it full screen with the earlier pictures and the auto-open switch, BACK
closes that. A picture starting reopens a panel closed by hand, unless
auto-open is off. Phone: the picture and the earlier ones in the panel.

### 12b. Modes, bookmarks and decoder panels (as OpenWebRX+)

Nothing new on the server: `api/spec`, `api/set`, `api/memories` and the
events socket as SERVER-API has them.

- **Modes** are the engine's own list (`api/spec` `mode.choices`), shown
  in the picker (below); choosing one is `api/set {"mode"}` (Control). The
  engine runs that mode's decoder by itself.
- **Bookmarks** are the engine's memories (`GET api/memories`: the app's
  band-plan presets - QO-100's for NB, the plan in force for Radio, by the
  station's country - then the operator's own). The frequency key is the
  app's (`dl` NB, `rf` Radio). Never a table in the client: band plans are
  data on the server. Read once when the app opens. Those on the scope's
  span are small ticks along the top edge of the spectrum, and only one is
  named: the one highlighted in the picker, else the one nearest the dial
  if it is within 6% of the span, else none (`ScopeLabels`; no two labels
  ever overlap, `CalmScreenTest`). They are offered in the picker in
  frequency order; with none on the span,
  the six nearest the dial. Choosing one is a single
  `api/set {"vfo", "mode"}` (all or nothing, what `api/recall` does), so
  "FT8 20m" lands on the dial with the FT8 decoder running. The one the
  radio is on (dial within 50 Hz, same mode) is marked.
- **Decoder panels** follow the engine's `mode` state, whoever changed it:
  FT8/FT4/Q65/JS8 the WSJT-X list, CW/RTTY/PSK the text line, SSTV the
  picture, any other mode none. A mode the client has no name for takes
  the panel its own events show (a `slot` is a list, characters a line).
  A decoder starting opens its panel; a voice mode closes it; the
  operator can hide it (it stays hidden until the decoder changes) or
  show it again. One device setting, **Decoders open by themselves**
  (on by default; it took over SSTV's own switch and its value), turns
  the opening off; a panel opened by hand then still follows a change of
  decoder. The tested rules: `DecoderPanelTest`.
- **Passband and a tap on a signal:** the mode's audio window relative to
  the dial is shaded on the scope; a tap on the phone's scope puts the
  tapped signal at the window's centre (dial = signal - centre, to 10 Hz):
  1500 Hz above the dial for FT8/FT4/Q65/JS8, FreeDV and HSmodem, 1900 Hz
  for SSTV, 1000 Hz for PSK and RTTY, the engine's `cwPitch` for CW, the
  middle of `filterLo`-`filterHi` for USB (mirrored for LSB), the dial
  itself for AM and FM. On the real box a FreeDV signal with the dial on
  its middle decoded nothing (09/10/2026). Voice modes read the engine's
  state keys; **the digital modes' windows are a copy of ohscore/modes'
  registry** (`offset_hz`, `passband_hz`), because `api/spec` publishes
  only the modes' names - the engine should publish the registry and the
  copy then goes (`shack/ModeAudio.kt`, `ModeAudioTest`). Bookmarks are
  dials already and are used as they are.
- **TV (kept calm, Brian 10/10/2026: "too cluttered"):** one header line
  (dial, mode, NB's beacon lock as a dot and a word or Radio's band,
  Simulator / Reconnecting / On air elsewhere / Muted only when true, a
  slim meter bar from the engine's `meter` `rx` with no number - dBFS is
  uncalibrated, so never S-units either), the segment as a quiet subtitle
  (the step there for 4 s after it changes), then the waterfall with the
  list or picture docked beside it, or the text line under it - never
  over it. The docked FT8 list is time (once per slot, dimmed), dB and
  message; DT and Hz only full screen; CQ in the accent, a message to this
  station in amber. Focus starts on the waterfall: Left/Right tune by the
  step, Up opens the **picker** over the foot of the waterfall (bookmarks
  row, then modes row; one focus target moving a highlight, `PickerFocus`:
  opens on the bookmark nearest the dial, Down to the modes on the current
  one, Up back, OK chooses, BACK or Down off the modes closes, 8 s idle
  closes), Down to the panel (OK there: full screen), rewind/fast-forward
  (or channel up/down) change the step, OK opens the controls row over the
  foot of the screen (span, step, mute, NR, band, typed frequency, log,
  show/hide decoder, auto-open). Key hints float for 6 s at open, list only
  keys not yet used, and come back for 4 s on each key's first use
  (`KeyHints`). BACK closes the picker, the controls row or a full-screen
  view, and otherwise leaves for the apps from wherever the focus is.
  Everything beneath the picker, the controls row or a full-screen view is
  unfocusable (`LocalTvInert`).
- **Phone/tablet:** the same pruned header; the mode and bookmark chip rows
  folded behind one button ("FT8 · bookmarks and modes") that folds again
  after a choice; one bookmark named on the scope as on the TV; the panel
  under the scope with Hide, and Show decoder and the auto-open switch
  among the controls.

### 12d. Radio: the band plan along the waterfall, and band buttons (13/10/2026)

- **The plan strip** is drawn at the foot of the spectrum on every face, as
  Flex draws its own. Radio's server sends only `segment` (the row under
  the dial) and `planInUse`, so the client reads the plan itself from the
  shared dataset, which it **ships unchanged** in `assets/bandplan`
  (ohs-libs `data/bandplan`, copied by `tools/sync-bandplan.sh`, commit and
  `dataVersion` in `SOURCE`): never a table typed into the app. A band the
  plan in force has no rows for is filled from its region's IARU plan
  (RSGB 2026 has no 6 m), and no `planInUse` means IARU Region 1. Kinds and
  colours are Flex's (`kind_of`, `COLOURS`), copied (`RadioPlanStrip`).
  Should the server ever send its segments, as Flex's module does, the
  client should take them and drop the copy.
- **Band buttons** (phone and tablet, one row, scrolling sideways where it
  does not fit): 160 m to 70 cm, less any band outside `rxRange` when the
  status carries it (`[loHz, hiHz]`, optional; no server sends it yet, and
  `FAKESHACK_RADIO_RXRANGE` makes fakeshack add it). The lit button is the
  server's `band`, else the band the dial is in. A press is one
  `api/set {"vfo", "mode"}` (Control), receive only, to this device's last
  spot on that band of this controller (device preferences, per controller
  id and app), else, by Brian's rules for desktop Radio (13/10/2026): in a
  digital mode (the dataset's `modeClass` `digi`) the server's memory for
  that mode in the band, else its FT8 memory (the server has no FT4
  memories yet, so FT4 lands on FT8's dial); in FM, the plan's FM calling
  channel where it names one (2 m 145.500, 70 cm 433.500, staying in FM);
  otherwise the plan's widest SSB row without FM, USB on its start, LSB
  3 kHz in (LSB below 10 MHz, USB above and on 60 m); a band with no SSB
  row (30 m) goes to FT8. The **same band pressed again** steps FT8, SSB,
  CW (`RadioBands`, `RadioBandsTest`). The server's own `bandStacks` are
  not used: they move with the desktop app's band bar.
- **TV:** the bands as a quiet, unfocusable strip under the header, the
  dial's lit; the channel keys (and Band ◀ ▶ in the controls row) make the
  same jump to the next or previous band (REMOTE-KEYS §4). Until the data
  is read they fall back to the server's pad action `band`.
- **Phone and tablet layout (Radio only; NB keeps §12b's):** Flex's
  arrangement - status chips with Scope, Controls and Back in the header
  row; the frequency large with mode, band and segment under it and the
  meter beside it; the waterfall full width; the band buttons and one row
  of tune, step and mute at the foot. Modes, bookmarks, span, NR, typed
  frequency, log and the decoder panel's switches are in the Scope and
  Controls sheets. A tablet held sideways still splits 50/50 with Band
  Activity while FT8/FT4/Q65/JS8 decode.

## 13. Rig kinds on a controller: Icom and Flex (receive only)

Both open from a controller's app list by their exact `kind` (`icom`,
`flex`), with the same base and bearer-token rules as section 11. Neither
asks for transmit on any device: a TV's token is capped at Control by the
controller, and Remote's clients for these two kinds have no path that
keys, on a phone either.

**Icom (`icom`)** is OpenHamShack Icom's TV API (`icom/docs/TV-API.md`,
icom line of dock360-apps) under the app's base, opened with the kind that
came from stationflow-tv (`android/kind-stationflow`, git subtree with its
history; `:core-candidates` beside it). Remote hands the kind the app's
base as its address and an HTTP client that adds the bearer header to
every request and websocket upgrade (`shack/IcomKind.kt`); everything else
- state over `api/ws`, the acknowledged scope and audio, tuning, the
radio-shaped displays of stationflow-tv `docs/RADIO-DISPLAYS.md` - is the
kind's, unchanged. The kind posts only its receive controls
(`StationFlowClient.CONTROLS`), which the Icom store manifest puts at
Control. It never calls `GET /api/auth`: on a controller the controller
signs people in, and the level is learnt from a refusal. A 401 forgets the
token and pairs again.

**Flex (`flex`)** has no client API of its own: Remote reads what the
module serves its web page (`flex/app/main.py`, `flex/app/wire.py` on the
flex line of dock360-apps):

- `GET api/state` → `{"state", "tables"}`; the websocket `ws` sends
  `hello` (state and tables), `state` (about four a second when something
  changed) and `tables` (the station profile moved the band plan), and
  binary frames, little-endian: 1 sweep (pan id, low/high Hz as float64,
  int16 tenths of a dBm per bin), 2 waterfall row (pan id, low Hz, bin Hz,
  int16 intensity ×128 with no defined zero), 3 audio (channels, rate,
  s16le PCM - the module decodes the radio's Opus, 24 kHz stereo), 4
  levels (each slice's S-meter, tenths of a dBm).
- The worked slice is the radio's active slice if it is one of the
  module's own, else the module's lowest (the module never sets the active
  flag: PROTOCOL.md 7).
- Writes, all Control: `POST api/command {"action", "arguments"}` with only
  `slice.tune` (`slice_id`, `frequency` in MHz), `slice.mode` and
  `pan.frequency` (recentre the display when the dial comes within 12 % of
  an edge, as the page's follow-receiver does); `POST api/band {"band",
  "slice_id"}` (the module restores that band's last frequency and mode).
  `api/ptt*` and `api/transmit` (Transmit) are never called.
- **Receive controls** (Brian, 10/10/2026; flex API.md 5-7, module 0.3.1),
  all Control, none keys anything:
  - `slice.dsp` (`slice_id`, `name`, `enabled`, `level`): only names in the
    slice's `dsp_offered` (a newer one the radio lacks is 409
    `dsp_not_offered`), never a level for `rnn` or `anft` (400). NR is one
    press: in the extras' Receive row on a TV (Menu, its key until
    12/10/2026, now opens the settings, §14), a chip on a phone.
  - `slice.agc` (`mode` off/slow/med/fast, `threshold` 0-100 = AGC-T).
  - RF gain (`value` dB: ATT below 0, preamp above) is a panadapter
    setting (flex API.md 4a). The ladder is the worked slice's
    panadapter's `rf_gain_steps.list` (the radio's own `display pan
    rfgain_info` answer, published from module 0.3.1-beta.2), with the
    radio's current value on it. Only when `rf_gain_steps` is null (the
    radio would not say) or absent (an older module) does Remote use its
    per-model table (FLEX-6400/6400M -8..+32 in 8s, else -10..+30 in 10s).
    Sent as `pan.rf_gain` (`pan` named, `value`) when the panadapter has
    the `rf_gain_steps` key at all, null or not, since the module added
    both together; else as `slice.rf_gain` (`slice_id`, `value`), which a
    newer module also routes to the slice's panadapter.
  - `slice.antenna` with **`rx` only**, from the slice's `ant_list`;
    FlexClient refuses `tx` as it refuses keying actions.
  - A module before 0.3.1-beta.2 applied DSP and AGC to its state at once
    but not RF gain or an antenna (the radio never echoes a client's own
    change; a newer module applies those too), so Remote holds those on
    screen until the state says anything other than what it said when asked.
  - `POST api/decoders {"mode"}` (only the modes `modes` lists, or `off`)
    and `POST api/sstv {"on"}`. Decodes arrive on the socket (`decodes`,
    one per period) into the NB/Radio list; on joining while a decoder
    runs Remote asks `api/decodes?since=`. SSTV arrives as ohscore's events
    with `type: "sstv"` into the NB/Radio painter (late joiners get the
    picture from line 1; `done` with `aborted` keeps nothing); kept
    pictures from `api/sstv/images` and each PNG by its relative `url`
    (absolute or `..` urls ignored).
  - The calling-frequency hint comes from the tables' `spots` (WSJT-X's
    FT8/FT4 dials, the plan's SSTV centres), DIGU for the digital modes,
    LSB/USB below/above 10 MHz for SSTV; none for JS8 or Q65 (no spots).
- Remote never sends the websocket's `display_size`: it resizes the
  radio's panadapter for every client, the operator's browser included.
- The module sends audio to every open socket whether or not it is wanted;
  a muted Remote keeps the stream (instant unmute) and closing the screen
  closes the socket.

Screens (both kinds): calm. The frequency as large as fits, the mode, the
S-meter, the display; on a TV everything else is behind OK (Flex: one
panel of modes, bands and a Receive row whose items each open one page at
the right; a running decoder's panel docks beside the display; Icom: the radio face's soft keys and the
kind's own panels). Flex follows the NB/Radio calm pass: no app/controller
title, the meter in the accent (amber past S9; red only means on the air),
a white dial line, and the key hint shown at open and for 6 s after a step
change. Icom keeps the radio's own face.

## 14. Remote keys: one map for every face

The design and Brian's decisions are openhamshack `docs/REMOTE-KEYS.md`
(accepted 11/10/2026). The map itself is `docs/remote-keys.json` here: the
source of truth that Android's `core/keys/KeyMap.kt` is tested against
line for line (`KeyMapTest`), and the Apple twin will be.

- **One key, one job, on every face.** Radio faces (NB, Radio, Icom, Flex)
  and video faces (WB, DATV) differ only in what the job means:
  Left/Right tune (radio, held runs on) or change channel (video, one per
  press); Up opens Choose; Down moves into the face's controls; OK opens
  quick controls, held types a frequency; Menu opens settings, held shows
  the key legend (also Info, F1); Play/Pause mutes, held arms transmit;
  Rewind/FF change the step (radio) or the band (video); the channel keys
  change band (radio; NB steps its bookmarks) or channel (video). A face
  that lacks a job leaves its key to Compose and shows it as "—" in the
  legend.
- **D-pad, OK and Back reach everything;** Menu, the media and channel keys
  are accelerators and act from anywhere on the main screen. Inside an
  overlay only mute and transmit reach past it.
- **OK, Menu and Play/Pause act on key up** (or on the hold), never on key
  down, so a release cannot land on what a press just opened.
- **Back closes the innermost overlay, then leaves;** while transmit is
  armed or on, Back is one more key that disarms or stops.
- **Transmit (§12c):** hold Play/Pause 1 s to arm, a red overlay names the
  frequency, mode and message, OK or Play/Pause within 5 s starts; any
  other key or the time running out disarms; any key while on stops, and
  does nothing else. Code: `core/keys/` (the rules, pure and tested),
  `core/tv/RemoteKeys.kt` (the glue), `core/tv/KeyLegend.kt`,
  `core/tv/TxOverlay.kt`.
