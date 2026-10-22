"""Radio's decoder sessions (openhamshack-nb SERVER-API "Decoders",
RADIO-SDR-PLAN §4.4), for fakeshack to answer in place of the server while
the simulator behind it has none, and while a server that has them hears
nothing to decode: Remote can then be built and tested against a session
that is always talking.

The shapes are the store server's (ohscore/decoders.py and the api/decoders
handlers on feature/radio-sdr-m1), copied rather than imported - this repo
does not carry openhamshack-nb - so a change there must be made here too:

  * the catalogue: RTTY (fixed, available), FT8 (runs only as the mode:
    starting it is 409 decoder_unavailable, why mode_only) and DAB+
    (needs faad2, which is missing: 409 decoder_unavailable with `needs`);
  * one RTTY session already running at start, ds_fake1 on 14.0835 MHz,
    synced, sending a CQ and an exchange round and round about seven
    characters a second, as 45.45 Bd does;
  * sessions a client starts: RTTY only, `starting` for two seconds, a
    `state` result `synced`, then the same text;
  * the refusals with the server's codes and statuses (bad_params 400,
    unknown_decoder 404, decoder_unavailable 409, too_many_sessions 429 at
    four, unknown_session 404) and the server's {"error", "code"} body.

Nothing is decoded and nothing transmits: the text is made up here.
"""
from __future__ import annotations

import asyncio
import json
import time
from collections import deque
from datetime import datetime, timezone

KEPT = 500
MAX_SESSIONS = 4
CPU_MAX = 0.25

RTTY = {
    "id": "rtty", "family": "amateur", "inputs": ["iq"], "rateHz": 12000.0, "bandwidthHz": 3000.0,
    "outputs": ["text"], "panel": "text",
    "params": [{"key": "baud", "type": "choice", "choices": [45.45, 50.0, 75.0], "default": 45.45},
               {"key": "shift", "type": "choice", "choices": [170, 425, 450, 850], "default": 170},
               {"key": "reverse", "type": "bool", "default": False}],
    "needs": [], "engine": "shack_sdr", "available": True, "why": None, "whyText": "", "version": "0.1.0",
    "fixed": True,
}
FT8 = {
    "id": "ft8", "family": "amateur", "inputs": ["audio"], "rateHz": 12000.0, "bandwidthHz": 3000.0,
    "outputs": ["slots"], "panel": "slots", "params": [], "needs": [], "engine": "ohsdigi", "available": True,
    "why": None, "whyText": "", "version": "fake", "fixed": False,
}
DABPLUS = {
    "id": "dabplus", "family": "broadcast", "inputs": ["iq"], "rateHz": 2048000.0, "bandwidthHz": 1536000.0,
    "outputs": ["meta"], "panel": "meta", "params": [], "needs": ["faad2"], "engine": "shack_sdr",
    "available": False, "why": "codec_missing",
    "whyText": "Ask your controller's administrator to install faad2 (Controller › Optional libraries).",
    "version": "0.1.0", "fixed": True,
}
CATALOGUE = [RTTY, FT8, DABPLUS]
CODECS = [{"id": "faad2", "for": ["dabplus", "drm"], "state": "missing", "path": None, "version": None,
           "why": "not_found",
           "whyText": "Ask your controller's administrator to install faad2 (Controller › Optional libraries).",
           "where": None, "host": True, "shippable": False}]
MODE_ONLY = "This decoder runs only as the mode: choose it as the mode instead"

TEXT = ("CQ CQ CQ DE {call} {call} {call} PSE K\r\n"
        "{call} DE G4ABC G4ABC GA OM UR RST 599 599 NAME JIM QTH LEEDS LEEDS HW? {call} DE G4ABC K\r\n"
        "G4ABC DE {call} R TNX JIM UR 599 NAME BRIAN QTH EDINBURGH 73 SK\r\n")


