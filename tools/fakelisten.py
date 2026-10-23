"""Radio's listening bands, wide FM and DAB (RADIO-LISTEN-CONTRACT.md), for
fakeshack to lay over the simulator, which has none of them yet: with this,
Remote's listening areas, its WFM mode and its DAB station list can be
built and tested before the server side lands.

What it makes Radio look like (FAKESHACK_RADIO_LISTEN=1):

  * a receiver like the RSPdx on ohs-box: api/status carries
    `rxRange: [1000, 2000000000]` (unless FAKESHACK_RADIO_RXRANGE says
    otherwise), and api/spec's dial maximum is 2 GHz. The simulator's own
    floor (100 kHz) stays: nothing Remote's areas need is below it (LW
    starts at 148.5 kHz), and a dial under it is refused in the server's
    own words, as a real receiver's limit would be;
  * `wfm` among the modes (api/spec's mode choices). The simulator has no
    wide FM, so a set to `wfm` goes to it as `fm`, and while it is in force
    every `mode: "fm"` the server reports (GET api/state, a set's
    `changed`, the events socket's state messages) is reported as `wfm`.
    Any other mode set ends it. The audio is the simulator's FM. In it the
    state says what the server's does (openhamshack-nb SERVER-API, "Wide
    FM"): `rxFilterLo`/`rxFilterHi` -90000/90000, `txBlocked` the wfm
    sentence and `modes.wfm` {decode: true, encode: false}. A server that
    has wfm itself (it is in its spec or its state's `modes`, openhamshack-nb
    from d76e533) is left alone: its own wfm is passed through untouched.
    FAKESHACK_RADIO_LISTEN_WFM=missing makes the receiver one too slow for
    it, as an HL2 is: `modes.wfm` is {decode: false, encode: false,
    missing: <sentence>} and a set to wfm is 400 with that sentence;
  * FAKESHACK_RADIO_LISTEN_RXONLY=1: a receiver that cannot transmit, as
    the RSPdx: `deviceStatus.tx` is "receive only" and `txBlocked` "This
    radio can only receive" (wfm's own sentence in wfm);
  * a `dab` decoder as Radio's own (openhamshack-nb SERVER-API "DAB",
    dbb3e44): a session (`{"decoder": "dab", "freqHz": <block centre>, and/or
    "params": {"block": "11D", "service": "C221"}}`) holds the receiver
    while it runs - one at a time, a second is 409 receiver_busy - and
    fakeshack tunes the simulator to the block's centre, says so in state as
    `rxOwner` {by, session, block, freqHz, prior, text}, refuses api/set of
    the dial or mode with 409 receiver_busy and the sentence, and on DELETE
    puts the dial and mode back. The session is `panel: "meta"`,
    `holdsReceiver: true`, with `meta`, the feed: `sync` at once false and
    about two seconds later true, then `complete`, a made-up ensemble
    {id, label, shortLabel} and five to eight services {sid, label,
    shortLabel, bitrate, kind, why, whyText, needs}, `playing`, `why`/
    `whyText`, `audio` {codec}, `block`, and `dls` (the playing station's
    programme text). The dab+ services carry needs_codec:faad2 with its
    sentence, as on a controller without faad2; the catalogue's dab entry
    is `available` and `partial` with `missing` ["faad2"] and `missingText`
    (FAKESHACK_RADIO_LISTEN_DAB=full: faad2 present, everything plays).
    Block 5A never syncs (no_signal). A start with a block that does not
    exist is 400 bad_params with a sentence. api/status carries `hostLibs`
    (FAKESHACK_RADIO_LISTEN_HOSTLIBS=old: an older controller, its why).
  * a calibrated S-meter (Radio's server-side calibration, 23/10/2026): each
    `meter` item on the events socket gains `dbm` (the simulator's `rx`
    less 50.4 dB, a made-up calibration) and `s`, by the IARU rule - S9 is
    -93 dBm above 30 MHz and -73 dBm below, 6 dB an S-unit, "S9+N" (dB)
    above S9, S0 at the bottom - judged at the dial the server last
    reported. With FAKESHACK_RADIO_LISTEN_SMETER=uncalibrated it gains
    only `why` (a code, no_calibration) and `whyText` (the sentence) instead,
    to see the uncalibrated path; calibrated, `why` is null and `whyText` "",
    as openhamshack-nb's final shape. api/smeter/calibrate takes a
    calibration as the server does (POST {"dbm"}: the last reading is that
    level, for the dial's side of 30 MHz; DELETE clears it; GET lists them),
    and refuses 409 cannot_calibrate with a sentence before any reading.

  * local frequencies as Radio has them (openhamshack-nb SERVER-API "Local
    frequencies", fb64699): GET api/local-frequencies?radiusKm=&category=
    answers a made-up list near Edinburgh (IO85) in Radio's shape and order,
    the attributions as objects, and `ncdxf` (who is on each beacon frequency
    now, `at`, `nextChangeS`, the schedule, the credit); api/local-frequencies/
    mine adds (POST), changes (PUT) and removes (DELETE) the operator's own,
    refusing any other field 400 bad_field; refresh is 403 not_admin; api/set
    takes `localRadiusKm`. The names are placeholders.

The feed's results carry `type: "dab"`, `sync`, `ensemble`, `services` and
`playing`, polled with `?since=` or sent to an events socket that asked
for "decoder", as fakedecoders' sessions are. Field names follow the
contract and fakedecoders (`freqHz` is the server's name for the session
frequency; `rfHz`, the contract's, is taken too): a change on the server
side must be made here as well. Nothing here transmits or decodes.
"""
from __future__ import annotations

