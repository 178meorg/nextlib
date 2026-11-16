#pragma once
#include <cstdio>
#include <cstdarg>
#define ANDROID_LOG_ERROR 6
#define ANDROID_LOG_DEBUG 3
inline int __android_log_print(int, const char*, const char* format, ...) {
    va_list args;
    va_start(args, format);
    int result = vfprintf(stderr, format, args);
    va_end(args);
    return result;
}
