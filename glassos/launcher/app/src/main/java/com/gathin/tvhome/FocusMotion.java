package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * tvOS-style focus for one tile: it lifts toward the viewer with a soft shadow, leans into the
 * direction the remote moved it, catches a sheen of light across its face, then settles.
 *
 * Everything runs on springs rather than timed animations, for two reasons. Some TVs ship with
 * animator_duration_scale at 0.6 (the Vu 4K does), which clips every timed animation to 60% and
 * makes it feel abrupt; springs are physics and ignore that scale. And a move that interrupts
 * another keeps its velocity instead of restarting from rest, which is most of what makes a
 * quick run along the dock feel fluid.
 */
final class FocusMotion {

    /** The last horizontal move from the remote: -1 left, +1 right, 0 vertical. Set by MainActivity. */
    static int moveDir;
    /** When that move happened (uptime ms), so a focus change can tell a remote move from a rebind. */
    static long moveAt;

    private static final float LIFT_DP = 18f;        // shadow depth of the focused tile
    private static final float RAISE_DP = 3f;        // and how far it rises
    private static final float TILT_VELOCITY = 140f; // deg/s kick on a move; peaks near 6 degrees
    private static final float BUMP_VELOCITY_DP = 320f;

    private final View card;
    private final float density;
    private final SpringAnimation scaleX, scaleY, lift, raise, tilt, nudge, gloss;
    private final Sheen sheen = new Sheen();

    FocusMotion(View card, float density) {
        this.card = card;
        this.density = density;
        // A far camera keeps the lean subtle and free of the fisheye a near camera gives.
        card.setCameraDistance(14000 * density);
        if (Build.VERSION.SDK_INT >= 28) {
            card.setOutlineSpotShadowColor(0xFF000000);
            card.setOutlineAmbientShadowColor(0xFF000000);
        }
        scaleX = new SpringAnimation(card, DynamicAnimation.SCALE_X);
        scaleY = new SpringAnimation(card, DynamicAnimation.SCALE_Y);
        lift = spring(new SpringAnimation(card, DynamicAnimation.TRANSLATION_Z), 300f, 0.9f);
        raise = spring(new SpringAnimation(card, DynamicAnimation.TRANSLATION_Y), 300f, 0.85f);
        tilt = spring(new SpringAnimation(card, DynamicAnimation.ROTATION_Y), 190f, 0.5f);
        tilt.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_ROTATION_DEGREES);
        nudge = spring(new SpringAnimation(card, DynamicAnimation.TRANSLATION_X), 650f, 0.38f);
        gloss = spring(new SpringAnimation(sheen, Sheen.STRENGTH), 260f, 1f);
        gloss.setMinimumVisibleChange(0.004f);
        // The light sits opposite the lean, so it slides across the face as the tile tips and settles.
        tilt.addUpdateListener((a, value, velocity) -> sheen.setLean(value / 7f));

