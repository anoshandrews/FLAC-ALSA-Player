# AGENTS.md

This workspace is for a minimal Android USB audio player focused on bit-perfect local playback to an external USB DAC.

## Default agent rules

- Keep changes minimal and directly tied to the current task.
- Prefer updating existing files over adding new ones unless a new file is necessary.
- Before ending a run, update `STATUS.md`.
- After writing an implementation plan, continue by breaking work into explicit tasks with a model assignment for each task.
- `STATUS.md` must stay minimal:
  - current objective
  - what was done this run
  - immediate next step
- Do not add long plans, background, or duplicated notes to `STATUS.md`.
- Be deliberate about token use. Do not spend flagship-model tokens on routine coding work.

## Model routing

- Use `gpt-5.4` for planning, architecture, task decomposition, and high-ambiguity decisions.
- Use `gpt-5.3` or `gpt-5.2` for coding, implementation, debugging, and bounded execution tasks.
- Choose the smallest sufficient model for each task.
- Prefer cheaper coding models once the task is well specified.
- Keep prompts compact and scoped so token usage stays controlled across runs.

## Project direction

- MVP goal: local WAV/FLAC playback to a USB DAC with a final output path that bypasses Android's mixer path.
- Rooted-device target: add a root ALSA daemon path that talks directly to USB DAC ALSA hardware instead of `AudioTrack`.
- Near-term implementation order:
  1. Android app skeleton
  2. local file loading
  3. WAV decode path
  4. baseline `AudioTrack` verification path
  5. USB device detection and descriptor parsing
  6. native PCM buffer and USB streaming path
  7. rooted ALSA daemon path for exclusive USB DAC playback

## Working note

- If the repo is still empty, establish only the smallest scaffold needed for the current requested step.
