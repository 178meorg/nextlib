// Run against the actual packaged Android libavcodec, not the host FFmpeg.
#include <cstdio>
extern "C" {
#include <libavcodec/avcodec.h>
}

int main() {
    const AVCodec *named = avcodec_find_decoder_by_name("vc1");
    const AVCodec *byId = avcodec_find_decoder(AV_CODEC_ID_VC1);
    if (!named || named != byId || named->id != AV_CODEC_ID_VC1) {
        std::fprintf(stderr, "VC-1 decoder is not registered by both name and codec ID\n");
        return 1;
    }
    if (avcodec_find_decoder_by_name("wmv3")) {
        std::fprintf(stderr, "WMV3 must not be enabled by this VC-1 change\n");
        return 1;
    }
    AVCodecContext *context = avcodec_alloc_context3(named);
    if (!context) return 1;
    int result = avcodec_open2(context, named, nullptr);
    avcodec_free_context(&context);
    if (result >= 0) {
        std::fprintf(stderr, "VC-1 incorrectly accepted missing extradata\n");
        return 1;
    }
    std::puts("VC-1 registry and missing-extradata checks passed; WMV3 is disabled");
}
