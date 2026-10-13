#!/bin/sh
# Install the test fakebox as user services that start at boot. Linger is
# what lets user services run with nobody logged in; without it they only
# start at the first login after a reboot.
set -e
cd "$(dirname "$0")"
mkdir -p ~/.config/systemd/user ~/.config/openhamshack-fakebox
cp openhamshack-fakebox.service openhamshack-fakebox-lan.service ~/.config/systemd/user/
[ -f ~/.config/openhamshack-fakebox/env ] || cat > ~/.config/openhamshack-fakebox/env <<ENV
FAKEBOX_FILE=$HOME/build/fakebox-media/bbb_trailer_1080p.mov
FAKEBOX_CODEC=hevc
FAKEBOX_CORRUPT=0.02
FAKEBOX_LAN_LISTEN=198.51.100.208:18181
ENV
sudo loginctl enable-linger "$(id -un)"
systemctl --user daemon-reload
systemctl --user enable openhamshack-fakebox.service openhamshack-fakebox-lan.service
