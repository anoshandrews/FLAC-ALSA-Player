# Task Model Map

Use `gpt-5.4` only where architecture or ambiguity justifies it. Use `gpt-5.3` or `gpt-5.2` for routine implementation.

## Planning

- RFC updates, architecture decisions, USB pipeline design: `gpt-5.4`
- task decomposition and acceptance criteria: `gpt-5.4`

## Coding

- Android project scaffold and Gradle files: `gpt-5.2`
- basic Kotlin UI and file picker: `gpt-5.2`
- WAV header parser and PCM extraction: `gpt-5.3`
- `AudioTrack` playback controller: `gpt-5.3`
- USB detection and descriptor parsing Kotlin code: `gpt-5.3`
- NDK ring buffer and USB engine code: `gpt-5.3`
- narrowly scoped bug fixes and refactors: `gpt-5.2`

## Verification

- code review of audio/USB boundary logic: `gpt-5.4`
- routine test fixes and build-error iteration: `gpt-5.2`