import asyncio
import json
import random
import time

RX_RANGE = [1_000.0, 2_000_000_000.0]
#: The made-up calibration: dBm at the antenna from the simulator's dBFS.
DBM_OFFSET = -50.4
UNCALIBRATED = "This band has not been calibrated yet: tune to a signal of known strength and calibrate (Controls › S-meter)."
NO_READING = "There is no reading to calibrate against yet."
SYNC_AFTER = 2.0
#: Blocks with nothing on them, to see the "nothing here" sentence.
EMPTY = {"5A"}
FAAD2 = "DAB+ stations need faad2: ask your controller's administrator to install it (Controller › Optional libraries)."
DABPLUS_WHY = ("Needs faad2: ask your controller's administrator to install it (Controller › Optional libraries) "
               "to hear DAB+ stations.")
OLD_CONTROLLER = "Update the controller to use SDRplay radios and DAB+"
NO_SIGNAL = "No DAB signal on this block."
BUSY = "The receiver is playing DAB from block {block}: stop the DAB station to tune or change mode"
#: The api/set keys a held receiver refuses (SERVER-API "DAB").
HELD_KEYS = ("vfo", "mode", "rit", "ritOn", "rptReverse", "rptShift")
#: Band III, ETSI EN 300 401, kHz: the blocks the fake accepts.
BLOCKS = {"5A": 174928, "5B": 176640, "5C": 178352, "5D": 180064, "6A": 181936, "6B": 183648, "6C": 185360, "6D": 187072,
          "7A": 188928, "7B": 190640, "7C": 192352, "7D": 194064, "8A": 195936, "8B": 197648, "8C": 199360, "8D": 201072,
          "9A": 202928, "9B": 204640, "9C": 206352, "9D": 208064, "10A": 209936, "10N": 210096, "10B": 211648,
          "10C": 213360, "10D": 215072, "11A": 216928, "11N": 217088, "11B": 218640, "11C": 220352, "11D": 222064,
          "12A": 223936, "12N": 224096, "12B": 225648, "12C": 227360, "12D": 229072, "13A": 230784, "13B": 232496,
          "13C": 234208, "13D": 235776, "13E": 237488, "13F": 239200}
#: The server's sentences (openhamshack-nb SERVER-API "Wide FM", ohsradio/radio.py).
WFM_TX = "WFM is for listening only: nothing is ever transmitted in it"
WFM_MISSING = "Wide FM needs the receiver to run at 240 kS/s or more, and the hl2 runs at 48 kS/s"
RX_ONLY = "This radio can only receive"
WFM_HALF = 90_000.0

DAB = {
    "id": "dab", "family": "broadcast", "inputs": ["iq"], "rateHz": 2048000.0, "bandwidthHz": 1536000.0,
    "outputs": ["meta"], "panel": "meta", "params": [{"key": "block", "type": "text"}, {"key": "service", "type": "text"}],
    "needs": [], "engine": "fake", "available": True, "why": None, "whyText": "", "version": "fake", "fixed": True,
    "partial": False, "missing": [], "missingText": "",
}

STATIONS = ["Radio Wyvern", "Severn Sound", "Hilltop Jazz", "Kingfisher FM", "Northern Lights", "Canal Street Radio",
            "Morning Talk", "Classic Gold Fake", "Signal 1", "The Beacon", "Moorland Country", "Tide Radio"]


