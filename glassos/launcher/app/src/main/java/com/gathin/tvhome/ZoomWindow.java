package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.view.View;

import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * The tvOS app-launch zoom. What grows is a window, not a picture of the icon: a rounded rectangle in the
 * icon's own background colour that opens from the icon's exact place on the dock to the whole screen,
 * its corners easing from the icon's radius to square. The icon's artwork stays near its own size, so it
 * never turns soft, and dissolves early, the way an app's launch screen takes over from its icon. Home
 * dims behind it.
 *
 * As in Apple's launch animation, these are separate springs with their own timing (WWDC23, "Animate with
 * springs"): the window and the backdrop, with the artwork's fade tied to the window. Springs ignore the
 * TV's animator_duration_scale, and closing runs them back into the icon.
 *
 * Built for a weak TV GPU: no blurred shadow (re-blurring a full-screen shape cost ~40 ms a frame), no
 * clip path (the artwork's corners are cut once), Home is dimmed rather than scaled (scaling re-drew the
 * whole screen every frame), and the view is made once with Home and kept hidden, because adding a view at
 * launch time made Android lay out all of Home again mid-zoom.
 */
final class ZoomWindow extends View {
    interface Listener {
        /** The window has all but filled the screen: hand over to the app. */
        void onReadyForApp();

        /** Closed back into the icon. */
        void onClosed();
    }

    private final RectF icon = new RectF(), frame = new RectF(), artRect = new RectF();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint artPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint scrim = new Paint();
    private final SpringAnimation window, backdrop;
    private float iconRadius;
    private final float openRadius;
    private Bitmap art;
    private Listener listener;
    private float open;      // 0 = the icon, 1 = the whole screen
    private float recede;    // 0 = Home as it is, 1 = dimmed
    private boolean handedOver, closing;

    private static final FloatPropertyCompat<ZoomWindow> OPEN = new FloatPropertyCompat<ZoomWindow>("open") {
        @Override
        public float getValue(ZoomWindow z) {
            return z.open;
        }

        @Override
        public void setValue(ZoomWindow z, float v) {
            z.open = v;
            z.invalidate();
            // Hand over only once the window covers the screen: a cold app start takes the CPU, and the
            // zoom froze wherever it was at that moment (at 90% it left Home showing round the edges).
            if (!z.closing && !z.handedOver && v > 0.985f && z.listener != null) {
                z.handedOver = true;
                z.listener.onReadyForApp();
            }
        }
    };

    private static final FloatPropertyCompat<ZoomWindow> RECEDE = new FloatPropertyCompat<ZoomWindow>("recede") {
        @Override
        public float getValue(ZoomWindow z) {
            return z.recede;
        }

        @Override
        public void setValue(ZoomWindow z, float v) {
            z.recede = v;
            z.invalidate();
        }
    };

    ZoomWindow(Context ctx) {
        super(ctx);
        openRadius = 32 * ctx.getResources().getDisplayMetrics().density;
        scrim.setColor(0xFF000000);
        setVisibility(INVISIBLE);
        window = new SpringAnimation(this, OPEN);
        window.setMinimumVisibleChange(0.001f);
        backdrop = new SpringAnimation(this, RECEDE);
        backdrop.setMinimumVisibleChange(0.001f);
        window.addEndListener((a, cancelled, v, vel) -> {
            if (closing && !cancelled) {
                setVisibility(INVISIBLE);
                if (listener != null) listener.onClosed();
            }
        });
    }

    /**
     * @param iconOnScreen the tile as it is drawn now (focus scale included), in this view's coordinates
     * @param art          the tile's picture; drawn no bigger than 1.25x the tile
     */
    void prepare(RectF iconOnScreen, float iconRadius, Bitmap art, int color, Listener listener) {
        window.cancel();
        backdrop.cancel();
        icon.set(iconOnScreen);
        this.iconRadius = iconRadius;
        this.art = rounded(art, iconRadius / Math.max(1f, iconOnScreen.width()) * art.getWidth());
        this.listener = listener;
        fill.setColor(color);
        open = 0f;
        recede = 0f;
        handedOver = false;
    }

    boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    /** Icon to screen: a quick, nearly critically damped open, Home dimming a touch slower behind it. */
    void open() {
        closing = false;
        setVisibility(VISIBLE);
        invalidate();
        window.setSpring(new SpringForce(1f).setStiffness(230f).setDampingRatio(0.86f));
        backdrop.setSpring(new SpringForce(1f).setStiffness(170f).setDampingRatio(1f));
        // One frame for setup (the artwork's upload to the GPU) before the clock starts, or the spring's
        // first step lands most of the way open.
        postOnAnimation(() -> {
            window.start();
            backdrop.start();
        });
    }

    /** Screen back into the icon, from fully open: the same springs in reverse, a little brisker. */
    void close() {
        closing = true;
        open = 1f;
        recede = 1f;
        setVisibility(VISIBLE);
        window.setSpring(new SpringForce(0f).setStiffness(260f).setDampingRatio(0.9f));
        backdrop.setSpring(new SpringForce(0f).setStiffness(200f).setDampingRatio(1f));
        window.start();
        backdrop.start();
    }

    void cancel() {
        window.cancel();
        backdrop.cancel();
        setVisibility(INVISIBLE);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        scrim.setAlpha((int) (150 * Math.max(0f, Math.min(1f, recede))));
        c.drawRect(0, 0, w, h, scrim);

        // The window: icon rectangle to screen, corners from the icon's radius to square.
        float p = open;
        frame.set(lerp(icon.left, 0, p), lerp(icon.top, 0, p), lerp(icon.right, w, p), lerp(icon.bottom, h, p));
        // Rounded all the way out, as tvOS's are: from the icon's radius up to a soft one while the window
        // grows, squaring off only as it meets the screen's edges.
        float r = lerp(lerp(iconRadius, openRadius, smooth(0f, 0.35f, p)), 0f, smooth(0.75f, 1f, p));
        c.drawRoundRect(frame, r, r, fill);

        // The artwork: centred, growing only a little, gone by the time the window is half open
        // (and back in the last part of a close).
        if (art == null) return;
        float a = 1f - smooth(0.05f, 0.45f, p);
        if (a < 0.01f) return;
        float grow = 1f + 0.25f * smooth(0f, 0.6f, p);
        float aw = icon.width() * grow, ah = icon.height() * grow;
        float cx = frame.centerX(), cy = frame.centerY();
        artPaint.setAlpha((int) (255 * a));
        artRect.set(cx - aw / 2, cy - ah / 2, cx + aw / 2, cy + ah / 2);
        c.drawBitmap(art, null, artRect, artPaint);
    }

    /** The tile's picture with its rounded corners cut once, up front. */
    private static Bitmap rounded(Bitmap src, float radius) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        c.drawRoundRect(new RectF(0, 0, src.getWidth(), src.getHeight()), radius, radius, p);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        c.drawBitmap(src, 0, 0, p);
        return out;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smooth(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }
}
