#!/usr/bin/env python3
"""An OpenHamShack controller's front door, for Remote to talk to with no
controller: the parts of CONTROLLER-CONTRACT a client sees, in front of the
real store servers run on simulators and fakes.

    python3 -m ohsnb --server             # SHACK_PORT=18611 DOCK360_SHACK_PROXY_SECRET=fakeshack
    python3 -m ohsradio --server            # SHACK_PORT=18612, same secret
    FAKEBOX_PLATFORM=1 FAKEBOX_PORT=18621 python3 tools/fakebox.py      # QO-100 WB, platform mode
    DOCK360_SHACK_PROXY_SECRET=fakeshack python3 -m ohsdatv --server --port 18623   # DATV
    FAKEFLOW_PREFIX= FAKEFLOW_PASSWORD= FAKEFLOW_PORT=18624 python3 tools/fakeflow.py  # Icom (stationflow-tv)
    # Flex: the real module (dock360-apps flex line) in platform mode, on its
    # own pretend radio and its own fake services (docs/TESTING.md has the lines):
    python3 flex/tools/fakeflex.py --host 127.0.0.1 --port 4992
    # (or tools/fakeflex_rx.py here: the same radio plus antennas, AGC and a
    # busy FT8 band, for the Flex kind's receive controls)
    python3 flex/tools/fakecontroller.py --app http://127.0.0.1:18625 --secret fakeshack --services-port 18631
    SHACK_PLATFORM=dock360 FLEX_HTTP_PORT=18625 RADIO_HOST=127.0.0.1 DOCK360_SHACK_PROXY_SECRET=fakeshack \
        DOCK360_SHACK_URL=http://127.0.0.1:18631 DOCK360_SHACK_TOKEN=shackapp_test python3 -m app.main
    python3 tools/fakeshack.py           # :18600; the emulator reaches it as 10.0.2.2:18600

Any of them may be left out: the app is still listed, as `running: false`
(a controller lists installed apps, running or not).

What it does, and nothing more:

  * GET /.well-known/openhamshack lists the apps (kinds qo100nb, radio,
    qo100wb, datv, icom, flex) with relative bases and each app's anonymousView,
    as section 3 has it.
  * POST /api/shack/token (any user, password "fake") gives a phone a
    shackdev_ token capped at transmit; POST /api/shack/pair/start and
    /pair/poll pair a TV with a code, capped at control. Codes are approved
    by POST /fake/approve {"code"}, or at once with FAKESHACK_AUTO_APPROVE=1
    (so a TV on an emulator pairs without a second device).
  * /<app-id>/... is passed to the app with the prefix stripped,
    X-Forwarded-Prefix set, and the identity headers (5.3) the real proxy
    sends: the proxy secret, user, level (the token's cap: the only level
    this fake knows), device, auth, admin and reason. The client's own
    X-OpenHamShack-* headers are dropped, as the real proxy drops them.
  * The gate (5.2), as Dock360's shack/gate.py decides it: no credential is
    view for an app with anonymous viewing (QO-100 WB by default, as D3;
    FAKESHACK_ANONYMOUS=id,id changes the list) and 401
    credentials_required for any other; a token this fake did not issue is
    401 bad_credentials, a revoked one 401 token_revoked; a level below the
    route's minimum is 403 level_too_low. The minimums are the apps'
    manifests' (ROUTES below), first match wins.
  * POST /fake/revoke revokes every token issued so far, as removing the
    device on the controller does - the way to see Remote's "pair again".
  * GET /api/shack/v1/devices answers as the platform's own token check
    (4.4): 200 with the caller's device for a live token, 401
    token_revoked or bad_credentials otherwise. Remote asks it before
    believing an app's 401.
  * POST /fake/app401 {"app": "shack-flex", "count": 3} makes the next
    `count` requests to that app answer 401 proxy_required, as a module
    does while it restarts or Caddy reloads (the token is still good).
  * SSTV, which the simulators never send: on an NB or Radio events
    socket that asked for "events", fakeshack slips in a test picture of
    its own - Martin 1, 320 x 256 colour bars with a moving stripe - as
    ohscore/modes/sstv.py sends one (start, a line every ~40 ms so it takes
    ~10 s, done), every FAKESHACK_SSTV_EVERY seconds (default 60; 0 for
    none). Like the engine's own decoder it runs only in SSTV mode: the
    first picture starts 3 s after the engine's mode becomes "sstv" (read
    from the socket's hello and state messages), and a mode change
    mid-picture stops it there, as the real decoder stops - so choosing
    SSTV on a Remote and leaving it again shows the panel open and close
    with the matching events. FAKESHACK_SSTV_ANYMODE=1 sends pictures in
    any mode, as before (the first after 5 s). Every other decoder (FT8,
    CW, PSK, RTTY) is the simulator's own, already following the mode.
    Its frames go between the server's, never inside one: the server's
    frames are parsed (the engine's socket has no extensions), and the
    client's direction is untouched.
  * GET /api/shack/v1/profile answers the apps' own token (shackapp_fake)
    with a UK Full station profile (FAKESHACK_CALLSIGN, default M0TST), so
    QO-100 WB's licence check has a station to judge; the broker is not
    faked (404), so nothing claims a device.

  * Radio's receiver coverage, which no server sends yet: with
    FAKESHACK_RADIO_RXRANGE=lo-hi (Hz, e.g. 1000-60000000 for an HF
    receiver, or 1000-2000000000 for the RSPdx on ohs-box) Radio's GET
    api/status answers with `"rxRange": [lo, hi]` added, so Remote's band
    buttons can be seen to leave out the bands it cannot reach. Unset, the
    status passes untouched and every band has a button. The simulator
    behind it tunes 100 kHz-6 GHz, so HF itself needs nothing.

Bytes are piped after the head is rewritten, so the PCM stream, the TS
stream and the websocket pass through untouched (the handshake is the
client's own: no Sec-WebSocket-* rewriting, GUIDE section 5).

Transmit goes only to the simulators and fakes behind it: NB and Radio run
the simulator with no broker, fakebox keys nothing (it has no radio), DATV
and Icom have no transmit route a client can reach. Flex's module runs
with transmit off (ALLOW_TRANSMIT unset) and keys only fakeflex.py, and
its transmit routes need the Transmit level here, which no TV holds.
"""
from __future__ import annotations

