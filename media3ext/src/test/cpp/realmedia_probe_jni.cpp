// Opt-in feasibility probe only. Compile the actual production native decoder implementation.
#include "../../main/cpp/ffaudio.cpp"
#include "../../main/cpp/ffvideo.cpp"
#define main nextlibRealMediaReferenceMain
#include "realmedia_reference_test.cpp"
#undef main
#include <atomic>
#include <sstream>

namespace {
std::atomic<int> sessions{0};
constexpr const char *kIoException = "java/io/IOException";

struct Session {
    JNIEnv *env;
    jobject input = nullptr;
    jbyteArray buffer = nullptr;
    jthrowable failure = nullptr;
    jmethodID readMethod, seekMethod, lengthMethod, cancelMethod;
    AVFormatContext *format = nullptr;
    AVIOContext *io = nullptr;
    int64_t inputPosition = 0, bytesRead = 0;

    Session(JNIEnv *env, jobject source) : env(env) {
        sessions++;
        input = env->NewGlobalRef(source);
    }
    ~Session() {
        avformat_close_input(&format);
        if (io) { av_freep(&io->buffer); avio_context_free(&io); }
        if (failure) env->DeleteGlobalRef(failure);
        if (buffer) env->DeleteGlobalRef(buffer);
        if (input) env->DeleteGlobalRef(input);
        sessions--;
    }
    bool capture() {
        if (env->ExceptionCheck()) {
            auto local = env->ExceptionOccurred();
            env->ExceptionClear();
            if (!failure) failure = static_cast<jthrowable>(env->NewGlobalRef(local));
            env->DeleteLocalRef(local);
        }
        return failure != nullptr;
    }
    bool cancelled() {
        if (failure) return true;
        bool result = env->CallBooleanMethod(input, cancelMethod);
        return capture() || result;
    }
    static int interrupt(void *opaque) { return static_cast<Session *>(opaque)->cancelled(); }
    static int read(void *opaque, uint8_t *destination, int count) {
        auto &s = *static_cast<Session *>(opaque);
        if (s.cancelled()) return AVERROR_EXIT;
        count = std::min(count, 32768);
        int read = s.env->CallIntMethod(s.input, s.readMethod, s.buffer, 0, count);
        if (s.capture()) return AVERROR(EIO);
        if (read == -1) return AVERROR_EOF;
        if (read <= 0 || read > count) return AVERROR(EIO);
        s.env->GetByteArrayRegion(s.buffer, 0, read, reinterpret_cast<jbyte *>(destination));
        if (s.capture()) return AVERROR(EIO);
        s.inputPosition += read;
        s.bytesRead += read;
        return read;
    }
    static int64_t seek(void *opaque, int64_t offset, int whence) {
        auto &s = *static_cast<Session *>(opaque);
        if (s.failure) return AVERROR(EIO);
        if (whence == AVSEEK_SIZE) {
            jlong length = s.env->CallLongMethod(s.input, s.lengthMethod);
            return s.capture() ? AVERROR(EIO) : length < 0 ? AVERROR(ENOSYS) : length;
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
        jlong position = s.env->CallLongMethod(s.input, s.seekMethod, static_cast<jlong>(base + offset));
        if (s.capture()) return AVERROR(EIO);
        if (position < 0) return AVERROR(EIO);
        return s.inputPosition = position;
    }
    bool finish(int result, const char *operation) {
        // Some demuxers ignore a failed callback (notably index loading in rm_read_header).
        // Always prefer the original Java exception to a misleading native success/EOF.
        if (failure) {
            env->Throw(failure);
            return false;
        }
        if (result >= 0) return true;
        char error[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(result, error, sizeof(error));
        std::string message = std::string(operation) + ": " + error;
        env->ThrowNew(env->FindClass(result == AVERROR_EXIT ? "java/io/InterruptedIOException" : kIoException),
                      message.c_str());
        return false;
    }
    bool open() {
        auto clazz = env->GetObjectClass(input);
        readMethod = env->GetMethodID(clazz, "read", "([BII)I");
        seekMethod = env->GetMethodID(clazz, "seek", "(J)J");
        lengthMethod = env->GetMethodID(clazz, "getLength", "()J");
        cancelMethod = env->GetMethodID(clazz, "isCancelled", "()Z");
        auto seekableMethod = env->GetMethodID(clazz, "isSeekable", "()Z");
        env->DeleteLocalRef(clazz);
        if (env->ExceptionCheck()) return false;
        bool seekable = env->CallBooleanMethod(input, seekableMethod);
        auto localBuffer = env->NewByteArray(32768);
        buffer = static_cast<jbyteArray>(env->NewGlobalRef(localBuffer));
        env->DeleteLocalRef(localBuffer);
        if (env->ExceptionCheck()) return false;
        auto *bytes = static_cast<uint8_t *>(av_malloc(32768));
        require(bytes, "AVIO buffer");
        io = avio_alloc_context(bytes, 32768, 0, this, read, nullptr, seek);
        if (!io) { av_free(bytes); throw std::runtime_error("AVIO context"); }
        io->seekable = seekable ? AVIO_SEEKABLE_NORMAL : 0;
        format = avformat_alloc_context();
        require(format, "format context");
        format->pb = io;
        format->flags |= AVFMT_FLAG_CUSTOM_IO;
        format->interrupt_callback = {interrupt, this};
        // Never pass a URI to FFmpeg. All input, including every seek, belongs to Media3.
        if (!finish(avformat_open_input(&format, nullptr, av_find_input_format("rm"), nullptr), "open RM")) return false;
        format->probesize = 1024 * 1024;
        format->max_analyze_duration = 2 * AV_TIME_BASE;
        return finish(avformat_find_stream_info(format, nullptr), "RM stream info");
    }
};

using Packets = std::vector<AVPacket *>;
struct Samples {
    std::vector<Packets> tracks;
    explicit Samples(Session &session) : tracks(session.format->nb_streams) {
        AVPacket *packet = av_packet_alloc();
        require(packet, "packet");
        int result;
        while ((result = av_read_frame(session.format, packet)) >= 0) {
            AVPacket *copy = av_packet_clone(packet);
            require(copy, "clone packet");
            tracks.at(packet->stream_index).push_back(copy);
            av_packet_unref(packet);
        }
        av_packet_free(&packet);
        if (!session.finish(result == AVERROR_EOF ? 0 : result, "read samples"))
            throw std::runtime_error("read samples");
    }
    ~Samples() { for (auto &track : tracks) for (auto *packet : track) av_packet_free(&packet); }
};

static AVCodecContext *fullContext(AVStream *stream) {
    const AVCodec *codec = avcodec_find_decoder(stream->codecpar->codec_id);
    require(codec, "decoder registry");
    auto *context = avcodec_alloc_context3(codec);
    require(context, "codec context");
    if (avcodec_parameters_to_context(context, stream->codecpar) < 0) {
        avcodec_free_context(&context);
        throw std::runtime_error("copy codec parameters");
    }
    context->thread_count = 1;
    context->pkt_timebase = stream->time_base;
    context->request_sample_fmt = AV_SAMPLE_FMT_S16;
    if (avcodec_open2(context, codec, nullptr) < 0) {
        avcodec_free_context(&context);
        throw std::runtime_error("codec open");
    }
    return context;
}

static std::vector<FrameRecord> productionVideo(JniContext &context, const Packets &packets,
                                               AVRational timeBase, bool drain) {
    std::vector<FrameRecord> frames;
    auto receive = [&](bool once) {
        int result;
        do {
            AVFrame *frame = nullptr;
            result = context.ReceiveFrame(&frame);
            if (result >= 0) frames.push_back(record(frame, AVMEDIA_TYPE_VIDEO));
            av_frame_free(&frame);
            require(result >= 0 || result == AVERROR(EAGAIN) || result == AVERROR_EOF,
                    "production video receive");
        } while (!once && result >= 0);
    };
    for (auto *packet : packets) {
        // Match the real JNI handoff: payload plus microsecond PTS, without AVPacket side data,
        // flags, duration or DTS. Copying the complete demuxer's AVPacket would hide requirements.
        AVPacket encoded{};
        encoded.data = packet->data;
        encoded.size = packet->size;
        encoded.pts = packet->pts == AV_NOPTS_VALUE ? AV_NOPTS_VALUE :
                av_rescale_q(packet->pts, timeBase, AV_TIME_BASE_Q);
        encoded.dts = AV_NOPTS_VALUE;
        encoded.pos = -1;
        require(context.SendPacket(&encoded) == VIDEO_DECODER_SUCCESS, "production video send");
        receive(!drain);
    }
    if (drain) {
        require(context.SendPacket(nullptr) == VIDEO_DECODER_SUCCESS, "production video EOS send");
        receive(false);
    }
    return frames;
}

static std::vector<uint8_t> referencePcm(AVStream *stream, const Packets &packets) {
    AVCodecContext *context = fullContext(stream);
    AVFrame *frame = av_frame_alloc();
    SwrContext *swr = nullptr;
    std::vector<uint8_t> pcm;
    auto receive = [&]() {
        int result;
        while ((result = avcodec_receive_frame(context, frame)) >= 0) {
            if (!swr) {
                require(swr_alloc_set_opts2(&swr, &frame->ch_layout, AV_SAMPLE_FMT_S16, frame->sample_rate,
                        &frame->ch_layout, static_cast<AVSampleFormat>(frame->format), frame->sample_rate,
                        0, nullptr) >= 0 && swr_init(swr) >= 0, "reference resampler");
            }
            std::vector<uint8_t> bytes(frame->nb_samples * frame->ch_layout.nb_channels * 2);
            uint8_t *output[] = {bytes.data()};
            require(swr_convert(swr, output, frame->nb_samples,
                    const_cast<const uint8_t **>(frame->extended_data), frame->nb_samples) == frame->nb_samples,
                    "reference PCM conversion");
            pcm.insert(pcm.end(), bytes.begin(), bytes.end());
            av_frame_unref(frame);
        }
        require(result == AVERROR(EAGAIN) || result == AVERROR_EOF, "reference audio receive");
    };
    for (auto *packet : packets) {
        require(avcodec_send_packet(context, packet) >= 0, "reference audio send");
        receive();
    }
    require(avcodec_send_packet(context, nullptr) >= 0, "reference audio EOS");
    receive();
    av_frame_free(&frame);
    swr_free(&swr);
    avcodec_free_context(&context);
    return pcm;
}

static std::string compare(Session &session) {
    Samples samples(session);
    std::ostringstream report;
    report << "{\"tracks\":[";
    for (unsigned i = 0; i < session.format->nb_streams; i++) {
        auto *stream = session.format->streams[i];
        auto *params = stream->codecpar;
        Track reference(stream);
        for (auto *packet : samples.tracks[i]) reference.send(packet, false);
        reference.drain();
        jbyteArray extra = session.env->NewByteArray(params->extradata_size);
        session.env->SetByteArrayRegion(extra, 0, params->extradata_size,
                                       reinterpret_cast<const jbyte *>(params->extradata));
        auto *codec = const_cast<AVCodec *>(avcodec_find_decoder(params->codec_id));
        bool baselineInitialized;
        report << (i ? "," : "") << "{\"codec\":\"" << avcodec_get_name(params->codec_id)
               << "\",\"reference_frames\":" << reference.frames.size();
        if (params->codec_type == AVMEDIA_TYPE_VIDEO) {
            auto expected = reference.frames;
            for (auto &frame : expected)
                if (frame.pts != AV_NOPTS_VALUE) frame.pts = av_rescale_q(frame.pts, stream->time_base, AV_TIME_BASE_Q);
            std::unique_ptr<JniContext> baseline(createVideoContext(session.env, codec, extra, 1));
            baselineInitialized = baseline != nullptr;
            bool baselineDrainMatches = false;
            if (baseline) {
                try { baselineDrainMatches = productionVideo(*baseline, samples.tracks[i], stream->time_base, true) == expected; }
                catch (const std::exception &) { /* Missing primitive parameters are a gate result. */ }
            }
            // Candidate parameter handoff, followed by the actual existing native send/receive.
            JniContext candidate;
            candidate.codecContext = fullContext(stream);
            candidate.codecContext->pkt_timebase = AV_TIME_BASE_Q;
            auto legacy = productionVideo(candidate, samples.tracks[i], stream->time_base, false);
            candidate.ClearPendingFrames(); avcodec_flush_buffers(candidate.codecContext);
            for (int pass = 0; pass < 3; pass++) {
                auto actual = productionVideo(candidate, samples.tracks[i], stream->time_base, true);
                require(actual == expected, "production video differs from reference after drain/flush");
                candidate.ClearPendingFrames(); avcodec_flush_buffers(candidate.codecContext);
            }
            report << ",\"legacy_frames\":" << legacy.size()
                   << ",\"baseline_drain_matches\":" << (baselineDrainMatches ? "true" : "false")
                   << ",\"full_params_drain_flush_matches\":true,\"flush_passes\":3"
                   << ",\"reference_eos_frames\":" << reference.eosFrames;
        } else {
            AVCodecContext *baseline = createContext(session.env, codec, extra, false,
                                                     params->sample_rate, params->ch_layout.nb_channels);
            baselineInitialized = baseline != nullptr;
            releaseContext(baseline);
            auto expected = referencePcm(stream, samples.tracks[i]);
            AVCodecContext *candidate = createContext(session.env, codec, extra, false,
                    params->sample_rate, params->ch_layout.nb_channels, params->block_align);
            require(candidate, "production Cook initializer with minimal primitive parameters");
            std::vector<uint8_t> pcm;
            uint8_t output[131072];
            for (int pass = 0; pass < 3; pass++) {
                pcm.clear();
                const bool combineCookBlocks = params->codec_id == AV_CODEC_ID_COOK && pass == 2;
                auto &packets = samples.tracks[i];
                for (size_t p = 0; p < packets.size(); p++) {
                    auto *packet = packets[p];
                    std::vector<uint8_t> combined;
                    AVPacket encoded{};
                    encoded.data = packet->data;
                    encoded.size = packet->size;
                    // Prove the production audio loop drains more than one frame from a packet.
                    if (combineCookBlocks && p + 1 < packets.size()) {
                        auto *second = packets[++p];
                        combined.resize(packet->size + second->size + AV_INPUT_BUFFER_PADDING_SIZE, 0);
                        memcpy(combined.data(), packet->data, packet->size);
                        memcpy(combined.data() + packet->size, second->data, second->size);
                        encoded.data = combined.data();
                        encoded.size = packet->size + second->size;
                    }
                    encoded.pts = encoded.dts = AV_NOPTS_VALUE;
                    encoded.pos = -1;
                    int count = decodePacket(candidate, &encoded, output, sizeof(output), {});
                    require(count >= 0, "production PCM decode");
                    pcm.insert(pcm.end(), output, output + count);
                }
                if (pcm != expected) {
                    auto mismatch = std::mismatch(pcm.begin(), pcm.end(), expected.begin(), expected.end());
                    std::ostringstream error;
                    error << "production PCM differs from reference: pass=" << pass
                          << " actual_bytes=" << pcm.size() << " reference_bytes=" << expected.size()
                          << " first_mismatch=" << (mismatch.first - pcm.begin());
                    releaseContext(candidate);
                    throw std::runtime_error(error.str());
                }
                candidate = reinterpret_cast<AVCodecContext *>(
                        Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegAudioDecoder_ffmpegReset(
                                session.env, nullptr, reinterpret_cast<jlong>(candidate), extra));
                require(candidate, "production Cook reset");
            }
            releaseContext(candidate);
            report << ",\"pcm_bytes\":" << pcm.size()
                   << ",\"samples_per_channel\":" << reference.samples
                   << ",\"full_params_pcm_flush_matches\":true,\"flush_passes\":3"
                   << ",\"reference_eos_frames\":" << reference.eosFrames
                   << ",\"two_cook_blocks_per_packet_test\":" << (params->codec_id == AV_CODEC_ID_COOK ? "true" : "false")
                   << ",\"missing_frame_pts\":" << reference.missingPts;
        }
        session.env->DeleteLocalRef(extra);
        report << ",\"baseline_initializer_accepts_params\":" << (baselineInitialized ? "true" : "false") << "}";
    }
    report << "]}";
    return report.str();
}

static std::string seekAndDecode(Session &session, int count) {
    require(count > 0 && count <= 100, "seek count must be in [1,100]");
    int video = -1;
    for (unsigned i = 0; i < session.format->nb_streams; i++)
        if (session.format->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) video = i;
    require(video >= 0, "seek probe needs video");
    Samples samples(session);
    Track golden(session.format->streams[video]);
    for (auto *packet : samples.tracks[video]) golden.send(packet, false);
    golden.drain();
    require(!golden.frames.empty(), "seek golden frames");
    Track actual(session.format->streams[video]);
    std::mt19937 random(0x524d5642);
    int64_t maxBytes = 0, maxMicros = 0;
    AVPacket *packet = av_packet_alloc();
    require(packet, "seek packet");
    for (int i = 0; i < count; i++) {
        auto started = std::chrono::steady_clock::now();
        const auto &target = golden.frames[random() % golden.frames.size()];
        require(Session::seek(&session, 0, SEEK_SET) == 0, "reopen at media start");
        Session fresh(session.env, session.input);
        if (!fresh.open()) { av_packet_free(&packet); throw std::runtime_error("reopen demuxer"); }
        int64_t before = 0;
        int64_t preroll = av_rescale_q(2, AVRational{1, 1}, fresh.format->streams[video]->time_base);
        if (!fresh.finish(av_seek_frame(fresh.format, video, std::max<int64_t>(0, target.pts - preroll), AVSEEK_FLAG_BACKWARD), "seek")) {
            av_packet_free(&packet); throw std::runtime_error("seek callback");
        }
        avcodec_flush_buffers(actual.decoder);
        actual.frames.clear();
        bool matched = false;
        for (int packets = 0; packets < 20000 && fresh.bytesRead - before < 8 * 1024 * 1024; packets++) {
            int result = av_read_frame(fresh.format, packet);
            if (result == AVERROR_EOF) actual.drain();
            else {
                if (!fresh.finish(result, "seek preroll")) {
                    av_packet_free(&packet); throw std::runtime_error("seek read callback");
                }
                if (packet->stream_index == video) actual.send(packet, false, true);
                av_packet_unref(packet);
            }
            if (!actual.frames.empty() && std::abs(actual.frames.back().pts - target.pts) <= 1 &&
                actual.frames.back().digest == target.digest) {
                matched = true; break;
            }
            if (result == AVERROR_EOF) break;
        }
        require(matched, "seek convergence exceeded budget");
        maxBytes = std::max(maxBytes, fresh.bytesRead - before);
        maxMicros = std::max<int64_t>(maxMicros, std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - started).count());
    }
    av_packet_free(&packet);
    std::ostringstream out;
    out << "{\"targets\":" << count << ",\"pixel_matches\":" << count << ",\"byte_budget\":8388608,\"max_bytes\":"
        << maxBytes << ",\"max_us\":" << maxMicros << "}";
    return out.str();
}

Session &get(JNIEnv *env, jlong handle) {
    auto &session = *reinterpret_cast<Session *>(handle);
    session.env = env;
    return session;
}
void throwError(JNIEnv *env, const std::exception &error) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass(kIoException), error.what());
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_realmedia_RealMediaProbe_nativeOpen(JNIEnv *env, jclass, jobject input) {
    try {
        auto session = std::make_unique<Session>(env, input);
        if (!session->open()) return 0;
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::exception &error) { throwError(env, error); return 0; }
}
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_realmedia_RealMediaProbe_nativeCompare(JNIEnv *env, jclass, jlong handle) {
    try { auto report = compare(get(env, handle)); return env->NewStringUTF(report.c_str()); }
    catch (const std::exception &error) { throwError(env, error); return nullptr; }
}
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_realmedia_RealMediaProbe_nativeSeek(JNIEnv *env, jclass, jlong handle, jint count) {
    try { auto report = seekAndDecode(get(env, handle), count); return env->NewStringUTF(report.c_str()); }
    catch (const std::exception &error) { throwError(env, error); return nullptr; }
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_realmedia_RealMediaProbe_nativeRead(JNIEnv *env, jclass, jlong handle) {
    auto &session = get(env, handle);
    AVPacket *packet = av_packet_alloc();
    if (!packet) { env->ThrowNew(env->FindClass(kIoException), "packet allocation"); return nullptr; }
    int result = av_read_frame(session.format, packet);
    jlongArray output = nullptr;
    if (session.finish(result == AVERROR_EOF ? 0 : result, "read packet") && result != AVERROR_EOF) {
        const jlong values[] = {packet->stream_index, packet->pts, packet->dts, packet->size,
            static_cast<jlong>(hashBytes(UINT64_C(14695981039346656037), packet->data, packet->size))};
        output = env->NewLongArray(5);
        if (output) env->SetLongArrayRegion(output, 0, 5, values);
    }
    av_packet_free(&packet);
    return output;
}
extern "C" JNIEXPORT void JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_realmedia_RealMediaProbe_nativeBufferCheck(JNIEnv *env, jclass, jlong handle) {
    auto &session = get(env, handle);
    try {
        int64_t start = avio_tell(session.io);
        uint8_t original[64], replay[8];
        require(avio_read(session.io, original, sizeof(original)) == sizeof(original), "buffered initial read");
        require(avio_seek(session.io, start + 5, SEEK_SET) == start + 5, "buffered backward seek");
        require(avio_read(session.io, replay, sizeof(replay)) == sizeof(replay) &&
                !memcmp(original + 5, replay, sizeof(replay)), "buffered replay bytes");
        require(avio_seek(session.io, 1024 * 1024, SEEK_SET | AVSEEK_FORCE) == 1024 * 1024,
                "outside-buffer seek");
        session.finish(0, "buffered seek");
    } catch (const std::exception &error) { session.finish(0, "buffered seek"); throwError(env, error); }
}
extern "C" JNIEXPORT void JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_realmedia_RealMediaProbe_nativeClose(JNIEnv *env, jclass, jlong handle) {
    delete &get(env, handle);
}
extern "C" JNIEXPORT jint JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_realmedia_RealMediaProbe_liveSessions(JNIEnv *, jclass) { return sessions.load(); }
