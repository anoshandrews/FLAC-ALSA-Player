#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <vector>

namespace {

struct IsoUrbPacket {
    usbdevfs_urb urb;
    usbdevfs_iso_packet_desc packet;
};

class UsbAudioEngine {
public:
    UsbAudioEngine(int fd, int endpoint_address, bool is_isochronous, int packet_size)
        : fd_(dup(fd)),
          endpoint_address_(endpoint_address),
          is_isochronous_(is_isochronous),
          packet_size_(std::max(packet_size, 1)) {}

    ~UsbAudioEngine() {
        CleanupIsochronous();
        if (fd_ >= 0) {
            close(fd_);
            fd_ = -1;
        }
    }

    bool IsValid() const {
        return fd_ >= 0;
    }

    int Write(const uint8_t* data, int length) {
        if (fd_ < 0 || data == nullptr || length <= 0) {
            return -1;
        }
        std::lock_guard<std::mutex> lock(mutex_);
        if (is_isochronous_) {
            return WriteIsochronous(data, length);
        }
        return WriteBulk(data, length);
    }

private:
    int WriteBulk(const uint8_t* data, int length) {
        usbdevfs_bulktransfer transfer = {};
        transfer.ep = static_cast<unsigned int>(endpoint_address_);
        transfer.len = static_cast<unsigned int>(length);
        transfer.timeout = 1000;
        transfer.data = const_cast<uint8_t*>(data);
        return ioctl(fd_, USBDEVFS_BULK, &transfer);
    }

    int WriteIsochronous(const uint8_t* data, int length) {
        if (length <= 0 || length > packet_size_) {
            return -1;
        }
        if (pending_iso_ != nullptr && ReapPendingIsochronous() != 0) {
            return -1;
        }
        iso_buffer_.assign(data, data + length);
        std::memset(&iso_urb_packet_, 0, sizeof(iso_urb_packet_));
        iso_urb_packet_.urb.type = USBDEVFS_URB_TYPE_ISO;
        iso_urb_packet_.urb.endpoint = static_cast<unsigned char>(endpoint_address_);
        iso_urb_packet_.urb.flags = USBDEVFS_URB_ISO_ASAP;
        iso_urb_packet_.urb.buffer = iso_buffer_.data();
        iso_urb_packet_.urb.buffer_length = length;
        iso_urb_packet_.urb.number_of_packets = 1;
        iso_urb_packet_.urb.usercontext = nullptr;
        iso_urb_packet_.urb.error_count = 0;
        iso_urb_packet_.packet.length = static_cast<unsigned int>(length);
        iso_urb_packet_.packet.actual_length = 0;
        iso_urb_packet_.packet.status = 0;
        if (ioctl(fd_, USBDEVFS_SUBMITURB, &iso_urb_packet_.urb) != 0) {
            return -1;
        }
        pending_iso_ = &iso_urb_packet_.urb;
        return length;
    }

    int ReapPendingIsochronous() {
        void* completed_urb = nullptr;
        if (ioctl(fd_, USBDEVFS_REAPURB, &completed_urb) != 0) {
            pending_iso_ = nullptr;
            return -1;
        }
        pending_iso_ = nullptr;
        if (completed_urb != &iso_urb_packet_.urb) {
            return -1;
        }
        if (iso_urb_packet_.urb.status != 0 || iso_urb_packet_.packet.status != 0) {
            return -1;
        }
        return 0;
    }

    void CleanupIsochronous() {
        if (!is_isochronous_ || fd_ < 0) {
            return;
        }
        if (pending_iso_ != nullptr) {
            ioctl(fd_, USBDEVFS_DISCARDURB, pending_iso_);
            void* completed_urb = nullptr;
            ioctl(fd_, USBDEVFS_REAPURBNDELAY, &completed_urb);
            pending_iso_ = nullptr;
        }
    }

    int fd_ = -1;
    int endpoint_address_;
    bool is_isochronous_;
    int packet_size_;
    std::mutex mutex_;
    IsoUrbPacket iso_urb_packet_ = {};
    std::vector<uint8_t> iso_buffer_;
    usbdevfs_urb* pending_iso_ = nullptr;
};

jlong ToHandle(std::unique_ptr<UsbAudioEngine> engine) {
    return reinterpret_cast<jlong>(engine.release());
}

UsbAudioEngine* FromHandle(jlong handle) {
    return reinterpret_cast<UsbAudioEngine*>(handle);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_anosh_musicplayer_usb_NativeUsbAudioEngine_nativeCreate(
    JNIEnv*,
    jobject,
    jint file_descriptor,
    jint endpoint_address,
    jboolean is_isochronous,
    jint packet_size) {
    auto engine = std::make_unique<UsbAudioEngine>(
        file_descriptor,
        endpoint_address,
        is_isochronous == JNI_TRUE,
        packet_size);
    if (!engine->IsValid()) {
        return 0;
    }
    return ToHandle(std::move(engine));
}

extern "C" JNIEXPORT void JNICALL
Java_dev_anosh_musicplayer_usb_NativeUsbAudioEngine_nativeRelease(
    JNIEnv*,
    jobject,
    jlong handle) {
    delete FromHandle(handle);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_anosh_musicplayer_usb_NativeUsbAudioEngine_nativeWrite(
    JNIEnv* env,
    jobject,
    jlong handle,
    jbyteArray data,
    jint offset,
    jint length) {
    auto* engine = FromHandle(handle);
    if (engine == nullptr || data == nullptr || offset < 0 || length <= 0) {
        return -1;
    }
    const jsize total_length = env->GetArrayLength(data);
    if (offset + length > total_length) {
        return -1;
    }
    std::vector<uint8_t> bytes(static_cast<size_t>(length));
    env->GetByteArrayRegion(data, offset, length, reinterpret_cast<jbyte*>(bytes.data()));
    return engine->Write(bytes.data(), length);
}
