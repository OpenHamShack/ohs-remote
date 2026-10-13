#!/usr/bin/env python3
"""StationFlow's fakeflow with what the Icom face's new controls read.

    FAKEFLOW_SRC=<stationflow-tv>/tools/fakeflow.py FAKEFLOW_PORT=19524 \
        FAKEFLOW_PREFIX= FAKEFLOW_PASSWORD= python3 tools/fakeflow_face.py

fakeflow (stationflow-tv) is the Icom module Remote is tested against
without a radio. The module grew four things on 10 October 2026 that
fakeflow does not have (contract: icom docs/TV-API.md 6, 7a, 8a, 9;
~/build/nr2-zoom-scope/CONTRACT.md), and the face only offers a control
for what the module reports, so without them the new controls would never
be seen:

- **P.AMP and ATT** (`model.preamps`, `model.attenuators`, `preamp_modes`,
  `preamp_mode`, `att`, `preamp_modes_refused`), POST api/preamp and
  api/att. EXT is refused, as a 9700 whose menu has it off on 2 m.
- **Scope settings** (`model.scope`, `scope_settings`), POST api/scope,
  checked against the capabilities as the module checks them. The sweeps
  themselves are fakeflow's and do not change with the span.
- **NR2** (`nr2`), POST api/nr2.
- **CW** (`cw`, `cw_pitch`): a line of text while the radio is in CW or
  CW-R, as the module's decoder would read.
- **D-STAR** (`dv`, `dv_heard`) while in DV: a caller via a repeater, and
  two earlier overs.

A stand-in for testing only: it imports fakeflow and adds to it, so it
follows that file as it moves. Never reaches a radio and keys nothing:
fakeflow's transmit paths are untouched (403, logged at /fake/posts).
"""
from __future__ import annotations

import importlib.util
import os
import sys
import time

SRC = os.environ.get("FAKEFLOW_SRC", os.path.expanduser("~/stationflow-tv/tools/fakeflow.py"))
spec = importlib.util.spec_from_file_location("fakeflow", SRC)
ff = importlib.util.module_from_spec(spec)
sys.modules["fakeflow"] = ff
spec.loader.exec_module(ff)
web = ff.web

SCOPE_CAPS = {
    "spans_hz": [5000, 10000, 20000, 50000, 100000, 200000, 500000, 1000000],
    "modes": ["center", "fixed"], "edges": 3,
    "ref_db": {"min": -20.0, "max": 20.0, "step": 0.5},
    "speeds": ["fast", "mid", "slow"], "vbw": ["narrow", "wide"], "averaging": [0, 2, 3, 4],
}
PREAMP_MODES = ["OFF", "P.AMP", "EXT", "P.AMP+EXT"]

extra = {
    "scope_settings": {"receiver": 0, "mode": "center", "span_hz": 100000, "edge": 1, "ref_db": 0.0,
                       "speed": "mid", "vbw": "narrow", "averaging": 0},
    "nr2": {"available": True, "why": None, "on": False, "level": 50, "latency_ms": 21},
    "preamp_mode": 1, "att": 0,
}
CW_TEXT = "CQ CQ DE GM4BRI GM4BRI K  GM4BRI DE M0XYZ UR 599 599 NAME BOB QTH LEEDS BK"
started = time.time()


def dv_fields() -> dict:
    now = time.time()
    # A new over every 20 s, the call live for its first 8.
    t0 = now - (now - started) % 20
    last = {"caller": "M0XYZ", "suffix": "/M", "ur": "CQCQCQ", "rpt1": "GB7DV  B", "rpt2": "GB7DV  G",
            "message": "mobile on the A1", "receiving": now - t0 < 8, "packet_loss": False, "heard_at": t0}
    heard = [{k: v for k, v in last.items() if k not in ("receiving", "packet_loss", "heard_at")} | {"at": t0},
             {"caller": "GM4BRI", "suffix": "", "ur": "CQCQCQ", "rpt1": "", "rpt2": "", "message": "", "at": t0 - 95},
             {"caller": "2E0ABC", "suffix": "ID51", "ur": "CQCQCQ", "rpt1": "GB7EK  C", "rpt2": "GB7EK  G",
              "message": "testing the new hotspot", "at": t0 - 700}]
    return {"dv": last, "dv_heard": heard}


original_public = ff.Fake.public_state


