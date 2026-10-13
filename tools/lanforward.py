#!/usr/bin/env python3
"""Forward one TCP port on the LAN to the fakebox's remote port on loopback.

fakebox listens on 127.0.0.1 only, on purpose: its 18080 answers as the box's
own screen and is always authorised, so it must never be reachable from
anywhere else. A real TV or tablet on the LAN needs a box to talk to, so this
exposes just the "browser elsewhere" port (18081, PIN rules apply) on one LAN
address. Plain asyncio rather than socat because shack has no socat, and a
long-lived stream.ts connection is just bytes both ways.

    lanforward.py 198.51.100.208:18181 127.0.0.1:18081
"""
import asyncio
import sys


async def pipe(r, w):
    try:
        while (b := await r.read(65536)):
            w.write(b)
            await w.drain()
    except Exception:
        pass
    finally:
        w.close()


def main():
    listen, target = sys.argv[1], sys.argv[2]
    lhost, lport = listen.rsplit(":", 1)
    thost, tport = target.rsplit(":", 1)

    async def handle(cr, cw):
        try:
            ur, uw = await asyncio.open_connection(thost, int(tport))
        except Exception:
            cw.close()
            return
        await asyncio.gather(pipe(cr, uw), pipe(ur, cw))

    async def serve():
        s = await asyncio.start_server(handle, lhost, int(lport))
        async with s:
            await s.serve_forever()

    asyncio.run(serve())


if __name__ == "__main__":
    main()
