package com.gathin.tvhome;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the app switcher shows: the apps used most recently (Android's usage history, which the launcher
 * allows itself over loopback ADB), and the last frame of each one as it was left.
 *
 * Frames are taken by PhoneRemote's watcher while an app is on screen, through the key helper's screen
 * capture, every few seconds. Each is kept at full screen size as a JPEG (~200 KB) in memory only, so a
 * card is sharp at the size the switcher shows it and only the cards on screen are ever unpacked. Apps
 * whose video is protected (Netflix, Prime Video) capture as black or not at all; they are remembered as
 * such and the switcher draws them a card of their own.
 */
final class Recents {
    private static final Map<String, byte[]> frames = new ConcurrentHashMap<>();
    private static final Set<String> blocked = ConcurrentHashMap.newKeySet();
    /** Black frames in a row per app: one is an app loading, two in a row is protected video. */
    private static final Map<String, Integer> blackRun = new ConcurrentHashMap<>();
    /** Apps closed from the switcher, with when: hidden until they are used again. */
    private static final Map<String, Long> closedAt = new ConcurrentHashMap<>();

    /** Where frames are also kept, so a reboot or an update does not leave every card blank. */
    private static volatile java.io.File cacheDir;

    private Recents() {
    }

    static void useCache(Context ctx) {
        java.io.File d = new java.io.File(ctx.getCacheDir(), "switcher");
        if (d.isDirectory() || d.mkdirs()) cacheDir = d;
        // Closed cards stay closed across restarts too, until the app is used again.
        try {
            for (String line : java.nio.file.Files.readAllLines(new java.io.File(d, "closed.txt").toPath())) {
                int eq = line.indexOf('=');
                if (eq > 0) closedAt.put(line.substring(0, eq), Long.parseLong(line.substring(eq + 1)));
            }
        } catch (Exception ignored) {
        }
    }

    private static void saveClosed() {
        java.io.File d = cacheDir;
        if (d == null) return;
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Long> e : closedAt.entrySet()) b.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        try {
            java.nio.file.Files.write(new java.io.File(d, "closed.txt").toPath(), b.toString().getBytes());
        } catch (java.io.IOException ignored) {
        }
    }

    private static java.io.File file(String pkg) {
        java.io.File d = cacheDir;
        return d == null || !pkg.matches("[A-Za-z0-9_.]+") ? null : new java.io.File(d, pkg + ".jpg");
    }

    /** The last frame of {@code pkg}, unpacked at 1/{@code sample} of the screen, or null. */
    static Bitmap frame(String pkg, int sample) {
        byte[] jpeg = frames.get(pkg);
        if (jpeg == null) {
            java.io.File f = file(pkg);
            if (f == null || !f.isFile()) return null;
            try {
                jpeg = java.nio.file.Files.readAllBytes(f.toPath());
                frames.put(pkg, jpeg);
            } catch (java.io.IOException e) {
                return null;
            }
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
    }

    static boolean isProtected(String pkg) {
        return blocked.contains(pkg);
    }

    static void forget(String pkg) {
        frames.remove(pkg);
        java.io.File f = file(pkg);
        if (f != null) f.delete();
        closedAt.put(pkg, System.currentTimeMillis());
        saveClosed();
    }

    /**
     * A screen capture (PNG) while {@code pkg} was in front. An empty capture (the shell may not read a
     * secure window at all) or a black one marks the app as protected.
     */
    static void capture(String pkg, byte[] png) {
        if (png == null) return;   // the helper is not running: no news
        if (png.length == 0) {     // the shell could not read the window at all: protected for sure
            blocked.add(pkg);
            frames.remove(pkg);
            java.io.File f = file(pkg);
            if (f != null) f.delete();
            return;
        }
        Bitmap b = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (b == null) return;
        if (isBlack(b)) {
            b.recycle();
            // A black frame can be an app between screens; only a second one in a row means protected.
            int run = blackRun.merge(pkg, 1, Integer::sum);
            if (run >= 2) {
                blocked.add(pkg);
                frames.remove(pkg);
            }
            return;
        }
        blackRun.remove(pkg);
        blocked.remove(pkg);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(256 * 1024);
        b.compress(Bitmap.CompressFormat.JPEG, 86, out);
        b.recycle();
        byte[] jpeg = out.toByteArray();
        frames.put(pkg, jpeg);
        java.io.File f = file(pkg);
        if (f != null) {
            try {
                java.nio.file.Files.write(f.toPath(), jpeg);
            } catch (java.io.IOException ignored) {
            }
        }
    }

    private static boolean isBlack(Bitmap b) {
        long sum = 0;
        int n = 0;
        for (int y = b.getHeight() / 8; y < b.getHeight(); y += Math.max(1, b.getHeight() / 8)) {
            for (int x = b.getWidth() / 8; x < b.getWidth(); x += Math.max(1, b.getWidth() / 8)) {
                int px = b.getPixel(x, y);
                sum += ((px >> 16) & 0xFF) + ((px >> 8) & 0xFF) + (px & 0xFF);
                n++;
            }
        }
        return n > 0 && sum / (n * 3) < 6;
    }

    /** The app in front right now, from the usage history's last resume, or null. */
    static String foreground(Context ctx) {
        UsageStatsManager usm = ctx.getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        UsageEvents events;
        try {
            events = usm.queryEvents(now - 60 * 60_000L, now);
        } catch (SecurityException e) {
            return null;
        }
        String last = null;
        UsageEvents.Event e = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) last = e.getPackageName();
        }
        return last;
    }

    /** Apps with a TV entry point, most recently used first, without this launcher or ones just closed. */
    static List<String> recent(Context ctx, int max) {
        UsageStatsManager usm = ctx.getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        Map<String, UsageStats> stats;
        try {
            stats = usm.queryAndAggregateUsageStats(now - 3L * 24 * 60 * 60_000L, now);
        } catch (SecurityException e) {
            return Collections.emptyList();
        }
        PackageManager pm = ctx.getPackageManager();
        List<UsageStats> used = new ArrayList<>();
        for (UsageStats s : stats.values()) {
            String pkg = s.getPackageName();
            if (pkg.equals(ctx.getPackageName()) || s.getLastTimeUsed() <= 0) continue;
            Long closed = closedAt.get(pkg);
            if (closed != null && s.getLastTimeUsed() <= closed) continue;
            if (pm.getLeanbackLaunchIntentForPackage(pkg) == null) continue;   // services, the setup wizard...
            used.add(s);
        }
        Collections.sort(used, (a, b) -> Long.compare(b.getLastTimeUsed(), a.getLastTimeUsed()));
        List<String> out = new ArrayList<>();
        for (UsageStats s : used) {
            out.add(s.getPackageName());
            if (out.size() >= max) break;
        }
        return out;
    }

    static Intent launchIntent(Context ctx, String pkg) {
        Intent i = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return i;
    }
}
