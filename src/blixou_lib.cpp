#include "beauty_filter.h"
#include <map>
#include <mutex>

static std::map<int, BeautyFilter*> g_filters;
static std::mutex g_filters_mutex;
static int g_next_filter_id = 1;

#if defined(_WIN32)
#define FFI_EXPORT __declspec(dllexport)
#else
#define FFI_EXPORT __attribute__((visibility("default"))) __attribute__((used))
#endif

extern "C" {

FFI_EXPORT int init_beauty_filter(int width, int height) {
    std::lock_guard<std::mutex> lock(g_filters_mutex);
    BeautyFilter* filter = new BeautyFilter();
    filter->init(width, height);
    int filter_id = g_next_filter_id++;
    g_filters[filter_id] = filter;
    return filter_id;
}

FFI_EXPORT void set_beauty_intensity(int filter_id, float intensity) {
    std::lock_guard<std::mutex> lock(g_filters_mutex);
    auto it = g_filters.find(filter_id);
    if (it != g_filters.end()) {
        it->second->setIntensity(intensity);
    }
}

FFI_EXPORT void set_face_bounds(int filter_id, float min_x, float min_y, float max_x, float max_y) {
    std::lock_guard<std::mutex> lock(g_filters_mutex);
    auto it = g_filters.find(filter_id);
    if (it != g_filters.end()) {
        it->second->setFaceBounds(min_x, min_y, max_x, max_y);
    }
}

FFI_EXPORT int process_texture(int filter_id, int input_texture_id, int width, int height) {
    std::lock_guard<std::mutex> lock(g_filters_mutex);
    auto it = g_filters.find(filter_id);
    if (it != g_filters.end()) {
        return it->second->processTexture(input_texture_id, width, height);
    }
    return input_texture_id;
}

FFI_EXPORT void release_beauty_filter(int filter_id) {
    std::lock_guard<std::mutex> lock(g_filters_mutex);
    auto it = g_filters.find(filter_id);
    if (it != g_filters.end()) {
        delete it->second;
        g_filters.erase(it);
    }
}

}

#if defined(__ANDROID__)
#include <jni.h>
extern "C" {

JNIEXPORT jint JNICALL
Java_com_softians_cli_1blixou_blixou_1lib_BeautyVideoProcessorJni_initBeautyFilter(JNIEnv *env, jclass clazz, jint width, jint height) {
    return init_beauty_filter(width, height);
}

JNIEXPORT void JNICALL
Java_com_softians_cli_1blixou_blixou_1lib_BeautyVideoProcessorJni_setBeautyIntensity(JNIEnv *env, jclass clazz, jint filter_id, jfloat intensity) {
    set_beauty_intensity(filter_id, intensity);
}

JNIEXPORT void JNICALL
Java_com_softians_cli_1blixou_blixou_1lib_BeautyVideoProcessorJni_setFaceBounds(JNIEnv *env, jclass clazz, jint filter_id, jfloat min_x, jfloat min_y, jfloat max_x, jfloat max_y) {
    set_face_bounds(filter_id, min_x, min_y, max_x, max_y);
}

JNIEXPORT jint JNICALL
Java_com_softians_cli_1blixou_blixou_1lib_BeautyVideoProcessorJni_processTexture(JNIEnv *env, jclass clazz, jint filter_id, jint texture_id, jint width, jint height) {
    return process_texture(filter_id, texture_id, width, height);
}

JNIEXPORT void JNICALL
Java_com_softians_cli_1blixou_blixou_1lib_BeautyVideoProcessorJni_releaseBeautyFilter(JNIEnv *env, jclass clazz, jint filter_id) {
    release_beauty_filter(filter_id);
}

}
#endif
