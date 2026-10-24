package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock (favourites, fixed order),
 * then the module cards the owner chose to show (by label), the rest is alphabetical.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for a D-pad direction key on a tile. Return true when handled (focus stays put). */
        boolean onTileVerticalKey(int position, int keyCode);

        /** The Settings icon at the end of the dock. */
        void onOpenSettings();

        /** Holding OK on the Settings icon: the launcher's own settings. */
        void onOpenLauncherSettings();
    }

    static final class Entry {
        static final int APP = 0, HOME_SETTINGS = 1, CARD = 2, SETTINGS = 3;

        final int kind;
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        /** Text under the tile when it is focused in the grid: the label, plus the caption for a card. */
        final String name;
        /** Ambient glow colour; 0 until sampled from the banner, or the card's accent from the start. */
        int ambient;
        /** The icon as the dock draws it, built on first bind. */
        Drawable dockIcon;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this(APP, label, component, banner, icon, null, 0);
        }

        Entry(int kind, String label, ComponentName component, Drawable banner, Drawable icon, String caption, int accent) {
            this.kind = kind;
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
            this.name = caption == null ? label : label + " \u00B7 " + caption;
            this.ambient = accent;
        }
    }

    static final float FOCUS_SCALE = 1.10f;
    /** Design size of a tile (dp); tiles shrink from here when the columns don't fit, see tileWidthPx. */
    static final float DESIGN_W_DP = 180f, DESIGN_H_DP = 101f;

    /**
     * Tile width for a grid of this inner width: the cell minus room for the focus zoom and the glass
     * highlight, capped at the design size so 4 columns on a 1080p TV look exactly as before.
     */
    static int tileWidthPx(int innerWidthPx, int columns, float density) {
        int design = Math.round(DESIGN_W_DP * density);
        if (innerWidthPx <= 0 || columns <= 0) return design;
        int cell = innerWidthPx / columns;
        int fit = (int) ((cell - 12 * density) / FOCUS_SCALE);
        return Math.max(Math.round(80 * density), Math.min(design, fit));
    }

    /** Dock icons: macOS-style squares, packed together in the middle of the screen (see DockDecoration). */
    /** Set from LauncherPrefs when Home starts (the defaults live there). */
    static float DOCK_DP = 66f, DOCK_GAP_DP = 18f;
    /** Icons visible in the dock at once; the rest scroll. */
    static int DOCK_VISIBLE = 8;
    private static final String TV_SETTINGS_PKG = "com.android.tv.settings";
    private static final int TYPE_TILE = 0, TYPE_DOCK = 1;

    static int dockTilePx(float density) {
        return Math.round(DOCK_DP * density);
    }

    static int tileHeightPx(int tileWidthPx) {
        return Math.round(tileWidthPx * DESIGN_H_DP / DESIGN_W_DP);
    }

    /** Height of one grid item: the tile, the name label under it and the item's own padding. */
    static int itemHeightPx(int tileHeightPx, float density) {
        return tileHeightPx + Math.round(45 * density);
    }

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    /** Dock order (config.xml dock_apps, or a theme's config.json). */
    private final List<String> favourites;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final boolean showHomeSettings;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): no overshoot, shorter fades. */
    private final boolean reducedMotion;
    private final List<Entry> items = new ArrayList<>();
    /** Icon style and tint from Home settings (IconThemes). */
    private final int iconTheme, iconTint;

    AppAdapter(Context ctx, TileListener listener, int dockSize, Config cfg) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.favourites = cfg.dockApps;
        this.hidden = new HashSet<>(cfg.hiddenApps);
        this.showHomeSettings = cfg.showHomeSettingsShortcut;
        this.reducedMotion = cfg.reducedMotion;
        LauncherPrefs prefs = LauncherPrefs.get(ctx);
        this.iconTheme = prefs.getInt(LauncherPrefs.ICON_THEME, LauncherPrefs.DEF_ICON_THEME);
        this.iconTint = IconThemes.tint(prefs.getInt(LauncherPrefs.ICON_TINT, LauncherPrefs.DEF_ICON_TINT));
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    /**
     * Rebuilds the tiles. A shown module card stands in for every tile of its package (same component,
     * so focus follows it); hidden_apps wins over a card, and a card's package in dock_apps takes that slot.
     */
    void reload(List<Cards.Card> cards) {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        LauncherPrefs prefs = LauncherPrefs.get(ctx);
        Set<String> hiddenByOwner = prefs.hidden();
        List<String> order = prefs.order();
        Map<String, Cards.Card> cardByPkg = new HashMap<>();
        for (Cards.Card c : cards) cardByPkg.put(c.pkg, c);
        List<Entry> fresh = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg) || hiddenByOwner.contains(pkg)
                    || pkg.equals(TV_SETTINGS_PKG)) continue;
            ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
            Cards.Card card = cardByPkg.get(pkg);
            if (card != null && !card.component.equals(component)) continue;   // the card stands in for this tile
            Drawable banner = loadBanner(pm, ri);
            Drawable icon = ri.loadIcon(pm);
            if (card != null) {
                fresh.add(new Entry(Entry.CARD, card.label, component, banner, icon, card.caption, card.accent));
            } else {
                // Same cleaner as the card prompt: an app label with a bidi override or line separator must not
                // redraw the tile name as something else (#117).
                fresh.add(new Entry(Cards.cleanLabel(String.valueOf(ri.loadLabel(pm)), pkg), component, banner, icon));
            }
        }
        Collections.sort(fresh, (a, b) -> {
            // The owner's arrangement from the launcher settings wins over everything else.
            int oa = order.indexOf(a.pkg), ob = order.indexOf(b.pkg);
            if (oa >= 0 || ob >= 0) {
                if (oa < 0) return 1;
                if (ob < 0) return -1;
                return Integer.compare(oa, ob);
            }
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            // Cards come right after the configured dock apps, before the alphabetical grid.
            boolean ca = a.kind == Entry.CARD, cb = b.kind == Entry.CARD;
            if (ca != cb) return ca ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
        // Optional "Default home" tile (config.xml), only when this TV actually has a home-screen picker.
        if (showHomeSettings
                && pm.resolveActivity(new Intent(Settings.ACTION_HOME_SETTINGS), 0) != null) {
            fresh.add(new Entry(Entry.HOME_SETTINGS, ctx.getString(R.string.home_settings),
                    new ComponentName(ctx, HomeSettingsActivity.class), null,
                    ctx.getDrawable(R.drawable.ic_home_settings), null, 0));
        }
        // Settings always closes the dock.
        fresh.add(new Entry(Entry.SETTINGS, ctx.getString(R.string.settings),
                new ComponentName(TV_SETTINGS_PKG, "settings"), null, settingsIcon(), null, 0xFF8E8E93));
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    private Drawable loadBanner(PackageManager pm, ResolveInfo ri) {
        int id = ri.activityInfo.banner;
        if (id == 0) id = ri.activityInfo.applicationInfo.banner;
        if (id != 0) {
            try {
                Resources resources = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                // Keep at least xhdpi detail rather than downsampling artwork before the focus zoom.
                int density = Math.max(DisplayMetrics.DENSITY_XHIGH, ctx.getResources().getDisplayMetrics().densityDpi);
                return resources.getDrawableForDensity(id, density, null);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            }
        }
        return ri.activityInfo.loadBanner(pm);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        float density = parent.getResources().getDisplayMetrics().density;
        int width = parent.getWidth() > 0 ? parent.getWidth() : parent.getMeasuredWidth();
        int inner = width - parent.getPaddingLeft() - parent.getPaddingRight();
        int w = tileWidthPx(inner, dockSize, density), h = tileHeightPx(w);
        if (viewType == TYPE_DOCK) {
            w = h = dockTilePx(density);
            // A sideways dock: each item is its icon plus the gap (not the whole list), so the focus zoom
            // fits inside the item; the dock clips children to their bounds at its scrolling ends.
            ViewGroup.LayoutParams itemLp = v.getLayoutParams();
            itemLp.width = w + Math.round(DOCK_GAP_DP * density);
            v.setLayoutParams(itemLp);
            // Liquid glass rim and sheen over the icon (Home settings: Glass icon edges).
            LauncherPrefs prefs = LauncherPrefs.get(ctx);
            // Clear icons are see-through glass: no dark tile behind them.
            if (iconTheme == IconThemes.CLEAR) v.findViewById(R.id.card).setBackground(null);
            if (prefs.getBool(LauncherPrefs.ICON_GLASS)) {
                int edge = prefs.getInt(LauncherPrefs.GLASS_EDGE, LauncherPrefs.DEF_GLASS_EDGE);
                int bright = prefs.getInt(LauncherPrefs.GLASS_BRIGHT, LauncherPrefs.DEF_GLASS_BRIGHT);
                ((FrameLayout) v.findViewById(R.id.card)).setForeground(new GlassEdgeDrawable(density, edge, bright));
            }
        }
        View card = v.findViewById(R.id.card);
        ViewGroup.LayoutParams cardLp = card.getLayoutParams();
        cardLp.width = w;
        cardLp.height = h;
        card.setLayoutParams(cardLp);
        View name = v.findViewById(R.id.name);
        ViewGroup.MarginLayoutParams nameLp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
        nameLp.width = w;
        nameLp.topMargin = h + Math.round(7 * density);
        name.setLayoutParams(nameLp);
        return new Holder(v, listener, reducedMotion);
    }

    @Override
    public int getItemViewType(int position) {
        return position < dockSize ? TYPE_DOCK : TYPE_TILE;
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.name.setText(e.name);
        h.name.setVisibility(position < dockSize ? View.INVISIBLE : View.VISIBLE);   // the dock has no labels
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.name.setAlpha(focused ? 1f : 0f);
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        if (position < dockSize && e.icon != null) {
            // Dock: the app icon fills the square; adaptive icons drop the system mask (a circle on Google TV)
            // and show their layers full bleed, so the tile's rounded outline is the only shape.
            if (e.dockIcon == null) {
                Drawable custom = CustomIcons.forPackage(ctx, e.pkg);
                e.dockIcon = custom != null ? custom
                        : e.kind == Entry.SETTINGS ? e.icon : fullBleed(e.pkg, e.icon, h.card.getLayoutParams().width);
                // iOS-style icon look (Home settings: Icon style), made from the icon as the dock draws it.
                if (iconTheme != IconThemes.ORIGINAL) {
                    int n = h.card.getLayoutParams().width;   // the size fullBleed's layer insets were made for
                    e.dockIcon = new BitmapDrawable(ctx.getResources(),
                            IconThemes.apply(rasterize(e.dockIcon, n).getBitmap(), iconTheme, iconTint));
                }
            }
            h.banner.setScaleType(ImageView.ScaleType.FIT_XY);
            h.banner.setImageDrawable(e.dockIcon);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }
        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            if (e.kind == Entry.SETTINGS) {
                listener.onOpenLauncherSettings();
                return true;
            }
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
            } catch (ActivityNotFoundException ignored) {
            }
            return true;
        });
    }

    /**
     * A legacy icon cut to its opaque part: many TV apps (Netflix) ship a shape with a transparent margin,
     * which would sit inside the dock square with dark edges. Square-ish content is stretched to fill.
     */
    private Drawable trimmed(Drawable icon, int sizePx) {
        int n = Math.max(sizePx, 96);
        Drawable d = icon.getConstantState() != null ? icon.getConstantState().newDrawable().mutate() : icon;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, n, n);
        d.draw(new Canvas(bmp));
        int l = n, t = n, r = -1, b = -1;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (Color.alpha(bmp.getPixel(x, y)) < 200) continue;
                if (x < l) l = x;
                if (x > r) r = x;
                if (y < t) t = y;
                if (y > b) b = y;
            }
        }
        if (r < 0) {
            bmp.recycle();
            return icon;
        }
        // The icon's own (smaller) rounded corners fall outside the dock's corner radius, so inset a hair.
        int in = Math.round((r - l) * 0.02f);
        Bitmap cut = Bitmap.createBitmap(bmp, l + in, t + in, Math.max(1, r - l - 2 * in), Math.max(1, b - t - 2 * in));
        if (cut != bmp) bmp.recycle();
        return new BitmapDrawable(ctx.getResources(), cut);
    }

    /** The package's launcher icon at the highest density it ships, for scaling up without blur. */
    private Drawable sharpestIcon(String pkg, Drawable fallback) {
        try {
            PackageManager pm = ctx.getPackageManager();
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(pkg);
            ResolveInfo ri = pm.resolveActivity(tv, 0);
            int id = ri != null && ri.activityInfo.getIconResource() != 0 ? ri.activityInfo.getIconResource() : ai.icon;
            if (id != 0) return pm.getResourcesForApplication(ai).getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null);
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private BitmapDrawable rasterize(Drawable d, int n) {
        Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        copy.setBounds(0, 0, n, n);
        copy.setFilterBitmap(true);
        copy.draw(new Canvas(bmp));
        return new BitmapDrawable(ctx.getResources(), bmp);
    }

    /**
     * A flat icon drawn on black, put on another colour instead: each pixel is treated as its colour
     * times a coverage (its brightest channel), and the uncovered part filled with the new background.
     */
    private Drawable onBackground(Drawable flat, int bg) {
        if (!(flat instanceof BitmapDrawable)) return flat;
        Bitmap src = ((BitmapDrawable) flat).getBitmap();
        Bitmap out = src.copy(Bitmap.Config.ARGB_8888, true);
        int w = out.getWidth(), h = out.getHeight();
        int[] px = new int[w * h];
        out.getPixels(px, 0, w, 0, 0, w, h);
        int br = Color.red(bg), bgG = Color.green(bg), bb = Color.blue(bg);
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
            float t = Math.min(1f, Math.max(r, Math.max(g, b)) / 200f);   // coverage of the artwork
            px[i] = Color.rgb(Math.min(255, r + Math.round((1 - t) * br)),
                    Math.min(255, g + Math.round((1 - t) * bgG)),
                    Math.min(255, b + Math.round((1 - t) * bb)));
        }
        out.setPixels(px, 0, w, 0, 0, w, h);
        return new BitmapDrawable(ctx.getResources(), out);
    }

    /** macOS System Settings look: a dark gear on a light grey gradient square. */
    private Drawable settingsIcon() {
        int size = dockTilePx(ctx.getResources().getDisplayMetrics().density);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFFE5E5EA, 0xFFAEAEB2});
        bg.setSize(size, size);
        Drawable gear = ctx.getDrawable(R.drawable.ic_settings).mutate();
        gear.setTint(0xFF3A3A3C);
        LayerDrawable d = new LayerDrawable(new Drawable[]{bg, gear});
        int in = Math.round(size * 0.2f);
        d.setLayerInset(1, in, in, in, in);
        return d;
    }

    /** Apps whose TV icon has a background that looks wrong in the dock, and the colour to use instead. */
    private static final Map<String, Integer> ICON_BACKGROUNDS = new HashMap<>();
    static {
        ICON_BACKGROUNDS.put("com.android.vending", 0xFFFFFFFF);   // Play Store: black on Google TV, white elsewhere
    }

    /** An adaptive icon's layers drawn past the edges like the system does (a quarter each side), unmasked. */
    private Drawable fullBleed(String pkg, Drawable icon, int sizePx) {
        Integer bgOverride = ICON_BACKGROUNDS.get(pkg);
        if (bgOverride != null && !(icon instanceof AdaptiveIconDrawable)) {
            // The TV activity icon is a flat image; the app's own icon is often the layered phone one.
            try {
                Drawable app = ctx.getPackageManager().getApplicationIcon(pkg);
                if (app instanceof AdaptiveIconDrawable) icon = app;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            if (!(icon instanceof AdaptiveIconDrawable)) {
                // Recolour the sharpest copy of the icon, then sit the logo inside the square with a margin.
                Drawable sharp = sharpestIcon(pkg, icon);
                LayerDrawable d = new LayerDrawable(new Drawable[]{new ColorDrawable(bgOverride),
                        onBackground(rasterize(sharp, Math.max(sizePx * 2, 256)), bgOverride)});
                int in = Math.round(sizePx * 0.12f);
                d.setLayerInset(1, in, in, in, in);
                return d;
            }
        }
        if (!(icon instanceof AdaptiveIconDrawable)) return trimmed(icon, sizePx);
        AdaptiveIconDrawable a = (AdaptiveIconDrawable) icon;
        List<Drawable> layers = new ArrayList<>();
        Integer bg = ICON_BACKGROUNDS.get(pkg);
        if (bg != null) layers.add(new ColorDrawable(bg));
        else if (a.getBackground() != null) layers.add(a.getBackground());
        if (a.getForeground() != null) layers.add(a.getForeground());
        if (layers.isEmpty()) return icon;
        LayerDrawable d = new LayerDrawable(layers.toArray(new Drawable[0]));
        int q = -sizePx / 4;
        for (int i = 0; i < layers.size(); i++) d.setLayerInset(i, q, q, q, q);
        return d;
    }

    /** Opens the tile's activity by explicit component, with no extras: a card can name nothing else. */
    private void launch(Entry e) {
        if (e.kind == Entry.SETTINGS) {
            listener.onOpenSettings();
            return;
        }
        if (e.kind == Entry.HOME_SETTINGS) {
            ctx.startActivity(new Intent(ctx, HomeSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).component.flattenToShortString().hashCode();
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).component.flattenToShortString() : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).component.flattenToShortString().equals(key)) return i;
        }
        return -1;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry; a card's accent when it set one. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    /** Corner radius for a tile of this width: 16dp at the design width, proportionally less when smaller. */
    static float cornerRadius(int tileWidthPx, int tileHeightPx, float density) {
        if (tileWidthPx == tileHeightPx) return 0.225f * tileWidthPx;   // dock square: app-icon corners
        return 16f * density * Math.min(1f, tileWidthPx / (DESIGN_W_DP * density));
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;
        final boolean reducedMotion;

        Holder(View v, TileListener listener, boolean reducedMotion) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);
            this.reducedMotion = reducedMotion;

            final float density = v.getResources().getDisplayMetrics().density;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    // 16dp at the design width, scaled down with the tile so small tiles keep the same shape.
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius(view.getWidth(), view.getHeight(), density));
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().cancel();
                name.animate().cancel();
                if (reducedMotion) {
                    // No overshoot: the extra frames redraw the banner and the dock band on a weak GPU.
                    view.animate().scaleX(s).scaleY(s)
                            .setDuration(focused ? 120 : 80)
                            .setInterpolator(new DecelerateInterpolator())
                            .withLayer()
                            .start();
                    name.animate().alpha(focused ? 1f : 0f).setDuration(100).start();
                } else {
                    view.animate().scaleX(s).scaleY(s)
                            .setDuration(focused ? 260 : 180)
                            .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                            .withLayer()
                            .start();
                    name.animate().alpha(focused ? 1f : 0f)
                            .translationY(focused ? density * 4f : -density * 6f)
                            .setDuration(220).start();
                }
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
