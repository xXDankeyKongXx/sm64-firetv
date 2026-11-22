// Fire TV remote support: the Java side (RemoteInput) drives an SDL virtual game controller, so
// the remote works everywhere a gamepad does (Mario and the DJUI menus). controller_sdl.c reads
// every connected controller, so this pad and a Bluetooth gamepad work at the same time.
#ifdef TARGET_ANDROID
#include <jni.h>
#include <SDL2/SDL.h>

static SDL_Joystick* sRemotePad = NULL;

static SDL_Joystick* remote_pad(void) {
    if (sRemotePad != NULL) { return sRemotePad; }
    if (!SDL_WasInit(SDL_INIT_JOYSTICK)) { return NULL; } // game not started yet
    int index = SDL_JoystickAttachVirtual(SDL_JOYSTICK_TYPE_GAMECONTROLLER,
                                          SDL_CONTROLLER_AXIS_MAX, SDL_CONTROLLER_BUTTON_MAX, 0);
    if (index < 0) { return NULL; }
    sRemotePad = SDL_JoystickOpen(index);
    if (sRemotePad != NULL) {
        // Triggers rest at their minimum on a virtual pad.
        SDL_JoystickSetVirtualAxis(sRemotePad, SDL_CONTROLLER_AXIS_TRIGGERLEFT, SDL_JOYSTICK_AXIS_MIN);
        SDL_JoystickSetVirtualAxis(sRemotePad, SDL_CONTROLLER_AXIS_TRIGGERRIGHT, SDL_JOYSTICK_AXIS_MIN);
    }
    return sRemotePad;
}

JNIEXPORT void JNICALL Java_com_maniscat2_sm64coopdx_RemoteInput_nativeSetButton(JNIEnv* env, jclass cls, jint button, jboolean down) {
    SDL_Joystick* pad = remote_pad();
    if (pad != NULL && button >= 0 && button < SDL_CONTROLLER_BUTTON_MAX) {
        SDL_JoystickSetVirtualButton(pad, button, down ? 1 : 0);
    }
}

JNIEXPORT void JNICALL Java_com_maniscat2_sm64coopdx_RemoteInput_nativeSetAxis(JNIEnv* env, jclass cls, jint axis, jint value) {
    SDL_Joystick* pad = remote_pad();
    if (pad != NULL && axis >= 0 && axis < SDL_CONTROLLER_AXIS_MAX) {
        if (value > SDL_JOYSTICK_AXIS_MAX) { value = SDL_JOYSTICK_AXIS_MAX; }
        if (value < SDL_JOYSTICK_AXIS_MIN) { value = SDL_JOYSTICK_AXIS_MIN; }
        SDL_JoystickSetVirtualAxis(pad, axis, (Sint16)value);
    }
}
#endif