def public_state(self) -> dict:
    out = original_public(self)
    for r in out.get("radios", {}).values():
        r["model"] = {**r.get("model", {}), "scope": SCOPE_CAPS, "preamps": ["P.AMP"], "attenuators": [10],
                      "preamp_modes": PREAMP_MODES}
        r.update(scope_settings=dict(extra["scope_settings"]), nr2=dict(extra["nr2"]),
                 preamp_modes=PREAMP_MODES, preamp_mode=extra["preamp_mode"], preamp=1 if extra["preamp_mode"] in (1, 3) else 0,
                 preamp_ext=extra["preamp_mode"] >= 2, att=extra["att"],
                 preamp_modes_refused={"2": "EXT P.AMP off in radio menu", "3": "EXT P.AMP off in radio menu"},
                 ext_preamp_menu={"144": False, "430": False, "1200": None}, ext_preamp_here=False)
        cw = r.get("mode") in ("CW", "CWR")
        # How much of the line has been "read": a letter every 0.3 s, round again.
        n = int((time.time() - started) / 0.3) % (len(CW_TEXT) + 1)
        r["cw_pitch"] = 600 if cw else None
        r["cw"] = {"active": cw, "available": True, "why": None, "pitch": 600, "pitch_source": "radio",
                   "wpm": 18.0 if cw and n > 10 else None, "text": CW_TEXT[:n] if cw else ""}
        if r.get("mode") == "DV":
            r.update(dv_fields())
        else:
            r.update(dv=None, dv_heard=dv_fields()["dv_heard"])
    return out


ff.Fake.public_state = public_state


def bad(why: str, code: str, status: int = 400):
    return web.json_response({"error": why, "code": code}, status=status)


async def set_scope(request):
    body = await request.json()
    ff.fake.posts.append({"path": request.path, "body": body})
    s = dict(extra["scope_settings"])
    for k, v in body.items():
        if k == "radio":
            continue
        if k == "span_hz" and v in SCOPE_CAPS["spans_hz"]:
            s[k] = v
        elif k == "mode" and v in SCOPE_CAPS["modes"]:
            s[k] = v
        elif k == "edge" and isinstance(v, int) and 1 <= v <= SCOPE_CAPS["edges"]:
            s[k] = v
        elif k == "ref_db" and isinstance(v, (int, float)) and -20 <= v <= 20:
            s[k] = round(v * 2) / 2
        elif k == "speed" and v in SCOPE_CAPS["speeds"]:
            s[k] = v
        elif k == "vbw" and v in SCOPE_CAPS["vbw"]:
            s[k] = v
        elif k == "averaging" and v in SCOPE_CAPS["averaging"]:
            s[k] = v
        else:
            return bad(f"{k}: not one of the allowed values", "bad_scope_setting")
    extra["scope_settings"] = s
    await ff.fake.push_state()
    return web.json_response({"ok": True, "scope_settings": s})


async def set_nr2(request):
    body = await request.json()
    ff.fake.posts.append({"path": request.path, "body": body})
    n = dict(extra["nr2"])
    if "on" not in body and "level" not in body:
        return bad("give on or level", "bad_nr2")
    if "on" in body:
        if not isinstance(body["on"], bool):
            return bad("on must be true or false", "bad_nr2")
        n["on"] = body["on"]
    if "level" in body:
        if not isinstance(body["level"], int) or not 0 <= body["level"] <= 100:
            return bad("level must be a whole number 0-100", "bad_nr2")
        n["level"] = body["level"]
    extra["nr2"] = n
    await ff.fake.push_state()
    return web.json_response({"ok": True, "nr2": n})


async def set_preamp(request):
    body = await request.json()
    ff.fake.posts.append({"path": request.path, "body": body})
    if "mode" in body:
        m = body["mode"]
        if not isinstance(m, int) or not 0 <= m < len(PREAMP_MODES):
            return bad("mode outside preamp_modes", "bad_preamp")
        if m >= 2:
            return bad("EXT P.AMP off in radio menu", "preamp_refused", 409)
        extra["preamp_mode"] = m
    elif "level" in body:
        extra["preamp_mode"] = 1 if body["level"] else 0
    await ff.fake.push_state()
    return web.json_response({"ok": True})


async def set_att(request):
    body = await request.json()
    ff.fake.posts.append({"path": request.path, "body": body})
    if body.get("db") not in (0, 10):
        return bad("db must be 0 or 10", "bad_att")
    extra["att"] = body["db"]
    await ff.fake.push_state()
    return web.json_response({"ok": True})


original_build = ff.build


def build(ca_pem=None):
    app = original_build(ca_pem)
    for prefix in {ff.PREFIX, ""} if ff.PREFIX else {""}:
        app.router.add_post(prefix + "/api/scope", set_scope)
        app.router.add_post(prefix + "/api/nr2", set_nr2)
        app.router.add_post(prefix + "/api/preamp", set_preamp)
        app.router.add_post(prefix + "/api/att", set_att)
    return app


ff.build = build


async def push_loop():
    # The CW line grows and the D-STAR call starts and ends by themselves: push the state as they do.
    while True:
        await ff.asyncio.sleep(1)
        await ff.fake.push_state()


original_main = ff.main


async def main():
    ff.asyncio.get_running_loop().create_task(push_loop())
    await original_main()


if __name__ == "__main__":
    ff.asyncio.run(main())
