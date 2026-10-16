// Gate A/B probe. This is deliberately not shipped as an Extractor or a second player.
// Uses the pinned FFmpeg on the host; the Android differential test uses production JNI code.
#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cinttypes>
#include <cstdio>
#include <cstring>
#include <memory>
#include <random>
#include <stdexcept>
#include <string>
#include <vector>
extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/imgutils.h>
}

static void require(bool ok, const char *message) {
    if (!ok) throw std::runtime_error(message);
}

struct Input {
    FILE *file;
    int64_t size, position = 0, bytes = 0;
    int seeks = 0, outsideSeeks = 0;
    bool forwardOnly;
    explicit Input(const char *path, bool forward = false) : forwardOnly(forward) {
        file = std::fopen(path, "rb");
        require(file, "open sample");
        require(std::fseek(file, 0, SEEK_END) == 0, "size sample");
        size = std::ftell(file);
        std::rewind(file);
    }
    ~Input() { std::fclose(file); }
    static int read(void *opaque, uint8_t *buffer, int length) {
        auto &s = *static_cast<Input *>(opaque);
        int count = std::fread(buffer, 1, length, s.file);
        s.position += count;
        s.bytes += count;
        return count ? count : std::ferror(s.file) ? AVERROR(EIO) : AVERROR_EOF;
    }
    static int64_t seek(void *opaque, int64_t offset, int whence) {
        auto &s = *static_cast<Input *>(opaque);
        if (whence == AVSEEK_SIZE) return s.size;
        whence &= ~AVSEEK_FORCE;
        int64_t target = whence == SEEK_SET ? offset :
                         whence == SEEK_CUR ? s.position + offset : s.size + offset;
        s.seeks++;
        // ExtractorInput cannot reopen here. AVIO itself handles in-buffer backtracking.
        if (s.forwardOnly && target != s.position) {
            s.outsideSeeks++;
            return AVERROR(EAGAIN); // Diagnostic only: NEVER treat this as a continuation API.
        }
        if (target < 0 || target > s.size || std::fseek(s.file, target, SEEK_SET))
            return AVERROR(EIO);
        return s.position = target;
    }
};

struct Demux {
    AVFormatContext *format = nullptr;
    AVIOContext *io = nullptr;
    explicit Demux(Input &input, bool advertisedSeekable = true) {
        auto *buffer = static_cast<uint8_t *>(av_malloc(32768));
        require(buffer, "allocate AVIO buffer");
        io = avio_alloc_context(buffer, 32768, 0, &input, Input::read, nullptr, Input::seek);
        if (!io) { av_free(buffer); throw std::runtime_error("allocate AVIO"); }
        io->seekable = advertisedSeekable ? AVIO_SEEKABLE_NORMAL : 0;
        format = avformat_alloc_context();
        require(format, "allocate format");
        format->pb = io;
        format->flags |= AVFMT_FLAG_CUSTOM_IO;
        int result = avformat_open_input(&format, nullptr, av_find_input_format("rm"), nullptr);
        if (result < 0) {
            av_freep(&io->buffer);
            avio_context_free(&io);
            throw std::runtime_error("avformat_open_input");
        }
        format->probesize = 1024 * 1024;
        format->max_analyze_duration = 2 * AV_TIME_BASE;
        if (avformat_find_stream_info(format, nullptr) < 0) {
            avformat_close_input(&format);
            av_freep(&io->buffer);
            avio_context_free(&io);
            throw std::runtime_error("avformat_find_stream_info");
        }
    }
    ~Demux() {
        avformat_close_input(&format);
        av_freep(&io->buffer);
        avio_context_free(&io);
    }
};

struct FrameRecord {
    int64_t pts;
    int samples;
    uint64_t digest;
    bool operator==(const FrameRecord &other) const {
        return pts == other.pts && samples == other.samples && digest == other.digest;
    }
};

static uint64_t hashBytes(uint64_t hash, const uint8_t *data, size_t length) {
    for (size_t i = 0; i < length; i++) hash = (hash ^ data[i]) * UINT64_C(1099511628211);
    return hash;
}