import asyncio
import base64
import fnmatch
import json
import os
import secrets

PORT = int(os.environ.get("FAKESHACK_PORT", "18600"))
SECRET = os.environ.get("FAKESHACK_SECRET", "fakeshack")
AUTO = os.environ.get("FAKESHACK_AUTO_APPROVE", "") == "1"
APP_TOKEN = "shackapp_fake"
CALLSIGN = os.environ.get("FAKESHACK_CALLSIGN", "M0TST")


def _port(name: str, default: int) -> int:
    return int(os.environ.get(name, str(default)))


# id -> kind, name, port. The ids are the store packages' (REPO-LAYOUT 4).
APPS = {
    "shack-qo100nb": ("qo100nb", "QO-100 NB", _port("FAKESHACK_NB_PORT", 18611)),
    "shack-radio": ("radio", "Radio", _port("FAKESHACK_RADIO_PORT", 18612)),
    "shack-qo100wb": ("qo100wb", "QO-100 WB", _port("FAKESHACK_WB_PORT", 18621)),
    "shack-datv": ("datv", "DATV", _port("FAKESHACK_DATV_PORT", 18623)),
    "shack-icom": ("icom", "Icom", _port("FAKESHACK_ICOM_PORT", 18624)),
    "shack-flex": ("flex", "Flex", _port("FAKESHACK_FLEX_PORT", 18625)),
}
ANONYMOUS = set(filter(None, os.environ.get("FAKESHACK_ANONYMOUS", "shack-qo100wb").split(",")))
TX_CAPABLE = {"shack-qo100nb", "shack-radio", "shack-qo100wb", "shack-icom", "shack-flex"}

