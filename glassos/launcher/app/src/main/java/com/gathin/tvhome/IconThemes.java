package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * iOS-style icon looks, made from each app's own icon: Dark (logo in the app's colour on near-black),
 * Clear (a white logo on frosted, see-through glass) and Tinted (the logo in one chosen colour on a dark
 * tile of that colour). The icon's background colour is read from its border; whatever differs from it
 * is the logo. Picture-like icons, where no flat background exists, get a softer version of each look.
 */
final class IconThemes {

    static final int ORIGINAL = 0, DARK = 1, CLEAR = 2, TINTED = 3;
    static final String[] NAMES = {"Original", "Dark", "Clear", "Tinted"};

    static final String[] TINT_NAMES = {"Blue", "Purple", "Pink", "Red", "Orange", "Yellow", "Green", "Teal", "White"};
    private static final int[] TINTS = {0xFF4DA3FF, 0xFFB78CFF, 0xFFFF7EB6, 0xFFFF6B6B, 0xFFFFA14D,
            0xFFFFD84D, 0xFF5EDB7A, 0xFF4DD9D0, 0xFFF2F2F7};

    private IconThemes() {
    }

    static int tint(int index) {
        return TINTS[Math.max(0, Math.min(TINTS.length - 1, index))];
    }

    /** The icon in this look, same size; ORIGINAL returns the source. */
    static Bitmap apply(Bitmap src, int theme, int tint) {
        if (theme == ORIGINAL) return src;
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        int bg = borderColor(px, w, h);
        // Gradient backgrounds (iWant, StreamVault): the background where each pixel is, blended from the
        // edges in its row and column. A pixel counts as background if it is close to either estimate.
        int in = Math.max(1, w / 16);
        int[] top = edge(px, w, h, in, true), bottom = edge(px, w, h, h - 1 - in, true);
        int[] left = edge(px, w, h, in, false), right = edge(px, w, h, w - 1 - in, false);

        // How much each pixel belongs to the logo: 0 = background, 1 = logo.
        float[] fg = new float[px.length];
        int bgCount = 0;
        for (int i = 0; i < px.length; i++) {
            int x = i % w, y = i / w;
            float u = x / (float) (w - 1), v = y / (float) (h - 1);
            int local = lerp(lerp(top[x], bottom[x], v), lerp(left[y], right[y], u), 0.5f);
            float d = Math.min(distance(px[i], bg), distance(px[i], local));
            fg[i] = smooth(0.10f, 0.28f, d) * (Color.alpha(px[i]) / 255f);
            if (fg[i] < 0.5f) bgCount++;
        }
        boolean picture = bgCount < px.length * 0.35f;   // no flat background to lift a logo off

        int[] out = new int[px.length];
        float[] hsv = new float[3];
        for (int y = 0; y < h; y++) {
            float v = y / (float) (h - 1);
            for (int x = 0; x < w; x++) {
                int i = y * w + x, c = px[i];
                float t = fg[i];
                switch (theme) {
                    case DARK: {
                        int base = lerp(0xFF2C2C2E, 0xFF161618, v);
                        if (picture) {
                            out[i] = lerp(base, c, 0.62f);
                            break;
                        }
                        // A white or grey logo on a coloured tile takes the tile's colour, as on iOS.
                        int logo = c;
                        Color.colorToHSV(c, hsv);
                        float[] bgHsv = new float[3];
                        Color.colorToHSV(bg, bgHsv);
                        if (hsv[1] < 0.22f && bgHsv[1] > 0.25f) {
                            bgHsv[2] = Math.max(bgHsv[2], 0.85f);
                            logo = Color.HSVToColor(bgHsv);
                        }
                        out[i] = lerp(base, logo, t);
                        break;
                    }
                    case CLEAR: {
                        // Frosted glass: faint white, brighter at the top; the logo a soft white.
                        float glass = 0.20f - 0.10f * v;
                        float logo = picture ? luminance(c) * 0.85f : 0.92f * t;
                        float a = Math.min(1f, glass + logo * (1f - glass));
                        out[i] = Color.argb(Math.round(a * 255), 255, 255, 255);
                        break;
                    }
                    default: {   // TINTED
                        int dark = lerp(scale(tint, 0.20f), scale(tint, 0.10f), v);
                        float logo = picture ? luminance(c) : t;
                        out[i] = lerp(dark, tint, logo);
                        break;
                    }
                }
            }
        }
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.setPixels(out, 0, w, 0, 0, w, h);
        return b;
    }

    /**
     * The colour along one edge line (a row at y = at, or a column at x = at), each point the median of a
     * window around it, so a logo crossing the line here and there does not leak into the background.
     */
    private static int[] edge(int[] px, int w, int h, int at, boolean row) {
        int len = row ? w : h, win = Math.max(2, len / 8);
        int[] line = new int[len], out = new int[len];
        for (int i = 0; i < len; i++) line[i] = row ? px[at * w + i] : px[i * w + at];
        int[] r = new int[2 * win + 1], g = new int[2 * win + 1], b = new int[2 * win + 1];
        for (int i = 0; i < len; i++) {
            int n = 0;
            for (int k = Math.max(0, i - win); k <= Math.min(len - 1, i + win); k++, n++) {
                r[n] = Color.red(line[k]); g[n] = Color.green(line[k]); b[n] = Color.blue(line[k]);
            }
            java.util.Arrays.sort(r, 0, n);
            java.util.Arrays.sort(g, 0, n);
            java.util.Arrays.sort(b, 0, n);
            out[i] = Color.rgb(r[n / 2], g[n / 2], b[n / 2]);
        }
        return out;
    }

    /** Median colour of a band just inside the border, where an icon's background shows. */
    private static int borderColor(int[] px, int w, int h) {
        int in = Math.max(1, w / 16);
        java.util.List<Integer> r = new java.util.ArrayList<>(), g = new java.util.ArrayList<>(), b = new java.util.ArrayList<>();
        for (int x = in; x < w - in; x += 2) {
            for (int y : new int[]{in, h - 1 - in}) {
                int c = px[y * w + x];
                r.add(Color.red(c)); g.add(Color.green(c)); b.add(Color.blue(c));
            }
        }
        for (int y = in; y < h - in; y += 2) {
            for (int x : new int[]{in, w - 1 - in}) {
                int c = px[y * w + x];
                r.add(Color.red(c)); g.add(Color.green(c)); b.add(Color.blue(c));
            }
        }
        java.util.Collections.sort(r);
        java.util.Collections.sort(g);
        java.util.Collections.sort(b);
        int m = r.size() / 2;
        return Color.rgb(r.get(m), g.get(m), b.get(m));
    }

    private static float distance(int a, int b) {
        float dr = Color.red(a) - Color.red(b), dg = Color.green(a) - Color.green(b), db = Color.blue(a) - Color.blue(b);
        return (float) Math.sqrt(dr * dr + dg * dg + db * db) / 441.7f;
    }

    private static float luminance(int c) {
        return (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f;
    }

    private static float smooth(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    private static int lerp(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return Color.argb(255,
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private static int scale(int c, float f) {
        return Color.rgb(Math.round(Color.red(c) * f), Math.round(Color.green(c) * f), Math.round(Color.blue(c) * f));
    }
}
