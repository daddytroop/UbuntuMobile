#!/bin/bash
# Start the sound server. Runs inside the container.
#
# There is no sound card in the container, so PulseAudio plays into a
# virtual one ("Phone", a null sink) and streams what it plays as raw PCM
# (48 kHz, stereo, 16-bit) on 127.0.0.1:4713. The app reads that stream and
# plays it through Android's speaker or headphones (AudioBridge.kt).
#
# Exits 0 after starting the server (the caller keeps the container alive
# for it), 1 when it is already running, 2 when PulseAudio is not installed.
set -u
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp/runtime-root}"
mkdir -p "$XDG_RUNTIME_DIR"

command -v pulseaudio >/dev/null 2>&1 || exit 2
pactl info >/dev/null 2>&1 && exit 1

# The clients must not use shared memory either (see --disable-shm), and
# must not start a server of their own.
mkdir -p /etc/pulse/client.conf.d
printf 'enable-shm = no\nautospawn = no\n' >/etc/pulse/client.conf.d/linx.conf

pulseaudio --daemonize=yes -n --exit-idle-time=-1 --disable-shm=yes \
  --log-target=file:/tmp/linx-pulseaudio.log \
  --load="module-null-sink sink_name=phone sink_properties=device.description=Phone" \
  --load="module-native-protocol-unix auth-anonymous=1" \
  --load="module-simple-protocol-tcp source=phone.monitor record=true playback=false listen=127.0.0.1 port=4713 rate=48000 channels=2 format=s16le" \
  || exit 3
for _ in 1 2 3 4 5; do
  pactl set-default-sink phone 2>/dev/null && break
  sleep 1
done
echo "AUDIO_READY"
exit 0
