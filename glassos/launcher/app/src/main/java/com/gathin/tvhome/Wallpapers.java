package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/**
 * Built-in wallpapers, painted at start-up instead of shipped as pictures: a dark base and a few large,
 * soft colour fields in the style of the macOS / iOS abstract wallpapers. Rendered at half the screen size
 * (the gradients are smooth, so upscaling costs nothing visible) to keep memory low on a 1 GB TV.
 */
final class Wallpapers {

    /** Stored in LauncherPrefs: 0 = none, 1..PRESETS.length = a preset, CUSTOM = the theme's picture. */
    static final int NONE = 0, CUSTOM = 100, FILE = 200, DAILY = 300;

    /** Today as a day number in the TV's time zone; the daily wallpaper changes when it does. */
    static long today() {
        return java.time.LocalDate.now().toEpochDay();
    }

    /**
     * The daily wallpaper: the photos in the wallpapers folder in turn, one per day (the same one all day,
     * not random per start). Null when the folder is empty; Home then cycles the built-in ones instead.
     */
    static java.io.File dailyFile(android.content.Context ctx) {
        java.util.List<java.io.File> all = files(ctx);
        return all.isEmpty() ? null : all.get((int) Math.floorMod(today(), (long) all.size()));
    }

    static int dailyPreset() {
        return 1 + (int) Math.floorMod(today(), (long) PRESETS.length);
    }

    /** Pictures in this folder of the launcher's files are offered as wallpapers, named after the file. */
    static final String FOLDER = "wallpapers";

    /** JPEG / PNG / WebP files in the wallpapers folder, sorted by name. */
    static java.util.List<java.io.File> files(android.content.Context ctx) {
        java.util.List<java.io.File> out = new java.util.ArrayList<>();
        java.io.File dir = ctx.getExternalFilesDir(FOLDER);
        java.io.File[] all = dir != null ? dir.listFiles() : null;
        if (all == null) return out;
        for (java.io.File f : all) {
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (f.isFile() && f.length() > 0 && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                    || n.endsWith(".webp"))) out.add(f);
        }
        java.util.Collections.sort(out, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    /** "tahoe_day.jpg" -> "Tahoe Day". */
    static String label(java.io.File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        StringBuilder b = new StringBuilder();
        for (String w : n.replace('_', ' ').replace('-', ' ').trim().split("\\s+")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    private static final class Preset {
        final String name;
        final int base, baseBottom;
        /** Blobs: colour, centre x, centre y (fractions of the screen), radius (fraction of the width). */
        final float[][] blobs;
        final int[] colors;

        Preset(String name, int base, int baseBottom, int[] colors, float[][] blobs) {
            this.name = name;
            this.base = base;
            this.baseBottom = baseBottom;
            this.colors = colors;
            this.blobs = blobs;
        }
    }

    private static final Preset[] PRESETS = {
            new Preset("Aurora", 0xFF060B1E, 0xFF02040C,
                    new int[]{0xFF1FB5A7, 0xFF6D3BD8, 0xFF2A64D6, 0xFF0E8C7A},
                    new float[][]{{0.18f, 0.22f, 0.55f}, {0.78f, 0.30f, 0.50f}, {0.50f, 0.05f, 0.45f}, {0.92f, 0.85f, 0.35f}}),
            new Preset("Sunset", 0xFF1A0714, 0xFF070208,
                    new int[]{0xFFFF7A45, 0xFFE0407A, 0xFF7B3FD1, 0xFFFFB347},
                    new float[][]{{0.25f, 0.30f, 0.55f}, {0.70f, 0.20f, 0.50f}, {0.85f, 0.75f, 0.45f}, {0.10f, 0.85f, 0.30f}}),
            new Preset("Ocean", 0xFF03101F, 0xFF01060D,
                    new int[]{0xFF1E6FE0, 0xFF19B5D9, 0xFF2B3FB8, 0xFF0F8FA8},
                    new float[][]{{0.30f, 0.25f, 0.55f}, {0.75f, 0.40f, 0.45f}, {0.55f, 0.90f, 0.50f}, {0.05f, 0.70f, 0.35f}}),
            new Preset("Sequoia", 0xFF14040A, 0xFF060104,
                    new int[]{0xFFD6342C, 0xFFFF7B2E, 0xFF8E1E4E, 0xFFE8A13A},
                    new float[][]{{0.65f, 0.35f, 0.60f}, {0.30f, 0.60f, 0.45f}, {0.90f, 0.10f, 0.40f}, {0.15f, 0.15f, 0.30f}}),
            new Preset("Graphite", 0xFF0E0F12, 0xFF050506,
                    new int[]{0xFF5B6270, 0xFF3A3F4A, 0xFF7E8594, 0xFF2C3038},
                    new float[][]{{0.25f, 0.20f, 0.55f}, {0.80f, 0.55f, 0.50f}, {0.55f, 0.05f, 0.35f}, {0.10f, 0.85f, 0.40f}}),
            new Preset("Forest", 0xFF04110A, 0xFF010604,
                    new int[]{0xFF1F9E5A, 0xFF0E6B6B, 0xFF7FBF3F, 0xFF155E3A},
                    new float[][]{{0.20f, 0.30f, 0.55f}, {0.75f, 0.25f, 0.45f}, {0.60f, 0.80f, 0.40f}, {0.95f, 0.95f, 0.35f}}),
    };

    private Wallpapers() {
    }

    static int count() {
        return PRESETS.length;
    }

    /** Display name for a stored choice. */
    static String name(int choice, String none, String custom) {
        if (choice == NONE) return none;
        if (choice == CUSTOM) return custom;
        return choice >= 1 && choice <= PRESETS.length ? PRESETS[choice - 1].name : none;
    }

    /** The preset painted for a screen of this size, or null for NONE / CUSTOM / an unknown value. */
    static Bitmap render(int choice, int screenW, int screenH) {
        if (choice < 1 || choice > PRESETS.length) return null;
        Preset p = PRESETS[choice - 1];
        int w = Math.max(160, screenW / 2), h = Math.max(90, screenH / 2);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        paint.setShader(new LinearGradient(0, 0, 0, h, p.base, p.baseBottom, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        for (int i = 0; i < p.blobs.length; i++) {
            float[] b = p.blobs[i];
            int col = p.colors[i % p.colors.length] & 0x00FFFFFF;
            float r = b[2] * w;
            paint.setShader(new RadialGradient(b[0] * w, b[1] * h, r,
                    new int[]{0xB3000000 | col, 0x4D000000 | col, col},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, paint);
        }
        // Darken towards the bottom so the dock and its shade sit on calmer ground.
        paint.setShader(new LinearGradient(0, h * 0.45f, 0, h, 0x00000000, 0x8C000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        return bmp;
    }
}
