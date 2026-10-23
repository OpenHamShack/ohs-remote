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
    Any other mode set ends it. The audio is the simulator's FM;
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
SYNC_AFTER = 2.0
MAX_SESSIONS = 4
#: Blocks with nothing on them, to see the "nothing here" sentence.
EMPTY = {"5A"}
FAAD2 = "Ask your controller's administrator to install faad2 (Controller › Optional libraries)."
DABPLUS_WHY = "DAB+ needs faad2, which this controller does not have yet."

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


class Listen:
    def __init__(self, dab_missing: bool = False, rx_range: list[float] | None = None):
        self.wfm = False
        self.rx_range = rx_range or RX_RANGE
        self.dab_missing = dab_missing
        self.sessions: dict[str, Session] = {}
        self.listeners: set[asyncio.Queue] = set()
        self.n = 0

    # --- api/spec, api/status, api/state and api/set ------------------------

    def catalogue(self) -> dict:
        if not self.dab_missing:
            return DAB
        return dict(DAB, needs=["faad2"], available=False, why="codec_missing", whyText=FAAD2)

    def spec(self, obj: dict) -> dict:
        mode = obj.get("mode")
        if isinstance(mode, dict) and isinstance(mode.get("choices"), list) and "wfm" not in mode["choices"]:
            mode["choices"] = mode["choices"] + ["wfm"]
        vfo = obj.get("vfo")
        if isinstance(vfo, dict):
            vfo["max"] = self.rx_range[1]
        return obj

    def status(self, obj: dict) -> dict:
        obj.setdefault("rxRange", self.rx_range)
        return obj

    def state(self, obj: dict) -> dict:
        if self.wfm and obj.get("mode") == "fm":
            obj["mode"] = "wfm"
        return obj

    def take(self, changes: dict) -> dict:
        """An api/set's changes as the simulator should see them: wfm goes as fm."""
        if "mode" not in changes:
            return changes
        self.wfm = changes["mode"] == "wfm"
        return dict(changes, mode="fm") if self.wfm else changes

    def changed(self, obj: dict) -> dict:
        ch = obj.get("changed")
        if isinstance(ch, dict):
            self.state(ch)
        return obj

    def frame(self, payload: bytes) -> str | None:
        """A state message whose mode is fm, reported as wfm while it is in force; None for anything else."""
        if not self.wfm or len(payload) > 65536 or b'"mode"' not in payload:
            return None
        try:
            o = json.loads(payload)
        except ValueError:
            return None
        ch = o.get("changes") if o.get("kind") == "state" else None
        if not isinstance(ch, dict) or ch.get("mode") != "fm":
            return None
        ch["mode"] = "wfm"
        return json.dumps(o)

    # --- the dab decoder ----------------------------------------------------

    def answer(self, method: str, path: str, query: dict, body: dict) -> tuple[int, dict] | None:
        """(status, body) for a dab route, or None for anything this fixture does not own."""
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
