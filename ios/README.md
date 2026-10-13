# OpenHamShack Remote for iPad and Apple TV

Native remotes for an OpenHamShack DATV box, in Swift and SwiftUI. iPadOS 17+
(it runs on an iPhone too) and tvOS 17+. GPL-3.0. They talk to the box's
own console API, described in OpenHamShack's `docs/REMOTE-API.md`.

| Target | What it does |
|---|---|
| `OpenHamShackRemote` (iPad) | Received picture with a status overlay, receive tuning, a QO-100 tab (BATC's wideband spectrum and waterfall from the box's `/api/qo100`, the signals found, tap one to receive it), transmit settings (`/api/tx/tune`), transmit under the box's remote lease, the iPad's camera sent to the box's Stream source over SRT, the box's picture source |
| `OpenHamShackRemoteTV` (Apple TV) | Boxes found by Bonjour first, then the received picture full screen, overlay on click, up/down through receivers and presets, a QO-100 screen (the spectrum across the top, the signals as rows: up/down, select tunes), and a Tune panel (receiver, band, symbol rate, a typed frequency, or left/right nudges sent on select; PIN entered there). Receive only: no transmit, no camera |

## Layout

- `OpenHamShackKit/`: a Swift package with no UI and no third-party code. It holds the API client,
  the status model, the PTT lease controller, saved boxes and Bonjour. Both apps use it. Its
  tests run on a Mac with `swift test`; you don't need a simulator.
- `OpenHamShackMedia/`: a Swift package with two libraries. `OpenHamShackPlayer` wraps VLCKit to play
  `/api/rx/stream.ts`. `OpenHamShackCamera` uses HaishinKit to send SRT to the box (iPad only).
- `App/`, `TV/`, `Shared/`: the two apps' SwiftUI sources. `Shared/` is compiled into both.
- `Config/`: the Info.plists (App Transport Security, Bonjour, camera and microphone strings).
- `OpenHamShackRemote.xcodeproj`: **generated** by `tools/genproject.py`. Do not edit it in Xcode;
  edit the script and run it again. You can add a source file to `App/`, `TV/` or `Shared/`
  without regenerating.

## Building (on the Mac)

```sh
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
tools/fetch-vlckit.sh /path/to/a/download/cache      # once: VLCKit into OpenHamShackMedia/Vendor/
xcodebuild build -project OpenHamShackRemote.xcodeproj -scheme OpenHamShackRemote \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath <dir> -clonedSourcePackagesDirPath <dir>
xcodebuild build -project OpenHamShackRemote.xcodeproj -scheme OpenHamShackRemoteTV \
  -destination 'generic/platform=tvOS Simulator' -derivedDataPath <dir> -clonedSourcePackagesDirPath <dir>
(cd OpenHamShackKit && swift test)
```

Simulators are arm64 only, so build on an Apple-silicon Mac. To build for a device, add
`-destination 'generic/platform=iOS' DEVELOPMENT_TEAM=<team id> -allowProvisioningUpdates`.
Signing is automatic.

## Testing against a fake box

`tools/fakebox.py` at the repository root runs OpenHamShack's real API handler with the radio
faked. Its remote-facing port needs the PIN 1234. Then:

```sh
(cd OpenHamShackKit && OPENHAMSHACK_FAKEBOX=<host>:<port> swift test)    # the client against the real handler
xcrun simctl launch booted com.openhamshack.remote \
  -openBox <host>:<port> -pin 1234 -panel transmit -action key
```

The launch arguments (`App/DebugLaunch.swift`) work in debug builds only. There are others: `-list YES`
starts at the box list, `-openFound <name>` opens a box Bonjour found, and
`-action txTune -patch frequencyMHz=146.95,fec=2/3` sends a transmit tune. `-panel qo100 -qoTap 10499.25` opens the QO-100 tab and taps the spectrum there once it is in. To have a box
for Bonjour to find, advertise fakebox from the Mac:
`dns-sd -P M0TST _openhamshack._tcp local <port> fakebox.local <ip> name=M0TST version=0.9.17 port=<port>`. `-action camera`
sends the test pattern, which is the only source the simulator has. On the Apple TV, `-openBox`
and `-pin` work the same, `-tune YES` opens the Tune panel, `-qo100 YES` the QO-100 screen, and `-nudge 3 -step 0.25` (add `-select YES`
to send it) stands in for the remote's presses.

## Safety rules in the transmit code

These are in `OpenHamShackKit/PTTController.swift`, and they match the Android app's:

- Off is always the safe direction.
- Keying needs a one-second hold. Stopping takes one tap.
- The app sends a heartbeat every second. If the box hears none for 5 s, it unkeys.
- If heartbeats fail for 3 s, the app gives up and sends a key-down.
- If the app leaves the screen or loses the network, it lets go of the transmitter, but only
  if this app keyed it.
- If a box is too old to have the lease, the app unkeys it at once.
