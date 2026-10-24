package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;

import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import java.util.ArrayList;
import java.util.List;

/**
 * The app switcher, as tvOS has it (double-press Home): on a dark ground, the current app as one large
 * card with its icon and name above it, the apps used before it stacked behind it to the left like a deck,
 * and Home as a card peeking in on the right. Left and Right move through them, OK goes back into one
 * (its snapshot grows to the screen, then the app takes over), Up closes it, Back leaves.
 *
 * One view, drawn in one pass from one spring-driven position ({@link #pos}, the focused card as a float),
 * so sliding through the deck is cheap on a weak GPU: every card is a rounded rect filled by a bitmap
 * shader, with no clip paths, blurs or child views. Made once with Home and kept hidden.
 */
final class AppSwitcher extends View {
    interface Host {
        /** Close {@code pkg} for good (closing its card). */
        void closeApp(String pkg);

        /** Open {@code pkg}; the switcher has grown its card to the screen. */
        void openFromSwitcher(String pkg);

        /** The switcher has gone; Home is on screen again. */
        void onSwitcherClosed();

        /** A picture of Home as it is now, for the card on the right. */
        Bitmap homeSnapshot();
    }

    private static final class Item {
        final String pkg;     // null: Home
        final String label;
        Bitmap icon;
        Bitmap face;          // the snapshot, or the drawn card of a protected app
        final Paint facePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Matrix m = new Matrix(), im = new Matrix();
        Bitmap shaderFace, shaderIcon;   // what each paint's shader was made from
        float lift;           // 0..1: flying up and away when closed

