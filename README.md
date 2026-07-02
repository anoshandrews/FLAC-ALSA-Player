````markdown
# FLAC ALSA Player

A lightweight command-line FLAC player written in C that decodes FLAC audio and streams it directly to ALSA for low-latency playback on Linux.

## Features

- 🎵 Native FLAC playback
- ⚡ Direct ALSA audio output
- 🖥️ Minimal terminal-based interface
- 🚀 Lightweight with minimal dependencies
- 🐧 Built specifically for Linux

## Tech Stack

- C
- ALSA (Advanced Linux Sound Architecture)
- libFLAC

## Project Structure

```
.
├── src/            # Source files
├── include/        # Header files
├── Makefile
└── README.md
```

## Prerequisites

Install the required development packages.

### Ubuntu / Debian

```bash
sudo apt install build-essential libasound2-dev libflac-dev
```

### Arch Linux

```bash
sudo pacman -S base-devel alsa-lib flac
```

### Fedora

```bash
sudo dnf install gcc make alsa-lib-devel flac-devel
```

## Build

```bash
make
```

## Run

```bash
./flac-alsa-player <path-to-audio.flac>
```

Example:

```bash
./flac-alsa-player music/song.flac
```

## Motivation

This project was built to better understand:

- Audio decoding using libFLAC
- PCM audio pipelines
- ALSA programming
- Low-level systems programming in C
- Memory management and streaming audio buffers

## Future Improvements

- Playlist support
- Directory playback
- Pause / Resume
- Seek support
- Volume control
- Gapless playback
- WAV and other PCM formats
- TUI (Terminal User Interface)

## License

This project is licensed under the MIT License.

---

If you find this project useful, feel free to ⭐ the repository.
````
