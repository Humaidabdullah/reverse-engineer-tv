package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Screensaver: the owner's wallpapers (Home settings or the phone's Looks), one after another, each
 * drifting slowly across the screen and dissolving into the next, the way Apple TV's aerials move.
 * The time sits small in a corner. Shuffled once per start, so the order never repeats exactly.
 *
 * Two image views take turns: the next picture is decoded on a worker thread while the current one
 * drifts, so a slow TV never stalls mid-pan.
 */
public class WallpaperDream extends DreamService {
    private static final long SHOW_MS = 14_000L, FADE_MS = 2_400L;
    private static final float ZOOM = 1.12f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<File> pictures = new ArrayList<>();
    private ImageView front, back;
    private TextView clock;
    private int next;
    private boolean panLeft;

    private final Runnable advance = this::showNext;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            clock.setText(DateFormat.getTimeFormat(WallpaperDream.this).format(new Date()));
            handler.postDelayed(this, 15_000L);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        back = image();
        front = image();
        root.addView(back);
        root.addView(front);
        float d = getResources().getDisplayMetrics().density;
        clock = new TextView(this);
        clock.setTextColor(0xE6FFFFFF);
        clock.setTextSize(30);
        clock.setShadowLayer(12 * d, 0, 0, 0x99000000);
        try {
            clock.setTypeface(Typeface.create(getResources().getFont(R.font.outfit), Typeface.NORMAL));
        } catch (RuntimeException ignored) {
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, (int) (48 * d), (int) (36 * d));
        root.addView(clock, lp);
        setContentView(root);
    }

    private ImageView image() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setAlpha(0f);
        v.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return v;
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        pictures.clear();
        pictures.addAll(Wallpapers.files(this));
        Collections.shuffle(pictures);
        handler.post(tick);
        showNext();
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacksAndMessages(null);
        front.animate().cancel();
        back.animate().cancel();
        super.onDreamingStopped();
    }

    private void showNext() {
        if (pictures.isEmpty()) return;   // no pictures: the clock on black
        File f = pictures.get(next++ % pictures.size());
        int w = getResources().getDisplayMetrics().widthPixels;
        new Thread(() -> {
            Bitmap bmp = decode(f, w);
            handler.post(() -> {
                if (bmp == null) {
                    handler.post(advance);
                    return;
                }
                // The incoming picture goes on top, starts slightly zoomed and pans one way; the next pans the other.
                ImageView in = back, out = front;
                front = in;
                back = out;
                in.bringToFront();
                clock.bringToFront();
                in.setImageBitmap(bmp);
                in.animate().cancel();
                in.setAlpha(0f);
                float shift = w * (ZOOM - 1f) / 2f * 0.85f;
                in.setScaleX(ZOOM);
                in.setScaleY(ZOOM);
                in.setTranslationX(panLeft ? shift : -shift);
                // Timed, so corrected for animator_duration_scale: at the Vu's 0.6 the pan would stop mid-picture.
                in.animate().alpha(1f).setDuration(FocusMotion.ms(this, FADE_MS)).start();
                in.animate().translationX(panLeft ? -shift : shift).scaleX(ZOOM - 0.04f).scaleY(ZOOM - 0.04f)
                        .setDuration(FocusMotion.ms(this, SHOW_MS + FADE_MS)).setInterpolator(new LinearInterpolator()).start();
                panLeft = !panLeft;
                handler.postDelayed(() -> out.setImageDrawable(null), FADE_MS + 100);
                handler.postDelayed(advance, SHOW_MS);
            });
        }, "dream-decode").start();
    }

    private static Bitmap decode(File f, int screenW) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= screenW) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        try {
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }
}