def _services(block: str, faad2: bool = False) -> tuple[dict, list[dict]]:
    """The same made-up ensemble every time for a block: 5-8 services, about half dab+ (silent without faad2)."""
    rnd = random.Random(block)
    n = rnd.randint(5, 8)
    names = rnd.sample(STATIONS, n)
    out = []
    for i, name in enumerate(names):
        plus = i % 2 == 1
        s = {"sid": f"{0xC220 + i:04X}", "label": name, "shortLabel": name[:8],
             "bitrate": rnd.choice([64, 80, 96]) if plus else rnd.choice([128, 160, 192]),
             "kind": "dab+" if plus else "dab", "why": None, "whyText": "", "needs": []}
        if plus and not faad2:
            s.update(why="needs_codec:faad2", whyText=DABPLUS_WHY, needs=["faad2"])
        out.append(s)
    ens = {"id": f"C{rnd.randint(0x100, 0xFFF):03X}", "label": f"Fake Mux {block}", "shortLabel": f"Mux {block}"}
    return ens, out


def s_units(dbm: float, dial_hz: float) -> str:
    """IARU Region 1's S-meter: S9 is -73 dBm below 30 MHz and -93 dBm above,
    6 dB an S-unit; above S9 the excess in whole dB ("S9+12")."""
    s9 = -93.0 if dial_hz >= 30e6 else -73.0
    if dbm > s9 + 0.5:
        return f"S9+{round(dbm - s9)}"
    return f"S{max(0, min(9, 9 + round((dbm - s9) / 6)))}"


class Session:
    def __init__(self, sid: str, freq: float, block: str, service: str | None, faad2: bool):
        self.id, self.freq, self.block = sid, freq, block
        self.state = "starting"
        self.since = time.time()
        self.rev = 0
        self.results: list[dict] = []
        self.ensemble, self.services = _services(block, faad2)
        self.playing: str | None = service or None
        self.synced_at: float | None = None

    def meta(self) -> dict:
        synced = self.state == "synced"
        svc = next((x for x in self.services if x["sid"] == self.playing), None) if synced else None
        silent = svc is not None and bool(svc.get("why"))
        m = {"sync": synced, "complete": synced and self.synced_at is not None and time.time() - self.synced_at > 0.8,
             "ensemble": self.ensemble if synced else None, "services": self.services if synced else [],
             "playing": self.playing, "why": None, "whyText": "", "block": self.block,
             "audio": {"codec": ("he-aac" if svc["kind"] == "dab+" else "mp2")} if svc and not silent else None,
             "dls": f"{svc['label']} - now: the fake's programme text" if svc and not silent else None}
        if self.state == "nothing":
            m.update(why="no_signal", whyText=NO_SIGNAL)
        elif silent:
            m.update(why=svc["why"], whyText=svc["whyText"])
        return m

    def view(self) -> dict:
        return {"id": self.id, "decoder": "dab", "freqHz": self.freq, "params": {"block": self.block, "service": self.playing or ""},
                "state": self.state, "why": None, "whyText": "", "since": self.since, "rev": self.rev, "panel": "meta",
                "holdsReceiver": True, "block": self.block, "meta": self.meta()}

    def feed(self) -> dict:
        self.rev += 1
        r = dict(self.meta(), kind="decoder", session=self.id, rev=self.rev, t=round(time.time(), 2), type="meta")
        self.results.append(r)
        return r


