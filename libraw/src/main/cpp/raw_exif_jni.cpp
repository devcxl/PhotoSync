#include <jni.h>
#include <android/log.h>
#include "libraw.h"
#include <cmath>
#include <cstring>

#define LOG_TAG "LibRawExifJNI"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct PathGuard {
    JNIEnv* env;
    jstring jpath;
    const char* cpath;
    ~PathGuard() {
        if (cpath) {
            env->ReleaseStringUTFChars(jpath, cpath);
        }
    }
};

struct LibRawGuard {
    LibRaw* proc;
    explicit LibRawGuard(LibRaw* p) : proc(p) {}
    ~LibRawGuard() {
        if (proc) {
            proc->recycle();
        }
    }
};

template <size_t N>
jstring newSafeUtfString(JNIEnv* env, const char (&buf)[N]) {
    size_t len = strnlen(buf, N);
    if (len == 0) {
        return nullptr;
    }
    char temp[N + 1];
    std::memcpy(temp, buf, len);
    temp[len] = '\0';
    return env->NewStringUTF(temp);
}

double dmsToDecimal(const float dms[3], char ref) {
    double deg = static_cast<double>(dms[0]) +
                 static_cast<double>(dms[1]) / 60.0 +
                 static_cast<double>(dms[2]) / 3600.0;
    if (ref == 'S' || ref == 's' || ref == 'W' || ref == 'w') {
        deg = -deg;
    }
    return deg;
}

}  // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_cn_devcxl_photosync_wrapper_RawWrapper_extractExifMetadata(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring jpath
) {
    if (!jpath) {
        return nullptr;
    }
    const char* cpath = env->GetStringUTFChars(jpath, nullptr);
    if (!cpath) {
        return nullptr;
    }
    PathGuard pathGuard{env, jpath, cpath};

    LibRaw proc;
    LibRawGuard procGuard(&proc);

    int ret = proc.open_file(cpath);
    if (ret != LIBRAW_SUCCESS) {
        ALOGE("open_file failed (%d) while extracting EXIF for %s", ret, cpath);
        return nullptr;
    }

    jclass metaCls = env->FindClass("cn/devcxl/photosync/wrapper/RawExifMetadata");
    if (!metaCls) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }

    jmethodID ctor = env->GetMethodID(
        metaCls,
        "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JFFFFILjava/lang/String;"
        "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IILjava/lang/String;"
        "Ljava/lang/String;ZDDD)V"
    );
    if (!ctor) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(metaCls);
        return nullptr;
    }

    const libraw_iparams_t& idata = proc.imgdata.idata;
    const libraw_imgother_t& other = proc.imgdata.other;
    const libraw_lensinfo_t& lens = proc.imgdata.lens;
    const libraw_shootinginfo_t& shoot = proc.imgdata.shootinginfo;

    jstring jMake = (strnlen(idata.normalized_make, sizeof(idata.normalized_make)) > 0)
        ? newSafeUtfString(env, idata.normalized_make)
        : newSafeUtfString(env, idata.make);
    jstring jModel = (strnlen(idata.normalized_model, sizeof(idata.normalized_model)) > 0)
        ? newSafeUtfString(env, idata.normalized_model)
        : newSafeUtfString(env, idata.model);
    jstring jSoftware = newSafeUtfString(env, idata.software);
    jstring jLensMake = newSafeUtfString(env, lens.LensMake);
    jstring jLensModel = (strnlen(lens.Lens, sizeof(lens.Lens)) > 0)
        ? newSafeUtfString(env, lens.Lens)
        : newSafeUtfString(env, lens.makernotes.Lens);
    jstring jLensSerial = newSafeUtfString(env, lens.LensSerial);
    jstring jBodySerial = newSafeUtfString(env, shoot.BodySerial);
    jstring jDesc = newSafeUtfString(env, other.desc);
    jstring jArtist = newSafeUtfString(env, other.artist);

    const jboolean gpsParsed = (other.parsed_gps.gpsparsed != 0) ? JNI_TRUE : JNI_FALSE;
    const jdouble gpsLat = gpsParsed
        ? dmsToDecimal(other.parsed_gps.latitude, other.parsed_gps.latref)
        : 0.0;
    const jdouble gpsLon = gpsParsed
        ? dmsToDecimal(other.parsed_gps.longitude, other.parsed_gps.longref)
        : 0.0;
    double gpsAlt = gpsParsed ? static_cast<double>(other.parsed_gps.altitude) : 0.0;
    if (gpsParsed && other.parsed_gps.altref != 0) {
        gpsAlt = -gpsAlt;
    }

    jobject result = env->NewObject(
        metaCls,
        ctor,
        jMake,
        jModel,
        jSoftware,
        static_cast<jlong>(other.timestamp),
        static_cast<jfloat>(other.shutter),
        static_cast<jfloat>(other.aperture),
        static_cast<jfloat>(other.iso_speed),
        static_cast<jfloat>(other.focal_len),
        static_cast<jint>(lens.FocalLengthIn35mmFormat),
        jLensMake,
        jLensModel,
        jLensSerial,
        jBodySerial,
        static_cast<jint>(shoot.ExposureProgram),
        static_cast<jint>(shoot.MeteringMode),
        jDesc,
        jArtist,
        gpsParsed,
        gpsLat,
        gpsLon,
        static_cast<jdouble>(gpsAlt)
    );

    if (jMake) env->DeleteLocalRef(jMake);
    if (jModel) env->DeleteLocalRef(jModel);
    if (jSoftware) env->DeleteLocalRef(jSoftware);
    if (jLensMake) env->DeleteLocalRef(jLensMake);
    if (jLensModel) env->DeleteLocalRef(jLensModel);
    if (jLensSerial) env->DeleteLocalRef(jLensSerial);
    if (jBodySerial) env->DeleteLocalRef(jBodySerial);
    if (jDesc) env->DeleteLocalRef(jDesc);
    if (jArtist) env->DeleteLocalRef(jArtist);
    env->DeleteLocalRef(metaCls);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return result;
}
