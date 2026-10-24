package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/**
 * Replacement icons for apps whose own TV icon looks out of place in the dock, drawn in code so they stay
 * sharp at any dock size. Square, full bleed: the dock's rounded outline and glass edge go on top.
 */
final class CustomIcons {

    private CustomIcons() {
    }

    /** A replacement for this package's icon, or null to keep the app's own. */
    static Drawable forPackage(Context ctx, String pkg) {
        if ("com.streamvault.app".equals(pkg)) return new BitmapDrawable(ctx.getResources(), streamVault(256));
        return null;
    }

    /** StreamVault: a violet-to-indigo tile, a white vault-dial ring with tick marks, a play symbol inside. */
    private static Bitmap streamVault(int n) {
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

        p.setShader(new LinearGradient(0, 0, n, n, new int[]{0xFF8B5CF6, 0xFF5B3BE0, 0xFF2B1A8F},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, n, n, p);
        // Soft light from the top left, like the other glass icons.
        p.setShader(new RadialGradient(n * 0.25f, n * 0.15f, n * 0.8f, 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, n, n, p);
        p.setShader(null);

        float cx = n / 2f, cy = n / 2f, r = n * 0.30f;
        // Dial ring.
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(n * 0.045f);
        p.setColor(0xF2FFFFFF);
        c.drawCircle(cx, cy, r, p);
        // Tick marks around the dial.
        p.setStrokeWidth(n * 0.022f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(0x99FFFFFF);
        for (int i = 0; i < 12; i++) {
            double a = Math.PI * 2 * i / 12;
            float r1 = r + n * 0.055f, r2 = r + n * (i % 3 == 0 ? 0.095f : 0.075f);
            c.drawLine(cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1,
                    cx + (float) Math.cos(a) * r2, cy + (float) Math.sin(a) * r2, p);
        }
        // Play symbol, nudged right to look optically centred.
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        float s = r * 0.62f, ox = cx + s * 0.12f;
        Path play = new Path();
        play.moveTo(ox - s * 0.5f, cy - s * 0.6f);
        play.lineTo(ox + s * 0.62f, cy);
        play.lineTo(ox - s * 0.5f, cy + s * 0.6f);
        play.close();
        p.setPathEffect(new android.graphics.CornerPathEffect(n * 0.02f));
        c.drawPath(play, p);
        return bmp;
    }
}
