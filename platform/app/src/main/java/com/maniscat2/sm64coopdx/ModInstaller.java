package com.maniscat2.sm64coopdx;

import android.os.Build;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Downloads GameBanana files and installs them the way sm64coopdx expects
 * (same rules as the game's own drag-and-drop import, src/pc/mods/mod_import.c):
 *   - anything with .lua/.luac is a Lua mod: a folder with main.lua, or a single .lua file,
 *     installed into user/mods/ and turned on ("enable-mod:" in sm64config.txt)
 *   - otherwise .bin/.tex/.png/.lvl/.bhv/.col files make a DynOS model/texture pack, installed as a
 *     folder in user/dynos/packs/ and turned on ("dynos-pack:")
 *   - palette .ini files go to user/palettes/
 * Downloads unpack into a staging folder first; mods move into place only when complete.
 */
final class ModInstaller {

    interface Progress { void onProgress(String stage, long done, long total); }

    static final class CancelledException extends IOException {
        CancelledException() { super("Cancelled"); }
    }

    enum Kind { LUA_MOD, DYNOS_PACK, PALETTE }

    /** Something installed in user/mods, user/dynos/packs or user/palettes. */
    static final class InstalledMod {
        File location;
        String name;           // file/folder name (the game's relative path / pack name)
        String title;          // GameBanana title if installed from there
        Kind kind;
        boolean enabled;
        long sizeBytes;
        int gameBananaId;
    }

    /** Result of an install: what went where. */
    static final class Result {
        final List<InstalledMod> installed = new ArrayList<>();
    }

    private static final String MANIFEST = ".gamebanana.json";
    private static final int MAX_NESTING = 2;
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);

    static boolean tryBegin() { return BUSY.compareAndSet(false, true); }
    static void end() { BUSY.set(false); }
    static File stagingRoot() { return new File(LauncherConfig.gameDir(), ".staging"); }

    static void cleanupStaging(File cacheDir) {
        if (BUSY.get()) return;
        deleteRecursive(stagingRoot());
        File[] old = cacheDir.listFiles((d, n) -> n.startsWith("gb-"));
        if (old != null) for (File f : old) deleteRecursive(f);
    }

    // ---- Install --------------------------------------------------------------------------------

    static Result install(GameBananaApi.ModSummary mod, GameBananaApi.ModFile file, File cacheDir,
                          Progress progress, AtomicBoolean cancel) throws IOException {
        String nonce = file.id + "-" + System.currentTimeMillis();
        File work = new File(cacheDir, "gb-" + nonce);
        File stage = new File(stagingRoot(), nonce);
        deleteRecursive(work);
        if (!work.mkdirs()) throw new IOException("Couldn't create a download folder");
        try {
            File download = new File(work, safeName(file.name));
            downloadTo(file.downloadUrl, download, file.size, progress, cancel);
            if (file.md5 != null && !file.md5.isEmpty()) {
                progress.onProgress("Checking file…", 0, 0);
                String actual = md5(download);
                if (!actual.isEmpty() && !actual.equalsIgnoreCase(file.md5)) {
                    throw new IOException("Download was corrupted (checksum mismatch). Try again.");
                }
            }
            checkFreeSpace(download.length() * 4);
            if (!stage.mkdirs()) throw new IOException("Couldn't create " + stage);

            progress.onProgress("Unpacking…", 0, 0);
            unpack(download, stage, work, 0, cancel);
            if (cancel.get()) throw new CancelledException();

            progress.onProgress("Installing…", 0, 0);
            return placeMods(stage, mod, file);
        } finally {
            deleteRecursive(work);
            deleteRecursive(stage);
        }
    }

    /** Sorts unpacked files into Lua mods / DynOS packs / palettes and moves them into place. */
    private static Result placeMods(File stage, GameBananaApi.ModSummary mod, GameBananaApi.ModFile file)
            throws IOException {
        Result result = new Result();
        List<File> all = new ArrayList<>();
        listFiles(stage, all);
        boolean hasLua = false, hasDynos = false, hasPalette = false;
        for (File f : all) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".lua") || n.endsWith(".luac")) hasLua = true;
            else if (n.endsWith(".tex") || n.endsWith(".png") || n.endsWith(".bin") || n.endsWith(".lvl")
                    || n.endsWith(".bhv") || n.endsWith(".col")) hasDynos = true;
            else if (n.endsWith(".ini")) hasPalette = true;
        }

        if (hasLua) {
            // Each folder holding a main.lua is a mod; loose .lua files outside such folders are
            // single-file mods.
            List<File> modFolders = new ArrayList<>();
            findMainLuaFolders(stage, modFolders);
            for (File folder : modFolders) {
                String name = folder.equals(stage) ? safeName(mod.name) : safeName(folder.getName());
                result.installed.add(moveInto(folder, LauncherConfig.modsDir(), name, Kind.LUA_MOD, mod, file));
            }
            List<File> loose = new ArrayList<>();
            findLooseLua(stage, modFolders, loose);
            for (File lua : loose) {
                result.installed.add(moveInto(lua, LauncherConfig.modsDir(), safeName(lua.getName()), Kind.LUA_MOD, mod, file));
            }
        } else if (hasDynos) {
            // A pack is one folder; use the archive's single top folder when there is one.
            File packRoot = singleTopFolder(stage);
            String name = safeName(packRoot != null ? packRoot.getName() : mod.name);
            result.installed.add(moveInto(packRoot != null ? packRoot : stage, LauncherConfig.packsDir(),
                    name, Kind.DYNOS_PACK, mod, file));
        } else if (hasPalette) {
            for (File f : all) {
                if (f.getName().toLowerCase(Locale.ROOT).endsWith(".ini")) {
                    result.installed.add(moveInto(f, LauncherConfig.palettesDir(), safeName(f.getName()), Kind.PALETTE, mod, file));
                }
            }
        }
        if (result.installed.isEmpty()) {
            throw new IOException("Couldn't find a Coop Deluxe mod in " + file.name
                    + " (no .lua mod, DynOS pack or palette). It may be for a different port.");
        }

        // Turn on what was installed, like the game does after importing.
        LauncherConfig.Config c = LauncherConfig.Config.load();
        for (InstalledMod m : result.installed) {
            if (m.kind == Kind.LUA_MOD) c.setListed("enable-mod:", m.name, true);
            if (m.kind == Kind.DYNOS_PACK) c.setListed("dynos-pack:", m.name, true);
            m.enabled = true;
        }
        c.save();
        return result;
    }

    /** Moves src to destDir/name, replacing an earlier install of the same GameBanana mod only. */
    private static InstalledMod moveInto(File src, File destDir, String name, Kind kind,
                                         GameBananaApi.ModSummary mod, GameBananaApi.ModFile file) throws IOException {
        destDir.mkdirs();
        File target = new File(destDir, name);
        if (target.exists()) {
            if (manifestId(target) == mod.id) {
                deleteRecursive(target); // update of the same mod
            } else {
                int dot = name.lastIndexOf('.');
                String base = (kind == Kind.LUA_MOD && src.isFile() && dot > 0) ? name.substring(0, dot) : name;
                String ext = (kind == Kind.LUA_MOD && src.isFile() && dot > 0) ? name.substring(dot) : "";
                target = new File(destDir, base + "-" + mod.id + ext);
                deleteRecursive(target);
            }
        }
        if (src.isDirectory()) writeManifest(src, mod, file);
        if (!src.renameTo(target)) throw new IOException("Couldn't move " + name + " into place");
        if (target.isFile()) writeSidecar(target, mod);
        InstalledMod m = new InstalledMod();
        m.location = target;
        m.name = target.getName();
        m.title = mod.name;
        m.kind = kind;
        m.gameBananaId = mod.id;
        m.sizeBytes = sizeOf(target);
        return m;
    }

    private static void findMainLuaFolders(File dir, List<File> out) {
        File main = new File(dir, "main.lua");
        File mainc = new File(dir, "main.luac");
        if (main.isFile() || mainc.isFile()) { out.add(dir); return; } // don't look inside a mod
        File[] children = dir.listFiles();
        if (children == null) return;
        Arrays.sort(children);
        for (File c : children) if (c.isDirectory()) findMainLuaFolders(c, out);
    }

    private static void findLooseLua(File dir, List<File> modFolders, List<File> out) {
        if (modFolders.contains(dir)) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        Arrays.sort(children);
        for (File c : children) {
            if (c.isDirectory()) findLooseLua(c, modFolders, out);
            else {
                String n = c.getName().toLowerCase(Locale.ROOT);
                if (n.endsWith(".lua") || n.endsWith(".luac")) out.add(c);
            }
        }
    }

    private static File singleTopFolder(File stage) {
        File[] children = stage.listFiles(f -> !f.getName().startsWith("."));
        if (children != null && children.length == 1 && children[0].isDirectory()) return children[0];
        return null;
    }

    // ---- Download + unpack ----------------------------------------------------------------------

    private static void downloadTo(String url, File dest, long expectedSize, Progress progress, AtomicBoolean cancel)
            throws IOException {
        HttpURLConnection c = GameBananaApi.open(url);
        c.setRequestProperty("Accept", "*/*");
        c.setReadTimeout(60000);
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("Download failed (HTTP " + code + ")");
            long header = c.getContentLengthLong();
            long total = header > 0 ? header : expectedSize;
            long done = 0, last = 0;
            try (InputStream in = new BufferedInputStream(c.getInputStream());
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancel.get()) throw new CancelledException();
                    out.write(buf, 0, n);
                    done += n;
                    if (done - last > 256 * 1024 || done == total) {
                        last = done;
                        progress.onProgress("Downloading…", done, total);
                    }
                }
            }
            if (header > 0 && done != header) throw new IOException("Download was cut off. Try again.");
        } finally {
            c.disconnect();
        }
    }

    /** Unpacks a download (or copies a plain file) into dest, keeping its folder structure. */
    private static void unpack(File archive, File dest, File work, int depth, AtomicBoolean cancel) throws IOException {
        String lower = archive.getName().toLowerCase(Locale.ROOT);
        byte[] head = readHead(archive, 8);
        boolean zip = lower.endsWith(".zip") || (head[0] == 'P' && head[1] == 'K');
        boolean sevenZ = lower.endsWith(".7z") || (head[0] == '7' && head[1] == 'z');
        boolean rar = lower.endsWith(".rar") || (head[0] == 'R' && head[1] == 'a' && head[2] == 'r');
        if (zip) unzip(archive, dest, cancel);
        else if (sevenZ) { needsApi26("7z"); un7z(archive, dest, cancel); }
        else if (rar) { needsApi26("RAR"); unrar(archive, dest, cancel); }
        else {
            copyFile(archive, new File(dest, safeName(archive.getName()))); // e.g. a bare .lua
            return;
        }
        // Archives inside the archive ("pick a version" packs): unpack them next to themselves.
        if (depth < MAX_NESTING) {
            List<File> all = new ArrayList<>();
            listFiles(dest, all);
            for (File f : all) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (n.endsWith(".zip") || n.endsWith(".7z") || n.endsWith(".rar")) {
                    File into = new File(f.getParentFile(), stripExt(f.getName()));
                    into.mkdirs();
                    try {
                        unpack(f, into, work, depth + 1, cancel);
                    } catch (CancelledException c) {
                        throw c;
                    } catch (IOException ignored) {
                        // not a usable archive; leave it
                    }
                    f.delete();
                }
            }
        }
    }

    private static void needsApi26(String kind) throws IOException {
        if (Build.VERSION.SDK_INT < 26) {
            throw new IOException("Unpacking " + kind + " files needs Android 8 (Fire OS 7) or newer.");
        }
    }

    /** Resolves an archive entry path inside dest, refusing anything that would escape it. */
    private static File safeTarget(File dest, String entryName) throws IOException {
        String norm = entryName.replace('\\', '/');
        StringBuilder rel = new StringBuilder();
        for (String part : norm.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) continue;
            String clean = part.replaceAll("[:*?\"<>|\\p{Cntrl}]", "").trim();
            if (clean.isEmpty()) continue;
            if (rel.length() > 0) rel.append('/');
            rel.append(clean);
        }
        if (rel.length() == 0) return null;
        File f = new File(dest, rel.toString());
        String root = dest.getCanonicalPath() + File.separator;
        if (!f.getCanonicalPath().startsWith(root)) throw new IOException("Unsafe path in archive: " + entryName);
        return f;
    }

    private static boolean skipEntry(String name) {
        String n = name.replace('\\', '/');
        return n.startsWith("__MACOSX/") || n.contains("/__MACOSX/") || n.endsWith(".DS_Store");
    }

    private static void unzip(File zip, File dest, AtomicBoolean cancel) throws IOException {
        try (ZipFile zf = openZip(zip)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                if (cancel.get()) throw new CancelledException();
                ZipEntry e = entries.nextElement();
                if (skipEntry(e.getName())) continue;
                File out = safeTarget(dest, e.getName());
                if (out == null) continue;
                if (e.isDirectory()) { out.mkdirs(); continue; }
                out.getParentFile().mkdirs();
                try (InputStream in = zf.getInputStream(e)) { writeStream(in, out); }
            }
        }
    }

    private static ZipFile openZip(File zip) throws IOException {
        try {
            return new ZipFile(zip, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return new ZipFile(zip, java.nio.charset.Charset.forName("IBM437"));
        }
    }

    private static void un7z(File archive, File dest, AtomicBoolean cancel) throws IOException {
        try (SevenZFile z = SevenZFile.builder().setFile(archive).setMaxMemoryLimitKb(128 * 1024).get()) {
            SevenZArchiveEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = z.getNextEntry()) != null) {
                if (cancel.get()) throw new CancelledException();
                if (skipEntry(e.getName())) continue;
                File out = safeTarget(dest, e.getName());
                if (out == null) continue;
                if (e.isDirectory()) { out.mkdirs(); continue; }
                if (!e.hasStream()) continue;
                out.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(out)) {
                    int n;
                    while ((n = z.read(buf)) > 0) o.write(buf, 0, n);
                }
            }
        } catch (org.apache.commons.compress.MemoryLimitException e) {
            throw new IOException("This 7z file needs more memory to unpack than the TV can spare.");
        }
    }

    private static void unrar(File rar, File dest, AtomicBoolean cancel) throws IOException {
        try (Archive archive = new Archive(rar)) {
            for (FileHeader h : archive.getFileHeaders()) {
                if (cancel.get()) throw new CancelledException();
                if (skipEntry(h.getFileName())) continue;
                File out = safeTarget(dest, h.getFileName());
                if (out == null) continue;
                if (h.isDirectory()) { out.mkdirs(); continue; }
                out.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(out)) { archive.extractFile(h, o); }
            }
        } catch (com.github.junrar.exception.UnsupportedRarV5Exception e) {
            throw new IOException("This mod is packed as RAR 5, which the TV app can't unpack. "
                    + "Pick a .zip/.7z file if the mod has one, or install it from a computer.");
        } catch (com.github.junrar.exception.RarException e) {
            throw new IOException("Couldn't unpack the RAR file: " + e.getMessage());
        }
    }

    // ---- Installed mods -------------------------------------------------------------------------

    static List<InstalledMod> listInstalled() {
        List<InstalledMod> out = new ArrayList<>();
        LauncherConfig.Config cfg;
        try { cfg = LauncherConfig.Config.load(); } catch (IOException e) { cfg = new LauncherConfig.Config(); }
        java.util.Set<String> enabledMods = cfg.list("enable-mod:");
        java.util.Set<String> enabledPacks = cfg.list("dynos-pack:");

        File[] mods = LauncherConfig.modsDir().listFiles();
        if (mods != null) {
            for (File f : mods) {
                if (f.getName().startsWith(".")) continue;
                boolean isMod = f.isDirectory()
                        ? (new File(f, "main.lua").isFile() || new File(f, "main.luac").isFile())
                        : (f.getName().endsWith(".lua") || f.getName().endsWith(".luac"));
                if (!isMod) continue;
                out.add(describe(f, Kind.LUA_MOD, enabledMods.contains(f.getName())));
            }
        }
        File[] packs = LauncherConfig.packsDir().listFiles();
        if (packs != null) {
            for (File f : packs) {
                if (!f.isDirectory() || f.getName().startsWith(".")) continue;
                out.add(describe(f, Kind.DYNOS_PACK, enabledPacks.contains(f.getName())));
            }
        }
        out.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
        return out;
    }

    private static InstalledMod describe(File f, Kind kind, boolean enabled) {
        InstalledMod m = new InstalledMod();
        m.location = f;
        m.name = f.getName();
        m.title = f.getName();
        m.kind = kind;
        m.enabled = enabled;
        m.sizeBytes = sizeOf(f);
        File manifest = f.isDirectory() ? new File(f, MANIFEST) : sidecarFor(f);
        if (manifest.isFile()) {
            try {
                JSONObject j = new JSONObject(LauncherConfig.readText(manifest));
                m.title = j.optString("name", m.title);
                m.gameBananaId = j.optInt("gameBananaId");
            } catch (IOException | JSONException ignored) { }
        }
        return m;
    }

    static void setEnabled(InstalledMod m, boolean enabled) throws IOException {
        if (m.kind == Kind.LUA_MOD) LauncherConfig.setModEnabled(m.name, enabled);
        else if (m.kind == Kind.DYNOS_PACK) LauncherConfig.setPackEnabled(m.name, enabled);
        m.enabled = enabled;
    }

    static void delete(InstalledMod m) throws IOException {
        deleteRecursive(m.location);
        if (m.location.isFile() || m.location.isDirectory()) throw new IOException("Couldn't delete " + m.name);
        if (m.location.getParentFile() != null) sidecarFor(m.location).delete();
        setEnabled(m, false);
    }

    static java.util.Set<Integer> installedIds() {
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (InstalledMod m : listInstalled()) if (m.gameBananaId != 0) ids.add(m.gameBananaId);
        return ids;
    }

    // ---- Helpers --------------------------------------------------------------------------------

    private static File sidecarFor(File modFile) {
        // Single-file mods keep their GameBanana info next to them, as a hidden file.
        return new File(modFile.getParentFile(), "." + modFile.getName() + MANIFEST);
    }

    private static int manifestId(File target) {
        File manifest = target.isDirectory() ? new File(target, MANIFEST) : sidecarFor(target);
        if (!manifest.isFile()) return 0;
        try { return new JSONObject(LauncherConfig.readText(manifest)).optInt("gameBananaId"); }
        catch (IOException | JSONException e) { return 0; }
    }

    private static void writeManifest(File dir, GameBananaApi.ModSummary mod, GameBananaApi.ModFile file) {
        writeJson(new File(dir, MANIFEST), mod, file);
    }

    private static void writeSidecar(File modFile, GameBananaApi.ModSummary mod) {
        writeJson(sidecarFor(modFile), mod, null);
    }

    private static void writeJson(File out, GameBananaApi.ModSummary mod, GameBananaApi.ModFile file) {
        try {
            JSONObject j = new JSONObject();
            j.put("gameBananaId", mod.id);
            j.put("name", mod.name);
            j.put("version", mod.version == null ? "" : mod.version);
            if (file != null) { j.put("file", file.name); j.put("fileId", file.id); }
            j.put("installedAt", System.currentTimeMillis());
            try (OutputStream o = new FileOutputStream(out)) {
                o.write(j.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | JSONException ignored) { }
    }

    private static void listFiles(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.isDirectory()) listFiles(c, out);
            else out.add(c);
        }
    }

    private static String stripExt(String n) {
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static void checkFreeSpace(long needed) throws IOException {
        long free = android.os.Environment.getExternalStorageDirectory().getUsableSpace();
        if (free > 0 && free < needed) {
            throw new IOException("Not enough free space on the TV (" + formatSize(free) + " free, about "
                    + formatSize(needed) + " needed).");
        }
    }

    static String safeName(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").trim();
        s = s.replaceAll("\\s+", " "); // the game joins config tokens with single spaces
        while (s.startsWith(".")) s = s.substring(1);
        if (s.length() > 60) s = s.substring(0, 60).trim();
        return s.isEmpty() ? "mod" : s;
    }

    private static void writeStream(InputStream in, File dest) throws IOException {
        try (OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static void copyFile(File src, File dest) throws IOException {
        try (InputStream in = new FileInputStream(src)) { writeStream(in, dest); }
    }

    private static byte[] readHead(File f, int n) throws IOException {
        byte[] b = new byte[n];
        try (InputStream in = new FileInputStream(f)) {
            if (in.read(b) < 3) Arrays.fill(b, (byte) 0);
        }
        return b;
    }

    private static String md5(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", x));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }

    static long sizeOf(File f) {
        if (f.isFile()) return f.length();
        long total = 0;
        File[] files = f.listFiles();
        if (files != null) for (File c : files) total += sizeOf(c);
        return total;
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursive(c);
        f.delete();
    }

    static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        if (bytes >= 1024) return String.format(Locale.ROOT, "%d KB", bytes / 1024);
        return bytes + " B";
    }

    private ModInstaller() { }
}
