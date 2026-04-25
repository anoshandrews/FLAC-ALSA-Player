#include "root_audio_protocol.h"
#include "tinyalsa_shim.h"

#include <android/log.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#include <cerrno>
#include <cstdarg>
#include <cstdint>
#include <cstring>
#include <memory>
#include <optional>
#include <string>
#include <vector>

namespace {

constexpr const char* kLogTag = "root_audio_daemon";

void Log(const char* format, ...) {
    va_list args;
    va_start(args, format);
    __android_log_vprint(ANDROID_LOG_INFO, kLogTag, format, args);
    va_end(args);
}

bool ReadFully(int fd, void* buffer, size_t size) {
    auto* bytes = static_cast<uint8_t*>(buffer);
    size_t offset = 0;
    while (offset < size) {
        const ssize_t read_count = TEMP_FAILURE_RETRY(read(fd, bytes + offset, size - offset));
        if (read_count <= 0) {
            return false;
        }
        offset += static_cast<size_t>(read_count);
    }
    return true;
}

bool WriteFully(int fd, const void* buffer, size_t size) {
    const auto* bytes = static_cast<const uint8_t*>(buffer);
    size_t offset = 0;
    while (offset < size) {
        const ssize_t write_count = TEMP_FAILURE_RETRY(write(fd, bytes + offset, size - offset));
        if (write_count <= 0) {
            return false;
        }
        offset += static_cast<size_t>(write_count);
    }
    return true;
}

rootaudio::StatusResponse MakeStatus(rootaudio::Status status, uint32_t detail_code = 0) {
    return rootaudio::StatusResponse{
        .status = static_cast<uint32_t>(status),
        .detail_code = detail_code,
    };
}

class RootAudioDaemon {
public:
    int Run() {
        if (!tinyalsa_.IsAvailable()) {
            Log("TinyALSA unavailable: %s", tinyalsa_.LastError().c_str());
        }

        unlink(rootaudio::kSocketPath);

        int server_fd = socket(AF_UNIX, SOCK_STREAM, 0);
        if (server_fd < 0) {
            Log("socket failed: %s", strerror(errno));
            return 1;
        }

        sockaddr_un address = {};
        address.sun_family = AF_UNIX;
        std::strncpy(address.sun_path, rootaudio::kSocketPath, sizeof(address.sun_path) - 1);

        if (bind(server_fd, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0) {
            Log("bind failed: %s", strerror(errno));
            close(server_fd);
            return 1;
        }
        chmod(rootaudio::kSocketPath, 0660);

        if (listen(server_fd, 4) != 0) {
            Log("listen failed: %s", strerror(errno));
            close(server_fd);
            return 1;
        }

        Log("root audio daemon listening on %s", rootaudio::kSocketPath);
        while (true) {
            const int client_fd = accept(server_fd, nullptr, nullptr);
            if (client_fd < 0) {
                if (errno == EINTR) {
                    continue;
                }
                Log("accept failed: %s", strerror(errno));
                break;
            }
            HandleClient(client_fd);
            close(client_fd);
        }

        close(server_fd);
        return 0;
    }

private:
    void HandleClient(int client_fd) {
        while (true) {
            rootaudio::MessageHeader header = {};
            if (!ReadFully(client_fd, &header, sizeof(header))) {
                return;
            }
            if (header.magic != rootaudio::kMagic || header.version != rootaudio::kProtocolVersion) {
                const auto response = MakeStatus(rootaudio::Status::kError, 100);
                WriteFully(client_fd, &response, sizeof(response));
                return;
            }

            std::vector<uint8_t> payload(header.payload_size);
            if (header.payload_size > 0 && !ReadFully(client_fd, payload.data(), payload.size())) {
                return;
            }

            switch (static_cast<rootaudio::Command>(header.command)) {
                case rootaudio::Command::kPing:
                    Reply(client_fd, MakeStatus(rootaudio::Status::kOk));
                    break;
                case rootaudio::Command::kOpenStream:
                    HandleOpenStream(client_fd, payload);
                    break;
                case rootaudio::Command::kWritePcm:
                    HandleWritePcm(client_fd, payload);
                    break;
                case rootaudio::Command::kStop:
                    active_stream_.reset();
                    Reply(client_fd, MakeStatus(rootaudio::Status::kOk));
                    break;
                case rootaudio::Command::kListCards:
                    HandleListCards(client_fd);
                    break;
                case rootaudio::Command::kStatus:
                    Reply(client_fd, MakeStatus(rootaudio::Status::kOk, active_stream_ ? 1 : 0));
                    break;
            }
        }
    }

    void HandleOpenStream(int client_fd, const std::vector<uint8_t>& payload) {
        if (payload.size() != sizeof(rootaudio::OpenStreamRequest)) {
            Reply(client_fd, MakeStatus(rootaudio::Status::kError, 101));
            return;
        }
        if (!tinyalsa_.IsAvailable()) {
            Reply(client_fd, MakeStatus(rootaudio::Status::kUnsupported, 102));
            return;
        }

        rootaudio::OpenStreamRequest request = {};
        std::memcpy(&request, payload.data(), sizeof(request));

        rootaudio::StreamConfig config = {};
        config.card = request.card;
        config.device = request.device;
        config.sample_rate = request.sample_rate;
        config.channel_count = request.channel_count;
        config.period_size = request.period_size;
        config.period_count = request.period_count;
        config.format = static_cast<rootaudio::PcmFormat>(request.pcm_format);

        std::string error;
        auto handle = tinyalsa_.OpenPlayback(config, &error);
        if (handle == nullptr) {
            Log("open stream failed: %s", error.c_str());
            Reply(client_fd, MakeStatus(rootaudio::Status::kError, 103));
            return;
        }

        active_stream_ = std::move(handle);
        Reply(client_fd, MakeStatus(rootaudio::Status::kOk));
    }

    void HandleWritePcm(int client_fd, const std::vector<uint8_t>& payload) {
        if (!active_stream_) {
            Reply(client_fd, MakeStatus(rootaudio::Status::kError, 104));
            return;
        }
        std::string error;
        if (!active_stream_->Write(payload.data(), static_cast<unsigned int>(payload.size()), &error)) {
            Log("pcm write failed: %s", error.c_str());
            Reply(client_fd, MakeStatus(rootaudio::Status::kError, 105));
            return;
        }
        Reply(client_fd, MakeStatus(rootaudio::Status::kOk, static_cast<uint32_t>(payload.size())));
    }

    void HandleListCards(int client_fd) {
        const auto cards = tinyalsa_.ListCards();
        std::string serialized;
        for (const auto& card : cards) {
            serialized += std::to_string(card.card);
            serialized += "|";
            serialized += card.id;
            serialized += "|";
            serialized += card.name;
            serialized += "\n";
        }
        const auto status = MakeStatus(rootaudio::Status::kOk, static_cast<uint32_t>(serialized.size()));
        Reply(client_fd, status, serialized.data(), serialized.size());
    }

    void Reply(int fd, const rootaudio::StatusResponse& response, const void* payload = nullptr, size_t payload_size = 0) {
        WriteFully(fd, &response, sizeof(response));
        if (payload != nullptr && payload_size > 0) {
            WriteFully(fd, payload, payload_size);
        }
    }

    rootaudio::TinyAlsaShim tinyalsa_;
    std::unique_ptr<rootaudio::TinyAlsaPcmHandle> active_stream_;
};

}  // namespace

int main() {
    RootAudioDaemon daemon;
    return daemon.Run();
}
