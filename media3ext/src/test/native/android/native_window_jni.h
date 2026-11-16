#pragma once
#include <jni.h>
#include <cstdlib>
#include <cstdint>
// Host tests exercise decoding only. Any attempt to render a Surface must fail loudly.
struct ANativeWindow {};
struct ANativeWindow_Buffer { int32_t width, height, stride, format; void* bits; uint32_t reserved[6]; };
inline void ANativeWindow_release(ANativeWindow*) { std::abort(); }
inline ANativeWindow* ANativeWindow_fromSurface(JNIEnv*, jobject) { std::abort(); }
inline int ANativeWindow_setBuffersGeometry(ANativeWindow*, int32_t, int32_t, int32_t) { std::abort(); }
inline int ANativeWindow_lock(ANativeWindow*, ANativeWindow_Buffer*, void*) { std::abort(); }
inline int ANativeWindow_unlockAndPost(ANativeWindow*) { std::abort(); }