        Item(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Item> items = new ArrayList<>();
    private final Paint ground = new Paint(), dim = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF(), ir = new RectF(), src = new RectF();
    private final SpringAnimation slide, appear, grow, flyAway, closeGap;
    private float pos, shown, grown;
    /** Closing a card: the cards from {@link #gapAt} on sit {@link #gap} slots further right, closing to 0. */
    private float gap;
    private int gapAt = -1;
    private int focus;
    private float s = 1f;   // screen width / 1920: the layout is written for 1920x1080
    private Item closing;

    private static final FloatPropertyCompat<AppSwitcher> POS = prop("pos");
    private static final FloatPropertyCompat<AppSwitcher> SHOWN = prop("shown");
    private static final FloatPropertyCompat<AppSwitcher> GROWN = prop("grown");
    private static final FloatPropertyCompat<AppSwitcher> LIFT = prop("lift");
    private static final FloatPropertyCompat<AppSwitcher> GAP = prop("gap");

    private static FloatPropertyCompat<AppSwitcher> prop(String name) {
        return new FloatPropertyCompat<AppSwitcher>(name) {
            @Override
            public float getValue(AppSwitcher a) {
                switch (name) {
                    case "pos": return a.pos;
                    case "shown": return a.shown;
                    case "grown": return a.grown;
                    case "gap": return a.gap;
                    default: return a.closing == null ? 0 : a.closing.lift;
                }
            }

            @Override
            public void setValue(AppSwitcher a, float v) {
                switch (name) {
                    case "pos": a.pos = v; break;
                    case "shown": a.shown = v; break;
                    case "grown": a.grown = v; break;
                    case "gap": a.gap = v; break;
                    default: if (a.closing != null) a.closing.lift = v; break;
                }
                a.invalidate();
            }
        };
    }

    AppSwitcher(Context ctx, Host host) {
        super(ctx);
        this.host = host;
        setVisibility(INVISIBLE);
        ground.setColor(0xFF0E1211);
        rim.setStyle(Paint.Style.STROKE);
        rim.setColor(0x14FFFFFF);
        title.setColor(Color.WHITE);
        try {
            title.setTypeface(Typeface.create(ctx.getResources().getFont(R.font.outfit), Typeface.NORMAL));
        } catch (RuntimeException ignored) {
        }
        slide = new SpringAnimation(this, POS).setSpring(new SpringForce().setStiffness(260f).setDampingRatio(0.88f));
        slide.setMinimumVisibleChange(0.001f);
        appear = new SpringAnimation(this, SHOWN).setSpring(new SpringForce().setStiffness(300f).setDampingRatio(0.92f));
        appear.setMinimumVisibleChange(0.002f);
        grow = new SpringAnimation(this, GROWN).setSpring(new SpringForce(1f).setStiffness(260f).setDampingRatio(0.9f));
        grow.setMinimumVisibleChange(0.001f);
        // A flick: quick and without bounce, so the card is gone before the others have settled.
        flyAway = new SpringAnimation(this, LIFT).setSpring(new SpringForce(1f).setStiffness(320f).setDampingRatio(1f));
        flyAway.setMinimumVisibleChange(0.002f);
        // The same spring as the slide, so a card that should stay put (the slide and the gap cancel out) does.
        closeGap = new SpringAnimation(this, GAP).setSpring(new SpringForce(0f).setStiffness(260f).setDampingRatio(0.88f));
        closeGap.setMinimumVisibleChange(0.001f);
        appear.addEndListener((a, cancelled, v, vel) -> {
            if (!cancelled && v < 0.01f) finish();
        });
        grow.addEndListener((a, cancelled, v, vel) -> {
            if (cancelled) return;
            Item it = items.get(focus);
            host.openFromSwitcher(it.pkg);
            main.postDelayed(this::finish, 400);   // after the app has drawn over it
        });
        flyAway.addEndListener((a, cancelled, v, vel) -> {
            if (cancelled) return;
            Item gone = closing;
            closing = null;
            if (gone != null) {
                if (gone.face != null) gone.face.recycle();
                if (gone.icon != null) gone.icon.recycle();
                // Stopped only now: a force-stop is a burst of work for this TV, and during the flick it cost frames.
                host.closeApp(gone.pkg);
            }
            if (items.size() <= 1) dismiss();   // only Home left
            invalidate();
        });
        closeGap.addEndListener((a, cancelled, v, vel) -> {
            if (!cancelled) gapAt = -1;
        });
    }

    boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    /** Growing a card into its app: leaving Home is expected, so the switcher stays until the app is up. */
    boolean isOpening() {
        return isShowing() && grown > 0f;
    }

    /** Opens on the most recent app, with Home on the right. */
    void show(List<String> recent) {
        if (isShowing()) return;
        s = Math.max(1, getWidth()) / 1920f;
        title.setTextSize(40 * s);
        rim.setStrokeWidth(2 * s);
        items.clear();
        for (int i = recent.size() - 1; i >= 0; i--) items.add(new Item(recent.get(i), label(recent.get(i))));
        Item home = new Item(null, "Home");
        home.face = host.homeSnapshot();
        items.add(home);
        focus = Math.max(0, items.size() - 2);
        pos = focus;
        grown = 0f;
        closing = null;
        shown = 0f;
        setVisibility(VISIBLE);
        appear.animateToFinalPosition(1f);
        // Icons and faces off the main thread, the cards nearest the focus first, so opening never waits.
        List<Item> order = new ArrayList<>(items);
        Item focused = items.get(focus);
        order.sort((x, y) -> Float.compare(Math.abs(items.indexOf(x) - focus), Math.abs(items.indexOf(y) - focus)));
        new Thread(() -> loadFaces(order, focused), "switcher-faces").start();
    }

    void dismiss() {
        if (!isShowing() || grown > 0f) return;
        appear.animateToFinalPosition(0f);
    }

    /** Gone at once (Home was left for an app some other way). */
    void hideNow() {
        if (!isShowing()) return;
        appear.cancel();
        slide.cancel();
        grow.cancel();
        finish();
    }

    private void finish() {
        setVisibility(INVISIBLE);
        for (Item it : items) {
            if (it.face != null) it.face.recycle();
            if (it.icon != null) it.icon.recycle();
        }
        items.clear();
        host.onSwitcherClosed();
    }

    // ---- keys -----------------------------------------------------------------------------------

    boolean onKey(KeyEvent e) {
        if (!isShowing()) return false;
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (grown > 0f) return true;   // already opening an app
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                move(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                move(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                open();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                closeFocused();
                return true;
            case KeyEvent.KEYCODE_BACK:
                dismiss();
                return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_POWER:
                return false;   // the TV's own keys keep working
            default:
                return true;    // nothing else gets through to Home while the switcher is up
        }
    }

    private void move(int by) {
        int next = Math.max(0, Math.min(items.size() - 1, focus + by));
        if (next == focus) return;
        focus = next;
        slide.animateToFinalPosition(focus);
        FocusMotion.Sound.tick(getContext());
    }

    private void open() {
        Item it = items.get(focus);
        if (it.pkg == null) {   // Home
            dismiss();
            return;
        }
        grow.setStartValue(0f);
        grow.start();
    }

    /**
     * Up on an app: its card flicks up and away while the next app slides into its place, in one motion.
     * The card leaves the list at once; the cards after it start one slot further right (the gap) and the
     * gap closes on the same spring as the slide, so nothing jumps.
     */
    private void closeFocused() {
        Item it = items.get(focus);
        if (it.pkg == null || closing != null || gapAt >= 0) return;   // one close at a time, deck settled
        int at = focus;
        items.remove(at);
        closing = it;
        it.lift = 0f;
        Recents.forget(it.pkg);
        // pos is left where it is, mid-slide or not: with the gap, every card keeps its place this frame.
        gapAt = at;
        gap = 1f;
        // The next older app comes forward from the deck; with none older, the next one slides in from the right.
        focus = at > 0 ? at - 1 : 0;
        slide.animateToFinalPosition(focus);
        closeGap.setStartValue(1f);
        closeGap.animateToFinalPosition(0f);
        flyAway.setStartValue(0f);
        flyAway.setStartVelocity(4f);
        flyAway.start();
        FocusMotion.Sound.tick(getContext());
    }

    // ---- content --------------------------------------------------------------------------------

    private String label(String pkg) {
        try {
            android.content.pm.PackageManager pm = getContext().getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    private void loadFaces(List<Item> order, Item focused) {
        for (Item it : order) {
            if (it.pkg == null) continue;
            Bitmap icon = drawIcon(it.pkg, (int) (220 * s));
            // The focused card is shown nearly full width: unpack it whole; the rest at half size.
            Bitmap face = Recents.isProtected(it.pkg) ? null : Recents.frame(it.pkg, it == focused ? 1 : 2);
            if (face == null) face = drawCard(icon);
            Bitmap f = face;
            main.post(() -> {
                if (!items.contains(it)) {
                    if (f != null) f.recycle();
                    if (icon != null) icon.recycle();
                    return;
                }
                it.icon = icon;
                it.face = f;
                invalidate();
            });
        }
    }

    private Bitmap drawIcon(String pkg, int n) {
        try {
            Drawable d = getContext().getPackageManager().getApplicationIcon(pkg);
            Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, n, n);
            d.draw(new Canvas(b));
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The card of an app with no snapshot (protected video, or not opened since the TV started): its own
     * colour, darkened to a gradient, with its icon drawn at full resolution in the middle and its name.
     */
    private Bitmap drawCard(Bitmap icon) {
        // At the size the focused card is drawn, so the icon in it is never scaled up.
        int w = Math.round(1400 * s), h = Math.round(1400 * s * 9 / 16);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int base = icon == null ? 0xFF2A2D35 : dominant(icon);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, w, h, shade(base, 0.55f), shade(base, 0.18f), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        if (icon != null) {
            float n = Math.min(icon.getWidth(), 220 * s), x = (w - n) / 2f, y = (h - n) / 2f;
            RectF ir = new RectF(x, y, x + n, y + n);
            Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            BitmapShader sh = new BitmapShader(icon, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setRectToRect(new RectF(0, 0, icon.getWidth(), icon.getHeight()), ir, Matrix.ScaleToFit.FILL);
            sh.setLocalMatrix(m);
            ip.setShader(sh);
            c.drawRoundRect(ir, n * 0.22f, n * 0.22f, ip);
        }
        return b;
    }

    private static int dominant(Bitmap icon) {
        long r = 0, g = 0, bl = 0;
        int n = 0;
        for (int y = 0; y < icon.getHeight(); y += 6) {
            for (int x = 0; x < icon.getWidth(); x += 6) {
                int px = icon.getPixel(x, y);
                if ((px >>> 24) < 200) continue;
                int R = (px >> 16) & 0xFF, G = (px >> 8) & 0xFF, B = px & 0xFF;
                if (Math.max(R, Math.max(G, B)) - Math.min(R, Math.min(G, B)) < 40) continue;   // greys and whites
                r += R;
                g += G;
                bl += B;
                n++;
            }
        }
        return n == 0 ? 0xFF3A3D47 : Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
    }

    private static int shade(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    // ---- drawing --------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        float W = getWidth(), H = getHeight();
        float a = Math.max(0f, Math.min(1f, shown));
        ground.setAlpha((int) (255 * a));
        c.drawRect(0, 0, W, H, ground);
        if (items.isEmpty()) return;

        float cardW = 1400 * s, cardH = cardW * 9 / 16, cardX = 330 * s, cardY = 137 * s, radius = 30 * s;
        // Opening: everything rises out of a slightly larger, fading view, as tvOS's switcher does.
        float zoom = 1.06f - 0.06f * a;
        c.save();
        c.scale(zoom, zoom, W / 2f, H / 2f);

        // Back to front: the deck's far end, then nearer cards, the focused one, and the cards to its right.
        int n = items.size();
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (x, y) -> {
            float dx = x - pos + (gapAt >= 0 && x >= gapAt ? gap : 0f), dy = y - pos + (gapAt >= 0 && y >= gapAt ? gap : 0f);
            float kx = dx <= 0 ? -dx * 2 : dx * 2 - 0.5f, ky = dy <= 0 ? -dy * 2 : dy * 2 - 0.5f;   // further = earlier
            return Float.compare(ky, kx);
        });
        for (int idx : order) {
            Item it = items.get(idx);
            float d = idx - pos + (gapAt >= 0 && idx >= gapAt ? gap : 0f);
            if (d < -3.5f || d > 1.8f) continue;
            float sc, x, dimA;
            if (d <= 0) {           // the deck: further back is smaller, darker and further left
                float k = -d;
                sc = 1f - 0.06f * k;
                x = cardX - 78 * s * k;
                dimA = Math.min(0.62f, 0.32f * k);
            } else {                // to the right: the next card, larger, half off screen
                sc = 1f + 0.05f * d;
                x = cardX + (cardW + 6 * s) * d;
                dimA = 0.15f * Math.min(1f, d);
            }
            float w = cardW * sc, h = cardH * sc, y = cardY + (cardH - h) / 2f;
            r.set(x, y, x + w, y + h);
            float alpha = a * (d < -2.5f ? Math.max(0f, 3.5f + d) : 1f);
            if (idx == focus && grown > 0f) {   // opening: the card grows to the screen
                float gx = W / zoom, gy = H / zoom;   // undo the appear scale at full growth
                r.set(lerp(r.left, (W - gx) / 2f, grown), lerp(r.top, (H - gy) / 2f, grown),
                        lerp(r.right, (W + gx) / 2f, grown), lerp(r.bottom, (H + gy) / 2f, grown));
            }
            float rad = idx == focus ? lerp(radius, 0, grown) : radius;
            drawFace(c, it, r, rad, alpha, dimA);
            if (d <= 0.6f) drawHeader(c, it, r, d, alpha);
        }
        if (closing != null) {   // flicking up and away, over the others, shrinking a little as it goes
            float l = closing.lift, sc = 1f - 0.08f * l;
            float w = cardW * sc, h = cardH * sc;
            float x = cardX + (cardW - w) / 2f, y = cardY + (cardH - h) / 2f - (cardY + cardH) * l;
            r.set(x, y, x + w, y + h);
            drawFace(c, closing, r, radius, a * Math.max(0f, 1f - l * 1.2f), 0f);
        }
        c.restore();
    }

    private void drawFace(Canvas c, Item it, RectF r, float rad, float alpha, float dimA) {
        Bitmap f = it.face;
        if (f != null && !f.isRecycled()) {
            if (it.shaderFace != f) {
                it.facePaint.setShader(new BitmapShader(f, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                it.shaderFace = f;
            }
            src.set(0, 0, f.getWidth(), f.getHeight());
            it.m.setRectToRect(src, r, Matrix.ScaleToFit.FILL);
            it.facePaint.getShader().setLocalMatrix(it.m);
            it.facePaint.setAlpha((int) (255 * alpha));
            c.drawRoundRect(r, rad, rad, it.facePaint);
        } else {
            dim.setColor(0xFF1E2321);
            dim.setAlpha((int) (255 * alpha));
            c.drawRoundRect(r, rad, rad, dim);
        }
        if (dimA > 0.01f) {
            dim.setColor(Color.BLACK);
            dim.setAlpha((int) (255 * dimA * alpha));
            c.drawRoundRect(r, rad, rad, dim);
        }
        rim.setAlpha((int) (20 * alpha));
        c.drawRoundRect(r, rad, rad, rim);
    }

    /** The app's icon (and, for the focused card, its name) above the card's top-left corner. */
    private void drawHeader(Canvas c, Item it, RectF card, float d, float alpha) {
        if (it.pkg == null) return;   // Home has no header, as on tvOS
        float n = 72 * s;
        float x = card.left + 4 * s, y = card.top - n - 16 * s;
        float hdrA = alpha * (d < 0 ? Math.max(0f, 1f + d * 0.25f) : Math.max(0f, 1f - d)) * (1f - grown);
        if (hdrA <= 0.01f) return;
        if (it.icon != null && !it.icon.isRecycled()) {
            if (it.shaderIcon != it.icon) {
                it.iconPaint.setShader(new BitmapShader(it.icon, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                it.shaderIcon = it.icon;
            }
            ir.set(x, y, x + n, y + n);
            src.set(0, 0, it.icon.getWidth(), it.icon.getHeight());
            it.im.setRectToRect(src, ir, Matrix.ScaleToFit.FILL);
            it.iconPaint.getShader().setLocalMatrix(it.im);
            it.iconPaint.setAlpha((int) (255 * hdrA));
            c.drawRoundRect(ir, n * 0.22f, n * 0.22f, it.iconPaint);
        }
        float nameA = Math.max(0f, 1f - Math.abs(d) * 2f);   // only the focused card shows its name
        if (nameA > 0.01f) {
            title.setAlpha((int) (255 * hdrA * nameA));
            c.drawText(it.label, x + n + 22 * s, y + n / 2f + title.getTextSize() * 0.35f, title);
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
