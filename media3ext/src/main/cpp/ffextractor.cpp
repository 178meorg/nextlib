#include <jni.h>
#include <cstdint>
#include <cstdio>
#include <limits>
#include <memory>
#include <vector>
extern "C" {
#include <libavformat/avformat.h>
#include <libavutil/error.h>
}

namespace {
constexpr int kBufferSize = 32768;
constexpr int64_t kTimeUnset = INT64_C(-9223372036854775807);

void fail(JNIEnv* env, const char* operation, int error) {
    if (env->ExceptionCheck()) return;
    char detail[AV_ERROR_MAX_STRING_SIZE], message[256];
    av_strerror(error, detail, sizeof(detail));
    snprintf(message, sizeof(message), "%s: %s", operation, detail);
    jclass exception = env->FindClass("java/io/IOException");
    env->ThrowNew(exception, message);
    env->DeleteLocalRef(exception);
}

struct Demuxer {
    JNIEnv* env;
    jobject owner;
    jbyteArray buffer = nullptr;
    jmethodID readAt, onTrack, onReady, onSample;
    AVFormatContext* format = nullptr;
    AVIOContext* io = nullptr;
    AVPacket* packet = nullptr;
    int64_t position = 0, length, startUs = 0;
    std::vector<bool> supported;

    Demuxer(JNIEnv* env, jobject owner, int64_t length) : env(env), length(length) {
        this->owner = env->NewGlobalRef(owner);
    }
    ~Demuxer() {
        av_packet_free(&packet);
        avformat_close_input(&format);
        if (io) { av_freep(&io->buffer); avio_context_free(&io); }
        if (buffer) env->DeleteGlobalRef(buffer);
        env->DeleteGlobalRef(owner);
    }
};

int readPacket(void* opaque, uint8_t* data, int size) {
    auto* d = static_cast<Demuxer*>(opaque);
    if (d->env->ExceptionCheck()) return AVERROR_EXIT;
    if (d->length >= 0 && d->position >= d->length) return AVERROR_EOF;
    // AVIO uses the fixed buffer size. Keep the JNI callback bounded even for direct reads.
    size = size < kBufferSize ? size : kBufferSize;
    jbyteArray buffer = size == kBufferSize ? d->buffer : d->env->NewByteArray(size);
    if (!buffer) return AVERROR(ENOMEM);
    jint count = d->env->CallIntMethod(d->owner, d->readAt, (jlong)d->position, buffer);
    if (!d->env->ExceptionCheck() && count > 0 && count <= size)
        d->env->GetByteArrayRegion(buffer, 0, count, reinterpret_cast<jbyte*>(data));
    if (buffer != d->buffer) d->env->DeleteLocalRef(buffer);
    if (d->env->ExceptionCheck()) return AVERROR_EXIT;
    if (count < 0) return AVERROR_EOF;
    if (count == 0 || count > size) return AVERROR(EIO);
    if (d->env->ExceptionCheck()) return AVERROR_EXIT;
    d->position += count;
    return count;
}

int64_t seekInput(void* opaque, int64_t offset, int whence) {
    auto* d = static_cast<Demuxer*>(opaque);
    if (d->env->ExceptionCheck()) return AVERROR_EXIT;
    if (whence == AVSEEK_SIZE) return d->length >= 0 ? d->length : AVERROR(ENOSYS);
    whence &= ~AVSEEK_FORCE;
    int64_t base;
    if (whence == SEEK_SET) base = 0;
    else if (whence == SEEK_CUR) base = d->position;
    else if (whence == SEEK_END && d->length >= 0) base = d->length;
    else return AVERROR(ENOSYS);
    if (offset < -base || offset > INT64_MAX - base) return AVERROR(EINVAL);
    // Cut/truncated files can retain index offsets beyond EOF. Reject them here so FFmpeg
    // falls back instead of asking Media3/HTTP for an invalid range (HTTP 416).
    if (d->length >= 0 && base + offset > d->length) return AVERROR(EINVAL);
    d->position = base + offset;
    return d->position;
}

const char* mime(AVCodecID codec) {
    switch (codec) {
        case AV_CODEC_ID_RV10: return "video/x-nextlib-rv10";
        case AV_CODEC_ID_RV20: return "video/x-nextlib-rv20";
        case AV_CODEC_ID_RV30: return "video/x-nextlib-rv30";
        case AV_CODEC_ID_RV40: return "video/x-nextlib-rv40";
        case AV_CODEC_ID_COOK: return "audio/x-nextlib-cook";
        case AV_CODEC_ID_SIPR: return "audio/x-nextlib-sipr";
        case AV_CODEC_ID_RA_144: return "audio/x-nextlib-ra144";
        case AV_CODEC_ID_RA_288: return "audio/x-nextlib-ra288";
        case AV_CODEC_ID_ATRAC3: return "audio/x-nextlib-atrac3";
        case AV_CODEC_ID_AAC: return "audio/mp4a-latm";
        case AV_CODEC_ID_AC3: return "audio/ac3";
        case AV_CODEC_ID_MP3: return "audio/mpeg";
        default: return nullptr;
    }
}
}