# Each app's manifest `levels` and `default_write_level` (CONTROLLER-CONTRACT
# 5.2): (method or None, path glob, level), first match wins; reads are view.
_ENGINE = ([(None, "/api/ptt/heartbeat", "transmit"), ("POST", "/api/ptt", "control"),
            (None, "/api/audio/tx", "transmit"), ("POST", "/api/halt", "control")], "control")
ROUTES = {
    # NB and Radio leave a key-up to the app (control at the door: an unkey
    # must always get through), and the heartbeat needs transmit.
    "shack-qo100nb": _ENGINE,
    "shack-radio": _ENGINE,
    "shack-qo100wb": ([(None, "/api/ptt*", "transmit"), ("POST", "/api/tx/*", "transmit"),
                       ("POST", "/api/pluto/*", "transmit")], "control"),
    "shack-datv": ([], "control"),
    # Icom's allow-list design: transmit unless listed (TV-API.md 3).
    "shack-icom": ([("POST", "/api/freq", "control"), ("POST", "/api/mode", "control"),
                    ("POST", "/api/filter", "control"), ("POST", "/api/vfo", "control"),
                    ("POST", "/api/split", "control"), ("POST", "/api/data", "control"),
                    ("POST", "/api/preamp", "control"), ("POST", "/api/att", "control"),
                    ("POST", "/api/scope", "control"), ("POST", "/api/nr2", "control"),
                    ("POST", "/api/digi/mode", "control"), ("POST", "/api/sstv/start", "control"),
                    ("POST", "/api/aprs/start", "control")], "transmit"),
    # Flex's store manifest (flex/store/module.yaml): the same allow-list
    # design, every write Transmit unless listed.
    "shack-flex": ([(None, "/api/ptt*", "transmit"), ("POST", "/api/transmit", "transmit"),
                    ("POST", "/api/command", "control"), ("POST", "/api/band", "control"),
                    ("POST", "/api/panadapter", "control"), ("POST", "/api/notch", "control"),
                    ("POST", "/api/reconnect", "control"), ("POST", "/api/controller", "control"),
                    ("POST", "/api/shack/pad/action", "control"), ("POST", "/api/decoders", "control"),
                    ("POST", "/api/sstv", "control"), ("POST", "/api/scope", "control"),
                    ("POST", "/api/nr2", "control")], "transmit"),
}
LEVELS = ("none", "view", "control", "transmit")
LABELS = {"view": "View", "control": "Control", "transmit": "Transmit"}

def _rxrange(raw: str) -> list[float] | None:
    lo, _, hi = raw.partition("-")
    try:
        r = [float(lo), float(hi)]
    except ValueError:
        return None
    return r if 0 <= r[0] < r[1] else None


RADIO_RXRANGE = _rxrange(os.environ.get("FAKESHACK_RADIO_RXRANGE", ""))

SSTV_EVERY = float(os.environ.get("FAKESHACK_SSTV_EVERY", "60"))
SSTV_ANYMODE = os.environ.get("FAKESHACK_SSTV_ANYMODE", "") == "1"
ENGINES = {"shack-qo100nb", "shack-radio"}

tokens: dict[str, dict] = {}    # token -> {level, device, revoked}
pairs: dict[str, dict] = {}     # pairId -> {secret, code, state}

PROFILE = {
    "schema": 1, "callsign": CALLSIGN, "operatorName": "", "locator": "IO85JW", "country": "GB",
    "dxccPrefix": "G", "iaruRegion": 1, "licence": {"regime": "auto", "class": "full", "custom": []},
    "novs": [], "conditions": {}, "units": "metric", "timeDisplay": "utc",
}


