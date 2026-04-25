#!/bin/sh

set -eu

if [ $# -lt 1 ]; then
  echo "usage: $0 <path-to-root_audio_daemon-binary>"
  exit 1
fi

BINARY_PATH=$1
REMOTE_PATH=/data/local/tmp/root_audio_daemon

adb push "$BINARY_PATH" "$REMOTE_PATH"
adb shell su -c "chmod 755 $REMOTE_PATH"
adb shell su -c "pkill -f root_audio_daemon || true"
adb shell su -c "$REMOTE_PATH >/data/local/tmp/root_audio_daemon.log 2>&1 &"
echo "daemon started at $REMOTE_PATH"
