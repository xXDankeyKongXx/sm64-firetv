// Two helpers the sm64coopdx Android port expects from its patched SDL build. Implemented here so
// the game links against stock SDL 2.28.1 (built from source for 32-bit ARM).
#ifdef TARGET_ANDROID
#include <jni.h>
#include <stdio.h>
#include <SDL2/SDL.h>
#include <SDL2/SDL_system.h>

// Shared storage root (/sdcard on most devices): Environment.getExternalStorageDirectory().
const char *SDL_AndroidGetTopExternalStoragePath(void) {
    static char path[1024] = { 0 };
    if (path[0] != '\0') { return path; }

    JNIEnv *env = (JNIEnv *) SDL_AndroidGetJNIEnv();
    if (env == NULL) { return NULL; }
    if ((*env)->PushLocalFrame(env, 8) < 0) { return NULL; }

    const char *result = NULL;
    jclass envClass = (*env)->FindClass(env, "android/os/Environment");
    jmethodID getDir = envClass ? (*env)->GetStaticMethodID(env, envClass, "getExternalStorageDirectory", "()Ljava/io/File;") : NULL;
    jobject dir = getDir ? (*env)->CallStaticObjectMethod(env, envClass, getDir) : NULL;
    if (dir != NULL) {
        jclass fileClass = (*env)->GetObjectClass(env, dir);
        jmethodID getPath = (*env)->GetMethodID(env, fileClass, "getAbsolutePath", "()Ljava/lang/String;");
        jstring jpath = getPath ? (jstring) (*env)->CallObjectMethod(env, dir, getPath) : NULL;
        if (jpath != NULL) {
            const char *chars = (*env)->GetStringUTFChars(env, jpath, NULL);
            if (chars != NULL) {
                snprintf(path, sizeof(path), "%s", chars);
                (*env)->ReleaseStringUTFChars(env, jpath, chars);
                result = path;
            }
        }
    }
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); result = NULL; path[0] = '\0'; }
    (*env)->PopLocalFrame(env, NULL);
    if (result == NULL) { SDL_SetError("Couldn't get the external storage directory"); }
    return result;
}

// Copies the APK's bundled lang/mods/dynos/palettes folders: SDLActivity.copyAssetFilesToDir(String).
void SDL_AndroidCopyAssetFilesToDir(const char *destpath) {
    JNIEnv *env = (JNIEnv *) SDL_AndroidGetJNIEnv();
    jobject activity = (jobject) SDL_AndroidGetActivity();
    if (env == NULL || activity == NULL || destpath == NULL) { return; }
    if ((*env)->PushLocalFrame(env, 8) < 0) { (*env)->DeleteLocalRef(env, activity); return; }

    // The activity's class (sm64coopdxActivity) inherits the static method from SDLActivity.
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID copy = (*env)->GetStaticMethodID(env, cls, "copyAssetFilesToDir", "(Ljava/lang/String;)V");
    if (copy != NULL) {
        jstring jdest = (*env)->NewStringUTF(env, destpath);
        (*env)->CallStaticVoidMethod(env, cls, copy, jdest);
    }
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env); }
    (*env)->PopLocalFrame(env, NULL);
    (*env)->DeleteLocalRef(env, activity); // SDL_AndroidGetActivity returns a local reference
}
#endif
