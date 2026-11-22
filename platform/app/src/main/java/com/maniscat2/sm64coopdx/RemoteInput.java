package com.maniscat2.sm64coopdx;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

/**
 * Plays Super Mario 64 with the Fire TV remote alone. Remote keys drive an SDL virtual game
 * controller (native side: src/pc/controller/controller_android_remote.c), so they work in-game
 * and in every Coop Deluxe menu, which navigate with the stick and A/B. A Bluetooth gamepad keeps
 * working at the same time.
 *
 *   D-pad ............ move Mario / move through menus (left stick)
 *   Select (center) .. A (jump, choose)
 *   Back ............. B (punch, back out of menus)
 *   Play/Pause ....... Start (pause and options menu)
 *   Rewind ........... Z (crouch, ground pound)
 *   Fast-forward ..... R (camera mode)
 *   Menu (≡) tap ..... arm the C-buttons: the next D-pad press is C-up/left/down/right (camera)
 *   Menu (≡) hold .... Start
 */
final class RemoteInput {

    // SDL_GameControllerButton / SDL_GameControllerAxis numbering
    static final int BTN_A = 0, BTN_B = 1, BTN_START = 6, BTN_RIGHTSHOULDER = 10;
    static final int AXIS_LEFTX = 0, AXIS_LEFTY = 1, AXIS_RIGHTX = 2, AXIS_RIGHTY = 3,
            AXIS_TRIGGERLEFT = 4, AXIS_TRIGGERRIGHT = 5;
    private static final int TILT = 32767, AXIS_MIN = -32768;
    private static final long PULSE_MS = 150, LONG_PRESS_MS = 600;

    static native void nativeSetButton(int button, boolean down);
    static native void nativeSetAxis(int axis, int value);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean up, down, left, right, cArmed, menuLongFired;
    private TextView cIndicator;
    private final Runnable menuLongPress = () -> {
        menuLongFired = true;
        setCArmed(false);
        pulse(BTN_START);
    };

    /** Small "C" badge while the C-buttons are armed. */
    void attachIndicator(ViewGroup root) {
        if (root == null) return;
        cIndicator = new TextView(root.getContext());
        cIndicator.setText("C ▲◀▼▶");
        cIndicator.setTextColor(Color.rgb(255, 214, 64));
        cIndicator.setTextSize(22);
        cIndicator.setPadding(24, 12, 24, 12);
        cIndicator.setBackgroundColor(Color.argb(150, 0, 0, 0));
        cIndicator.setVisibility(View.GONE);
        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        lp.addRule(RelativeLayout.ALIGN_PARENT_END);
        lp.setMargins(0, 48, 48, 0);
        root.addView(cIndicator, lp);
    }

    /** True for the TV remote; gamepads are left to SDL. */
    static boolean isRemoteEvent(KeyEvent e) {
        InputDevice dev = e.getDevice();
        int sources = dev != null ? dev.getSources() : e.getSource();
        if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) return false;
        if ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return false;
        return true;
    }

    /** Returns true if consumed. */
    boolean handle(KeyEvent e) {
        if (!isRemoteEvent(e)) return false;
        boolean isUp = e.getAction() == KeyEvent.ACTION_UP;
        boolean first = e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0;
        try {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:    return direction(first, isUp, 0);
                case KeyEvent.KEYCODE_DPAD_DOWN:  return direction(first, isUp, 1);
                case KeyEvent.KEYCODE_DPAD_LEFT:  return direction(first, isUp, 2);
                case KeyEvent.KEYCODE_DPAD_RIGHT: return direction(first, isUp, 3);
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (first) setCArmed(false);
                    return button(first, isUp, BTN_A);
                case KeyEvent.KEYCODE_BACK:
                    if (first && cArmed) { setCArmed(false); return true; }
                    return button(first, isUp, BTN_B);
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    return button(first, isUp, BTN_START);
                case KeyEvent.KEYCODE_MEDIA_REWIND: // Z = left trigger
                    if (first) nativeSetAxis(AXIS_TRIGGERLEFT, TILT);
                    else if (isUp) nativeSetAxis(AXIS_TRIGGERLEFT, AXIS_MIN);
                    return true;
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    return button(first, isUp, BTN_RIGHTSHOULDER);
                case KeyEvent.KEYCODE_MENU:
                    if (first) {
                        menuLongFired = false;
                        handler.postDelayed(menuLongPress, LONG_PRESS_MS);
                    } else if (isUp) {
                        handler.removeCallbacks(menuLongPress);
                        if (!menuLongFired) setCArmed(!cArmed);
                    }
                    return true;
                default:
                    return false;
            }
        } catch (UnsatisfiedLinkError notLoaded) {
            return false; // game library not loaded yet: let Android handle the key
        }
    }

    private boolean button(boolean first, boolean isUp, int id) {
        if (first) nativeSetButton(id, true);
        else if (isUp) nativeSetButton(id, false);
        return true;
    }

    private boolean direction(boolean first, boolean isUp, int dir) {
        if (first && cArmed) {
            setCArmed(false);
            // C-buttons are on the right stick: up/left = negative, down/right = positive
            final int axis = (dir <= 1) ? AXIS_RIGHTY : AXIS_RIGHTX;
            final int value = (dir == 0 || dir == 2) ? AXIS_MIN : TILT;
            nativeSetAxis(axis, value);
            handler.postDelayed(() -> nativeSetAxis(axis, 0), PULSE_MS);
            return true;
        }
        if (!first && !isUp) return true; // key repeat while held: already tilted
        boolean held = first;
        switch (dir) {
            case 0: up = held; break;
            case 1: down = held; break;
            case 2: left = held; break;
            default: right = held; break;
        }
        nativeSetAxis(AXIS_LEFTX, (right ? TILT : 0) - (left ? TILT : 0));
        nativeSetAxis(AXIS_LEFTY, (down ? TILT : 0) - (up ? TILT : 0));
        return true;
    }

    private void pulse(int id) {
        nativeSetButton(id, true);
        handler.postDelayed(() -> nativeSetButton(id, false), PULSE_MS);
    }

    private void setCArmed(boolean armed) {
        cArmed = armed;
        if (cIndicator != null) cIndicator.setVisibility(armed ? View.VISIBLE : View.GONE);
    }

    /** Let go of everything (focus lost mid-press). */
    void releaseAll() {
        handler.removeCallbacksAndMessages(null);
        up = down = left = right = false;
        setCArmed(false);
        try {
            for (int a = 0; a < 4; a++) nativeSetAxis(a, 0);
            nativeSetAxis(AXIS_TRIGGERLEFT, AXIS_MIN);
            nativeSetAxis(AXIS_TRIGGERRIGHT, AXIS_MIN);
            for (int b : new int[] { BTN_A, BTN_B, BTN_START, BTN_RIGHTSHOULDER }) nativeSetButton(b, false);
        } catch (UnsatisfiedLinkError ignored) { }
    }
}
