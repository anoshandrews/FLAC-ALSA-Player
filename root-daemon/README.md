# Root Audio Daemon

This directory contains the rooted playback path for exclusive USB DAC output.

## Purpose

The Android app can decode local audio, but true mixer-bypassed playback on rooted devices should go through ALSA hardware directly instead of `AudioTrack`.

This daemon is intended to:
- run as root
- discover ALSA cards
- open the USB DAC PCM device through `libtinyalsa.so`
- accept PCM from the app over a Unix domain socket
- write PCM directly to the DAC's ALSA device

## Expected runtime model

1. Build `root_audio_daemon`.
2. Push it to the device, for example under `/data/local/tmp/`.
3. Start it with `su`.
4. The Android app connects to `/data/local/tmp/music_player_root_audio.sock`.
5. The app sends `OPEN_STREAM`, then repeated `WRITE_PCM` messages.

## Current status

- socket protocol: implemented
- card enumeration: implemented from `/proc/asound`
- runtime `libtinyalsa.so` loading: implemented
- direct PCM write path: implemented
- deployment and device-specific validation: still required

## Important limits

- This path assumes a rooted device.
- `libtinyalsa.so` must be present and loadable on the target device.
- Card/device discovery may need to be specialized for a given phone and DAC.
- Exact ALSA parameters still need validation on real hardware.
