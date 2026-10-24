package com.gathin.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The floating panel under the Settings pill, macOS Control Center style: liquid-glass pill tiles floating
 * over Home with no panel behind them, each with a round icon, a name and a status line. A tile whose feature is on (Wi-Fi connected, Bluetooth on) shows its icon
 * in a white circle. The tiles only open things: a launcher cannot switch Wi-Fi or Bluetooth itself on
 * Android 10 and later.
 */
final class ControlCenter {

    /** One tile; a null subtitle leaves only the name. */
    static final class Tile {
        final int icon;
        final String title;
        final String subtitle;
        final boolean active;
        final Runnable click;
        final Runnable longClick;

        Tile(int icon, String title, String subtitle, boolean active, Runnable click, Runnable longClick) {
            this.icon = icon;
            this.title = title;
            this.subtitle = subtitle;
            this.active = active;
            this.click = click;
            this.longClick = longClick;
        }
    }

    /** Panel width in dp, for the popup. */
    static final int WIDTH_DP = 340;

    static final int ACCENT = 0xFF0A84FF;
    private static final int TILE_DP = 54;

    private final Context ctx;
    private final float density;
    private final Typeface font;
    private final LauncherPrefs prefs;
    /** Smoky see-through fill of a glass tile at rest (Home settings: Liquid glass); the rim is GlassEdgeDrawable. */
    private final int glass;

    ControlCenter(Context ctx) {
        this.ctx = ctx;
        prefs = LauncherPrefs.get(ctx);
        glass = prefs.uiGlassFill();
        density = ctx.getResources().getDisplayMetrics().density;
        font = ctx.getResources().getFont(R.font.outfit);
    }

    /** The whole panel; each row holds one tile (full width) or two. The first tile is tagged "first". */
    View build(Tile[][] rows) {
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        // No card behind the tiles; a little padding leaves room for a focused tile to grow.
        panel.setPadding(dp(6), 0, dp(6), dp(8));
        panel.setClipChildren(false);
        panel.setClipToPadding(false);

        boolean first = true;
        for (Tile[] row : rows) {
            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setClipChildren(false);
            for (int i = 0; i < row.length; i++) {
                View tile = tile(row[i]);
                if (first) {
                    tile.setTag("first");
                    first = false;
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(TILE_DP), 1f);
                if (i > 0) lp.leftMargin = dp(8);
                line.addView(tile, lp);
            }
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = dp(8);
            panel.addView(line, llp);
        }
        return panel;
    }

    private View tile(Tile t) {
        LinearLayout tile = new LinearLayout(ctx);
        tile.setOrientation(LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(9), 0, dp(12), 0);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setForeground(prefs.uiRim(density, true, 0));

        FrameLayout circle = new FrameLayout(ctx);
        ImageView icon = new ImageView(ctx);
        icon.setImageResource(t.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        circle.addView(icon, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        tile.addView(circle, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout words = new LinearLayout(ctx);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(10), 0, 0, 0);
        TextView title = text(t.title, 13, 0, 600);
        TextView sub = text(t.subtitle == null ? "" : t.subtitle, 11, 0, 400);
        words.addView(title);
        if (t.subtitle != null) words.addView(sub);
        tile.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tile.setContentDescription(t.subtitle == null ? t.title : t.title + ", " + t.subtitle);

        // Smoky glass at rest; white when focused, like the pills and the Inputs list.
        Runnable style = () -> {
            boolean f = tile.isFocused();
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(TILE_DP / 2f));
            g.setColor(f ? 0xF2FFFFFF : glass);
            tile.setBackground(g);
            title.setTextColor(f ? 0xFF111114 : 0xFFFFFFFF);
            sub.setTextColor(f ? 0x99111114 : 0xA6FFFFFF);
            GradientDrawable c = new GradientDrawable();
            c.setShape(GradientDrawable.OVAL);
            int fill, tint;
            if (t.active) {
                fill = f ? ACCENT : 0xFFFFFFFF;
                tint = f ? 0xFFFFFFFF : ACCENT;
            } else {
                fill = f ? 0x1A000000 : 0x29FFFFFF;
                tint = f ? 0xFF111114 : 0xFFFFFFFF;
            }
            c.setColor(fill);
            circle.setBackground(c);
            icon.setImageTintList(ColorStateList.valueOf(tint));
            tile.animate().scaleX(f ? 1.04f : 1f).scaleY(f ? 1.04f : 1f).setDuration(120).start();
        };
        style.run();
        tile.setOnFocusChangeListener((v, hasFocus) -> style.run());
        tile.setOnClickListener(v -> t.click.run());
        if (t.longClick != null) tile.setOnLongClickListener(v -> {
            t.longClick.run();
            return true;
        });
        return tile;
    }

    private TextView text(String s, int sp, int color, int weight) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(font);
        t.setFontVariationSettings("'wght' " + weight);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