static FrameRecord record(const AVFrame *frame, AVMediaType type) {
    uint64_t hash = UINT64_C(14695981039346656037);
    if (type == AVMEDIA_TYPE_VIDEO) {
        int size = av_image_get_buffer_size(static_cast<AVPixelFormat>(frame->format),
                                           frame->width, frame->height, 1);
        require(size > 0, "video buffer size");
        std::vector<uint8_t> bytes(size);
        require(av_image_copy_to_buffer(bytes.data(), size, frame->data, frame->linesize,
                static_cast<AVPixelFormat>(frame->format), frame->width, frame->height, 1) == size,
                "copy canonical video pixels");
        hash = hashBytes(hash, bytes.data(), bytes.size());
    } else {
        auto fmt = static_cast<AVSampleFormat>(frame->format);
        int planes = av_sample_fmt_is_planar(fmt) ? frame->ch_layout.nb_channels : 1;
        int size = frame->nb_samples * av_get_bytes_per_sample(fmt) *
                   (planes == 1 ? frame->ch_layout.nb_channels : 1);
        for (int i = 0; i < planes; i++) hash = hashBytes(hash, frame->extended_data[i], size);
    }
    return {frame->pts, frame->nb_samples, hash};
}

struct Track {
    AVCodecContext *decoder = nullptr;
    AVMediaType type;
    int packets = 0, eagain = 0, eosFrames = 0, multiOutputPackets = 0, missingPts = 0;
    int missingPacketPts = 0, missingPacketDts = 0;
    int64_t samples = 0;
    std::vector<FrameRecord> frames;
    Track(AVStream *stream) : type(stream->codecpar->codec_type) {
        const AVCodec *codec = avcodec_find_decoder(stream->codecpar->codec_id);
        if (!codec) std::fprintf(stderr, "Missing decoder: %s\n", avcodec_get_name(stream->codecpar->codec_id));
        require(codec, "required decoder disabled");
        decoder = avcodec_alloc_context3(codec);
        require(decoder, "allocate decoder");
        require(avcodec_parameters_to_context(decoder, stream->codecpar) >= 0, "copy codec params");
        decoder->pkt_timebase = stream->time_base;
        decoder->thread_count = 1;
        require(avcodec_open2(decoder, codec, nullptr) >= 0, "open decoder with full params");
    }
    ~Track() { avcodec_free_context(&decoder); }
    void receive(bool once, bool eos) {
        AVFrame *frame = av_frame_alloc();
        require(frame, "allocate frame");
        int outputs = 0, result;
        do {
            result = avcodec_receive_frame(decoder, frame);
            if (result < 0) break;
            frames.push_back(record(frame, type));
            samples += frame->nb_samples;
            missingPts += frame->pts == AV_NOPTS_VALUE;
            eosFrames += eos;
            outputs++;
            av_frame_unref(frame);
        } while (!once);
        av_frame_free(&frame);
        if (result == AVERROR(EAGAIN)) eagain++;
        else require(result >= 0 || result == AVERROR_EOF, "decode receive error");
        multiOutputPackets += !eos && outputs > 1;
    }
    void send(AVPacket *packet, bool legacy, bool allowPreroll = false) {
        // Same schedule as NextLib video: preserve EAGAIN output, retry the same packet.
        int result;
        while ((result = avcodec_send_packet(decoder, packet)) == AVERROR(EAGAIN)) receive(false, false);
        // A seek can land before a keyframe's display time, with B pictures that refer to
        // the previous GOP. They cannot be displayed until a reference picture is available.
        if (allowPreroll && frames.empty() && result == AVERROR_INVALIDDATA) return;
        require(result >= 0, "decode send error");
        packets++;
        missingPacketPts += packet->pts == AV_NOPTS_VALUE;
        missingPacketDts += packet->dts == AV_NOPTS_VALUE;
        receive(legacy && type == AVMEDIA_TYPE_VIDEO, false);
    }
    void drain() {
        require(avcodec_send_packet(decoder, nullptr) >= 0, "send EOS");
        receive(false, true);
    }
};

static std::vector<std::unique_ptr<Track>> decode(Demux &demux, bool legacy) {
    std::vector<std::unique_ptr<Track>> tracks;
    for (unsigned i = 0; i < demux.format->nb_streams; i++)
        tracks.push_back(std::make_unique<Track>(demux.format->streams[i]));
    AVPacket *packet = av_packet_alloc();
    require(packet, "allocate packet");
    int result;
    while ((result = av_read_frame(demux.format, packet)) >= 0) {
        tracks.at(packet->stream_index)->send(packet, legacy);
        av_packet_unref(packet);
    }
    av_packet_free(&packet);
    require(result == AVERROR_EOF, "demux did not end cleanly");
    if (!legacy) for (auto &track : tracks) track->drain();
    return tracks;
}

static uint64_t packetDigest(Demux &d, int &count) {
    AVPacket *packet = av_packet_alloc();
    uint64_t hash = UINT64_C(14695981039346656037);
    int result;
    while ((result = av_read_frame(d.format, packet)) >= 0) {
        const int64_t meta[] = {packet->stream_index, packet->pts, packet->dts, packet->size};
        hash = hashBytes(hash, reinterpret_cast<const uint8_t *>(meta), sizeof(meta));
        hash = hashBytes(hash, packet->data, packet->size);
        count++;
        av_packet_unref(packet);
    }
    av_packet_free(&packet);
    require(result == AVERROR_EOF, "packet digest demux error");
    return hash;
}