def running(port: int) -> bool:
    import socket
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.3):
            return True
    except OSError:
        return False


def well_known() -> dict:
    return {
        "openhamshack": 1, "contract": "1.0",
        "controllerId": "00000000-fake-4000-8000-000000000001",
        "name": "FAKESHACK", "version": "2.0.0-fake",
        "station": {"callsign": CALLSIGN, "locator": "IO85", "country": "GB"},
        "auth": {"token": "api/shack/token", "pair": "api/shack/pair/start", "totp": "per-user",
                 "anonymousView": sorted(i for i in APPS if i in ANONYMOUS)},
        "services": {"base": "api/shack/v1/", "profile": 1},
        "apps": [{"id": i, "kind": k, "name": n, "version": "fake", "base": i + "/",
                  "running": running(p), "txCapable": i in TX_CAPABLE, "anonymousView": i in ANONYMOUS}
                 for i, (k, n, p) in sorted(APPS.items(), key=lambda a: a[1][1])],
    }


def new_token(level: str, device: str) -> str:
    t = "shackdev_" + secrets.token_urlsafe(32)
    tokens[t] = {"level": level, "device": device, "revoked": False}
    return t


def required(app: str, method: str, path: str) -> str:
    if method in ("GET", "HEAD", "OPTIONS"):
        return "view"
    rules, default = ROUTES.get(app, ([], "control"))
    for m, glob, level in rules:
        if (m is None or m == method) and fnmatch.fnmatchcase(path, glob):
            return level
    return default


#: /fake/app401: how many more requests each app refuses as a restarting module does.
app401: dict[str, int] = {}


def platform(method: str, path: str, body: dict, auth: str) -> tuple[int, dict]:
    if method == "GET" and path == "/.well-known/openhamshack":
        return 200, well_known()
    if method == "POST" and path == "/api/shack/token":
        if body.get("deviceKind") == "tv":
            return 400, {"error": "A TV pairs with a code.", "code": "use_tv_pairing"}
        if body.get("password") != "fake":
            return 401, {"error": "Wrong user name or password.", "code": "bad_credentials"}
        return 201, {"token": new_token("transmit", "dev_FAKE"), "deviceId": "dev_FAKE",
                     "user": body.get("username", ""), "admin": False, "maxLevel": "transmit",
                     "controllerId": well_known()["controllerId"]}
    if method == "POST" and path == "/api/shack/pair/start":
        pid, code = "pr_" + secrets.token_hex(3), "%06d" % secrets.randbelow(10 ** 6)
        pairs[pid] = {"secret": secrets.token_urlsafe(32), "code": code, "state": "pending"}
        print(f"pair code {code}", flush=True)
        return 200, {"pairId": pid, "pairSecret": pairs[pid]["secret"], "code": code,
                     "expiresIn": 300, "pollEvery": 2}
    if method == "POST" and path == "/api/shack/pair/poll":
        p = pairs.get(body.get("pairId", ""))
        if not p or p["secret"] != body.get("pairSecret"):
            return 410, {"error": "That pairing has expired.", "code": "pair_expired"}
        if AUTO and p["state"] == "pending":
            p["state"] = "approved"
        if p["state"] == "approved":
            del pairs[body["pairId"]]
            return 200, {"state": "approved", "token": new_token("control", "dev_FAKETV"), "deviceId": "dev_FAKETV",
                         "user": "brian", "maxLevel": "control"}
        return 200, {"state": p["state"]}
    if method == "GET" and path == "/api/shack/v1/profile":
        if auth != "Bearer " + APP_TOKEN:
            return 401, {"error": "Apps only.", "code": "bad_credentials"}
        return 200, dict(PROFILE, derived={"canTransmit": True})
    if method == "POST" and path == "/fake/approve":
        for p in pairs.values():
            if p["code"] == body.get("code"):
                p["state"] = "approved"
                return 200, {"ok": True}
        return 404, {"error": "No such code.", "code": "no_code"}
    if method == "GET" and path == "/api/shack/v1/devices":
        t = tokens.get(auth[7:].strip()) if auth.startswith("Bearer ") else None
        if not auth.startswith("Bearer "):
            return 401, {"error": "This needs a device token.", "code": "credentials_required"}
        if t is None:
            return 401, {"error": "That device token is not one this station issued.", "code": "bad_credentials"}
        if t["revoked"]:
            return 401, {"error": "This device has been removed from the station.", "code": "token_revoked"}
        return 200, {"devices": [{"id": t["device"], "maxLevel": t["level"]}]}
    if method == "POST" and path == "/fake/app401":
        if body.get("app") not in APPS:
            return 404, {"error": "No such app.", "code": "no_app"}
        app401[body["app"]] = int(body.get("count", 1))
        return 200, {"ok": True}
    if method == "POST" and path == "/fake/revoke":
        for t in tokens.values():
            t["revoked"] = True
        return 200, {"revoked": len(tokens)}
    return 404, {"error": "Not found.", "code": "not_found"}


