#!/usr/bin/env python3
"""Flex's own pretend radio with the receive controls Remote's Flex kind needs.

    FAKEFLEX_DIR=<flex checkout>/flex python3 tools/fakeflex_rx.py \
        [--port 4992] [--extended-dsp] [--audio tone|ft8|ft4|sstv] [--log FILE]
        [--rfgain-info LOW,HIGH,STEP[,MARKERS]|none]

The Flex module's `tools/fakeflex.py` (dock360-apps, flex line) is the radio
Remote is tested against, through the real module. As of flex 5b9a5d4 it
plays the DSP (the five published functions, and the newer six with
--extended-dsp) and audio that decodes (FT8/FT4 or SSTV), but two things a
real FLEX-6000 reports are missing from its slice status, so a client's
controls for them have nothing to show:

- **Antennas.** A radio sends `rxant=ANT1` and the list it can receive on,
  `ant_list=ANT1,ANT2,RX_A,RX_B,XVTA` (FlexLib's Slice parses both; the
  module keeps them as `rx_ant` and `ant_list`). This fake reports a
  FLEX-6400's list.
- **AGC.** `agc_mode` and `agc_threshold` in the status, and a `slice set`
  of either remembered, as a radio does.
- **RF gain** (the preamp, or the attenuator below 0 dB) is the module's
  fake's own since flex f81678a: a panadapter setting, `display pan
  rfgain_info` answered as a FLEX-6400 (-8 to 32 dB in 8s), which the
  module publishes as `rf_gain_steps`. This fake passes `--rfgain-info`
  through - `--rfgain-info=-10,30,10,0,20` (the `=` matters: argparse reads
  a bare -10 as an option) for a radio with another ladder, `none` for
  firmware that will not say, so a client falls back to its own table - and
  refuses a `display pan set ... rfgain=` that is not on the ladder, as a
  radio refuses a value it will not take; the module's fake takes any.

Like the real radio (PROTOCOL.md 1) nothing is echoed to the client that
made a change: the module's own state has to show it, or the client does.

It also lays several FT8/FT4 stations into each slot rather than the one
`--message` gives (a decoder panel with one line in it says little), one of
them between fakeshack's station (M0TST) and GM4BRI, the callsign Flex's
own fakecontroller gives the module's station profile, so the "names you"
colour can be seen. A stand-in for testing only: it subclasses the module's fake and
changes nothing there, so it follows that file as it moves. Never reaches a
radio; keys nothing (the parent's transmit paths are untouched and log to
--log).
"""

from __future__ import annotations

import argparse
import asyncio
import os
import sys
from pathlib import Path

FLEX_DIR = Path(os.environ.get("FAKEFLEX_DIR", Path.home() / "dock360-apps-flex" / "flex"))
sys.path.insert(0, str(FLEX_DIR / "tools"))
sys.path.insert(0, str(FLEX_DIR))

import fakeflex  # noqa: E402  (the module's own fake, found through FAKEFLEX_DIR)

#: A FLEX-6400's receive antennas, as its slice status lists them.
ANTENNAS = "ANT1,ANT2,RX_A,RX_B,XVTA"

#: (audio Hz, message) for each pretend station in a slot.
STATIONS = ((620, "CQ EA8XYZ IL18"), (1100, "M0TST GM4BRI IO75"), (1500, "CQ DX OH2ABC KP20"),
            (1870, "G4ABC DL1XYZ -12"), (2310, "CQ K1ABC FN42"))


class _Stations:
    """Several stations in each slot on the UTC clock, at different audio
    frequencies, made by ohsdigi (the family's decoder) as the module's own
    fake makes its one."""

    def __init__(self, mode: str) -> None:
        import array

        import ohsdigi

        self.period = fakeflex._Slotted.PERIODS[mode]
        waves = []
        for hz, text in STATIONS:
            w = array.array("f")
            w.frombytes(ohsdigi.encode_audio(mode, text, float(hz), sample_rate=24000.0))
            waves.append(w)
        n = max(len(w) for w in waves)
        self.wave = [sum(w[k] for w in waves if k < len(w)) / len(waves) * 1.6 for k in range(n)]

    def sample(self, t: float) -> float:
        k = int(((t % self.period) - 0.5) * 24000)
        return self.wave[k] if 0 <= k < len(self.wave) else 0.0


class FakeFlexRx(fakeflex.FakeFlex):
    def _rf_gains(self) -> list[int] | None:
        """The settings the rfgain_info answer allows, or None when it gives none to check against."""
        try:
            low, high, step = (int(float(x)) for x in (self.rfgain_info or "").split(",")[:3])
        except ValueError:
            return None
        return list(range(low, high + 1, step)) if step > 0 and high >= low else None

    def _signal_source(self):
        if self.audio_kind in ("ft8", "ft4"):
            try:
                return _Stations(self.audio_kind)
            except ImportError as exc:
                print("fakeflex_rx: %s, so the audio is the tone" % exc, file=sys.stderr, flush=True)
                return None
        return super()._signal_source()

    def _answer(self, client, command):
        words = command.split()
        if command.startswith("slice set") and len(words) > 2 and words[2].isdigit():
            index = int(words[2])
            for token in words[3:]:
                key, _s, value = token.partition("=")
                if key == "rxant" and value not in ANTENNAS.split(","):
                    # What a radio says to an antenna it does not have.
                    return "5000002D", ""
                if key in ("rxant", "agc_mode", "agc_threshold") and index in self.slices:
                    self.slices[index].setdefault("rx", {})[key] = value
        if command.startswith("display pan set"):
            gains = self._rf_gains()
            for token in words[4:]:
                key, _s, value = token.partition("=")
                if key == "rfgain" and gains is not None and value.lstrip("-").isdigit() and int(value) not in gains:
                    return "5000002D", ""
        return super()._answer(client, command)

    def _status(self, client, text):
        # The parent writes a slice's whole status in one line starting
        # "slice <n> in_use=1"; the radio's antenna and AGC keys go on its end
        # (RF gain is the panadapter's, in the module's fake's pan status).
        words = text.split()
        if len(words) > 2 and words[0] == "slice" and words[1].isdigit() and words[2] == "in_use=1":
            rx = {"rxant": "ANT1", "agc_mode": "med", "agc_threshold": "65"}
            rx.update(self.slices.get(int(words[1]), {}).get("rx", {}))
            text += " ant_list=%s %s" % (ANTENNAS, " ".join("%s=%s" % kv for kv in rx.items()))
        super()._status(client, text)


async def serve(args) -> None:
    log = open(args.log, "a", encoding="utf-8") if args.log else None
    fake = FakeFlexRx(log=log, extended_dsp=args.extended_dsp, audio=args.audio,
                      rfgain_info=None if args.rfgain_info == "none" else args.rfgain_info)
    port = await fake.start(args.host, args.port)
    print("fakeflex_rx: SmartSDR on %s:%d (TCP), antennas %s, audio %s, rfgain_info %s"
          % (args.host, port, ANTENNAS, args.audio, args.rfgain_info), flush=True)
    await asyncio.Event().wait()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=4992)
    ap.add_argument("--log", default="", help="append PTT ON/OFF lines here")
    ap.add_argument("--extended-dsp", action="store_true")
    ap.add_argument("--audio", choices=("tone", "ft8", "ft4", "sstv"), default="tone")
    ap.add_argument("--rfgain-info", default=fakeflex.FLEX_6400_RFGAIN,
                    help="the radio's rfgain_info reply (low,high,step,markers), or none")
    try:
        asyncio.run(serve(ap.parse_args()))
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