def _iso(t: float) -> str:
    return datetime.fromtimestamp(t, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class Session:
    def __init__(self, sid: str, freq: float, params: dict, text: str, synced: bool):
        self.id, self.freq, self.params, self.text = sid, freq, params, text
        self.state = "synced" if synced else "starting"
        self.since = time.time()
        self.rev = 0
        self.at = 0                     # where in [text] the next characters come from
        self.results: deque = deque(maxlen=KEPT)

    def view(self) -> dict:
        return {"id": self.id, "decoder": "rtty", "freqHz": self.freq, "params": dict(self.params),
                "state": self.state, "why": None, "whyText": "", "since": _iso(self.since), "rev": self.rev,
                "stats": {"snrDb": 14.2, "offsetHz": -12.5, "cpu": 0.004, "dropped": 0}, "panel": "text"}

    def result(self, **it) -> dict:
        self.rev += 1
        r = {"kind": "decoder", "session": self.id, "rev": self.rev, "t": round(time.time(), 2), **it}
        self.results.append(r)
        return r


class Decoders:
    """The sessions, and the results sent to every events socket that asked for "decoder"."""

    def __init__(self, callsign: str):
        self.text = TEXT.format(call=callsign)
        self.sessions: dict[str, Session] = {}
        self.listeners: set[asyncio.Queue] = set()
        self.n = 0
        self._new(14_083_500.0, {"baud": 45.45, "shift": 170, "reverse": False}, synced=True)

    def _new(self, freq: float, params: dict, synced: bool = False) -> Session:
        self.n += 1
        s = Session(f"ds_fake{self.n}", freq, params, self.text, synced)
        self.sessions[s.id] = s
        return s

    def answer(self, method: str, path: str, query: dict, body: dict) -> tuple[int, dict] | None:
        """(status, body) for one of the decoder routes, or None for any other path."""
        if path == "/api/decoders" and method == "GET":
            return 200, {"decoders": CATALOGUE, "codecs": CODECS,
                         "limits": {"sessions": MAX_SESSIONS, "cpuMax": CPU_MAX, "threads": 1},
                         "sessions": [s.view() for s in self.sessions.values()]}
        if path == "/api/decoders/codecs/rescan" and method == "POST":
            return 200, {"codecs": CODECS}
        if path == "/api/decoders/sessions" and method == "POST":
            return self._start(body)
        if path.startswith("/api/decoders/sessions/"):
            sid = path.rstrip("/").rsplit("/", 1)[-1]
            s = self.sessions.get(sid)
            if method == "GET":
                try:
                    since = int(query["since"][0]) if "since" in query else None
                except ValueError:
                    return 400, {"error": "since must be a whole number", "code": "bad_params"}
                if s is None:
                    return 404, {"error": f"There is no decoder session called {sid}", "code": "unknown_session"}
                return 200, dict(s.view(), results=[r for r in s.results if since is None or r["rev"] > since])
            if method == "DELETE":
                if s is None:
                    return 404, {"error": f"There is no decoder session called {sid}", "code": "unknown_session"}
                del self.sessions[sid]
                return 200, {"stopped": True}
        if path == "/api/decoders" or path.startswith("/api/decoders/"):
            return 404, {"error": "Not found.", "code": "not_found"}
        return None

    def _start(self, b: dict) -> tuple[int, dict]:
        decoder, freq, params = b.get("decoder"), b.get("freqHz"), b.get("params") or {}
        if not isinstance(decoder, str) or not isinstance(params, dict) or isinstance(freq, bool) \
                or not isinstance(freq, (int, float)):
            return 400, {"error": "Give decoder, freqHz and, if any, params", "code": "bad_params"}
        entry = next((e for e in CATALOGUE if e["id"] == decoder), None)
        if entry is None:
            return 404, {"error": f"There is no decoder called {decoder!r}", "code": "unknown_decoder"}
        if not entry["available"]:
            return 409, {"error": entry["whyText"], "code": "decoder_unavailable", "needs": entry["needs"][0]}
        if not entry["fixed"]:
            return 409, {"error": MODE_ONLY, "code": "decoder_unavailable"}
        if len(self.sessions) >= MAX_SESSIONS:
            return 429, {"error": f"There are already {len(self.sessions)} decoders running, the most "
                                  "Settings › Decoders allows", "code": "too_many_sessions"}
        given = {p["key"]: p for p in RTTY["params"]}
        out = {}
        for key, p in given.items():
            v = params.get(key, p["default"])
            if p["type"] == "choice" and v not in p["choices"] or p["type"] == "bool" and not isinstance(v, bool):
                return 400, {"error": f"{key} cannot be {v!r}", "code": "bad_params"}
            out[key] = v
        return 201, self._new(float(freq), out).view()

    def status(self) -> dict:
        """api/status's `decoders`."""
        return {"available": True, "sessions": len(self.sessions), "cpuMax": CPU_MAX, "codecsMissing": ["faad2"]}

    async def run(self, every: float = 0.14):
        """About seven characters a second per session; a session started
        here says `synced` after two seconds and only then decodes."""
        while True:
            await asyncio.sleep(every)
            now = time.time()
            for s in list(self.sessions.values()):
                if s.state == "starting":
                    if now - s.since >= 2:
                        s.state = "synced"
                        self._send(s.result(type="state", state="synced", why=None, whyText=""))
                    continue
                n = 1 + s.rev % 3 // 2          # mostly one character, now and then two
                chunk = (s.text * 2)[s.at:s.at + n]
                s.at = (s.at + n) % len(s.text)
                self._send(s.result(type="text", text=chunk, offsetHz=-12.5, snrDb=14.0))

    def _send(self, r: dict):
        for q in list(self.listeners):
            q.put_nowait(json.dumps(r))
