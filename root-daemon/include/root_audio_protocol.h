#pragma once

#include <stdint.h>

namespace rootaudio {

constexpr const char* kSocketPath = "/data/local/tmp/music_player_root_audio.sock";
constexpr uint32_t kProtocolVersion = 1;
constexpr uint32_t kMagic = 0x4D505241;  // MPRA

enum class Command : uint32_t {
    kPing = 1,
    kOpenStream = 2,
    kWritePcm = 3,
    kStop = 4,
    kListCards = 5,
    kStatus = 6,
};

enum class Status : uint32_t {
    kOk = 0,
    kError = 1,
    kUnsupported = 2,
};

struct MessageHeader {
    uint32_t magic;
    uint32_t version;
    uint32_t command;
    uint32_t payload_size;
};

struct OpenStreamRequest {
    uint32_t card;
    uint32_t device;
    uint32_t sample_rate;
    uint32_t channel_count;
    uint32_t period_size;
    uint32_t period_count;
    uint32_t pcm_format;
};

struct StatusResponse {
    uint32_t status;
    uint32_t detail_code;
};

}  // namespace rootaudio