#: Made-up local frequencies near IO85 (placeholders: real data comes from the server's sources), in Radio's
#: shape (openhamshack-nb SERVER-API "Local frequencies", fb64699): category, name, sub, freqHz, mode, step,
#: distanceKm, bearingDeg, source. The channel tables carry no distance.
LOCAL = [
    ("airband", "Edinburgh Airport", "TWR Edinburgh Tower", 118_700_000, "am", 8333, 11.8, 284, "ourairports"),
    ("airband", "Edinburgh Airport", "APP Edinburgh Approach", 121_200_000, "am", 8333, 11.8, 284, "ourairports"),
    ("airband", "Edinburgh Airport", "ATIS", 131_355_000, "am", 8333, 11.8, 284, "ourairports"),
    ("airband", "Glasgow Airport", "TWR Glasgow Tower", 118_800_000, "am", 8333, 64.0, 262, "ourairports"),
    ("marine", "Forth Coastguard", "Channel 16 · distress, safety and calling", 156_800_000, "fm", 25000, 9.5, 20, "hmcg"),
    ("marine", "Leith Port", "Channel 12 · port operations", 156_600_000, "fm", 25000, 4.2, 15, "osm-marine"),
    ("marine", "Marine Ch 16", "distress, safety and calling", 156_800_000, "fm", 25000, None, None, "itu-marine"),
    ("pmr", "PMR446 Ch 1", "licence-free", 446_006_250, "fm", 12500, None, None, "channels"),
    ("cb", "CB Ch 19", "CEPT · the calling channel", 27_185_000, "fm", 10000, None, None, "channels"),
    ("lpd", "LPD433 Ch 1", "licence-free", 433_075_000, "fm", 25000, None, None, "channels"),
]
#: The order Radio lists categories in.
CATEGORIES = ["airband", "enroute", "marine", "pmr", "cb", "lpd", "beacon", "mine"]
NCDXF = [("4U1UN", "United Nations, New York"), ("VE8AT", "Eureka, Nunavut"), ("W6WX", "Mt Umunhum, California"),
         ("KH6RS", "Maui, Hawaii"), ("ZL6B", "Masterton, New Zealand"), ("VK6RBP", "Rolystone, Australia"),
         ("JA2IGY", "Mt Asama, Japan"), ("RR9O", "Novosibirsk, Russia"), ("VR2B", "Hong Kong"),
         ("4S7B", "Colombo, Sri Lanka"), ("ZS6DN", "Pretoria, South Africa"), ("5Z4B", "Kariobangi, Kenya"),
         ("4X6TU", "Tel Aviv, Israel"), ("OH2B", "Lohja, Finland"), ("CS3B", "Madeira"), ("LU4AA", "Buenos Aires, Argentina"),
         ("OA4B", "Lima, Peru"), ("YV5B", "Caracas, Venezuela")]
NCDXF_FREQS = [14_100_000, 18_110_000, 21_150_000, 24_930_000, 28_200_000]
ATTRIBUTIONS = {
    "ourairports": {"source": "OurAirports", "text": "Airfield frequencies from OurAirports (public domain), placeholder entries",
                    "licence": "Public domain", "url": "https://ourairports.com/data/"},
    "hmcg": {"source": "HM Coastguard", "text": "Coastguard working channels, HM Coastguard",
             "licence": "Open Government Licence v3.0", "url": "https://www.nationalarchives.gov.uk/doc/open-government-licence/"},
    "osm-marine": {"source": "OpenStreetMap", "text": "Marine VHF from OpenStreetMap contributors",
                   "licence": "ODbL 1.0", "url": "https://www.openstreetmap.org/copyright"},
    "itu-marine": {"source": "ITU", "text": "Marine channels from ITU Radio Regulations Appendix 18", "licence": "", "url": ""},
    "channels": {"source": "Radio", "text": "PMR446, CB and LPD433 channel plans", "licence": "", "url": ""},
    "ncdxf": {"source": "NCDXF", "text": "NCDXF/IARU International Beacon Project", "licence": "", "url": "https://www.ncdxf.org/beacon/"},
}
NO_LOCATOR = "Set the station's locator in the station profile to list what is near it"
#: The fields "my frequencies" accept (SERVER-API): anything else is 400 bad_field.
MINE_FIELDS = {"name", "freqHz", "mode", "category", "locator", "sub", "notes"}


def _iso(t: float) -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(t))


