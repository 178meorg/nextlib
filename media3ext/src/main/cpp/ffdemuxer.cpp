#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <string>
#include <vector>
extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
}

namespace {
constexpr int64_t kTimeUnset = INT64_MIN + 1;
constexpr const char *kDemuxException =
        "io/github/anilbeesetti/nextlib/media3ext/realmedia/FfmpegDemuxer$DemuxException";
constexpr const char *kPacketClass =
        "io/github/anilbeesetti/nextlib/media3ext/realmedia/FfmpegDemuxer$Packet";

int64_t micros(int64_t time, AVRational base) {
    return time == AV_NOPTS_VALUE ? kTimeUnset : av_rescale_q(time, base, AV_TIME_BASE_Q);
}

struct PacketDeleter { void operator()(AVPacket *p) const { av_packet_free(&p); } };
using Packet = std::unique_ptr<AVPacket, PacketDeleter>;

struct Session {
    JNIEnv *env;
    jobject input = nullptr;
    jbyteArray bytes = nullptr;
    jthrowable failure = nullptr;
    jmethodID readMethod{}, seekMethod{}, lengthMethod{}, cancelMethod{};
    AVFormatContext *format = nullptr;
    AVIOContext *io = nullptr;
    int64_t inputPosition = 0;
    int seekStream = -1;
    bool seekable = false;
    std::vector<int> headerIndexCounts;

