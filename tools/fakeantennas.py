"""Radio's SDR antenna inputs (openhamshack-nb SERVER-API, "What it is not
(yet)": soapyAntenna, soapyAntennaLabels, deviceStatus.antenna/antennas),
for fakeshack to lay over the simulator, which opens no SoapySDR device
and so reports no inputs: with this, Remote's antenna picker can be built
and tested against what an SDRplay RSPdx on ohs-box reports.

What it makes Radio look like:

  * the inputs an RSPdx's driver (SoapySDRPlay3) names, "Antenna A",
    "Antenna B" and "Antenna C", with "Antenna C" (its HF socket) in use;
  * the operator's labels A "Diamond X7000", B "Unused", C "Loop Antenna",
    kept in soapyAntennaLabels as the server keeps them (one line of JSON,
    ohscore/antennas.py format_labels);
  * all of it wherever Remote reads it: GET api/state, and every `state`
    message on the events socket that carries deviceStatus or either
    setting (the simulator's own deviceStatus fields are kept; only the
    antenna keys are laid over them);
  * POST api/set with soapyAntenna and/or soapyAntennaLabels applied here,
    with a `state` message for the change sent to every Radio events
    socket, as the engine publishes one. "" is the driver's default, which
    on an RSPdx is "Antenna A". An input the radio does not have is kept
    as the setting but not applied, and toasted as the engine toasts it
    ("Antenna not changed: ...") - the server takes any string through
    api/set and refuses only at the device.

The server's shapes are copied, not imported (this repo does not carry
openhamshack-nb): a change there must be made here too. Nothing here
transmits; an antenna input is receive-only.
"""
from __future__ import annotations

import asyncio
import json

INPUTS = ["Antenna A", "Antenna B", "Antenna C"]
#: SoapySDRPlay3 opens an RSPdx on its first input when nothing is asked for.
DRIVER_DEFAULT = "Antenna A"
KEYS = ("soapyAntenna", "soapyAntennaLabels")


def format_labels(labels: dict[str, str]) -> str:
    """ohscore/antennas.py format_labels: one line, no empty labels, "" for none."""
    clean = {k: v for k, v in labels.items() if v}
    return json.dumps(clean, ensure_ascii=False, separators=(",", ":")) if clean else ""


class Antennas:
    def __init__(self):
        self.chosen = "Antenna C"
        self.labels = format_labels({"Antenna A": "Diamond X7000", "Antenna B": "Unused", "Antenna C": "Loop Antenna"})
        self.current = self.chosen
        self.listeners: set[asyncio.Queue] = set()

    def device(self, ds) -> dict:
        """The device's report with the antenna keys laid over it."""
        return dict(ds if isinstance(ds, dict) else {}, antenna=self.current, antennas=list(INPUTS))

    def settings(self) -> dict:
        return {"soapyAntenna": self.chosen, "soapyAntennaLabels": self.labels}

    def state(self, st: dict) -> dict:
        """GET api/state's answer, with the inputs, the setting and the labels."""
        return dict(st, deviceStatus=self.device(st.get("deviceStatus")), **self.settings())

    def changes(self, ch: dict) -> dict:
        """A `state` message's changes: the simulator's own soapyAntenna and
        labels ("" for both) must not undo ours, and a deviceStatus it
        sends whole must keep the inputs."""
        out = dict(ch)
        if "deviceStatus" in out:
            out["deviceStatus"] = self.device(out["deviceStatus"])
        for k in KEYS:
            if k in out:
                out[k] = self.settings()[k]
        return out

    @staticmethod
    def split(changes: dict) -> tuple[dict, dict]:
        """(the antenna keys, everything else) of an api/set's changes."""
        return ({k: v for k, v in changes.items() if k in KEYS},
                {k: v for k, v in changes.items() if k not in KEYS})

    @staticmethod
    def check(mine: dict) -> str | None:
        """A sentence for a value the server's registry would not coerce
        (ohscore/state.py coerce, type str: text, one line, at most 200
        characters), else None."""
        for k, v in mine.items():
            if not isinstance(v, str) or "\n" in v or len(v) > 200:
                return f"{k} must be a str, not {v!r}"
        return None

    def apply(self, mine: dict, device: dict) -> dict:
        """Take the keys, as Radio.set does; the `changed` it answers with.
        [device] is the simulator's last deviceStatus, under ours."""
        changed = {k: v for k, v in mine.items() if self.settings()[k] != v}
        toast = None
        if "soapyAntenna" in changed:
            self.chosen = changed["soapyAntenna"]
            want = self.chosen.strip() or DRIVER_DEFAULT
            if want in INPUTS:
                self.current = want
            else:
                toast = (f"Antenna not changed: This SDR has no antenna input called {want!r} "
                         f"(it has: {', '.join(INPUTS)})")
        if "soapyAntennaLabels" in changed:
            self.labels = changed["soapyAntennaLabels"]
        if changed:
            ch = dict(changed)
            if "soapyAntenna" in changed:
                ch["deviceStatus"] = self.device(device)
            self._send({"kind": "state", "changes": ch})
        if toast:
            self._send({"kind": "toast", "text": toast, "level": "warn"})
        return changed

    def _send(self, msg: dict):
        for q in list(self.listeners):
            q.put_nowait(json.dumps(msg))