def decide(app: str, method: str, path: str, auth: str) -> tuple[int, dict, dict]:
    """(status, refusal body, identity headers): the gate's decision."""
    name = APPS[app][1]
    if auth.startswith("Bearer "):
        t = tokens.get(auth[7:].strip())
        if t is None:
            return 401, {"error": "That device token is not one this station issued.", "code": "bad_credentials"}, {}
        if t["revoked"]:
            return 401, {"error": "This device has been removed from the station.", "code": "token_revoked"}, {}
        level, user, device, how = t["level"], "brian", t["device"], "device"
    elif app in ANONYMOUS:
        level, user, device, how = "view", "-", "-", "anonymous"
    else:
        return 401, {"error": f"Sign in to use {name}.", "code": "credentials_required"}, {}
    needs = required(app, method, path)
    if LEVELS.index(level) < LEVELS.index(needs):
        if how == "anonymous":
            return 401, {"error": f"Sign in to use {name}.", "code": "credentials_required"}, {}
        return 403, {"error": f"This needs the {LABELS[needs]} level; your level for {name} is {LABELS[level]}.",
                     "code": "level_too_low", "level": level, "needs": needs}, {}
    return 200, {}, {"X-OpenHamShack-User": user, "X-OpenHamShack-Level": level, "X-OpenHamShack-Device": device,
                     "X-OpenHamShack-Auth": how, "X-OpenHamShack-Admin": "0",
                     "X-OpenHamShack-Reason": "anonymous" if how == "anonymous" else "ok"}


async def reply(w, code: int, obj: dict):
    data = json.dumps(obj).encode()
    w.write(f"HTTP/1.1 {code} X\r\nContent-Type: application/json\r\nContent-Length: {len(data)}\r\n"
            f"Cache-Control: no-store\r\nConnection: close\r\n\r\n".encode() + data)
    await w.drain()
    w.close()


async def pipe(r, w):
    try:
        while data := await r.read(65536):
            w.write(data)
            await w.drain()
    except (ConnectionError, OSError):
        pass
    finally:
        w.close()


def ws_frame(text: str) -> bytes:
    """One unmasked text frame, as a server sends it."""
    data = text.encode()
    n = len(data)
    head = bytes([0x81, n]) if n < 126 else bytes([0x81, 126]) + n.to_bytes(2, "big") if n < 65536 \
        else bytes([0x81, 127]) + n.to_bytes(8, "big")
    return head + data