        if (card instanceof FrameLayout) {
            Drawable fg = ((FrameLayout) card).getForeground();
            ((FrameLayout) card).setForeground(fg == null ? sheen : new LayerDrawable(new Drawable[]{fg, sheen}));
        }
    }

    private static SpringAnimation spring(SpringAnimation a, float stiffness, float damping) {
        a.setSpring(new SpringForce().setStiffness(stiffness).setDampingRatio(damping));
        return a;
    }

    /** Focus arrived or left: lift or settle, leaning with the remote's last move. */
    void focus(boolean focused) {
        boolean fromRemote = SystemClock.uptimeMillis() - moveAt < 300;
        float s = focused ? AppAdapter.FOCUS_SCALE : 1f;
        // A little overshoot on the way up, the way a tvOS poster pops and settles; none on the way down.
        SpringForce force = focused
                ? new SpringForce(s).setStiffness(320f).setDampingRatio(0.62f)
                : new SpringForce(s).setStiffness(420f).setDampingRatio(0.95f);
        scaleX.setSpring(force);
        scaleY.setSpring(new SpringForce(s).setStiffness(force.getStiffness()).setDampingRatio(force.getDampingRatio()));
        scaleX.start();
        scaleY.start();
        lift.animateToFinalPosition(focused ? LIFT_DP * density : 0f);
        raise.animateToFinalPosition(focused ? -RAISE_DP * density : 0f);
        gloss.animateToFinalPosition(focused ? 1f : 0f);
        if (fromRemote && moveDir != 0) {
            // Both tiles lean the same way, the arriving one and the one left behind, like a hand
            // sweeping across them.
            tilt.cancel();
            tilt.setStartVelocity(moveDir * TILT_VELOCITY);
            tilt.animateToFinalPosition(0f);
        }
        if (focused && fromRemote) Sound.tick(card.getContext());
    }

    /** Pressed past the end of the row: the tile nudges that way and springs back. */
    void bump(int dir) {
        nudge.cancel();
        nudge.setStartVelocity(dir * BUMP_VELOCITY_DP * density);
        nudge.animateToFinalPosition(0f);
        tilt.cancel();
        tilt.setStartVelocity(dir * TILT_VELOCITY * 0.8f);
        tilt.animateToFinalPosition(0f);
    }

    /** A rebind: jump straight to the resting state, no motion. */
    void snap(boolean focused) {
        for (SpringAnimation a : new SpringAnimation[]{scaleX, scaleY, lift, raise, tilt, nudge, gloss}) a.cancel();
        float s = focused ? AppAdapter.FOCUS_SCALE : 1f;
        card.setScaleX(s);
        card.setScaleY(s);
        card.setTranslationZ(focused ? LIFT_DP * density : 0f);
        card.setTranslationY(focused ? -RAISE_DP * density : 0f);
        card.setTranslationX(0f);
        card.setRotationY(0f);
        sheen.setLean(0f);
        Sheen.STRENGTH.setValue(sheen, focused ? 1f : 0f);
    }

    /**
     * A timed animation's duration made immune to animator_duration_scale, for the few transitions
     * (the top shelf's crossfade) that stay timed. A scale of 0 means the owner turned animations
     * off, which is left alone.
     */
    static long ms(Context c, long ms) {
        float scale = Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        return scale > 0.05f ? (long) (ms / scale) : ms;
    }

    /** The highlight: a soft light from above, sliding across the face as the tile leans. */
    static final class Sheen extends Drawable {
        static final FloatPropertyCompat<Sheen> STRENGTH = new FloatPropertyCompat<Sheen>("sheen") {
            @Override
            public float getValue(Sheen s) {
                return s.strength;
            }

            @Override
            public void setValue(Sheen s, float value) {
                s.strength = Math.max(0f, value);
                s.invalidateSelf();
            }
        };

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float strength, lean;
        private float shaderLean = Float.NaN, shaderAlpha = -1f;
        private final Rect shaderBounds = new Rect();

        void setLean(float lean) {
            this.lean = Math.max(-1.5f, Math.min(1.5f, lean));
            invalidateSelf();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (strength < 0.01f) return;
            Rect b = getBounds();
            if (b.isEmpty()) return;
            float alpha = Math.min(1f, strength);
            // Rebuild the gradient only when it actually changed: most frames of a settle move it by
            // less than a pixel, and a new shader per frame is garbage on a 1 GB TV.
            if (Math.abs(lean - shaderLean) > 0.01f || Math.abs(alpha - shaderAlpha) > 0.01f || !b.equals(shaderBounds)) {
                float w = b.width(), h = b.height();
                float cx = b.left + w * (0.5f - 0.38f * lean);
                int a = (int) (alpha * 0x42);
                paint.setShader(new RadialGradient(cx, b.top - h * 0.25f, Math.max(w, h) * 0.95f,
                        new int[]{(a << 24) | 0xFFFFFF, ((a / 3) << 24) | 0xFFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
                shaderLean = lean;
                shaderAlpha = alpha;
                shaderBounds.set(b);
            }
            canvas.drawRect(b, paint);
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

    /** The focus click: a soft tick on every move, like tvOS. Silent when the TV's system sounds are off. */
    static final class Sound {
        private static SoundPool pool;
        private static int tick;
        private static boolean loaded;
        private static boolean enabled = true;
        private static long checkedAt;

        static void init(Context c) {
            if (pool != null) return;
            pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            pool.setOnLoadCompleteListener((p, id, status) -> loaded = status == 0);
            tick = pool.load(c.getApplicationContext(), R.raw.focus_tick, 1);
        }

        static void release() {
            if (pool == null) return;
            pool.release();
            pool = null;
            loaded = false;
        }

        static void tick(Context c) {
            if (pool == null || !loaded) return;
            long now = SystemClock.uptimeMillis();
            if (now - checkedAt > 5000) {
                checkedAt = now;
                enabled = c.getResources().getBoolean(R.bool.focus_sounds) && Settings.System.getInt(
                        c.getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0;
            }
            if (enabled) pool.play(tick, 0.32f, 0.32f, 1, 0, 1f);
        }
    }
}
