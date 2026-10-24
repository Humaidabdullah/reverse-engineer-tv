package com.gathin.tvhome;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.widget.TextClock;

/**
 * Lock-screen "liquid glass" text without a blur: a translucent fill that fades from bright at the top
 * to see-through at the bottom (the ambient glow shows through), a specular top edge, and a soft halo.
 * Two passes of the normal text draw, so it costs about the same as plain text.
 */
public class GlassClock extends TextClock {

    private LinearGradient fill;
    private LinearGradient rim;
    private int shaderHeight = -1;
    private Shader shadow;
    private int shadowShaderAlpha = -1;
    /** Alpha of the dark drop shadow under the text (Home settings: Clock shadow); 0 draws none. */
    private int shadowAlpha = 0x66;

    void setShadowStrength(int percent) {
        shadowAlpha = Math.round(0xCC * Math.max(0, Math.min(100, percent)) / 100f);
        invalidate();
    }

    public GlassClock(Context context, AttributeSet attrs) {
        super(context, attrs);
        setTextColor(0xFFFFFFFF);   // the shaders carry the colour; white keeps their alpha as is
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int h = getHeight();
        if (h != shaderHeight) {
            shaderHeight = h;
            float top = getPaddingTop(), bottom = h - getPaddingBottom();
            fill = new LinearGradient(0, top, 0, bottom,
                    new int[]{0xE0FFFFFF, 0x9EFFFFFF, 0x6BFFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            rim = new LinearGradient(0, top, 0, bottom,
                    new int[]{0xB3FFFFFF, 0x00FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.3f, 1f}, Shader.TileMode.CLAMP);
        }
        TextPaint p = getPaint();
        p.setStyle(Paint.Style.FILL);
        if (shadowAlpha > 0) {
            // Drop shadow: a soft, dark copy of the text drawn a little lower, under the glass passes.
            // Invisible on black; on a bright wallpaper it lifts the text off the background. (A shader, not
            // the paint colour, because TextView resets the colour on every draw.)
            if (shadow == null || shadowShaderAlpha != shadowAlpha) {
                shadowShaderAlpha = shadowAlpha;
                shadow = new LinearGradient(0, 0, 0, 1, shadowAlpha << 24, shadowAlpha << 24, Shader.TileMode.CLAMP);
            }
            p.setShader(shadow);
            p.setMaskFilter(new BlurMaskFilter(Math.max(1f, getTextSize() / 14f), BlurMaskFilter.Blur.NORMAL));
            canvas.save();
            canvas.translate(0, getTextSize() / 40f);
            super.onDraw(canvas);
            canvas.restore();
            p.setMaskFilter(null);
        }
        // Body: translucent, fading towards the bottom, with a faint halo.
        p.setShader(fill);
        p.setShadowLayer(getTextSize() / 7f, 0, 0, 0x2EFFFFFF);
        super.onDraw(canvas);
        // Specular: the top of each glyph again, nudged up a hair, so its upper edges catch the light.
        // (A stroke would trace the font's overlapping contours inside the digits.)
        p.clearShadowLayer();
        p.setShader(rim);
        float lift = Math.max(1f, getTextSize() / 110f);
        canvas.save();
        canvas.translate(0, -lift);
        super.onDraw(canvas);
        canvas.restore();
        p.setShader(null);
    }
}
