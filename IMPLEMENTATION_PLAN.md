# Implementation Plan

## Goal

Build a minimal Android player that:
- loads local WAV/FLAC files
- decodes to PCM
- plays a Phase 1 verification path through `AudioTrack`
- evolves toward direct USB DAC playback through USB Host APIs and a native USB audio path

## Phases

### Phase 1

- Android app scaffold
- local file picker
- WAV reader
- PCM playback through `AudioTrack`

Deliverable:
- select a local WAV file and play it on-device

### Phase 2

- detect USB DAC devices with `UsbManager`
- handle attach and detach events
- identify USB Audio Class interfaces

Deliverable:
- show connected DAC summary in-app

### Phase 3

- parse USB audio descriptors
- extract alternate settings, endpoint addresses, channel count, sample rates, and bit depth

Deliverable:
- structured capability output for a connected DAC

### Phase 4

- add native PCM ring buffer
- separate decode and output responsibilities

Deliverable:
- stable producer/consumer PCM path

### Phase 5

- implement native USB streaming path
- package PCM into endpoint-sized transfers
- drive playback toward bit-perfect external output

Deliverable:
- PCM streamed to supported USB DAC hardware

### Phase 6

- sample-rate and format switching
- stop/reconfigure/restart output path per track

### Phase 7

- FLAC decode support

### Phase 8

- thread priorities
- underrun handling
- stability tuning
