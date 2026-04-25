#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <vector>

namespace {

class PCMBuffer {
public:
    explicit PCMBuffer(size_t capacity_bytes)
        : buffer_(capacity_bytes, 0), capacity_(capacity_bytes) {}

    size_t write(const uint8_t* data, size_t length) {
        std::lock_guard<std::mutex> lock(mutex_);
        const size_t writable = std::min(length, free_space_locked());
        for (size_t index = 0; index < writable; ++index) {
            buffer_[(write_pos_ + index) % capacity_] = data[index];
        }
        write_pos_ = (write_pos_ + writable) % capacity_;
        size_ += writable;
        return writable;
    }

    size_t read(uint8_t* out, size_t length) {
        std::lock_guard<std::mutex> lock(mutex_);
        const size_t readable = std::min(length, size_);
        for (size_t index = 0; index < readable; ++index) {
            out[index] = buffer_[(read_pos_ + index) % capacity_];
        }
        read_pos_ = (read_pos_ + readable) % capacity_;
        size_ -= readable;
        return readable;
    }

    size_t available() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return size_;
    }

    size_t capacity() const {
        return capacity_;
    }

    void clear() {
        std::lock_guard<std::mutex> lock(mutex_);
        read_pos_ = 0;
        write_pos_ = 0;
        size_ = 0;
    }

private:
    size_t free_space_locked() const {
        return capacity_ - size_;
    }

    mutable std::mutex mutex_;
    std::vector<uint8_t> buffer_;
    size_t capacity_;
    size_t read_pos_ = 0;
    size_t write_pos_ = 0;
    size_t size_ = 0;
};

jlong to_handle(std::unique_ptr<PCMBuffer> buffer) {
    return reinterpret_cast<jlong>(buffer.release());
}

PCMBuffer* from_handle(jlong handle) {
    return reinterpret_cast<PCMBuffer*>(handle);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_anosh_musicplayer_audio_NativePcmRingBuffer_nativeCreate(
    JNIEnv*,
    jobject,
    jint capacity_bytes) {
    if (capacity_bytes <= 0) {
        return 0;
    }
    return to_handle(std::make_unique<PCMBuffer>(static_cast<size_t>(capacity_bytes)));
}

extern "C" JNIEXPORT void JNICALL
Java_dev_anosh_musicplayer_audio_NativePcmRingBuffer_nativeRelease(
    JNIEnv*,
    jobject,
    jlong handle) {
    delete from_handle(handle);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_anosh_musicplayer_audio_NativePcmRingBuffer_nativeWrite(
    JNIEnv* env,
    jobject,
    jlong handle,
    jbyteArray data,
    jint offset,
    jint length) {
    auto* buffer = from_handle(handle);
    if (buffer == nullptr || data == nullptr || offset < 0 || length < 0) {
        return 0;
    }

    const jsize total_length = env->GetArrayLength(data);
    if (offset + length > total_length) {
        return 0;
    }

    std::vector<uint8_t> bytes(static_cast<size_t>(length));
    env->GetByteArrayRegion(data, offset, length, reinterpret_cast<jbyte*>(bytes.data()));
    return static_cast<jint>(buffer->write(bytes.data(), bytes.size()));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_anosh_musicplayer_audio_NativePcmRingBuffer_nativeRead(
    JNIEnv* env,
    jobject,
    jlong handle,
    jint length) {
    auto* buffer = from_handle(handle);
    if (buffer == nullptr || length <= 0) {
        return env->NewByteArray(0);
    }

    std::vector<uint8_t> bytes(static_cast<size_t>(length));
    const size_t read_count = buffer->read(bytes.data(), bytes.size());
    jbyteArray result = env->NewByteArray(static_cast<jsize>(read_count));
    if (read_count > 0) {
        env->SetByteArrayRegion(
            result,
            0,
            static_cast<jsize>(read_count),
            reinterpret_cast<const jbyte*>(bytes.data()));
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_anosh_musicplayer_audio_NativePcmRingBuffer_nativeAvailable(
    JNIEnv*,
    jobject,
    jlong handle) {
    auto* buffer = from_handle(handle);
    return buffer == nullptr ? 0 : static_cast<jint>(buffer->available());
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_anosh_musicplayer_audio_NativePcmRingBuffer_nativeCapacity(
    JNIEnv*,
    jobject,
    jlong handle) {
    auto* buffer = from_handle(handle);
    return buffer == nullptr ? 0 : static_cast<jint>(buffer->capacity());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_anosh_musicplayer_audio_NativePcmRingBuffer_nativeClear(
    JNIEnv*,
    jobject,
    jlong handle) {
    auto* buffer = from_handle(handle);
    if (buffer != nullptr) {
        buffer->clear();
    }
}