    Session(JNIEnv *env, jobject source) : env(env) { input = env->NewGlobalRef(source); }
    ~Session() {
        clearFormat();
        if (failure) env->DeleteGlobalRef(failure);
        if (bytes) env->DeleteGlobalRef(bytes);
        if (input) env->DeleteGlobalRef(input);
    }
    void clearFormat() {
        avformat_close_input(&format);
        if (io) { av_freep(&io->buffer); avio_context_free(&io); }
    }
    bool capture() {
        if (env->ExceptionCheck()) {
            jthrowable local = env->ExceptionOccurred();
            env->ExceptionClear();
            if (!failure) failure = static_cast<jthrowable>(env->NewGlobalRef(local));
            env->DeleteLocalRef(local);
        }
        return failure != nullptr;
    }
    bool cancelled() {
        if (failure) return true;
        bool cancelled = env->CallBooleanMethod(input, cancelMethod);
        return capture() || cancelled;
    }
    static int interrupt(void *opaque) { return static_cast<Session *>(opaque)->cancelled(); }
    static int read(void *opaque, uint8_t *output, int count) {
        auto &s = *static_cast<Session *>(opaque);
        if (s.cancelled()) return AVERROR_EXIT;
        count = std::min(count, 32768);
        int result = s.env->CallIntMethod(s.input, s.readMethod, s.bytes, 0, count);
        if (s.capture()) return AVERROR(EIO);
        if (result == -1) return AVERROR_EOF;
        if (result <= 0 || result > count) return AVERROR(EIO);
        s.env->GetByteArrayRegion(s.bytes, 0, result, reinterpret_cast<jbyte *>(output));
        if (s.capture()) return AVERROR(EIO);
        s.inputPosition += result;
        return result;
    }
    static int64_t seek(void *opaque, int64_t offset, int whence) {
        auto &s = *static_cast<Session *>(opaque);
        if (s.failure) return AVERROR(EIO);
        if (whence == AVSEEK_SIZE) {
            int64_t size = s.env->CallLongMethod(s.input, s.lengthMethod);
            return s.capture() ? AVERROR(EIO) : size < 0 ? AVERROR(ENOSYS) : size;
        }
        if (s.cancelled()) return AVERROR_EXIT;
        whence &= ~AVSEEK_FORCE;
        int64_t base;
        if (whence == SEEK_SET) base = 0;
        else if (whence == SEEK_CUR) base = s.inputPosition;
        else if (whence == SEEK_END) {
            base = seek(opaque, 0, AVSEEK_SIZE);
            if (base < 0) return base;
        } else return AVERROR(EINVAL);
        if ((offset > 0 && base > INT64_MAX - offset) ||
            (offset < 0 && base < INT64_MIN - offset)) return AVERROR(EINVAL);
        int64_t target = base + offset;
        if (target < 0) return AVERROR(EINVAL);
        int64_t length = s.env->CallLongMethod(s.input, s.lengthMethod);
        if (s.capture()) return AVERROR(EIO);
        // Binary probing may legitimately try a position outside the file. This is not a
        // DataSource/network failure and must remain a recoverable seek rejection inside FFmpeg.
        if (length >= 0 && target > length) return AVERROR(EINVAL);
        jlong result = s.env->CallLongMethod(s.input, s.seekMethod, static_cast<jlong>(target));
        if (s.capture()) return AVERROR(EIO);
        if (result < 0) return AVERROR(EIO);
        return s.inputPosition = result;
    }
    bool finish(int result, const char *operation) {
        if (failure) { env->Throw(failure); return false; }
        if (result >= 0) return true;
        char error[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(result, error, sizeof(error));
        std::string message = std::string(operation) + ": " + error;
        jclass type = env->FindClass(result == AVERROR_EXIT ? "java/io/InterruptedIOException" : kDemuxException);
        if (type) { env->ThrowNew(type, message.c_str()); env->DeleteLocalRef(type); }
        return false;
    }
    bool initialize() {
        jclass clazz = env->GetObjectClass(input);
        readMethod = env->GetMethodID(clazz, "read", "([BII)I");
        seekMethod = env->GetMethodID(clazz, "seek", "(J)J");
        lengthMethod = env->GetMethodID(clazz, "getLength", "()J");
        cancelMethod = env->GetMethodID(clazz, "isCancelled", "()Z");
        jmethodID seekableMethod = env->GetMethodID(clazz, "isSeekable", "()Z");
        env->DeleteLocalRef(clazz);
        if (env->ExceptionCheck()) return false;
        seekable = env->CallBooleanMethod(input, seekableMethod);
        jbyteArray local = env->NewByteArray(32768);
        if (!local) return false;
        bytes = static_cast<jbyteArray>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);
        return !env->ExceptionCheck();
    }
    bool open(bool streamInfo) {
        auto *buffer = static_cast<uint8_t *>(av_malloc(32768));
        if (!buffer) return finish(AVERROR(ENOMEM), "allocate AVIO buffer");
        io = avio_alloc_context(buffer, 32768, 0, this, read, nullptr, seek);
        if (!io) { av_free(buffer); return finish(AVERROR(ENOMEM), "allocate AVIO"); }
        io->seekable = seekable ? AVIO_SEEKABLE_NORMAL : 0;
        format = avformat_alloc_context();
        if (!format) return finish(AVERROR(ENOMEM), "allocate demuxer");
        format->pb = io;
        format->flags |= AVFMT_FLAG_CUSTOM_IO;
        format->interrupt_callback = {interrupt, this};
        // Deliberately no URL: protocols, authentication and caching remain with Media3.
        if (!finish(avformat_open_input(&format, nullptr, av_find_input_format("rm"), nullptr), "open RealMedia")) return false;
        headerIndexCounts.clear();
        for (unsigned i = 0; i < format->nb_streams; i++)
            headerIndexCounts.push_back(avformat_index_get_entries_count(format->streams[i]));
        if (streamInfo) {
            format->probesize = 1024 * 1024;
            format->max_analyze_duration = 2 * AV_TIME_BASE;
            if (!finish(avformat_find_stream_info(format, nullptr), "RealMedia stream info")) return false;
        }
        return true;
    }
    bool publish(jobject receiver) {
        jclass clazz = env->GetObjectClass(receiver);
        jmethodID trackMethod = env->GetMethodID(clazz, "addTrack", "(ILjava/lang/String;[BIIIIIJ)V");
        jmethodID headerMethod = env->GetMethodID(clazz, "setHeader", "(JJZ)V");
        env->DeleteLocalRef(clazz);
        if (env->ExceptionCheck()) return false;
        int64_t origin = format->start_time;
        int selected = -1;
        bool validIndex = false;
        const int64_t length = seek(this, 0, AVSEEK_SIZE);
        for (unsigned i = 0; i < format->nb_streams; i++) {
            AVStream *stream = format->streams[i];
            auto *p = stream->codecpar;
            if (p->codec_type != AVMEDIA_TYPE_AUDIO && p->codec_type != AVMEDIA_TYPE_VIDEO) continue;
            if (stream->time_base.num <= 0 || stream->time_base.den <= 0)
                return finish(AVERROR_INVALIDDATA, "invalid RealMedia time base");
            int64_t start = micros(stream->start_time, stream->time_base);
            if (start != kTimeUnset && (origin == AV_NOPTS_VALUE || start < origin)) origin = start;
            jstring codec = env->NewStringUTF(avcodec_get_name(p->codec_id));
            jbyteArray extra = env->NewByteArray(p->extradata_size);
            if (!codec || !extra) return false;
            if (p->extradata_size) env->SetByteArrayRegion(extra, 0, p->extradata_size,
                    reinterpret_cast<const jbyte *>(p->extradata));
            env->CallVoidMethod(receiver, trackMethod, static_cast<jint>(i), codec, extra,
                                p->width, p->height, p->sample_rate, p->ch_layout.nb_channels, p->block_align,
                                static_cast<jlong>(start));
            env->DeleteLocalRef(codec); env->DeleteLocalRef(extra);
            if (env->ExceptionCheck()) return false;
            if (selected < 0 || p->codec_type == AVMEDIA_TYPE_VIDEO) selected = i;
        }
        seekStream = selected;
        if (selected >= 0 && length > 0) {
            AVStream *stream = format->streams[selected];
            int count = avformat_index_get_entries_count(stream);
            validIndex = count > 1 && headerIndexCounts[selected] > 1;
            int64_t lastTime = INT64_MIN, lastPosition = -1;
            for (int i = 0; validIndex && i < count; i++) {
                const auto *entry = avformat_index_get_entry(stream, i);
                if (!entry || entry->pos < 0 || entry->pos >= length || entry->timestamp < lastTime ||
                    entry->pos < lastPosition || !(entry->flags & AVINDEX_KEYFRAME)) validIndex = false;
                else { lastTime = entry->timestamp; lastPosition = entry->pos; }
            }
            // An index learned only during probing is not a file-wide seek index.
            if (validIndex && stream->duration > 0) {
                int64_t end = stream->start_time == AV_NOPTS_VALUE ? stream->duration : stream->start_time + stream->duration;
                int64_t tolerance = av_rescale_q(10, AVRational{1, 1}, stream->time_base);
                validIndex = lastTime >= end - tolerance;
            }
        }
        if (origin == AV_NOPTS_VALUE) origin = 0;
        int64_t duration = format->duration == AV_NOPTS_VALUE ? kTimeUnset : format->duration;
        env->CallVoidMethod(receiver, headerMethod, static_cast<jlong>(duration), static_cast<jlong>(origin),
                            static_cast<jboolean>(seekable && validIndex));
        return !env->ExceptionCheck();
    }
};

Session &get(JNIEnv *env, jlong handle) {
    auto &session = *reinterpret_cast<Session *>(handle);
    session.env = env;
    return session;
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_realmedia_FfmpegDemuxer_nativeOpen(
        JNIEnv *env, jclass, jobject input, jobject receiver, jboolean allowIndex) {
    try {
        auto session = std::make_unique<Session>(env, input);
        if (!session->input || env->ExceptionCheck()) return 0;
        if (!session->initialize()) return 0;
        session->seekable = session->seekable && allowIndex;
        if (!session->open(true) || !session->publish(receiver)) return 0;
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::bad_alloc &) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "RealMedia demuxer allocation"); return 0;
    }
}

