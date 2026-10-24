package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

/**
 * The "liquid glass" edge of a dock icon, drawn over the artwork: a specular rim that is brightest along
 * the top and catches light again at the bottom, a faint dark inner line that gives the rim depth, and a
 * soft sheen over the top of the icon. No blur, just three gradient draws per icon.
 */
class GlassEdgeDrawable extends Drawable {

    private final float density;
    /** Rim width in px, from Home settings (Glass edge thickness, half-dp steps). */
    private final float rimWidth;
    /** Brightness of the rim and sheen, 1 = the designed look (Glass edge brightness, in percent). */
    private final float bright;
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    /** Fully rounded ends (Control Center's pill tiles and round buttons) instead of an icon's corners. */
    private final boolean capsule;
    /** A fixed corner radius in px (the Inputs panel), or 0 for capsule / icon corners. */
    private final float cornerPx;

    GlassEdgeDrawable(float density, int thicknessSteps, int brightnessPercent) {
        this(density, thicknessSteps, brightnessPercent, false, 0);
    }

    GlassEdgeDrawable(float density, int thicknessSteps, int brightnessPercent, boolean capsule) {
        this(density, thicknessSteps, brightnessPercent, capsule, 0);
    }

    GlassEdgeDrawable(float density, int thicknessSteps, int brightnessPercent, float cornerPx) {
        this(density, thicknessSteps, brightnessPercent, false, cornerPx);
    }

    private GlassEdgeDrawable(float density, int thicknessSteps, int brightnessPercent, boolean capsule, float cornerPx) {
        this.capsule = capsule;
        this.cornerPx = cornerPx;
        this.density = density;
        this.bright = Math.max(0, brightnessPercent) / 100f;
        this.rimWidth = Math.max(1f, thicknessSteps * 0.5f * density);
        rim.setStyle(Paint.Style.STROKE);
        inner.setStyle(Paint.Style.STROKE);
        inner.setColor(0x26000000);
    }

    @Override
    protected void onBoundsChange(@NonNull Rect b) {
        float h = b.height();
        float w = rimWidth;
        rim.setStrokeWidth(w);
        inner.setStrokeWidth(Math.max(1f, Math.min(w * 0.8f, 1.6f * density)));
        rim.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                new int[]{white(0xD9), white(0x40), white(0x14), white(0x59)},
                new float[]{0f, 0.28f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        sheen.setShader(new LinearGradient(0, b.top, 0, b.top + h * 0.5f,
                new int[]{white(0x2E), 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
    }

    /** White at the designed alpha, scaled by the brightness setting. */
    private int white(int alpha) {
        return (Math.min(255, Math.round(alpha * bright)) << 24) | 0xFFFFFF;
    }

    @Override
    public void draw(@NonNull Canvas c) {
        Rect b = getBounds();
        float radius = cornerPx > 0 ? cornerPx
                : capsule ? Math.min(b.width(), b.height()) / 2f : 0.225f * b.width();
        rect.set(b);
        c.drawRoundRect(rect, radius, radius, sheen);
        float half = rim.getStrokeWidth() / 2f;
        rect.inset(half, half);
        c.drawRoundRect(rect, radius - half, radius - half, rim);
        float in = rim.getStrokeWidth() + inner.getStrokeWidth() / 2f;
        rect.set(b);
        rect.inset(in, in);
        c.drawRoundRect(rect, radius - in, radius - in, inner);
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
