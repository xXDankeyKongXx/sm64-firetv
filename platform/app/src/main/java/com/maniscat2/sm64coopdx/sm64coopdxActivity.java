package com.maniscat2.sm64coopdx;

import android.app.AlertDialog;
import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.Toast;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The game. Fire TV additions: play with the remote (RemoteInput), a remote-friendly ROM list
 * instead of the system file picker Fire TV doesn't have, and a smaller render surface the TV's
 * hardware scaler upscales.
 *
 * Front ends can start this activity directly (skipping the launcher) with optional extras:
 *   rom         String   path to the Super Mario 64 (USA) .z64, used when the game asks for one
 *   resolution  int      render height (480/720/1080, 0 = native); remembered
 */
public class sm64coopdxActivity extends SDLActivity
{
    private static final String TAG = "SM64-FireTV";
    static final String PREFS = "sm64firetv";
    static final String PREF_RENDER_HEIGHT = "renderHeight";

    /** True while the game exists; the launcher offers "Resume" instead of a fresh start. */
    static volatile boolean sGameRunning = false;

    private boolean isTv;
    private RemoteInput remote;
    private String launcherRomPath;
    private boolean romChooserShowing = false;

    /**
     * No 32-bit curl library (the game's own update checker is compiled out with NO_CURL), so
     * only load SDL2 and the game; libc++_shared comes in as a dependency.
     */
    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "main" };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        sGameRunning = true;
        isTv = detectTv(this);
        readLaunchIntent(getIntent());
        super.onCreate(savedInstanceState);
        applyRenderResolution();
        if (isTv) {
            remote = new RemoteInput();
            remote.attachIndicator((ViewGroup) getContentView());
        }
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setPreferMinimalPostProcessing(true); // TV game mode
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readLaunchIntent(intent);
    }

    @Override
    protected void onPause() {
        if (remote != null) remote.releaseAll();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        sGameRunning = false;
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (remote != null && remote.handle(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    static boolean detectTv(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        UiModeManager ui = (UiModeManager) ctx.getSystemService(Context.UI_MODE_SERVICE);
        return (ui != null && ui.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION)
                || pm.hasSystemFeature("amazon.hardware.fire_tv")
                || pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                || !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
    }

    private void readLaunchIntent(Intent intent) {
        if (intent == null) return;
        String rom = intent.getStringExtra("rom");
        if (rom != null && !rom.isEmpty()) launcherRomPath = rom;
        if (intent.hasExtra("resolution")) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt(PREF_RENDER_HEIGHT, intent.getIntExtra("resolution", 720)).apply();
        }
    }

    // Render into a smaller surface and let the display hardware upscale it for free.
    private void applyRenderResolution() {
        int def = isTv ? LauncherConfig.defaultRenderHeight(this) : 0;
        int targetHeight = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_RENDER_HEIGHT, def);
        if (targetHeight <= 0 || mSurface == null) return;
        DisplayMetrics real = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(real);
        int screenW = Math.max(real.widthPixels, real.heightPixels);
        int screenH = Math.min(real.widthPixels, real.heightPixels);
        if (screenH <= 0 || targetHeight >= screenH) return;
        int targetWidth = Math.round(targetHeight * (screenW / (float) screenH)) & ~1;
        Log.i(TAG, "Render surface " + targetWidth + "x" + targetHeight + " (display " + screenW + "x" + screenH + ")");
        // SurfaceView sizes itself from setFixedSize() under WRAP_CONTENT; keep it full screen.
        mSurface.setLayoutParams(new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mSurface.getHolder().setFixedSize(targetWidth, targetHeight);
    }

    // ---------------------------------------------------------------------------------------------
    // ROM selection. The game calls openFilePicker() until it has a valid ROM; Fire TV has no
    // system file picker, so list .z64 files found on the device instead. The game checks the
    // ROM's MD5 and copies it into its own folder.
    // ---------------------------------------------------------------------------------------------

    @Override
    public void openFilePicker() {
        runOnUiThread(this::chooseRom);
    }

    private void chooseRom() {
        if (romChooserShowing) return;
        if (launcherRomPath != null) {
            File f = new File(launcherRomPath);
            launcherRomPath = null;
            if (f.isFile() && f.canRead()) {
                deliverRom(f);
                return;
            }
            Toast.makeText(this, "Can't read ROM: " + f.getPath(), Toast.LENGTH_LONG).show();
        }
        romChooserShowing = true;
        Executors.newSingleThreadExecutor().execute(() -> {
            List<File> roms = scanForRoms();
            runOnUiThread(() -> showRomChooser(roms));
        });
    }

    private void showRomChooser(List<File> roms) {
        if (isFinishing()) return;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        String sd = Environment.getExternalStorageDirectory().getAbsolutePath();
        for (File rom : roms) {
            String shown = rom.getAbsolutePath().startsWith(sd) ? rom.getAbsolutePath().substring(sd.length()) : rom.getAbsolutePath();
            labels.add(rom.getName() + "   (" + (rom.length() / (1024 * 1024)) + " MB, " + shown + ")");
            actions.add(() -> deliverRom(rom));
        }
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*");
        if (picker.resolveActivity(getPackageManager()) != null) {
            labels.add("Browse with file picker…");
            actions.add(() -> super.openFilePicker());
        }
        labels.add("Rescan");
        actions.add(this::chooseRom);
        labels.add("Quit");
        actions.add(() -> { finishAffinity(); System.exit(0); });

        String title = roms.isEmpty()
                ? "No ROM found. Copy your Super Mario 64 (USA) .z64 to the Download folder or a USB drive, then Rescan."
                : "Select your Super Mario 64 (USA) ROM";
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setCancelable(false)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    romChooserShowing = false;
                    actions.get(which).run();
                })
                .show();
    }

    private void deliverRom(File rom) {
        Log.i(TAG, "Using ROM " + rom.getAbsolutePath());
        nativeFilePicked(rom.getAbsolutePath()); // validates (MD5) and copies into the game folder
    }

    static List<File> scanForRoms() {
        File sd = Environment.getExternalStorageDirectory();
        List<File> roots = new ArrayList<>();
        for (String n : new String[] { "Download", "Downloads", "ROMs", "Roms", "roms", "N64", "n64",
                "ROMs/N64", "roms/n64", "roms/N64", "Roms/N64", "SM64", "Emulation/roms/n64" }) {
            roots.add(new File(sd, n));
        }
        File[] volumes = new File("/storage").listFiles();  // USB drives
        if (volumes != null) {
            for (File v : volumes) {
                if (!v.getName().equals("emulated") && !v.getName().equals("self")) roots.add(v);
            }
        }
        Map<String, File> found = new LinkedHashMap<>();
        for (File r : roots) scan(r, 2, found);
        scan(sd, 0, found);
        return new ArrayList<>(found.values());
    }

    private static void scan(File dir, int depth, Map<String, File> found) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                if (depth > 0 && !f.getName().startsWith(".")) scan(f, depth - 1, found);
                continue;
            }
            String n = f.getName().toLowerCase(Locale.ROOT);
            long len = f.length();
            // SM64 is 8 MB (expanded hacks are larger); the game rejects anything that isn't the USA ROM.
            if ((n.endsWith(".z64") || n.endsWith(".n64") || n.endsWith(".v64"))
                    && len >= 8L * 1024 * 1024 && len <= 64L * 1024 * 1024) {
                String key;
                try { key = f.getCanonicalPath(); } catch (IOException e) { key = f.getAbsolutePath(); }
                found.put(key, f);
            }
        }
    }
}
