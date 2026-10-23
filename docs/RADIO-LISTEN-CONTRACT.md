# Radio: wide FM, listening bands and DAB (what Remote needs from the server)

Brian, 23/10/2026: "add wide fm support, 23cm band, a way to change the step
down … airband, broadcast FM band, DAB and other known areas such as PMR 446,
CB etc." He chose: the Remote's Radio screen is the one overhauled; Radio's
server side is the Radio session's; the listening areas sit **mixed into the
band list** in frequency order; DAB is **a station list and play**.

Remote builds against fakeshack fakes of everything below
(`FAKESHACK_RADIO_LISTEN=1`) and switches over when the server has it. Any
field may be renamed by the server side; tell the Remote session and the fake
follows. Nothing here transmits.

## 1. A wide FM mode: `wfm`

- `"wfm"` in Radio's mode list (`state.MODES`) and therefore in `api/state`'s
  mode choices and `api/set {"changes":{"mode":"wfm"}}`.
- Demodulation: a ~180 kHz channel (Carson for broadcast ±75 kHz deviation
  plus 15 kHz audio), 50 µs de-emphasis (Europe; 75 µs as a setting is a nice
  extra), mono L+R first. Stereo and RDS are later, not this pass.
- The IQ rate must cover the channel: on the RSPdx and Pluto that is already
  true; a receiver that cannot (HL2 at 48 kHz) should refuse `wfm` with an
  operator sentence, as other refusals do.
- Receive only. The licence check already refuses every frequency the class
  does not hold; `wfm` should additionally be refused for transmit everywhere.
- The filter width reported for `wfm` in state should be the real one, so
  Remote's passband overlay draws it.

## 2. Listening areas (receive-only bands)

Remote keeps its own table of these (below) for the band list, since they are
not amateur allocations and the band-plan dataset will not carry them. What
Remote needs from the server is only that **receive works there**: a dial
anywhere inside the receiver's `rxRange` is accepted and demodulated in the
mode sent, and transmit is refused with the licence sentence it already gives.

Please confirm (or tell us what refuses) a dial set in each of these on the
RSPdx:

| Area | Range | Mode | Step |
| --- | --- | --- | --- |
| LW broadcast | 148.5–283.5 kHz | am | 9 kHz |
| MW broadcast | 526.5–1606.5 kHz | am | 9 kHz |
| CB (CEPT) | 26.965–27.405 MHz | fm | 10 kHz |
| CB (UK 27/81) | 27.60125–27.99125 MHz | fm | 10 kHz |
| Broadcast FM | 87.5–108 MHz | wfm | 100 kHz |
| Airband | 118–137 MHz | am | 8.33 kHz |
| Marine VHF | 156–162.025 MHz | fm | 25 kHz |
| DAB (Band III) | 174–240 MHz | dab | block |
| LPD433 (inside UK 70 cm: listed, not RX-tagged) | 433.075–434.775 MHz | fm | 25 kHz |
| PMR446 | 446.00625–446.19375 MHz | fm | 12.5 kHz |

Two server asks follow from the table:

- **8.33 kHz is not a whole number of Hz** (8333.33…). Remote sends the
  channel's exact dial in whole Hz (e.g. 118.008333 → 118 008 333); the server
  must not round it to a 10 Hz or 1 kHz grid.
- **AM's filter for airband**: AM's default width should be wide enough for
  airband voice (±3–4 kHz); if AM is narrower today, say what it is.

## 3. DAB: the station list and play

Through the decoders (SERVER-API, decoders M1) with a decoder `dab`:

- Catalogue entry `{"id":"dab", "needs":["faad2"], ...}`, refused
  `decoder_unavailable` (409, with `needs`) when faad2 is missing, exactly as
  the catalogue already describes for `dabplus`. (If the id stays `dabplus`,
  Remote follows.)
