#!/bin/bash
# Start the sound server. Runs inside the container.
#
# There is no sound card in the container, so PulseAudio plays into a
# virtual one ("Phone", a null sink) and streams what it plays as raw PCM
# (48 kHz, stereo, 16-bit) on 127.0.0.1:4713. The app reads that stream and
# plays it through Android's speaker or headphones (AudioBridge.kt).
#
# The other way, the phone's microphone is "Phone microphone", the default
# input: the app sends what the microphone hears (48 kHz, mono, 16-bit) to
# 127.0.0.1:4714, into a second null sink whose monitor the microphone
# source is made from (MicBridge.kt). linx-mic.sh tells the app when a
# Linux app is listening, so the microphone is only on while one is.
#
# Exits 0 after starting the server (the caller keeps the container alive
# for it), 1 when it is already running, 2 when PulseAudio is not installed.
set -u
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp/runtime-root}"
mkdir -p "$XDG_RUNTIME_DIR"

command -v pulseaudio >/dev/null 2>&1 || exit 2

MIC_MODULES=(
  "module-null-sink sink_name=micsink sink_properties=device.description=Microphone-input"
  "module-simple-protocol-tcp sink=micsink record=false playback=true listen=127.0.0.1 port=4714 rate=48000 channels=1 format=s16le"
  "module-remap-source master=micsink.monitor source_name=phonemic source_properties=device.description=Phone-microphone"
)
add_microphone() {
  pactl list short sources 2>/dev/null | grep -q phonemic && return 0
  local m
  for m in "${MIC_MODULES[@]}"; do
    # shellcheck disable=SC2086
    pactl load-module $m >/dev/null || return 1
  done
  pactl set-default-source phonemic
}

if pactl info >/dev/null 2>&1; then
  # Started by an older version: it gets the microphone now.
  add_microphone
  exit 1
fi

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
add_microphone
echo "AUDIO_READY"
exit 0
