package com.maniscat2.sm64coopdx;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Where sm64coopdx keeps its files on Android, its settings file (user/sm64config.txt, plain
 * "key value" lines), and the launcher's video presets.
 *
 * Paths match src/pc/platform.c: with storage permission the game uses
 * /sdcard/com.maniscat2.sm64coopdx, and its user data lives in the "user" folder below that.
 */
final class LauncherConfig {

    static final String GAME_FOLDER = "com.maniscat2.sm64coopdx"; // ANDROID_APPNAME in platform.h
    static final long LOW_RAM_BYTES = 2560L * 1024 * 1024;

    static File gameDir() { return new File(Environment.getExternalStorageDirectory(), GAME_FOLDER); }
    static File userDir() { return new File(gameDir(), "user"); }
    static File modsDir() { return new File(userDir(), "mods"); }
    static File packsDir() { return new File(userDir(), "dynos/packs"); }
    static File palettesDir() { return new File(userDir(), "palettes"); }
    static File configFile() { return new File(userDir(), "sm64config.txt"); }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(sm64coopdxActivity.PREFS, Context.MODE_PRIVATE);
    }

    static boolean isLowRamDevice(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.totalMem > 0 && mi.totalMem <= LOW_RAM_BYTES;
    }

    /** Render height on a TV when no preset was chosen: 720p on 2 GB devices, else 1080p. */
    static int defaultRenderHeight(Context ctx) {
        return isLowRamDevice(ctx) ? 720 : 1080;
    }

    // ---- Video presets -------------------------------------------------------------------------

    static final class Preset {
        final String id, title, summary;
        final int renderHeight, filtering, framerateMode, frameLimit, drawDistance, msaa;
        Preset(String id, String title, String summary, int renderHeight, int filtering,
               int framerateMode, int frameLimit, int drawDistance, int msaa) {
            this.id = id; this.title = title; this.summary = summary; this.renderHeight = renderHeight;
            this.filtering = filtering; this.framerateMode = framerateMode; this.frameLimit = frameLimit;
            this.drawDistance = drawDistance; this.msaa = msaa;
        }
    }

    // filtering: 0 nearest, 1 bilinear, 2 trilinear. framerate_mode: 0 auto (display), 1 manual.
    // coop_draw_distance: 0 = 0.5x, 1 = 1x (N64), 2 = 1.5x, 3 = 3x, ...
    static final Preset[] PRESETS = {
        new Preset("performance", "Performance",
                "720p, 30 fps cap, normal draw distance. For the weakest TVs and sticks.",
                720, 1, 1, 30, 1, 0),
        new Preset("balanced", "Balanced (recommended)",
                "720p at the TV's full frame rate, smooth filtering. Best fit for 2 GB Fire TVs.",
                720, 1, 0, 60, 1, 0),
        new Preset("quality", "Quality",
                "1080p, trilinear filtering, 1.5x draw distance. For Fire TV Cube / 4K Max class devices.",
                1080, 2, 0, 60, 2, 0),
        new Preset("retro", "Retro N64",
                "Sharp unfiltered textures and N64 draw distance at 720p.",
                720, 0, 0, 60, 1, 0),
    };

    static Preset presetById(String id) {
        for (Preset p : PRESETS) if (p.id.equals(id)) return p;
        return null;
    }

    static String presetLabel(Context ctx) {
        String id = prefs(ctx).getString("videoPreset", "");
        Preset p = presetById(id);
        if (p != null) return p.title;
        return "custom".equals(id) ? "Custom" : "Automatic";
    }

    static void applyPreset(Context ctx, Preset p) throws IOException {
        Config c = Config.load();
        c.set("texture_filtering", String.valueOf(p.filtering));
        c.set("framerate_mode", String.valueOf(p.framerateMode));
        c.set("frame_limit", String.valueOf(p.frameLimit));
        c.set("coop_draw_distance", String.valueOf(p.drawDistance));
        c.set("msaa", String.valueOf(p.msaa));
        c.save();
        prefs(ctx).edit()
                .putInt(sm64coopdxActivity.PREF_RENDER_HEIGHT, p.renderHeight)
                .putString("videoPreset", p.id)
                .apply();
    }

    // ---- sm64config.txt ------------------------------------------------------------------------

    /**
     * The game's settings file, edited line by line so everything the launcher doesn't touch
     * (key binds, players, bans...) is kept exactly as the game wrote it.
     */
    static final class Config {
        final List<String> lines = new ArrayList<>();

        static Config load() throws IOException {
            Config c = new Config();
            File f = configFile();
            if (f.exists()) {
                for (String l : readText(f).split("\n", -1)) c.lines.add(l.replace("\r", ""));
                while (!c.lines.isEmpty() && c.lines.get(c.lines.size() - 1).isEmpty()) {
                    c.lines.remove(c.lines.size() - 1);
                }
            }
            return c;
        }

        String get(String key) {
            for (String l : lines) {
                String[] t = l.trim().split("\\s+", 2);
                if (t.length == 2 && t[0].equals(key)) return t[1].trim();
            }
            return null;
        }

        int getInt(String key, int fallback) {
            try { String v = get(key); return v == null ? fallback : Integer.parseInt(v); }
            catch (NumberFormatException e) { return fallback; }
        }

        void set(String key, String value) {
            for (int i = 0; i < lines.size(); i++) {
                String[] t = lines.get(i).trim().split("\\s+", 2);
                if (t.length >= 1 && t[0].equals(key)) { lines.set(i, key + " " + value); return; }
            }
            lines.add(key + " " + value);
        }

        /** Values of repeated "key: value" lines, e.g. enable-mod: and dynos-pack:. */
        Set<String> list(String key) {
            Set<String> out = new LinkedHashSet<>();
            for (String l : lines) {
                if (l.startsWith(key + " ")) out.add(l.substring(key.length() + 1).trim());
            }
            return out;
        }

        void setListed(String key, String value, boolean present) {
            String line = key + " " + value;
            lines.removeIf(l -> l.trim().equals(line));
            if (present) lines.add(line);
        }

        void save() throws IOException {
            File f = configFile();
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            File tmp = new File(f.getPath() + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) {
                f.delete();
                if (!tmp.renameTo(f)) throw new IOException("Couldn't save " + f.getName());
            }
        }
    }

    static boolean isModEnabled(String relativePath) {
        try { return Config.load().list("enable-mod:").contains(relativePath); }
        catch (IOException e) { return false; }
    }

    static void setModEnabled(String relativePath, boolean enabled) throws IOException {
        Config c = Config.load();
        c.setListed("enable-mod:", relativePath, enabled);
        c.save();
    }

    static boolean isPackEnabled(String name) {
        try { return Config.load().list("dynos-pack:").contains(name); }
        catch (IOException e) { return false; }
    }

    static void setPackEnabled(String name, boolean enabled) throws IOException {
        Config c = Config.load();
        c.setListed("dynos-pack:", name, enabled);
        c.save();
    }

    static String readText(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bytes.write(buf, 0, n);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private LauncherConfig() { }
}