#define JNI_METHOD(name) Java_io_github_anilbeesetti_nextlib_media3ext_ffextractor_FfmpegDemuxer_##name

extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(nativeOpen)(JNIEnv* env, jobject self, jlong length) {
    std::unique_ptr<Demuxer> d(new Demuxer(env, self, length));
    jclass cls = env->GetObjectClass(self);
    d->readAt = env->GetMethodID(cls, "readAt", "(J[B)I");
    d->onTrack = env->GetMethodID(cls, "onTrack", "(ILjava/lang/String;IIII[BIII)V");
    d->onReady = env->GetMethodID(cls, "onReady", "(J)V");
    d->onSample = env->GetMethodID(cls, "onSample", "(I[BJZ)V");
    env->DeleteLocalRef(cls);
    if (env->ExceptionCheck()) return 0;
    jbyteArray buffer = env->NewByteArray(kBufferSize);
    if (!buffer) return 0;
    d->buffer = (jbyteArray)env->NewGlobalRef(buffer);
    env->DeleteLocalRef(buffer);
    auto* bytes = static_cast<uint8_t*>(av_malloc(kBufferSize));
    if (!bytes) { fail(env, "AVIO allocation", AVERROR(ENOMEM)); return 0; }
    d->io = avio_alloc_context(bytes, kBufferSize, 0, d.get(), readPacket, nullptr, seekInput);
    if (!d->io) { av_free(bytes); fail(env, "AVIO allocation", AVERROR(ENOMEM)); return 0; }
    d->io->seekable = length >= 0 ? AVIO_SEEKABLE_NORMAL : 0;
    d->format = avformat_alloc_context();
    d->packet = av_packet_alloc();
    if (!d->format || !d->packet) { fail(env, "Demux allocation", AVERROR(ENOMEM)); return 0; }
    d->format->pb = d->io;
    d->format->flags |= AVFMT_FLAG_CUSTOM_IO | AVFMT_FLAG_GENPTS;
    d->format->probesize = 1024 * 1024;
    d->format->max_analyze_duration = 2 * AV_TIME_BASE;
    const AVInputFormat* rm = av_find_input_format("rm");
    if (!rm) { fail(env, "RealMedia demuxer unavailable", AVERROR_DEMUXER_NOT_FOUND); return 0; }
    int result = avformat_open_input(&d->format, nullptr, rm, nullptr);
    if (result < 0 || env->ExceptionCheck()) { fail(env, "Open RealMedia", result); return 0; }
    result = avformat_find_stream_info(d->format, nullptr);
    if (result < 0 || env->ExceptionCheck()) { fail(env, "Read RealMedia tracks", result); return 0; }
    d->startUs = d->format->start_time == AV_NOPTS_VALUE ? 0 : d->format->start_time;
    d->supported.resize(d->format->nb_streams, false);
    bool hasTracks = false;
    for (unsigned i = 0; i < d->format->nb_streams; ++i) {
        const AVCodecParameters* p = d->format->streams[i]->codecpar;
        const char* type = mime(p->codec_id);
        if (!type || !avcodec_find_decoder(p->codec_id)) {
            if (p->codec_type == AVMEDIA_TYPE_VIDEO || p->codec_type == AVMEDIA_TYPE_AUDIO) {
                fail(env, "Unsupported RealMedia codec", AVERROR_DECODER_NOT_FOUND); return 0;
            }
            continue;
        }
        jstring mimeString = env->NewStringUTF(type);
        jbyteArray extra = env->NewByteArray(p->extradata_size);
        if (!mimeString || !extra) return 0;
        if (p->extradata_size) env->SetByteArrayRegion(extra, 0, p->extradata_size, (const jbyte*)p->extradata);
        if (!env->ExceptionCheck()) env->CallVoidMethod(d->owner, d->onTrack, (jint)i, mimeString,
            p->width, p->height, p->sample_rate, p->ch_layout.nb_channels, extra,
            p->block_align, p->bits_per_coded_sample, (jint)p->codec_tag);
        env->DeleteLocalRef(extra);
        env->DeleteLocalRef(mimeString);
        if (env->ExceptionCheck()) return 0;
        d->supported[i] = true;
        hasTracks = true;
    }
    if (!hasTracks) { fail(env, "No supported RealMedia tracks", AVERROR_STREAM_NOT_FOUND); return 0; }
    env->CallVoidMethod(d->owner, d->onReady,
        (jlong)(d->format->duration == AV_NOPTS_VALUE ? kTimeUnset : d->format->duration));
    if (env->ExceptionCheck()) return 0;
    return reinterpret_cast<jlong>(d.release());
}

extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(nativeRead)(JNIEnv* env, jobject, jlong handle) {
    auto* d = reinterpret_cast<Demuxer*>(handle);
    int result = av_read_frame(d->format, d->packet);
    if (env->ExceptionCheck()) return false;
    if (result == AVERROR_EOF) return false;
    if (result < 0) { fail(env, "Read RealMedia packet", result); return false; }
    AVPacket* p = d->packet;
    if (p->stream_index >= 0 && (unsigned)p->stream_index < d->supported.size() && d->supported[p->stream_index]) {
        int64_t timestamp = p->pts != AV_NOPTS_VALUE ? p->pts : p->dts;
        if (timestamp == AV_NOPTS_VALUE) {
            av_packet_unref(p);
            fail(env, "RealMedia packet has no timestamp", AVERROR_INVALIDDATA);
            return false;
        }
        int64_t timeUs = av_rescale_q(timestamp, d->format->streams[p->stream_index]->time_base, AV_TIME_BASE_Q) - d->startUs;
        jbyteArray data = env->NewByteArray(p->size);
        if (data) {
            env->SetByteArrayRegion(data, 0, p->size, (const jbyte*)p->data);
            if (!env->ExceptionCheck()) env->CallVoidMethod(d->owner, d->onSample, p->stream_index,
                data, (jlong)timeUs, (jboolean)((p->flags & AV_PKT_FLAG_KEY) != 0));
            env->DeleteLocalRef(data);
        }
    }
    av_packet_unref(p);
    return !env->ExceptionCheck();
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativeSeek)(JNIEnv* env, jobject, jlong handle, jlong timeUs) {
    auto* d = reinterpret_cast<Demuxer*>(handle);
    int result = av_seek_frame(d->format, -1, timeUs + d->startUs, AVSEEK_FLAG_BACKWARD);
    if (result < 0) fail(env, "Seek RealMedia", result);
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativeClose)(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Demuxer*>(handle);
}
