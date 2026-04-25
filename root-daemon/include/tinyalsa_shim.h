#pragma once

#include <stdint.h>

#include <memory>
#include <string>
#include <vector>

namespace rootaudio {

enum class PcmFormat : uint32_t {
    kInvalid = 0,
    kS16Le = 1,
    kS24Le = 2,
    kS32Le = 3,
};

struct CardInfo {
    unsigned int card = 0;
    std::string id;
    std::string driver;
    std::string name;
};

struct StreamConfig {
    unsigned int card = 0;
    unsigned int device = 0;
    unsigned int sample_rate = 0;
    unsigned int channel_count = 0;
    unsigned int period_size = 0;
    unsigned int period_count = 0;
    PcmFormat format = PcmFormat::kInvalid;
};

class TinyAlsaPcmHandle {
public:
    TinyAlsaPcmHandle(void* shim, void* pcm, int (*pcm_close_fn)(void*));
    ~TinyAlsaPcmHandle();

    TinyAlsaPcmHandle(const TinyAlsaPcmHandle&) = delete;
    TinyAlsaPcmHandle& operator=(const TinyAlsaPcmHandle&) = delete;

    bool Write(const void* data, unsigned int byte_count, std::string* error_out);
    bool IsReady() const;

private:
    void* shim_;
    void* pcm_;
    int (*pcm_close_fn_)(void*);
};

class TinyAlsaShim {
public:
    TinyAlsaShim();
    ~TinyAlsaShim();

    TinyAlsaShim(const TinyAlsaShim&) = delete;
    TinyAlsaShim& operator=(const TinyAlsaShim&) = delete;

    bool IsAvailable() const;
    const std::string& LastError() const;
    std::vector<CardInfo> ListCards() const;
    std::unique_ptr<TinyAlsaPcmHandle> OpenPlayback(const StreamConfig& config, std::string* error_out);

private:
    void* library_ = nullptr;
    std::string last_error_;

    struct Functions;
    std::unique_ptr<Functions> functions_;
};

}  // namespace rootaudio
