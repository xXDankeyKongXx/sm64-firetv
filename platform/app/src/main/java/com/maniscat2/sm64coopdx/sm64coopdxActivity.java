package com.maniscat2.sm64coopdx;

import org.libsdl.app.SDLActivity;

public class sm64coopdxActivity extends SDLActivity
{
    /**
     * Fire TV build: there is no 32-bit curl library (the game's own update checker is compiled
     * out with NO_CURL), so only load SDL2 and the game. libc++_shared is pulled in as a
     * dependency by the dynamic linker.
     */
    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "main" };
    }
}