- `POST api/decoders/sessions {"decoder":"dab","rfHz":<block centre>,
  "params":{"block":"11D"}}` — the block's centre is the dial; the session's
  span must hold 1.536 MHz, else `outside_span`.
- The session's feed (the `?since=` poll and the `decoder` events) reports the
  ensemble and its services once acquired:

  ```json
  {"sync": true, "ensemble": {"id": "C1CE", "label": "BBC National DAB"},
   "services": [{"sid": "C221", "label": "BBC Radio 4", "bitrate": 128,
                 "kind": "dab+"}, ...],
   "playing": "C221"}
  ```

  `sync:false` with no services while it is still acquiring, and a sentence
  (`why`) when a block has nothing.
- `POST api/decoders/sessions/{id} {"params":{"service":"C221"}}` chooses what
  plays. The service's audio comes out on Radio's **ordinary audio stream**, so
  every client that hears Radio hears the station with no new audio path.
- A scan is Remote stepping through the blocks (5A–13F) one session at a time
  and keeping the ones that sync; no server scan is needed, but one would be
  welcome later.

Blocks and centres are the ETSI EN 300 401 Band III table; Remote carries it.

## 4. What Remote changes regardless (no server work)

- 23 cm (and 13 cm when the receiver reaches it) get band buttons.
- The step goes down as well as up: − and + around the step on phone and
  tablet, left/right on the TV's step control.
- The listening areas join the band list, tagged RX, filtered by `rxRange`
  as the amateur bands already are.

## 5. Local frequencies (what Remote builds against; the server's names may change)

Brian approved a "Local frequencies" screen (TvPolishLocal, 23/10/2026):
airports, area control, marine, PMR · CB · LPD, HF beacons and the
operator's own, nearest first, OK tuning with the mode and the step. Radio's
API for it is not out yet; Remote builds against this shape behind
`FAKESHACK_RADIO_LISTEN=1` and parses defensively (a missing field leaves
its part of the screen out, an unknown category is still listed):

- `GET api/local` (View level):

  ```json
  {"radiusKm": 100, "locator": "IO85", "attributions": ["Airports: OurAirports (public domain)", "..."],
   "entries": [{"id": "e1", "category": "airport", "name": "Edinburgh Tower", "sub": "EGPH",
                "freqHz": 118700000, "mode": "am", "step": 8333, "distanceKm": 12.4, "bearing": 285,
                "source": "OurAirports"}, ...],
   "ncdxf": {"bands": [{"band": "20m", "freqHz": 14100000}, ...],
             "schedule": ["4U1UN", "VE8AT", "W6WX", "KH6RS", "ZL6B", "VK6RBP", "JA2IGY", "RR9O", "VR2B",
                          "4S7B", "ZS6DN", "5Z4B", "4X6TU", "OH2B", "CS3B", "LU4AA", "OA4B", "YV5B"],
             "locations": {"4U1UN": "New York", ...},
             "now": [{"band": "20m", "freqHz": 14100000, "call": "4U1UN"}, ...]}}
  ```

  Categories: `airport`, `area-control`, `marine`, `pmr` (PMR446, CB and
  LPD channels), `beacon`, `mine`. `step` is optional (the step OK sets);
  `mode` is the server's mode name. Entries come nearest first; Remote sorts
  again by `distanceKm` (none last) so the order holds whatever the server
  does. The NCDXF beacons transmit in a fixed 3-minute cycle of 10 s slots
  (beacon *i* is on band *b* in slot *i + b* mod 18), so Remote works out who
  is on now from `schedule` and the clock when `now` is stale or absent.
- `POST api/local/mine {"name", "freqHz", "mode", "step"?}` adds one of the
  operator's own (Control level); `POST api/local/mine/{id}` with the same
  fields changes it; `DELETE api/local/mine/{id}` removes it. Each answers
  the entry (or `{"deleted": true}`); a refusal is the usual
  `{"error", "code"}` with 403 `level_too_low` below Control.
