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
  * a `dab` decoder (contract §3) in api/decoders: a session started on a
    Band III block (`{"decoder": "dab", "freqHz" (or "rfHz"), "params":
    {"block": "11D"}}`) is `starting`, reports `sync: false` with no
    services at once, and about two seconds later `sync: true`, a made-up
    ensemble and five to eight made-up services, a mix of "dab" (MP2,
    playable) and "dab+" (HE-AAC) - the dab+ ones carrying a `why`
    sentence and refused if chosen, as on a server without faad2 (the
    Radio session, 23/10/2026). A block in the fixture's EMPTY set (5A)
    never syncs and says why. `POST .../sessions/{id} {"params":
    {"service": sid}}` sets `playing` and sends a new result. With
    FAKESHACK_RADIO_LISTEN_DAB=missing the decoder is listed but not
    available, and starting it is 409 decoder_unavailable with `needs`
    and the server's sentence, as `dabplus` is today.

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

  * local frequencies (RADIO-LISTEN-CONTRACT §5, Radio's API not out yet):
    GET api/local answers a made-up list near Edinburgh (IO85) - airports,
    area control, marine, PMR/CB/LPD channels - nearest first, with
    attribution lines and the NCDXF beacons (the bands, the schedule, and who
    is on now by the clock); POST api/local/mine adds one of the operator's
    own, POST api/local/mine/{id} changes it, DELETE removes it, all kept in
    memory for as long as fakeshack runs. The names are placeholders.

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
MAX_SESSIONS = 4
#: Blocks with nothing on them, to see the "nothing here" sentence.
EMPTY = {"5A"}
FAAD2 = "Ask your controller's administrator to install faad2 (Controller › Optional libraries)."
DABPLUS_WHY = "DAB+ needs faad2, which this controller does not have yet."
#: The server's sentences (openhamshack-nb SERVER-API "Wide FM", ohsradio/radio.py).
WFM_TX = "WFM is for listening only: nothing is ever transmitted in it"
WFM_MISSING = "Wide FM needs the receiver to run at 240 kS/s or more, and the hl2 runs at 48 kS/s"
RX_ONLY = "This radio can only receive"
WFM_HALF = 90_000.0

DAB = {
    "id": "dab", "family": "broadcast", "inputs": ["iq"], "rateHz": 2048000.0, "bandwidthHz": 1536000.0,
    "outputs": ["meta"], "panel": "meta", "params": [{"key": "block", "type": "text"}, {"key": "service", "type": "text"}],
    "needs": [], "engine": "fake", "available": True, "why": None, "whyText": "", "version": "fake", "fixed": True,
}

STATIONS = ["Radio Wyvern", "Severn Sound", "Hilltop Jazz", "Kingfisher FM", "Northern Lights", "Canal Street Radio",
            "Morning Talk", "Classic Gold Fake", "Signal 1", "The Beacon", "Moorland Country", "Tide Radio"]


def _services(block: str) -> tuple[dict, list[dict]]:
    """The same made-up ensemble every time for a block: 5-8 services, about half dab+."""
    rnd = random.Random(block)
    n = rnd.randint(5, 8)
    names = rnd.sample(STATIONS, n)
    out = []
    for i, name in enumerate(names):
        plus = i % 2 == 1
        s = {"sid": f"{0xC220 + i:04X}", "label": name,
             "bitrate": rnd.choice([64, 80, 96]) if plus else rnd.choice([128, 160, 192]),
             "kind": "dab+" if plus else "dab"}
        if plus:
            s["why"] = DABPLUS_WHY
        out.append(s)
    ens = {"id": f"C{rnd.randint(0x100, 0xFFF):03X}", "label": f"Fake Mux {block}"}
    return ens, out


def s_units(dbm: float, dial_hz: float) -> str:
    """IARU Region 1's S-meter: S9 is -73 dBm below 30 MHz and -93 dBm above,
    6 dB an S-unit; above S9 the excess in whole dB ("S9+12")."""
    s9 = -93.0 if dial_hz >= 30e6 else -73.0
    if dbm > s9 + 0.5:
        return f"S9+{round(dbm - s9)}"
    return f"S{max(0, min(9, 9 + round((dbm - s9) / 6)))}"


class Session:
    def __init__(self, sid: str, freq: float, block: str):
        self.id, self.freq, self.block = sid, freq, block
        self.state = "starting"
        self.since = time.time()
        self.rev = 0
        self.results: list[dict] = []
        self.ensemble, self.services = _services(block)
        self.playing: str | None = None

    def view(self) -> dict:
        return {"id": self.id, "decoder": "dab", "freqHz": self.freq, "params": {"block": self.block},
                "state": self.state, "why": None, "whyText": "", "since": self.since, "rev": self.rev, "panel": "meta"}

    def feed(self) -> dict:
        self.rev += 1
        synced = self.state == "synced"
        r = {"kind": "decoder", "session": self.id, "rev": self.rev, "t": round(time.time(), 2), "type": "dab",
             "sync": synced, "ensemble": self.ensemble if synced else None,
             "services": self.services if synced else [], "playing": self.playing}
        if self.block in EMPTY and self.state == "nothing":
            r["why"] = f"Nothing was found on block {self.block}."
        self.results.append(r)
        return r


#: Made-up local frequencies near IO85 (placeholders: real data comes from the server's sources).
LOCAL = [
    ("airport", "Edinburgh Tower", "EGPH · tower", 118_700_000, "am", 8333, 11.8, 284, "OurAirports"),
    ("airport", "Edinburgh Approach", "EGPH · approach", 121_200_000, "am", 8333, 11.8, 284, "OurAirports"),
    ("airport", "Edinburgh ATIS", "EGPH · ATIS", 131_355_000, "am", 8333, 11.8, 284, "OurAirports"),
    ("airport", "Glasgow Tower", "EGPF · tower", 118_800_000, "am", 8333, 64.0, 262, "OurAirports"),
    ("area-control", "Scottish Control", "Sector 5", 126_300_000, "am", 8333, 48.0, 250, "UK AIP"),
    ("marine", "Forth Coastguard", "Channel 16 · calling and distress", 156_800_000, "fm", 25000, 9.5, 20, "ITU"),
    ("marine", "Leith Port", "Channel 12 · port operations", 156_600_000, "fm", 25000, 4.2, 15, "ITU"),
    ("pmr", "PMR446 channel 1", "Licence-free radios", 446_006_250, "fm", 12500, None, None, "Ofcom"),
    ("pmr", "CB channel 19", "CEPT · the calling channel", 27_185_000, "fm", 10000, None, None, "Ofcom"),
    ("pmr", "LPD433 channel 1", "Licence-free radios", 433_075_000, "fm", 25000, None, None, "Ofcom"),
]
NCDXF_CALLS = ["4U1UN", "VE8AT", "W6WX", "KH6RS", "ZL6B", "VK6RBP", "JA2IGY", "RR9O", "VR2B",
               "4S7B", "ZS6DN", "5Z4B", "4X6TU", "OH2B", "CS3B", "LU4AA", "OA4B", "YV5B"]
NCDXF_PLACES = {"4U1UN": "New York", "VE8AT": "Eureka", "W6WX": "Mt Umunhum", "KH6RS": "Maui", "ZL6B": "Masterton",
                "VK6RBP": "Rolystone", "JA2IGY": "Mt Asama", "RR9O": "Novosibirsk", "VR2B": "Hong Kong",
                "4S7B": "Colombo", "ZS6DN": "Pretoria", "5Z4B": "Kariobangi", "4X6TU": "Tel Aviv", "OH2B": "Lohja",
                "CS3B": "Madeira", "LU4AA": "Buenos Aires", "OA4B": "Lima", "YV5B": "Caracas"}
NCDXF_BANDS = [("20m", 14_100_000), ("17m", 18_110_000), ("15m", 21_150_000), ("12m", 24_930_000), ("10m", 28_200_000)]
ATTRIBUTIONS = ["Airports: OurAirports (public domain), placeholder entries",
                "Marine channels: ITU Radio Regulations Appendix 18",
                "Beacons: Northern California DX Foundation (NCDXF/IARU)"]


class Local:
    """The local-frequencies fixture: the made-up list and the operator's own, in memory."""

    def __init__(self):
        self.mine: dict[str, dict] = {}
        self.n = 0

    @staticmethod
    def entry(i: int, row) -> dict:
        cat, name, sub, hz, mode, step, km, brg, src = row
        e = {"id": f"e{i}", "category": cat, "name": name, "sub": sub, "freqHz": hz, "mode": mode, "step": step, "source": src}
        if km is not None:
            e["distanceKm"], e["bearing"] = km, brg
        return e

    def view(self, now: float | None = None) -> dict:
        slot = int((now if now is not None else time.time()) % 180 // 10)
        entries = [self.entry(i, r) for i, r in enumerate(LOCAL)] + list(self.mine.values())
        entries.sort(key=lambda e: (e.get("distanceKm") is None, e.get("distanceKm") or 0))
        return {"radiusKm": 100, "locator": "IO85", "attributions": ATTRIBUTIONS, "entries": entries,
                "ncdxf": {"bands": [{"band": b, "freqHz": f} for b, f in NCDXF_BANDS], "schedule": NCDXF_CALLS,
                          "locations": NCDXF_PLACES,
                          "now": [{"band": b, "freqHz": f, "call": NCDXF_CALLS[(slot - k) % 18]} for k, (b, f) in enumerate(NCDXF_BANDS)]}}

    @staticmethod
    def check(b: dict) -> str | None:
        name, hz, mode = b.get("name"), b.get("freqHz"), b.get("mode")
        if not isinstance(name, str) or not name.strip() or "\n" in name or len(name) > 60:
            return "Give the frequency a name of one line, at most 60 characters"
        if isinstance(hz, bool) or not isinstance(hz, (int, float)) or not 1_000 <= hz <= 6e9:
            return "Give a frequency between 1 kHz and 6 GHz, in Hz"
        if not isinstance(mode, str) or not mode:
            return "Give the mode to listen in"
        return None

    def answer(self, method: str, path: str, b: dict) -> tuple[int, dict] | None:
        if path == "/api/local" and method == "GET":
            return 200, self.view()
        if path == "/api/local/mine" and method == "POST":
            bad = self.check(b)
            if bad:
                return 400, {"error": bad, "code": "bad_params"}
            self.n += 1
            e = {"id": f"m{self.n}", "category": "mine", "name": b["name"].strip(), "sub": "", "freqHz": b["freqHz"],
                 "mode": b["mode"], "step": b.get("step"), "source": "you"}
            self.mine[e["id"]] = e
            return 201, e
        if path.startswith("/api/local/mine/"):
            mid = path.rsplit("/", 1)[-1]
            if mid not in self.mine:
                return 404, {"error": "There is no such frequency of yours", "code": "not_found"}
            if method == "DELETE":
                del self.mine[mid]
                return 200, {"deleted": True}
            if method == "POST":
                bad = self.check(b)
                if bad:
                    return 400, {"error": bad, "code": "bad_params"}
                self.mine[mid].update(name=b["name"].strip(), freqHz=b["freqHz"], mode=b["mode"], step=b.get("step"))
                return 200, self.mine[mid]
        return None


class Listen:
    def __init__(self, dab_missing: bool = False, rx_range: list[float] | None = None, calibrated: bool = True,
                 wfm_missing: bool = False, rx_only: bool = False):
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
        self.cals: dict[str, dict] = {}
        self.last_rx: float | None = None

    # --- api/spec, api/status, api/state and api/set ------------------------

    def catalogue(self) -> dict:
        if not self.dab_missing:
            return DAB
        return dict(DAB, needs=["faad2"], available=False, why="codec_missing", whyText=FAAD2)

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
        return obj

    def state(self, obj: dict) -> dict:
        """GET api/state's answer (or a state message's changes) as the fixture says it."""
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
        if path.startswith("/api/local"):
            return self.local.answer(method, path, body)
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
                want = params.get("service")
                svc = next((x for x in s.services if x["sid"] == want), None)
                if svc is None:
                    return 400, {"error": f"There is no service {want!r} on block {s.block}", "code": "bad_params"}
                if svc.get("why"):
                    return 409, {"error": svc["why"], "code": "decoder_unavailable", "needs": ["faad2"]}
                s.playing = want
                self._send(s.feed())
                return 200, s.view()
            if method == "DELETE":
                del self.sessions[sid]
                return 200, {"stopped": True}
        return None

    def _start(self, b: dict) -> tuple[int, dict]:
        freq = b.get("freqHz", b.get("rfHz"))
        params = b.get("params") or {}
        if isinstance(freq, bool) or not isinstance(freq, (int, float)) or not isinstance(params, dict):
            return 400, {"error": "Give decoder, freqHz and, if any, params", "code": "bad_params"}
        if self.dab_missing:
            return 409, {"error": FAAD2, "code": "decoder_unavailable", "needs": ["faad2"]}
        if len(self.sessions) >= MAX_SESSIONS:
            return 429, {"error": f"There are already {len(self.sessions)} decoders running, the most "
                                  "Settings › Decoders allows", "code": "too_many_sessions"}
        self.n += 1
        s = Session(f"ds_dab{self.n}", float(freq), str(params.get("block") or "?"))
        self.sessions[s.id] = s
        self._send(s.feed())
        return 201, s.view()

    async def run(self, every: float = 0.25):
        """A session syncs SYNC_AFTER seconds after it starts (or says it found nothing)."""
        while True:
            await asyncio.sleep(every)
            now = time.time()
            for s in list(self.sessions.values()):
                if s.state == "starting" and now - s.since >= SYNC_AFTER:
                    s.state = "nothing" if s.block in EMPTY else "synced"
                    self._send(s.feed())

    def _send(self, r: dict):
        for q in list(self.listeners):
            q.put_nowait(json.dumps(r))