def sstv_picture(n: int) -> list[str]:
    """A Martin 1 test picture as ohscore/modes/sstv.py's events: colour
    bars, with a white stripe whose place moves with [n] so two pictures
    in the gallery differ."""
    w, h = 320, 256
    name = f"sstv_martinm1_fake{n:04d}.png"
    bars = [(255, 255, 255), (255, 255, 0), (0, 255, 255), (0, 255, 0), (255, 0, 255), (255, 0, 0), (0, 0, 255), (0, 0, 0)]
    out = [json.dumps({"kind": "events", "mode": "sstv", "state": "start", "name": name, "fmt": "martinm1",
                       "width": w, "height": h})]
    stripe = (n * 37) % h
    for y in range(h):
        row = bytearray()
        for x in range(w):
            c = (255, 255, 255) if abs(y - stripe) < 6 else bars[x * len(bars) // w]
            row += bytes(c)
        out.append(json.dumps({"kind": "events", "mode": "sstv", "state": "line", "name": name, "line": y + 1,
                               "of": h, "row": base64.b64encode(bytes(row)).decode()}))
    out.append(json.dumps({"kind": "events", "mode": "sstv", "state": "done", "name": name,
                           "path": "/home/shack/sstv/" + name}))
    return out


def engine_mode(payload: bytes) -> str | None:
    """The engine's mode, if this server text frame says it: the hello's
    status, or a state message's changes. Spectra (most of the traffic)
    are passed over without parsing."""
    if b'"mode"' not in payload or len(payload) > 65536:
        return None
    try:
        o = json.loads(payload)
    except ValueError:
        return None
    if o.get("kind") == "hello":
        return (o.get("status") or {}).get("mode")
    if o.get("kind") == "state":
        return (o.get("changes") or {}).get("mode")
    return None


async def server_frames(r, w, inject: asyncio.Queue, mode: dict):
    """Copy the server's HTTP answer and then its websocket frames whole,
    writing any injected frame between two of them, and noting the
    engine's mode in [mode] as it goes by."""
    try:
        w.write(await r.readuntil(b"\r\n\r\n"))
        while True:
            get = asyncio.ensure_future(r.readexactly(2))
            while not get.done():
                done, _ = await asyncio.wait({get}, timeout=0.02)
                while not inject.empty():
                    w.write(inject.get_nowait())
                await w.drain()
            head = get.result()
            n = head[1] & 0x7F
            ext = await r.readexactly(2 if n == 126 else 8 if n == 127 else 0)
            if ext:
                n = int.from_bytes(ext, "big")
            mask = await r.readexactly(4 if head[1] & 0x80 else 0)
            payload = await r.readexactly(n)
            if head[0] & 0x0F == 1 and not mask:
                m = engine_mode(payload)
                if m is not None and m != mode.get("mode"):
                    mode["mode"] = m
                    mode["changed"].set()
            w.write(head + ext + mask + payload)
            await w.drain()
    except (asyncio.IncompleteReadError, ConnectionError, OSError):
        pass
    finally:
        w.close()


async def sstv_feed(inject: asyncio.Queue, mode: dict):
    """Pictures while the engine is in SSTV mode (any mode with
    FAKESHACK_SSTV_ANYMODE); a picture under way when the mode changes
    stops at that line, with no "done", as the real decoder's does."""
    n = 0
    if SSTV_ANYMODE:
        await asyncio.sleep(5)
    while True:
        if not SSTV_ANYMODE:
            while mode.get("mode") != "sstv":
                mode["changed"].clear()
                await mode["changed"].wait()
            await asyncio.sleep(3)
        n += 1
        for msg in sstv_picture(n):
            if not SSTV_ANYMODE and mode.get("mode") != "sstv":
                break
            await inject.put(ws_frame(msg))
            await asyncio.sleep(0.04)
        else:
            await asyncio.sleep(SSTV_EVERY)


async def handle(r, w):
    try:
        head = await r.readuntil(b"\r\n\r\n")
    except (asyncio.IncompleteReadError, asyncio.LimitOverrunError, ConnectionError):
        w.close()
        return
    lines = head.decode("latin-1").split("\r\n")
    method, target, version = lines[0].split(" ", 2)
    headers = [tuple(x.split(":", 1)) for x in lines[1:] if ":" in x]
    hdr = {k.strip().lower(): v.strip() for k, v in headers}
    path = target.split("?", 1)[0]
    app = path.lstrip("/").split("/", 1)[0]
    auth = hdr.get("authorization", "")
    if app not in APPS:
        n = int(hdr.get("content-length", "0") or 0)
        raw = await r.readexactly(n) if n else b""
        try:
            body = json.loads(raw or b"{}")
        except ValueError:
            body = {}
        code, obj = platform(method, path, body if isinstance(body, dict) else {}, auth)
        await reply(w, code, obj)
        return
    rest = target[len(app) + 1:] or "/"
    if app401.get(app, 0) > 0:
        app401[app] -= 1
        await reply(w, 401, {"error": "Not through the controller.", "code": "proxy_required"})
        return
    code, refusal, identity = decide(app, method, rest.split("?", 1)[0], auth)
    if code != 200:
        await reply(w, code, refusal)
        return
    try:
        ur, uw = await asyncio.open_connection("127.0.0.1", APPS[app][2])
    except OSError:
        await reply(w, 502, {"error": f"{APPS[app][1]} is not running.", "code": "app_down"})
        return
    upgrade = "upgrade" in hdr.get("connection", "").lower()
    keep = [(k, v) for k, v in headers if k.strip().lower() not in (
        "authorization", "connection", "x-forwarded-prefix") and not k.strip().lower().startswith("x-openhamshack-")]
    keep += [("Connection", "Upgrade" if upgrade else "close"), ("X-Forwarded-Prefix", "/" + app),
             ("X-OpenHamShack-Proxy-Secret", SECRET), *identity.items()]
    uw.write((f"{method} {rest} {version}\r\n" + "".join(f"{k.strip()}: {v.strip()}\r\n" for k, v in keep)
              + "\r\n").encode("latin-1"))
    await uw.drain()
    query = target.split("?", 1)[1] if "?" in target else ""
    if (upgrade and app in ENGINES and SSTV_EVERY > 0 and rest.split("?", 1)[0] == "/api/events"
            and "events" in query):
        inject: asyncio.Queue = asyncio.Queue()
        mode = {"mode": None, "changed": asyncio.Event()}
        feed = asyncio.ensure_future(sstv_feed(inject, mode))
        try:
            await asyncio.gather(pipe(r, uw), server_frames(ur, w, inject, mode))
        finally:
            feed.cancel()
        return
    if app == "shack-radio" and RADIO_RXRANGE and method == "GET" and rest.split("?", 1)[0] == "/api/status":
        await status_with_rxrange(ur, w)
        return
    await asyncio.gather(pipe(r, uw), pipe(ur, w))


async def status_with_rxrange(ur, w):
    """Radio's status, whole (the upstream closes after it), with rxRange added."""
    data = await ur.read()
    head, _, body = data.partition(b"\r\n\r\n")
    try:
        obj = json.loads(body)
    except ValueError:
        obj = None
    if not isinstance(obj, dict) or b"chunked" in head.lower():
        w.write(data)       # not what we expected: pass it as it came
        await w.drain()
        w.close()
        return
    obj["rxRange"] = RADIO_RXRANGE
    code = int(head.split(b" ", 2)[1])
    await reply(w, code, obj)


async def main():
    srv = await asyncio.start_server(handle, "0.0.0.0", PORT)
    pidfile = os.environ.get("FAKESHACK_PIDFILE")
    if pidfile:
        with open(pidfile, "w") as f:
            f.write(str(os.getpid()))
    print(f"fakeshack on :{PORT} (auto-approve {'on' if AUTO else 'off'}; anonymous view: "
          f"{', '.join(sorted(ANONYMOUS)) or 'none'})", flush=True)
    async with srv:
        await srv.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