// A seek result is checked against decoded pixels, not just av_seek_frame's return value.
static void checkSeeks(const char *path, const std::vector<std::unique_ptr<Track>> &golden) {
    Input input(path); Demux demux(input);
    int video = -1;
    for (size_t i = 0; i < golden.size(); i++)
        if (golden[i]->type == AVMEDIA_TYPE_VIDEO) video = static_cast<int>(i);
    if (video < 0) { std::printf(",\"reference_seek\":null"); return; }
    auto &frames = golden[video]->frames;
    std::mt19937 random(0x524d5642);
    int64_t maxBytes = 0, maxMicros = 0;
    int matches = 0;
    AVPacket *packet = av_packet_alloc();
    require(packet, "seek packet");
    for (int iteration = 0; iteration < 100; iteration++) {
        auto started = std::chrono::steady_clock::now();
        // rm_read_seek does not reset all packet-assembly state. Reopen the demuxer per seek.
        Input freshInput(path); Demux fresh(freshInput);
        Track track(fresh.format->streams[video]);
        const auto &target = frames[random() % frames.size()];
        int64_t before = 0;
        int64_t preroll = av_rescale_q(2, AVRational{1, 1}, fresh.format->streams[video]->time_base);
        require(av_seek_frame(fresh.format, video, std::max<int64_t>(0, target.pts - preroll), AVSEEK_FLAG_BACKWARD) >= 0,
                "reference indexed seek");
        avcodec_flush_buffers(track.decoder);
        track.frames.clear();
        bool matched = false;
        int result;
        // Bounded byte/packet budget: failure never becomes a full-file scan presented as a seek.
        for (int packets = 0; packets < 20000 && freshInput.bytes - before < 8 * 1024 * 1024; packets++) {
            result = av_read_frame(fresh.format, packet);
            if (result == AVERROR_EOF) { track.drain(); }
            else {
                require(result >= 0, "demux after seek");
                if (packet->stream_index == video) track.send(packet, false, true);
                av_packet_unref(packet);
            }
            if (!track.frames.empty() && std::abs(track.frames.back().pts - target.pts) <= 1 &&
                track.frames.back().digest == target.digest) {
                matched = true;
                break;
            }
            if (result == AVERROR_EOF) break;
        }
        if (!matched) {
            auto samePixels = std::find_if(track.frames.begin(), track.frames.end(), [&](const FrameRecord &f) {
                return f.digest == target.digest;
            });
            std::fprintf(stderr, "seek failed i=%d target=%" PRId64 " decoded_frames=%zu last_pts=%" PRId64
                    " same_pixels_pts=%" PRId64 " bytes=%" PRId64 "\n", iteration, target.pts, track.frames.size(),
                    track.frames.empty() ? AV_NOPTS_VALUE : track.frames.back().pts,
                    samePixels == track.frames.end() ? AV_NOPTS_VALUE : samePixels->pts, freshInput.bytes);
        }
        require(matched, "seek exceeded convergence budget");
        maxBytes = std::max(maxBytes, freshInput.bytes - before);
        maxMicros = std::max<int64_t>(maxMicros, std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - started).count());
        matches++;
    }
    av_packet_free(&packet);
    std::printf(",\"reference_seek\":{\"seed\":1380808258,\"preroll_seconds\":2,\"pts_tolerance_ticks\":1,\"byte_budget\":8388608,\"targets\":%d,\"pixel_matches\":%d,\"max_bytes\":%" PRId64 ",\"max_us\":%" PRId64 "}",
                matches, matches, maxBytes, maxMicros);
}