class Local:
    """The local-frequencies fixture, Radio's shape: the made-up list, the beacons by the clock, the operator's own."""

    def __init__(self, locator: str = "IO85"):
        self.mine: dict[str, dict] = {}
        self.n = 0
        self.radius = 100
        self.locator = locator

    @staticmethod
    def entry(i: int, row) -> dict:
        cat, name, sub, hz, mode, step, km, brg, src = row
        e = {"id": f"{src}:{i}:{hz}", "category": cat, "name": name, "sub": sub, "freqHz": hz, "mode": mode, "step": step, "source": src}
        if km is not None:
            e["distanceKm"], e["bearingDeg"] = km, brg
        return e

    def ncdxf(self, now: float) -> dict:
        slot = int(now % 180 // 10)
        on = {str(f): NCDXF[(slot - k) % 18] for k, f in enumerate(NCDXF_FREQS)}
        return {"now": {f: {"call": c, "location": loc} for f, (c, loc) in on.items()},
                "at": _iso(now), "nextChangeS": round(10 - now % 10, 1), "slotSeconds": 10, "cycleSeconds": 180,
                "frequencies": NCDXF_FREQS, "schedule": [{"slot": i, "call": c, "location": loc} for i, (c, loc) in enumerate(NCDXF)],
                "credit": "NCDXF/IARU International Beacon Project"}

    def view(self, now: float | None = None, radius: float | None = None, category: str | None = None) -> dict:
        now = time.time() if now is None else now
        r = radius or self.radius
        rows = [self.entry(i, x) for i, x in enumerate(LOCAL)]
        if not self.locator:
            rows = [e for e in rows if "distanceKm" not in e]
        rows = [e for e in rows if e.get("distanceKm") is None or e["distanceKm"] <= r]
        b = self.ncdxf(now)
        rows += [{"id": f"ncdxf:{f}", "category": "beacon", "name": f"NCDXF {f / 1e6:.3f}", "sub": b["now"][str(f)]["call"],
                  "freqHz": f, "mode": "cw", "step": 10, "source": "ncdxf"} for f in NCDXF_FREQS]
        rows += list(self.mine.values())
        if category:
            rows = [e for e in rows if e["category"] == category]
        rows.sort(key=lambda e: (CATEGORIES.index(e["category"]), e.get("distanceKm") is None, e.get("distanceKm") or 0))
        out = {"locator": self.locator, "radiusKm": r, "generatedAt": _iso(now),
               "attributions": [ATTRIBUTIONS[s] for s in dict.fromkeys(e["source"] for e in rows) if s in ATTRIBUTIONS],
               "entries": rows, "ncdxf": b}
        if not self.locator:
            out["why"] = NO_LOCATOR
        return out

    @staticmethod
    def check(b: dict, partial: bool = False) -> tuple[str, str] | None:
        """(field, sentence) for the first field Radio would refuse, else None."""
        for k in b:
            if k not in MINE_FIELDS:
                return k, f"{k} is not a field of my frequencies"
        for k, v in b.items():
            if isinstance(v, str) and ("\n" in v or "\r" in v):
                return k, f"{k} must be one line"
        if not partial or "name" in b:
            if not isinstance(b.get("name"), str) or not 1 <= len(b["name"].strip()) <= 60:
                return "name", "Give it a name of 1 to 60 characters"
        if not partial or "freqHz" in b:
            hz = b.get("freqHz")
            if isinstance(hz, bool) or not isinstance(hz, int) or not 10_000 <= hz <= 10_000_000_000:
                return "freqHz", "Give a whole frequency in Hz, 10 kHz to 10 GHz"
        if not partial or "mode" in b:
            if not isinstance(b.get("mode"), str) or not b["mode"]:
                return "mode", "Give the mode to listen in"
        return None

    def answer(self, method: str, path: str, b: dict, query: dict | None = None) -> tuple[int, dict] | None:
        q = query or {}
        if path == "/api/local-frequencies" and method == "GET":
            cat = (q.get("category") or [None])[0]
            if cat and cat not in CATEGORIES:
                return 400, {"error": f"No category {cat!r}", "code": "bad_category"}
            try:
                r = float(q["radiusKm"][0]) if "radiusKm" in q else None
            except ValueError:
                r = -1
            if r is not None and not 1 <= r <= 2000:
                return 400, {"error": "radiusKm runs from 1 to 2000", "code": "bad_radius"}
            return 200, self.view(radius=r, category=cat)
        if path == "/api/local-frequencies/refresh" and method == "POST":
            return 403, {"error": "Only the controller's administrator can refresh the data", "code": "not_admin"}
        if path == "/api/local-frequencies/mine" and method == "POST":
            bad = self.check(b)
            if bad:
                return 400, {"error": bad[1], "code": "bad_field", "field": bad[0]}
            self.n += 1
            e = {"id": f"mine:m{self.n}", "category": "mine", "name": b["name"].strip(), "sub": b.get("sub", ""), "freqHz": b["freqHz"],
                 "mode": b["mode"], "source": "mine", "userCategory": b.get("category") or "other", "notes": b.get("notes", ""),
                 "locator": b.get("locator", "")}
            self.mine[e["id"]] = e
            return 201, e
        if path.startswith("/api/local-frequencies/mine/"):
            mid = path[len("/api/local-frequencies/mine/"):]
            if mid not in self.mine:
                return 404, {"error": "There is no such frequency of yours", "code": "unknown_entry"}
            if method == "DELETE":
                del self.mine[mid]
                return 200, {"deleted": mid}
            if method == "PUT":
                bad = self.check(b, partial=True)
                if bad:
                    return 400, {"error": bad[1], "code": "bad_field", "field": bad[0]}
                e = self.mine[mid]
                for k, v in b.items():
                    e["userCategory" if k == "category" else k] = v.strip() if isinstance(v, str) else v
                return 200, e
        return None


class Listen:
    def __init__(self, dab_missing: bool = False, rx_range: list[float] | None = None, calibrated: bool = True,
                 wfm_missing: bool = False, rx_only: bool = False, faad2: bool = False, old_controller: bool = False):
        self.wfm = False
        #: The server has wfm of its own: pass it through rather than fake it.
        self.native = False
        self.wfm_missing = wfm_missing
        self.rx_only = rx_only
        self.calibrated = calibrated
        self.dial = 145_500_000.0
        self.rx_range = rx_range or RX_RANGE
        self.dab_missing = dab_missing
        self.sessions: dict[str, Session] = {}
        self.listeners: set[asyncio.Queue] = set()
        self.n = 0
        self.local = Local()
        #: The DAB session holding the receiver: {session, block, freqHz, prior}; None while the operator has it.
        self.owner: dict | None = None
        self.faad2 = faad2
        self.old_controller = old_controller
        #: State messages for events sockets that asked for state (rxOwner as it changes).
        self.state_listeners: set[asyncio.Queue] = set()
        self.cals: dict[str, dict] = {}
        self.last_rx: float | None = None

    # --- api/spec, api/status, api/state and api/set ------------------------

    def catalogue(self) -> dict:
        if self.faad2:
            return DAB
        return dict(DAB, partial=True, missing=["faad2"], missingText=OLD_CONTROLLER if self.old_controller else FAAD2)

    def host_libs(self) -> dict:
        if self.old_controller:
            return {"supported": False, "root": None, "libraries": {}, "why": OLD_CONTROLLER}
        return {"supported": True, "root": "/opt/hostlibs", "libraries": {"faad2": self.faad2, "sdrplay-api": True}, "why": ""}

    # --- the receiver held by a DAB session (SERVER-API "DAB") ----------------

    def owner_view(self) -> dict:
        """`rxOwner` as state and status carry it: {} while the operator has the receiver."""
        o = self.owner
        if not o:
            return {}
        return {"by": "dab", "session": o["session"], "block": o["block"], "freqHz": o["freqHz"], "prior": o["prior"],
                "text": BUSY.format(block=o["block"])}

    def radius_set(self, changes: dict) -> tuple[dict, dict]:
        """(localRadiusKm taken here, the rest): the simulator has no such setting yet."""
        mine = {k: v for k, v in changes.items() if k == "localRadiusKm"}
        if mine:
            self.local.radius = mine["localRadiusKm"]
        return mine, {k: v for k, v in changes.items() if k != "localRadiusKm"}

    def refuse_set(self, changes: dict) -> tuple[int, dict] | None:
        """409 receiver_busy for an api/set that would tune while a DAB session holds the receiver."""
        if self.owner and any(k in changes for k in HELD_KEYS):
            return 409, {"error": BUSY.format(block=self.owner["block"]), "code": "receiver_busy", "rxOwner": self.owner_view()}
        return None

    def spec(self, obj: dict) -> dict:
        mode = obj.get("mode")
        if isinstance(mode, dict) and isinstance(mode.get("choices"), list):
            if "wfm" in mode["choices"]:
                self.native = True
            else:
                mode["choices"] = mode["choices"] + ["wfm"]
        vfo = obj.get("vfo")
        if isinstance(vfo, dict):
            vfo["max"] = self.rx_range[1]
        return obj

    def status(self, obj: dict) -> dict:
        obj.setdefault("rxRange", self.rx_range)
        obj["rxOwner"] = self.owner_view()
        obj["hostLibs"] = self.host_libs()
        return obj

    def state(self, obj: dict) -> dict:
        """GET api/state's answer (or a state message's changes) as the fixture says it."""
        if "vfo" in obj and "mode" in obj:
            obj["rxOwner"] = self.owner_view()
            obj["hostLibs"] = self.host_libs()
        if isinstance(obj.get("vfo"), (int, float)) and not isinstance(obj.get("vfo"), bool):
            self.dial = float(obj["vfo"])
        if isinstance(obj.get("modes"), dict) and "wfm" in obj["modes"]:
            self.native = True
        faked = self.wfm and not self.native
        if faked and obj.get("mode") == "fm":
            obj["mode"] = "wfm"
        if faked and ("rxFilterLo" in obj or "mode" in obj):
            obj["rxFilterLo"], obj["rxFilterHi"] = -WFM_HALF, WFM_HALF
        if isinstance(obj.get("modes"), dict) or (not self.native and "mode" in obj and "vfo" in obj):
            modes = obj.get("modes") if isinstance(obj.get("modes"), dict) else {}
            if self.wfm_missing:
                modes["wfm"] = {"decode": False, "encode": False, "missing": WFM_MISSING}
            elif "wfm" not in modes:
                modes["wfm"] = {"decode": True, "encode": False, "missing": ""}
            obj["modes"] = modes
        in_wfm = obj.get("mode") == "wfm" or faked
        if "txBlocked" in obj or "mode" in obj:
            if in_wfm and not self.native:
                obj["txBlocked"] = WFM_TX
            elif self.rx_only and not (in_wfm and obj.get("txBlocked")):
                obj["txBlocked"] = RX_ONLY
        if self.rx_only and isinstance(obj.get("deviceStatus"), dict):
            obj["deviceStatus"] = dict(obj["deviceStatus"], tx="receive only")
        return obj

    def refuse(self, changes: dict) -> str | None:
        """The server's sentence for a set it would refuse (wfm on a receiver too slow), else None."""
        return WFM_MISSING if self.wfm_missing and changes.get("mode") == "wfm" else None

    def take(self, changes: dict) -> dict:
        """An api/set's changes as the simulator should see them: wfm goes as fm, unless the server has its own."""
        if "mode" not in changes or self.native:
            return changes
        self.wfm = changes["mode"] == "wfm"
        return dict(changes, mode="fm") if self.wfm else changes

    def changed(self, obj: dict) -> dict:
        ch = obj.get("changed")
        if isinstance(ch, dict):
            self.state(ch)
        return obj

    def frame(self, payload: bytes) -> str | None:
        """A meter item with the S-meter added, or a state message whose mode
        is fm reported as wfm while it is in force; None for anything else
        (spectra, the bulk of the traffic, are not parsed)."""
        if len(payload) > 65536 or not (b'"meter"' in payload or b'"vfo"' in payload or b'"mode"' in payload
                                        or b'"txBlocked"' in payload or b'"modes"' in payload or b'"deviceStatus"' in payload):
            return None
        try:
            o = json.loads(payload)
        except ValueError:
            return None
        if o.get("kind") == "meter":
            return json.dumps(self.meter(o))
        ch = o.get("changes") if o.get("kind") == "state" else None
        if not isinstance(ch, dict):
            return None
        before = json.dumps(ch, sort_keys=True)
        self.state(ch)
        return json.dumps(o) if json.dumps(ch, sort_keys=True) != before else None

    def cal_key(self) -> str:
        """The calibration's key: the server's is the device, antenna and band; the fake's is the dial's side of 30 MHz."""
        return "sim|" + ("vhf" if self.dial >= 30e6 else "hf")

    def meter(self, o: dict) -> dict:
        rx = o.get("rx")
        if isinstance(rx, (int, float)) and not isinstance(rx, bool):
            self.last_rx = float(rx)
        offset = self.cals.get(self.cal_key(), {}).get("offset")
        if offset is None and self.calibrated:
            offset = DBM_OFFSET
        if offset is None:
            return dict(o, dbm=None, s=None, why="no_calibration", whyText=UNCALIBRATED)
        if isinstance(rx, bool) or not isinstance(rx, (int, float)):
            return o
        dbm = round(rx + offset, 1)
        return dict(o, dbm=dbm, s=s_units(dbm, self.dial), why=None, whyText="")

    def calibrate(self, method: str, b: dict) -> tuple[int, dict]:
        """api/smeter/calibrate as SERVER-API has it, against the last reading rather than a 3 s average."""
        if method == "GET":
            return 200, {"calibrations": self.cals}
        if method == "DELETE":
            self.cals.pop(self.cal_key(), None)
            return 200, {"ok": True}
        dbm = b.get("dbm")
        if isinstance(dbm, bool) or not isinstance(dbm, (int, float)) or not -140 <= dbm <= 30:
            return 400, {"error": "dbm must be a number from -140 to +30", "code": "bad_params"}
        if self.last_rx is None:
            return 409, {"error": NO_READING, "code": "cannot_calibrate"}
        entry = {"offset": round(dbm - self.last_rx, 2), "gain": 0.0, "when": round(time.time())}
        self.cals[self.cal_key()] = entry
        return 200, {"calibration": entry}

    # --- the dab decoder ----------------------------------------------------

    def answer(self, method: str, path: str, query: dict, body: dict) -> tuple[int, dict] | None:
        """(status, body) for a dab or local route, or None for anything this fixture does not own."""
        if path.startswith("/api/local-frequencies"):
            return self.local.answer(method, path, body, query)
        if path == "/api/smeter/calibrate" and method in ("GET", "POST", "DELETE"):
            return self.calibrate(method, body)
        if path == "/api/decoders/sessions" and method == "POST" and body.get("decoder") == "dab":
            return self._start(body)
        if path.startswith("/api/decoders/sessions/"):
            sid = path.rstrip("/").rsplit("/", 1)[-1]
            s = self.sessions.get(sid)
            if s is None:
                return None
            if method == "GET":
                try:
                    since = int(query["since"][0]) if "since" in query else None
                except ValueError:
                    return 400, {"error": "since must be a whole number", "code": "bad_params"}
                return 200, dict(s.view(), results=[r for r in s.results if since is None or r["rev"] > since])
            if method == "POST":
                params = body.get("params") if isinstance(body.get("params"), dict) else {}
                if params.get("block") and str(params["block"]).upper() != s.block:
                    return 400, {"error": "A session plays one block: stop it and start one on the other", "code": "bad_params"}
                want = params.get("service")
                if s.state == "synced" and not any(x["sid"] == want for x in s.services):
                    return 400, {"error": f"There is no service {want!r} on block {s.block}", "code": "bad_params"}
                # A DAB+ station without faad2 is chosen all the same and is silent; the feed says why.
                s.playing = want or None
                self._send(s.feed())
                return 200, s.view()
            if method == "DELETE":
                del self.sessions[sid]
                return 200, {"stopped": True}
        return None

    def _start(self, b: dict) -> tuple[int, dict]:
        freq = b.get("freqHz", b.get("rfHz"))
        params = b.get("params") or {}
        if not isinstance(params, dict) or (freq is not None and (isinstance(freq, bool) or not isinstance(freq, (int, float)))):
            return 400, {"error": "Give decoder, freqHz and, if any, params", "code": "bad_params"}
        block = str(params.get("block") or "").upper()
        if block and block not in BLOCKS:
            return 400, {"error": f"There is no DAB block {params.get('block')!r}: Band III runs from 5A to 13F", "code": "bad_params"}
        if freq is not None:
            at = next((k for k, khz in BLOCKS.items() if abs(khz * 1000 - freq) <= 8000), None)
            if at is None or (block and at != block):
                return 400, {"error": "That frequency is not the centre of a DAB block", "code": "bad_params"}
            block = at
        if not block:
            return 400, {"error": "Give the block, or its centre frequency", "code": "bad_params"}
        if self.owner:
            return 409, {"error": f"Another DAB session is playing block {self.owner['block']}: stop it first", "code": "receiver_busy"}
        self.n += 1
        s = Session(f"ds_dab{self.n}", BLOCKS[block] * 1000.0, block, str(params.get("service") or "") or None, self.faad2)
        self.sessions[s.id] = s
        self._send(s.feed())
        return 201, s.view()

    def hold(self, s: "Session", prior: dict):
        """The session has the receiver (fakeshack has tuned the simulator): rxOwner says so."""
        self.owner = {"session": s.id, "block": s.block, "freqHz": s.freq, "prior": prior}
        self._state({"rxOwner": self.owner_view()})

    def give_back(self) -> dict | None:
        """The session stopped: the prior dial and mode, for fakeshack to put back; rxOwner is {} again."""
        o, self.owner = self.owner, None
        self._state({"rxOwner": {}})
        return o["prior"] if o else None

    def _state(self, changes: dict):
        for q in list(self.state_listeners):
            q.put_nowait(json.dumps({"kind": "state", "changes": changes}))

    async def run(self, every: float = 0.25):
        """A session syncs SYNC_AFTER seconds after it starts (or says it found nothing)."""
        while True:
            await asyncio.sleep(every)
            now = time.time()
            for s in list(self.sessions.values()):
                if s.state == "starting" and now - s.since >= SYNC_AFTER:
                    s.state = "nothing" if s.block in EMPTY else "synced"
                    s.synced_at = now
                    self._send(s.feed())
                elif s.state == "synced" and s.synced_at and 0.8 < now - s.synced_at <= 0.8 + every:
                    self._send(s.feed())

    def _send(self, r: dict):
        for q in list(self.listeners):
            q.put_nowait(json.dumps(r))