extern "C" JNIEXPORT jobject JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_realmedia_FfmpegDemuxer_nativeRead(JNIEnv *env, jclass, jlong handle) {
    auto &s = get(env, handle);
    Packet packet(av_packet_alloc());
    if (!packet) { s.finish(AVERROR(ENOMEM), "allocate packet"); return nullptr; }
    int result;
    do {
        av_packet_unref(packet.get());
        result = av_read_frame(s.format, packet.get());
        if (!s.finish(result == AVERROR_EOF ? 0 : result, "read RealMedia packet") || result == AVERROR_EOF) return nullptr;
    } while (packet->size == 0 ||
            (s.format->streams[packet->stream_index]->codecpar->codec_type != AVMEDIA_TYPE_AUDIO &&
             s.format->streams[packet->stream_index]->codecpar->codec_type != AVMEDIA_TYPE_VIDEO));
    AVStream *stream = s.format->streams[packet->stream_index];
    jbyteArray data = env->NewByteArray(packet->size);
    if (!data) return nullptr;
    env->SetByteArrayRegion(data, 0, packet->size, reinterpret_cast<const jbyte *>(packet->data));
    jclass type = env->FindClass(kPacketClass);
    jmethodID constructor = type ? env->GetMethodID(type, "<init>", "(IJJJZ[B)V") : nullptr;
    jobject output = nullptr;
    if (constructor && !env->ExceptionCheck()) output = env->NewObject(type, constructor, packet->stream_index,
            static_cast<jlong>(micros(packet->pts, stream->time_base)),
            static_cast<jlong>(micros(packet->dts, stream->time_base)),
            static_cast<jlong>(micros(packet->duration, stream->time_base)),
            static_cast<jboolean>(packet->flags & AV_PKT_FLAG_KEY), data);
    env->DeleteLocalRef(data);
    if (type) env->DeleteLocalRef(type);
    return output;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_realmedia_FfmpegDemuxer_nativeSeek(
        JNIEnv *env, jclass, jlong handle, jlong timeUs) {
    auto &s = get(env, handle);
    int stream = s.seekStream;
    if (stream < 0) { s.finish(AVERROR(ENOSYS), "seek stream unavailable"); return; }
    s.clearFormat();
    if (Session::seek(&s, 0, SEEK_SET) < 0) { s.finish(AVERROR(EIO), "reopen RealMedia input"); return; }
    // Fresh RM private state is required. Do not try to resume an interrupted native operation.
    if (!s.open(true)) return;
    if (stream >= static_cast<int>(s.format->nb_streams)) { s.finish(AVERROR_INVALIDDATA, "changed RealMedia streams"); return; }
    int64_t target = av_rescale_q(timeUs, AV_TIME_BASE_Q, s.format->streams[stream]->time_base);
    // RM's binary timestamp probing can fail in an audio-only tail after the last video GOP.
    // Start at a real preceding index point instead of searching for an unavailable later one.
    AVStream *selected = s.format->streams[stream];
    int index = av_index_search_timestamp(selected, target, AVSEEK_FLAG_BACKWARD);
    if (index >= 0) {
        const AVIndexEntry *entry = avformat_index_get_entry(selected, index);
        if (entry && (entry->flags & AVINDEX_KEYFRAME)) target = entry->timestamp;
    }
    s.finish(av_seek_frame(s.format, stream, target, AVSEEK_FLAG_BACKWARD), "seek RealMedia");
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_realmedia_FfmpegDemuxer_nativeClose(JNIEnv *env, jclass, jlong handle) {
    delete &get(env, handle);
}