int main(int argc, char **argv) {
    try {
        require(argc == 2 || argc == 3, "usage: realmedia_reference_test SAMPLE [--inspect|--packets|--decode-only]");
        av_log_set_level(AV_LOG_ERROR);
        Input referenceInput(argv[1]); Demux reference(referenceInput);
        if (argc == 3 && !std::strcmp(argv[2], "--inspect")) {
            std::printf("{\"tracks\":[");
            for (unsigned i = 0; i < reference.format->nb_streams; i++) {
                auto *p = reference.format->streams[i]->codecpar;
                std::printf("%s{\"codec\":\"%s\",\"width\":%d,\"height\":%d,\"sample_rate\":%d,\"channels\":%d,\"extradata_bytes\":%d}",
                            i ? "," : "", avcodec_get_name(p->codec_id), p->width, p->height, p->sample_rate,
                            p->ch_layout.nb_channels, p->extradata_size);
            }
            std::puts("]}"); return 0;
        }
        if (argc == 3 && !std::strcmp(argv[2], "--packets")) {
            int count = 0;
            uint64_t digest = packetDigest(reference, count);
            std::printf("{\"packets\":%d,\"digest\":\"%016" PRIx64 "\"}\n", count, digest);
            return 0;
        }
        auto full = decode(reference, false);
        if (argc == 3 && !std::strcmp(argv[2], "--seek-only")) {
            std::printf("{\"ffmpeg\":\"%s\"", av_version_info());
            checkSeeks(argv[1], full);
            std::puts("}"); return 0;
        }
        Input legacyInput(argv[1]); Demux legacyDemux(legacyInput);
        auto legacy = decode(legacyDemux, true);
        std::printf("{\"ffmpeg\":\"%s\",\"bytes\":%" PRId64 ",\"tracks\":[", av_version_info(), referenceInput.size);
        for (size_t i = 0; i < full.size(); i++) {
            AVStream *st = reference.format->streams[i];
            auto *p = st->codecpar;
            auto &r = *full[i]; auto &l = *legacy[i];
            require(!r.frames.empty(), "no decoded frames");
            bool prefixEqual = l.frames.size() <= r.frames.size() &&
                               std::equal(l.frames.begin(), l.frames.end(), r.frames.begin());
            std::printf("%s{\"codec\":\"%s\",\"time_base\":[%d,%d],\"stream_start\":%" PRId64
                ",\"width\":%d,\"height\":%d,\"sample_rate\":%d,\"channels\":%d,\"block_align\":%d,"
                "\"extradata_bytes\":%d,\"index_entries\":%d,\"packets\":%d,\"frames\":%zu,"
                "\"samples_per_channel\":%" PRId64 ",\"eos_frames\":%d,\"multi_output_packets\":%d,"
                "\"receive_eagain\":%d,\"missing_packet_pts\":%d,\"missing_packet_dts\":%d,\"missing_frame_pts\":%d,\"first_pts\":%" PRId64
                ",\"last_pts\":%" PRId64 ",\"legacy_frames\":%zu,\"legacy_prefix_equal\":%s}",
                i ? "," : "", avcodec_get_name(p->codec_id), st->time_base.num, st->time_base.den,
                st->start_time, p->width, p->height, p->sample_rate, p->ch_layout.nb_channels,
                p->block_align, p->extradata_size, avformat_index_get_entries_count(st), r.packets,
                r.frames.size(), r.samples, r.eosFrames, r.multiOutputPackets, r.eagain,
                r.missingPacketPts, r.missingPacketDts, r.missingPts,
                r.frames.front().pts, r.frames.back().pts, l.frames.size(), prefixEqual ? "true" : "false");
        }
        if (argc == 3 && !std::strcmp(argv[2], "--decode-only")) {
            std::printf("]");
            checkSeeks(argv[1], full);
            std::puts("}"); return 0;
        }
        std::printf("],\"avio\":[");
        uint64_t expectedHash = 0; int expectedCount = 0;
        for (int mode = 0; mode < 3; mode++) {
            Input input(argv[1], mode == 1);
            Demux d(input, mode != 2);
            int initialOutside = input.outsideSeeks;
            int initialIndex = 0;
            for (unsigned i = 0; i < d.format->nb_streams; i++)
                initialIndex += avformat_index_get_entries_count(d.format->streams[i]);
            int count = 0; uint64_t hash = 0;
            bool completed = true;
            try { hash = packetDigest(d, count); }
            catch (const std::exception &) {
                if (mode == 0) throw;
                completed = false;
            }
            if (!mode) { expectedCount = count; expectedHash = hash; }
            std::printf("%s{\"mode\":\"%s\",\"initial_outside_seeks\":%d,\"initial_index_entries\":%d,"
                "\"packets\":%d,\"completed\":%s,\"packet_digest\":\"%016" PRIx64 "\",\"same_packets\":%s,\"bytes_read\":%" PRId64 "}",
                mode ? "," : "", mode == 0 ? "random_access" : mode == 1 ? "eagain_on_seek" : "unseekable",
                initialOutside, initialIndex, count, completed ? "true" : "false", hash,
                completed && count == expectedCount && hash == expectedHash ? "true" : "false", input.bytes);
        }
        std::printf("]");
        checkSeeks(argv[1], full);
        std::puts("}");
    } catch (const std::exception &error) {
        std::fprintf(stderr, "FAIL: %s\n", error.what());
        return 1;
    }
    return 0;
}
