#include "tinyalsa_shim.h"

#include <dlfcn.h>
#include <sys/stat.h>

#include <cstring>
#include <fstream>
#include <sstream>

namespace rootaudio {

namespace {

struct pcm;

constexpr int kPcmOut = 0x00000000;
constexpr int kPcmMonotonic = 0x00000080;

struct pcm_config {
    unsigned int channels;
    unsigned int rate;
    unsigned int period_size;
    unsigned int period_count;
    int format;
    unsigned int start_threshold;
    unsigned int stop_threshold;
    unsigned int silence_threshold;
};

int ToTinyAlsaFormat(PcmFormat format) {
    switch (format) {
        case PcmFormat::kS16Le:
            return 0;
        case PcmFormat::kS32Le:
            return 2;
        case PcmFormat::kS24Le:
            return 6;
        case PcmFormat::kInvalid:
        default:
            return -1;
    }
}

std::string ReadFirstLine(const std::string& path) {
    std::ifstream stream(path);
    if (!stream.is_open()) {
        return {};
    }
    std::string line;
    std::getline(stream, line);
    return line;
}

}  // namespace

struct TinyAlsaShim::Functions {
    pcm* (*pcm_open)(unsigned int, unsigned int, unsigned int, const pcm_config*);
    int (*pcm_close)(pcm*);
    int (*pcm_is_ready)(const pcm*);
    int (*pcm_write)(pcm*, const void*, unsigned int);
    const char* (*pcm_get_error)(const pcm*);
};

TinyAlsaPcmHandle::TinyAlsaPcmHandle(void* shim, void* pcm, int (*pcm_close_fn)(void*))
    : shim_(shim), pcm_(pcm), pcm_close_fn_(pcm_close_fn) {}

TinyAlsaPcmHandle::~TinyAlsaPcmHandle() {
    if (pcm_ == nullptr || pcm_close_fn_ == nullptr) {
        return;
    }
    pcm_close_fn_(pcm_);
    pcm_ = nullptr;
}

bool TinyAlsaPcmHandle::Write(const void* data, unsigned int byte_count, std::string* error_out) {
    auto* shim = static_cast<TinyAlsaShim*>(shim_);
    if (shim == nullptr || pcm_ == nullptr || !shim->IsAvailable()) {
        if (error_out != nullptr) {
            *error_out = "TinyALSA handle is unavailable";
        }
        return false;
    }
    auto* functions = shim->functions_.get();
    auto* pcm = static_cast<struct pcm*>(pcm_);
    if (functions->pcm_write(pcm, data, byte_count) != 0) {
        if (error_out != nullptr) {
            const char* error = functions->pcm_get_error != nullptr ? functions->pcm_get_error(pcm) : "pcm_write failed";
            *error_out = error != nullptr ? error : "pcm_write failed";
        }
        return false;
    }
    return true;
}

bool TinyAlsaPcmHandle::IsReady() const {
    auto* shim = static_cast<TinyAlsaShim*>(shim_);
    if (shim == nullptr || pcm_ == nullptr || !shim->IsAvailable()) {
        return false;
    }
    return shim->functions_->pcm_is_ready(static_cast<struct pcm*>(pcm_)) == 1;
}

TinyAlsaShim::TinyAlsaShim() : functions_(std::make_unique<Functions>()) {
    library_ = dlopen("libtinyalsa.so", RTLD_NOW);
    if (library_ == nullptr) {
        last_error_ = dlerror();
        functions_.reset();
        return;
    }

    functions_->pcm_open = reinterpret_cast<pcm* (*)(unsigned int, unsigned int, unsigned int, const pcm_config*)>(
        dlsym(library_, "pcm_open"));
    functions_->pcm_close = reinterpret_cast<int (*)(pcm*)>(dlsym(library_, "pcm_close"));
    functions_->pcm_is_ready = reinterpret_cast<int (*)(const pcm*)>(dlsym(library_, "pcm_is_ready"));
    functions_->pcm_write = reinterpret_cast<int (*)(pcm*, const void*, unsigned int)>(dlsym(library_, "pcm_write"));
    functions_->pcm_get_error = reinterpret_cast<const char* (*)(const pcm*)>(dlsym(library_, "pcm_get_error"));

    if (functions_->pcm_open == nullptr ||
        functions_->pcm_close == nullptr ||
        functions_->pcm_is_ready == nullptr ||
        functions_->pcm_write == nullptr ||
        functions_->pcm_get_error == nullptr) {
        last_error_ = "libtinyalsa.so is missing required symbols";
        dlclose(library_);
        library_ = nullptr;
        functions_.reset();
    }
}

TinyAlsaShim::~TinyAlsaShim() {
    if (library_ != nullptr) {
        dlclose(library_);
        library_ = nullptr;
    }
}

bool TinyAlsaShim::IsAvailable() const {
    return library_ != nullptr && functions_ != nullptr;
}

const std::string& TinyAlsaShim::LastError() const {
    return last_error_;
}

std::vector<CardInfo> TinyAlsaShim::ListCards() const {
    std::vector<CardInfo> cards;
    for (unsigned int card = 0; card < 32; ++card) {
        std::ostringstream card_prefix;
        card_prefix << "/proc/asound/card" << card;
        struct stat info {};
        if (stat(card_prefix.str().c_str(), &info) != 0) {
            continue;
        }

        CardInfo card_info;
        card_info.card = card;
        card_info.id = ReadFirstLine(card_prefix.str() + "/id");
        card_info.driver = ReadFirstLine(card_prefix.str() + "/pcm0p/sub0/hw_params");
        card_info.name = ReadFirstLine(card_prefix.str() + "/usbid");
        if (card_info.name.empty()) {
            card_info.name = ReadFirstLine(card_prefix.str() + "/id");
        }
        cards.push_back(card_info);
    }
    return cards;
}

std::unique_ptr<TinyAlsaPcmHandle> TinyAlsaShim::OpenPlayback(
    const StreamConfig& config,
    std::string* error_out) {
    if (!IsAvailable()) {
        if (error_out != nullptr) {
            *error_out = last_error_.empty() ? "libtinyalsa.so is unavailable" : last_error_;
        }
        return nullptr;
    }

    const int tinyalsa_format = ToTinyAlsaFormat(config.format);
    if (tinyalsa_format < 0) {
        if (error_out != nullptr) {
            *error_out = "Unsupported PCM format requested";
        }
        return nullptr;
    }

    pcm_config pcm_cfg = {};
    pcm_cfg.channels = config.channel_count;
    pcm_cfg.rate = config.sample_rate;
    pcm_cfg.period_size = config.period_size;
    pcm_cfg.period_count = config.period_count;
    pcm_cfg.format = tinyalsa_format;
    pcm_cfg.start_threshold = 0;
    pcm_cfg.stop_threshold = 0;
    pcm_cfg.silence_threshold = 0;

    auto* pcm_handle = functions_->pcm_open(config.card, config.device, kPcmOut | kPcmMonotonic, &pcm_cfg);
    if (pcm_handle == nullptr || functions_->pcm_is_ready(pcm_handle) != 1) {
        if (error_out != nullptr) {
            const char* error = pcm_handle != nullptr ? functions_->pcm_get_error(pcm_handle) : "pcm_open failed";
            *error_out = error != nullptr ? error : "pcm_open failed";
        }
        if (pcm_handle != nullptr) {
            functions_->pcm_close(pcm_handle);
        }
        return nullptr;
    }

    return std::make_unique<TinyAlsaPcmHandle>(
        this,
        pcm_handle,
        reinterpret_cast<int (*)(void*)>(functions_->pcm_close));
}

}  // namespace rootaudio
