package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Home settings, made for the remote: Left/Right change a value, OK toggles. In the app list, OK picks an
 * app up, Up/Down move it and OK drops it; Right reaches its Shown/Hidden switch. Every change is saved
 * at once and Home rebuilds itself when you go back.
 */
public class LauncherSettingsActivity extends Activity {

    private static final class App {
        final String pkg;
        final String label;
        final Drawable icon;

        App(String pkg, String label, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    private static final int PANEL_DP = 360;

    private LauncherPrefs prefs;
    private float density;
    private Typeface font;
    private LinearLayout appRows;
    private final List<App> apps = new ArrayList<>();
    private Set<String> hidden;
    /** The row being moved, or null. */
    private View moving;
    /** Section headers and their rows; openSection is the index of the expanded one, or -1. */
    private final List<View> headers = new ArrayList<>();
    private final List<View> bodies = new ArrayList<>();
    private final List<TextView> chevrons = new ArrayList<>();
    private int openSection = -1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = LauncherPrefs.get(this);
        density = getResources().getDisplayMetrics().density;
        font = getResources().getFont(R.font.outfit);
        hidden = prefs.hidden();
        build();
    }

    private void build() {
        // A frosted panel floating on the right; Home stays visible on the left and rebuilds live.
        FrameLayout root = new FrameLayout(this);
        FrameLayout panel = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEB141416);
        bg.setCornerRadius(dp(28));
        bg.setStroke(Math.max(1, dp(1)), 0x33FFFFFF);
        panel.setBackground(bg);
        panel.setClipToOutline(true);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(PANEL_DP), ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END);
        plp.setMargins(dp(20), dp(20), dp(20), dp(20));
        root.addView(panel, plp);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(18), dp(24), dp(18), dp(40));
        panel.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(col, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(text(getString(R.string.ls_title), 24, 0xFFFFFFFF, 500));
        TextView hint = text(getString(R.string.ls_hint), 12, 0x99FFFFFF, 400);
        hint.setPadding(0, dp(6), 0, dp(10));
        col.addView(hint);

        // Collapsible sections, one open at a time so the panel stays short.
        headers.clear();
        bodies.clear();
        LinearLayout appsBody = group(col, R.string.ls_apps);
        TextView appsHint = text(getString(R.string.ls_apps_hint), 12, 0x80FFFFFF, 400);
        appsHint.setPadding(dp(4), 0, 0, dp(8));
        appsBody.addView(appsHint);
        appRows = new LinearLayout(this);
        appRows.setOrientation(LinearLayout.VERTICAL);
        appsBody.addView(appRows);
        loadApps();
        for (App a : apps) appRows.addView(appRow(a));

        LinearLayout wall = group(col, R.string.ls_wallpaper_section);
        wall.addView(wallpaperChooser());
        wall.addView(stepper(R.string.ls_wall_dim, LauncherPrefs.WALL_DIM, LauncherPrefs.DEF_WALL_DIM, 0, 80, 5, "%"));
        wall.addView(toggle(R.string.ls_glow, LauncherPrefs.GLOW, LauncherPrefs.DEF_GLOW));

        LinearLayout clock = group(col, R.string.ls_clock);
        clock.addView(toggle(R.string.ls_show_clock, LauncherPrefs.SHOW_CLOCK));
        clock.addView(toggle(R.string.ls_show_date, LauncherPrefs.SHOW_DATE));
        clock.addView(stepper(R.string.ls_clock_size, LauncherPrefs.CLOCK, LauncherPrefs.DEF_CLOCK_SP, 48, 140, 4, ""));
        clock.addView(stepper(R.string.ls_clock_weight, LauncherPrefs.CLOCK_WEIGHT, LauncherPrefs.DEF_CLOCK_WEIGHT, 100, 900, 50, ""));
        clock.addView(stepper(R.string.ls_clock_shadow, LauncherPrefs.CLOCK_SHADOW, LauncherPrefs.DEF_CLOCK_SHADOW, 0, 100, 10, "%"));
        clock.addView(stepper(R.string.ls_date_size, LauncherPrefs.DATE, LauncherPrefs.DEF_DATE_SP, 12, 40, 1, ""));
        clock.addView(stepper(R.string.ls_clock_top, LauncherPrefs.CLOCK_TOP, LauncherPrefs.DEF_CLOCK_TOP_DP, 0, 320, 4, "dp"));

        LinearLayout dock = group(col, R.string.ls_dock);
        dock.addView(stepper(R.string.ls_icon_size, LauncherPrefs.ICON, LauncherPrefs.DEF_ICON_DP, 32, 104, 4, "dp"));
        dock.addView(stepper(R.string.ls_spacing, LauncherPrefs.GAP, LauncherPrefs.DEF_GAP_DP, 6, 40, 2, "dp"));
        dock.addView(stepper(R.string.ls_visible, LauncherPrefs.VISIBLE, LauncherPrefs.DEF_VISIBLE, 4, 10, 1, ""));
        dock.addView(chooser(R.string.ls_icon_theme, LauncherPrefs.ICON_THEME, LauncherPrefs.DEF_ICON_THEME, IconThemes.NAMES));
        dock.addView(chooser(R.string.ls_icon_tint, LauncherPrefs.ICON_TINT, LauncherPrefs.DEF_ICON_TINT, IconThemes.TINT_NAMES));
        dock.addView(toggle(R.string.ls_icon_glass, LauncherPrefs.ICON_GLASS));
        dock.addView(stepper(R.string.ls_glass_edge, LauncherPrefs.GLASS_EDGE, LauncherPrefs.DEF_GLASS_EDGE, 1, 10, 1, ""));
        dock.addView(stepper(R.string.ls_glass_bright, LauncherPrefs.GLASS_BRIGHT, LauncherPrefs.DEF_GLASS_BRIGHT, 0, 200, 10, "%"));
        dock.addView(stepper(R.string.ls_shade, LauncherPrefs.SHADE, LauncherPrefs.DEF_SHADE, 0, 100, 10, "%"));
        dock.addView(stepper(R.string.ls_shade_height, LauncherPrefs.SHADE_HEIGHT, LauncherPrefs.DEF_SHADE_HEIGHT, 80, 540, 20, "dp"));
        dock.addView(stepper(R.string.ls_bottom, LauncherPrefs.BOTTOM, LauncherPrefs.DEF_BOTTOM_DP, 0, 200, 4, "dp"));
        dock.addView(toggle(R.string.ls_pill_labels, LauncherPrefs.PILL_LABELS, false));

        LinearLayout glass = group(col, R.string.ls_glass);
        glass.addView(stepper(R.string.ls_ui_rim, LauncherPrefs.UI_RIM, LauncherPrefs.DEF_UI_RIM, 0, 150, 10, "%"));
        glass.addView(stepper(R.string.ls_ui_rim_width, LauncherPrefs.UI_RIM_WIDTH, LauncherPrefs.DEF_UI_RIM_WIDTH, 1, 6, 1, ""));
        glass.addView(stepper(R.string.ls_ui_tint, LauncherPrefs.UI_TINT, LauncherPrefs.DEF_UI_TINT, 0, 100, 5, "%"));
        glass.addView(stepper(R.string.ls_dock_tint, LauncherPrefs.DOCK_TINT, LauncherPrefs.DEF_DOCK_TINT, 0, 50, 2, "%"));
        showOpen();

        col.addView(section(R.string.ls_other));
        View reset = row(text(getString(R.string.ls_reset), 15, 0xFFFF6961, 500), null);
        reset.setOnClickListener(v -> {
            prefs.reset();
            hidden = prefs.hidden();
            build();
        });
        col.addView(reset);

        setContentView(root);
        int focus = Math.max(0, openSection < 0 ? 0 : openSection);
        headers.get(focus).requestFocus();
    }

    // ---- rows ------------------------------------------------------------------------------------

    /** Left/Right through named choices, stored as their index; wraps around. */
    private View chooser(int labelRes, String key, int def, String[] names) {
        TextView value = text("", 15, 0xFFFFFFFF, 500);
        View row = row(text(getString(labelRes), 15, 0xE6FFFFFF, 400), value);
        int[] at = {Math.max(0, Math.min(names.length - 1, prefs.getInt(key, def)))};
        Runnable show = () -> value.setText("\u2039  " + names[at[0]] + "  \u203A");
        show.run();
        row.setOnKeyListener((r, keyCode, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            int d = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
            if (d == 0) return false;
            at[0] = (at[0] + d + names.length) % names.length;
            prefs.putInt(key, at[0]);
            show.run();
            return true;
        });
        return row;
    }

    private View stepper(int labelRes, String key, int def, int min, int max, int step, String unit) {
        TextView value = text("", 15, 0xFFFFFFFF, 500);
        View row = row(text(getString(labelRes), 15, 0xE6FFFFFF, 400), value);
        int[] v = {prefs.getInt(key, def)};
        Runnable show = () -> value.setText("‹  " + v[0] + (unit.isEmpty() ? "" : " " + unit) + "  ›");
        show.run();
        row.setOnKeyListener((r, keyCode, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            int d = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? step : keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -step : 0;
            if (d == 0) return false;
            int n = Math.max(min, Math.min(max, v[0] + d));
            if (n != v[0]) {
                v[0] = n;
                prefs.putInt(key, n);
                show.run();
            }
            return true;
        });
        return row;
    }

    /**
     * Left/Right through None, the built-in wallpapers, the pictures in the wallpapers folder and, when a
     * theme pushed one, its picture. Wraps around at either end.
     */
    private View wallpaperChooser() {
        List<int[]> codes = new ArrayList<>();      // {choice}
        List<String> files = new ArrayList<>();     // file name for FILE choices, else null
        List<String> names = new ArrayList<>();
        codes.add(new int[]{Wallpapers.NONE});
        files.add(null);
        names.add(getString(R.string.ls_wall_none));
        codes.add(new int[]{Wallpapers.DAILY});
        files.add(null);
        names.add(getString(R.string.ls_wall_daily));
        for (int i = 1; i <= Wallpapers.count(); i++) {
            codes.add(new int[]{i});
            files.add(null);
            names.add(Wallpapers.name(i, "", ""));
        }
        for (java.io.File f : Wallpapers.files(this)) {
            codes.add(new int[]{Wallpapers.FILE});
            files.add(f.getName());
            names.add(Wallpapers.label(f));
        }
        boolean custom = Config.load(this).wallpaper != null;
        if (custom) {
            codes.add(new int[]{Wallpapers.CUSTOM});
            files.add(null);
            names.add(getString(R.string.ls_wall_custom));
        }
        int current = prefs.getInt(LauncherPrefs.WALLPAPER, custom ? Wallpapers.CUSTOM : Wallpapers.NONE);
        String currentFile = prefs.getString(LauncherPrefs.WALL_FILE);
        int start = 0;
        for (int i = 0; i < codes.size(); i++) {
            if (codes.get(i)[0] == current && (current != Wallpapers.FILE || currentFile.equals(files.get(i)))) {
                start = i;
                break;
            }
        }
        TextView value = text("", 15, 0xFFFFFFFF, 500);
        value.setSingleLine(true);
        View row = row(text(getString(R.string.ls_wallpaper), 15, 0xE6FFFFFF, 400), value);
        int[] at = {start};
        Runnable show = () -> value.setText("\u2039  " + names.get(at[0]) + "  \u203A");
        show.run();
        row.setOnKeyListener((r, keyCode, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            int d = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
            if (d == 0) return false;
            at[0] = (at[0] + d + codes.size()) % codes.size();
            prefs.putWallpaper(codes.get(at[0])[0], files.get(at[0]));
            show.run();
            return true;
        });
        return row;
    }

    private View toggle(int labelRes, String key) {
        return toggle(labelRes, key, true);
    }

    private View toggle(int labelRes, String key, boolean def) {
        TextView value = text("", 15, 0xFFFFFFFF, 500);
        View row = row(text(getString(labelRes), 15, 0xE6FFFFFF, 400), value);
        Runnable show = () -> value.setText(prefs.getBool(key, def) ? R.string.ls_on : R.string.ls_off);
        show.run();
        row.setOnClickListener(r -> {
            prefs.putBool(key, !prefs.getBool(key, def));
            show.run();
        });
        return row;
    }

    private View appRow(App a) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.HORIZONTAL);
        main.setGravity(Gravity.CENTER_VERTICAL);
        main.setPadding(dp(10), dp(6), dp(12), dp(6));
        main.setFocusable(true);
        main.setBackground(rowBg(0x26FFFFFF));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(a.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getWidth() * 0.225f);
            }
        });
        icon.setClipToOutline(true);
        main.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView label = text(a.label, 15, 0xE6FFFFFF, 400);
        label.setSingleLine(true);
        label.setPadding(dp(12), 0, 0, 0);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        main.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView grip = text("", 14, 0xB3FFFFFF, 500);
        main.addView(grip);
        row.addView(main, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView vis = text("", 13, 0xFFFFFFFF, 500);
        vis.setGravity(Gravity.CENTER);
        vis.setFocusable(true);
        vis.setPadding(dp(10), dp(8), dp(10), dp(8));
        vis.setBackground(rowBg(0x33FFFFFF));
        LinearLayout.LayoutParams visLp = new LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT);
        visLp.leftMargin = dp(10);
        row.addView(vis, visLp);

        Runnable show = () -> {
            boolean h = hidden.contains(a.pkg);
            vis.setText(h ? R.string.ls_hidden : R.string.ls_shown);
            main.setAlpha(h ? 0.45f : 1f);
            grip.setText(moving == row ? getString(R.string.ls_moving) : "");
            main.setBackground(rowBg(moving == row ? 0x5C0A84FF : 0x26FFFFFF));
        };
        show.run();
        row.setTag(show);

        vis.setOnClickListener(v -> {
            if (!hidden.remove(a.pkg)) hidden.add(a.pkg);
            prefs.putHidden(hidden);
            show.run();
        });
        main.setOnClickListener(v -> {
            View was = moving;
            moving = moving == row ? null : row;
            if (was != null && was != row) ((Runnable) was.getTag()).run();
            show.run();
        });
        main.setOnKeyListener((v, keyCode, e) -> {
            if (moving != row || e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                moving = null;
                show.run();
                return true;
            }
            int d = keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
            if (d == 0) return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
            int i = appRows.indexOfChild(row), j = i + d;
            if (j < 0 || j >= appRows.getChildCount()) return true;
            appRows.removeViewAt(i);
            appRows.addView(row, j);
            Collections.swap(apps, i, j);
            List<String> order = new ArrayList<>();
            for (App x : apps) order.add(x.pkg);
            prefs.putOrder(order);
            main.requestFocus();
            return true;
        });
        return row;
    }

    /** A focusable label/value row. */
    private View row(TextView label, TextView value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));
        row.setFocusable(true);
        row.setBackground(rowBg(0x26FFFFFF));
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (value != null) row.addView(value);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        row.setLayoutParams(lp);
        return row;
    }

    /** A section header row (OK opens or closes it) followed by its rows; returns the rows' container. */
    private LinearLayout group(LinearLayout col, int titleRes) {
        int index = headers.size();
        TextView title = text(getString(titleRes).toUpperCase(), 12, 0xCCFFFFFF, 600);
        title.setLetterSpacing(0.18f);
        TextView chevron = text("\u203A", 18, 0xB3FFFFFF, 500);
        chevron.setGravity(Gravity.CENTER);
        View header = row(title, chevron);
        ((LinearLayout.LayoutParams) header.getLayoutParams()).topMargin = dp(index == 0 ? 6 : 2);
        header.setOnClickListener(v -> {
            if (moving != null) return;
            openSection = openSection == index ? -1 : index;
            showOpen();
        });
        col.addView(header);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(8), dp(2), 0, dp(8));
        col.addView(body);
        headers.add(header);
        bodies.add(body);
        chevrons.add(chevron);
        return body;
    }

    /** Shows only the open section's rows; its chevron points down. */
    private void showOpen() {
        for (int i = 0; i < bodies.size(); i++) {
            boolean open = i == openSection;
            bodies.get(i).setVisibility(open ? View.VISIBLE : View.GONE);
            chevrons.get(i).setRotation(open ? 90 : 0);
        }
    }

    private TextView section(int res) {
        TextView t = text(getString(res).toUpperCase(), 11, 0x8CFFFFFF, 600);
        t.setLetterSpacing(0.18f);
        t.setPadding(dp(4), dp(20), 0, dp(6));
        return t;
    }

    private TextView text(String s, int sp, int color, int weight) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(font);
        t.setFontVariationSettings("'wght' " + weight);   // Outfit is a variable font, as on Home
        return t;
    }

    /** Clear at rest, a soft white slab when focused, an optional tint when selected. */
    private StateListDrawable rowBg(int selectedColor) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, rounded(selectedColor == 0x26FFFFFF ? 0x2EFFFFFF : selectedColor, true));
        s.addState(new int[]{}, rounded(selectedColor == 0x26FFFFFF ? 0x00000000 : selectedColor, false));
        return s;
    }

    private GradientDrawable rounded(int color, boolean edge) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(14));
        if (edge) g.setStroke(Math.max(1, dp(1)), 0x59FFFFFF);
        return g;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    // ---- apps ------------------------------------------------------------------------------------

    /** The same apps and order as the dock (Settings, which always closes it, is not listed). */
    private void loadApps() {
        apps.clear();
        PackageManager pm = getPackageManager();
        Config cfg = Config.load(this);
        List<ResolveInfo> found = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || pkg.equals("com.android.tv.settings")
                    || cfg.hiddenApps.contains(pkg) || !seen.add(pkg)) continue;
            Drawable custom = CustomIcons.forPackage(this, pkg);
            apps.add(new App(pkg, String.valueOf(ri.loadLabel(pm)), custom != null ? custom : ri.loadIcon(pm)));
        }
        List<String> order = prefs.order();
        Collections.sort(apps, (a, b) -> LauncherPrefs.compare(a.pkg, a.label, b.pkg, b.label, order, cfg.dockApps));
    }

    @Override
    public void onBackPressed() {
        if (moving != null) {
            View was = moving;
            moving = null;
            ((Runnable) was.getTag()).run();
            return;
        }
        super.onBackPressed();
    }
}
