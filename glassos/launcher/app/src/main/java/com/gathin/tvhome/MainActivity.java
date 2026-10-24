package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.transition.ChangeBounds;
import android.transition.TransitionManager;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    private String liveTvPkg;
    private String liveTvAction;
    private String inputAction;
    private String sourcePanelService;
    private String sourcePanelAction;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    private int ambientColor = -1;
    private boolean heroShown;
    private TextView greeting;
    private View btnInputs;
    private TextView btnWifi;
    private ViewGroup topButtons;
    /** Home settings: show the Inputs, Wi-Fi and Settings labels on the focused pill (off: icons only). */
    private boolean pillLabels;
    /** The network as the Wi-Fi pill last showed it, for the Control Center's Wi-Fi tile. */
    private String netStatus = "";
    private int netIcon = R.drawable.ic_wifi;
    private boolean netOnline;
    private TextView shamsiDate;
    private boolean shamsi;
    private String vpnApp;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    // How far the grid has scrolled the glass. springY works without it, in grid content space, so a
    // scroll moves the glass in lockstep with its tile instead of becoming a moving spring target.
    private float glassScrollShift;
    private float glassScrollShiftX;
    /** Focus changes closer together than this snap the glow instead of springing it. */
    private static final long FAST_MOVE_MS = 220;
    private long lastGlassMoveAt;
    /** Left/Right is being held (key repeats arriving). */
    private boolean holdingDpad;
    /** LauncherPrefs revision this Home was built with; a newer one rebuilds it on resume. */
    private int prefsRev;
    /** Rebuilds Home shortly after a settings change, so the panel over it previews the result. */
    private final Runnable rebuildForPrefs = this::recreate;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (sp, key) -> {
        if (LauncherPrefs.REV.equals(key)) return;
        if (LauncherPrefs.WALLPAPER_KEYS.contains(key)) {
            // Wallpaper only: crossfade it in place instead of rebuilding Home.
            this.handler.removeCallbacks(this.fadeWallpaper);
            this.handler.postDelayed(this.fadeWallpaper, 250);
            return;
        }
        this.rebuildPending = true;
        this.handler.removeCallbacks(this.rebuildForPrefs);
        this.handler.postDelayed(this.rebuildForPrefs, 350);   // a held Left/Right only rebuilds once it settles
    };
    private boolean rebuildPending;
    private final Runnable fadeWallpaper = () -> {
        LauncherPrefs p = LauncherPrefs.get(this);
        applyWallpaper(p, true);
        if (!rebuildPending) prefsRev = p.rev();   // nothing else changed: no rebuild when the panel closes
    };
    /** The view holding the current wallpaper and its dimming; null before the first one is set. */
    private View wallpaperLayer;
    private File themeWallpaper;
    private int backgroundColor;
    private int dockBottomDp;
    /** Day the daily wallpaper was picked for, or -1 when the wallpaper is not the daily one. */
    private long wallpaperDay = -1;
    private boolean glassShown;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;
    /** Module cards (res/values/config.xml, module_cards): discovered on resume, shown only after the owner's Show. */
    private boolean moduleCards;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): stiff spring, short fades, no replay. */
    private boolean reducedMotion;
    /** hidden_apps as the adapter sees them (config.xml or a theme's config.json): a hidden app gets no card either. */
    private Set<String> hiddenApps = Collections.emptySet();
    private List<Cards.Card> cards = Collections.emptyList();
    /** The grid has been laid out and focused since the last resume; the card prompt waits for this. */
    private boolean tilesReady;
    private Dialog cardPrompt;
    /** Set when a decision could not be saved: no more prompts until the next resume, instead of asking in a loop. */
    private boolean promptsPaused;
    /** The Inputs or Module cards dropdown while one is open, so onPause can close it. */
    private PopupWindow openPopup;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /** Shows the Inputs pill once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }
    };
    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            reloadApps(false);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            // Daily wallpaper: past midnight, rebuild Home with the next one.
            if (wallpaperDay >= 0 && Wallpapers.today() != wallpaperDay) {
                applyWallpaper(LauncherPrefs.get(MainActivity.this), true);   // past midnight: crossfade to the next
            }
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        // config.xml, overlaid by config.json from a pushed theme (see Config and tools/theme.sh).
        Config cfg = Config.load(this);
        // One scrolling dock row and no grid: every position counts as the dock, so the row logic below
        // (Up stays, Down stays, the ends don't wrap) applies to the whole list.
        COLUMNS = 1000;
        // The owner's choices from the launcher settings (hold OK on the Settings icon).
        LauncherPrefs prefs = LauncherPrefs.get(this);
        prefsRev = prefs.rev();
        AppAdapter.DOCK_DP = prefs.iconDp();
        AppAdapter.DOCK_GAP_DP = prefs.gapDp();
        AppAdapter.DOCK_VISIBLE = prefs.visible();
        dockBottomDp = prefs.bottomDp();
        applyClockPrefs(prefs);
        addBottomShade(prefs);
        LauncherPrefs.store(this).registerOnSharedPreferenceChangeListener(prefsListener);
        reducedMotion = cfg.reducedMotion;
        liveTvPkg = cfg.liveTvPackage;
        liveTvAction = cfg.liveTvAction;
        inputAction = cfg.inputAction;
        sourcePanelService = cfg.sourcePanelService;
        sourcePanelAction = cfg.sourcePanelAction;

        root = findViewById(R.id.root);
        root.setBackgroundColor(cfg.backgroundColor);
        backgroundColor = cfg.backgroundColor;
        themeWallpaper = cfg.wallpaper;
        applyWallpaper(prefs, false);
        glass = findViewById(R.id.glass);
        ambient = findViewById(R.id.ambient);
        // The focused app's colour glow (Home settings: App colour glow).
        if (!prefs.getBool(LauncherPrefs.GLOW, LauncherPrefs.DEF_GLOW)) ambient.setVisibility(View.GONE);
        pillLabels = prefs.getBool(LauncherPrefs.PILL_LABELS, false);
        heroName = findViewById(R.id.heroName);
        greeting = findViewById(R.id.greeting);
        btnWifi = findViewById(R.id.btnWifi);
        shamsiDate = findViewById(R.id.shamsiDate);
        shamsi = cfg.shamsiDate;
        if (shamsi) {
            shamsiDate.setVisibility(View.VISIBLE);
            TextView date = findViewById(R.id.date);
            date.setTextSize(11f);
            date.setPadding(0, (int) (4 * density), 0, 0);
        }
        vpnApp = cfg.vpnApp;
        moduleCards = cfg.moduleCards;
        hiddenApps = new HashSet<>(cfg.hiddenApps);

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false) {
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                         @NonNull Rect rect, boolean immediate, boolean focusedChildVisible) {
                // Scroll the whole item (icon plus its gap) into view, not just the focused icon, so the
                // first and last icons keep the same inset from the band as when Home opens.
                rect.set(0, 0, child.getWidth(), child.getHeight());
                // Held D-pad: jump instead of animating, so the focused icon is always inside the dock.
                return super.requestChildRectangleOnScreen(parent, child, rect, immediate || holdingDpad,
                        focusedChildVisible);
            }
        });
        grid.setClipChildren(true);   // icons scrolling past the dock's ends are cut at the band
        grid.setClipToPadding(true);  // and scrolling keeps the end icons inside the side padding
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.addItemDecoration(new DockDecoration(COLUMNS, density, LauncherPrefs.get(this)));
        int enterAnim = reducedMotion ? R.anim.tile_enter_reduced : R.anim.tile_enter;
        float enterDelay = reducedMotion ? 0f : 0.06f;
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, enterAnim), enterDelay));
        adapter = new AppAdapter(this, this, COLUMNS, cfg);
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row towards the bottom of the screen, leaving room for
        // rows_below_dock grid rows under it; the rest of the grid stays below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            // Place the dock so the bottom of its band sits dockBottomDp above the screen edge: the icon
            // starts below the item's top padding (8dp) and the decoration's top offset (6dp), and the band
            // reaches PAD_DP below the icon. The item's unused name area just hangs off the bottom.
            int iconTopInItem = (int) ((6 + 8) * density);
            int want = Math.max(0, h - (int) ((dockBottomDp + DockDecoration.PAD_DP) * density)
                    - AppAdapter.dockTilePx(density) - iconTopInItem);
            if (grid.getPaddingTop() != want) {
                // Posted: a padding change from inside this layout pass is not laid out again, and the
                // sideways dock never scrolls vertically to make up for it.
                grid.post(() -> {
                    grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
                    realignGlassAfterLayout();
                });
            }
        });
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                sizeDock();
            }
        });
        sizeDock();

        // While the dock scrolls or the D-pad is held, put the glow on the focused icon every frame:
        // shifting it by scroll deltas drifts off the dock over a long held scroll.
        grid.getViewTreeObserver().addOnPreDrawListener(() -> {
            if (glassShown && (grid.getScrollState() != RecyclerView.SCROLL_STATE_IDLE
                    || SystemClock.uptimeMillis() - lastGlassMoveAt < FAST_MOVE_MS)) {
                pinGlassToFocus();
            }
            return true;
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetX -= dx;
                glassTargetY -= dy;
                glassScrollShiftX -= dx;
                glassScrollShift -= dy;
                glass.setTranslationX(glass.getTranslationX() - dx);
                glass.setTranslationY(glass.getTranslationY() - dy);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentX") {
            @Override
            public float getValue(View v) {
                return v.getTranslationX() - glassScrollShiftX;
            }

            @Override
            public void setValue(View v, float value) {
                v.setTranslationX(value + glassScrollShiftX);
            }
        });
        springY = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentY") {
            @Override
            public float getValue(View v) {
                return v.getTranslationY() - glassScrollShift;
            }

            @Override
            public void setValue(View v, float value) {
                v.setTranslationY(value + glassScrollShift);
            }
        });
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            if (reducedMotion) {
                // Stiff and almost critically damped: settles in a few frames instead of oscillating,
                // and stops once the highlight is within 1.5 px of its tile.
                s.setSpring(new SpringForce().setStiffness(1400f).setDampingRatio(0.95f));
                s.setMinimumVisibleChange(1.5f);
            } else {
                // Snappier than before so the glow keeps up with the dock; a little bounce stays.
                s.setSpring(new SpringForce().setStiffness(520f).setDampingRatio(0.72f));
            }
        }

        btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> openNetworkPill());
        btnWifi.setOnLongClickListener(v -> {
            open(systemIntent(Settings.ACTION_WIFI_SETTINGS));
            return true;
        });
        btnSettings.setOnClickListener(v -> showControlCenter());
        btnSettings.setOnLongClickListener(v -> {
            if (!moduleCards) return false;   // off: a long press does nothing, as before the feature
            showModuleCards();
            return true;
        });

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Back to the dock tile Up came from, not wherever the focus finder lands.
                focusTile(lastFocused % COLUMNS);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                // The left end is Inputs, or Wi-Fi on a box without inputs.
                return v.getId() == R.id.btnInputs
                        || (v.getId() == R.id.btnWifi && btnInputs.getVisibility() != View.VISIBLE);
            }
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        // Pills show only their icon; with labels on (Home settings) the focused one grows to show its label.
        topButtons = findViewById(R.id.topButtons);
        View.OnFocusChangeListener pillFocus = (v, hasFocus) -> {
            TransitionManager.beginDelayedTransition(topButtons, new ChangeBounds().setDuration(160));
            applyPillLabel((TextView) v);
        };
        for (TextView pill : new TextView[]{(TextView) btnInputs, btnWifi, (TextView) btnSettings}) {
            if (pill.getTag() == null) pill.setTag(pill.getText());
            applyPillLabel(pill);
            if (pillLabels) pill.setOnFocusChangeListener(pillFocus);
        }

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
            // The pills only stay up while one of them has focus.
            if (newF != null && newF.getParent() != topButtons) showPills(false);
        });

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, handler);
        if (cm != null) {
            cm.registerDefaultNetworkCallback(networkCallback);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), vpnCallback);
        }
    }

    /**
     * A theme's full-screen picture behind everything. Decoded once, downsampled to the screen so a 4K
     * JPEG costs a 1080p bitmap at most (about 8 MB), which is the price of a wallpaper on a 1 GB TV.
     */
    /** A full-screen view of this picture, or null when it cannot be decoded. */
    private View loadWallpaper(File file) {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
            int sample = 1;
            while (opts.outWidth / (sample * 2) >= dm.widthPixels && opts.outHeight / (sample * 2) >= dm.heightPixels) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;   // no alpha needed, half the memory
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
            if (bmp == null) return null;
            return wallpaperView(bmp);
        } catch (Exception | OutOfMemoryError e) {
            // A bad or huge picture must never take the home screen down; the plain colour stays.
            return null;
        }
    }

    private View wallpaperView(android.graphics.Bitmap bmp) {
        android.widget.ImageView wall = new android.widget.ImageView(this);
        wall.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        wall.setImageBitmap(bmp);
        return wall;
    }

    /**
     * Puts the chosen wallpaper (built-in, a photo, the daily one, the theme's picture or none) behind
     * everything, dimmed as set. With fade, the new picture crossfades over the old one, which goes once
     * the fade has finished, so a change never flashes black.
     */
    private void applyWallpaper(LauncherPrefs prefs, boolean fade) {
        int wallpaper = prefs.getInt(LauncherPrefs.WALLPAPER, themeWallpaper != null ? Wallpapers.CUSTOM : Wallpapers.NONE);
        View wall = null;
        wallpaperDay = -1;
        if (wallpaper == Wallpapers.CUSTOM && themeWallpaper != null) {
            wall = loadWallpaper(themeWallpaper);
        } else if (wallpaper == Wallpapers.DAILY) {
            wallpaperDay = Wallpapers.today();
            File today = Wallpapers.dailyFile(this);
            if (today != null) wall = loadWallpaper(today);
            else wallpaper = Wallpapers.dailyPreset();   // no photos: today's built-in one, drawn below
        }
        if (wall == null && wallpaper == Wallpapers.FILE) {
            File dir = getExternalFilesDir(Wallpapers.FOLDER);
            String name = prefs.getString(LauncherPrefs.WALL_FILE);
            if (dir != null && !name.isEmpty()) wall = loadWallpaper(new File(dir, name));
        } else if (wall == null && wallpaper != Wallpapers.NONE) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            android.graphics.Bitmap bmp = Wallpapers.render(wallpaper, dm.widthPixels, dm.heightPixels);
            if (bmp != null) wall = wallpaperView(bmp);
        }
        FrameLayout layer = new FrameLayout(this);
        FrameLayout.LayoutParams fill = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        if (wall != null) {
            layer.addView(wall, fill);
            int dim = prefs.getInt(LauncherPrefs.WALL_DIM, LauncherPrefs.DEF_WALL_DIM);
            if (dim > 0) {
                View veil = new View(this);
                veil.setBackgroundColor(Math.round(255 * Math.min(100, dim) / 100f) << 24);
                layer.addView(veil, new FrameLayout.LayoutParams(fill));
            }
        } else {
            layer.setBackgroundColor(backgroundColor);   // none: fade back to the plain colour
        }
        // The new layer goes just above the old wallpaper and under everything else.
        View old = wallpaperLayer;
        root.addView(layer, old != null ? root.indexOfChild(old) + 1 : 0, new FrameLayout.LayoutParams(fill));
        wallpaperLayer = layer;
        if (old == null || !fade) {
            if (old != null) root.removeView(old);
            return;
        }
        layer.setAlpha(0f);
        layer.animate().alpha(1f).setDuration(reducedMotion ? 250 : 700)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(() -> root.removeView(old)).start();
    }

    /**
     * Home pressed while Home is already in front: singleTask delivers it here, then onResume runs.
     * Back to the top with the first dock tile focused, like the stock launcher. Back from an app
     * never comes this way, so returning to the app you left from is unchanged (#118).
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        grid.scrollToPosition(0);
        lastFocused = 0;
        lastFocusedKey = adapter.getItemCount() > 0 ? adapter.keyAt(0) : null;
        // Home while already on Home: no onResume follows, so move the focus back to the first icon here.
        if (hasWindowFocus() && adapter.getItemCount() > 0) grid.post(() -> focusTile(0));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (LauncherPrefs.get(this).rev() != prefsRev) {
            // Back from the launcher settings with changes: rebuild with the new sizes and order.
            recreate();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packageFilter);
        }
        tilesReady = false;
        promptsPaused = false;
        reloadApps(true);
        updateGreeting();
        updateWifiLabel();
        updateInputsButton();
        handler.postDelayed(minuteTick, MINUTE_MS);
    }

    /** Rebuilds the tiles (and the module cards), then puts focus back where it was. */
    private void reloadApps(boolean animate) {
        // Capture the app before rebinding can move focus and overwrite its key.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final boolean restoreFocus = animate || grid.hasFocus();
        if (restoreFocus) {
            glassShown = false;
            glass.setAlpha(0f);
            heroShown = false;
            heroName.setAlpha(0f);
            ambient.setAlpha(0f);
        }
        reloadTiles();
        if (animate && !reducedMotion) grid.scheduleLayoutAnimation();

        if (restoreFocus) {
            // Follow the same app, or the nearest remaining slot when it was removed.
            restoreFocus();
        } else {
            // A package change must not steal focus from a top button or the Inputs popup.
            int pos = adapter.positionOf(returnKey);
            lastFocused = Math.max(0, Math.min(pos >= 0 ? pos : returnSlot, adapter.getItemCount() - 1));
            lastFocusedKey = adapter.keyAt(lastFocused);
            if (pos < 0) hideHero();
            // A newly installed app may have brought a card with it.
            tilesReady = true;
            showCardPrompt();
        }
    }

    /**
     * Rebuilds the tiles; the module cards the owner chose to show stand in for their app's tile.
     * Every step into the card code runs under CardFeature: if it fails, the tiles are the ordinary
     * ones and cards stay off for this process.
     */
    private void reloadTiles() {
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final List<Cards.Card> none = Collections.emptyList();
        cards = moduleCards ? CardFeature.run(() -> Cards.discover(this, hiddenApps, Cards.load(Cards.storeFile(this))), none) : none;
        adapter.reload(cards.isEmpty() ? none : CardFeature.run(() -> Cards.shown(cards), none));
        lastFocusedKey = returnKey;
        lastFocused = returnSlot;
    }

    /**
     * Return to the app we left from, or the same slot if that app is gone. Always refocus: after
     * reload() the view that holds focus may no longer show the app it had.
     */
    private void restoreFocus() {
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            focusTile(pos >= 0 ? pos : returnSlot);
            tilesReady = true;
            showCardPrompt();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(packageReceiver);
        handler.removeCallbacks(minuteTick);
        // Nothing of ours stays on screen over another app; an undecided prompt simply returns next time.
        if (cardPrompt != null) cardPrompt.dismiss();
        if (openPopup != null) openPopup.dismiss();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) showCardPrompt();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(rebuildForPrefs);
        LauncherPrefs.store(this).unregisterOnSharedPreferenceChangeListener(prefsListener);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
            cm.unregisterNetworkCallback(vpnCallback);
        }
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    /** The dock shows at most DOCK_VISIBLE icons, centred; the rest scroll in from the right. */
    private void sizeDock() {
        int shown = Math.min(Math.max(adapter.getItemCount(), 1), AppAdapter.DOCK_VISIBLE);
        int width = Math.round(shown * (AppAdapter.DOCK_DP + AppAdapter.DOCK_GAP_DP) * density
                + 2 * (DockDecoration.PAD_DP - AppAdapter.DOCK_GAP_DP / 2f) * density);
        // Side padding puts the first and last icon PAD_DP inside the band at either end of the scroll.
        int side = Math.round((DockDecoration.PAD_DP - AppAdapter.DOCK_GAP_DP / 2f) * density);
        // No bottom padding: the dock's height comes from the top padding alone (see the layout listener).
        if (grid.getPaddingLeft() != side || grid.getPaddingBottom() != 0) {
            grid.setPadding(side, grid.getPaddingTop(), side, 0);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) grid.getLayoutParams();
        if (lp.width == width) return;
        lp.width = width;
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        grid.setLayoutParams(lp);
        realignGlassAfterLayout();
    }

    /** Left edge for a glow of this width centred on centerX, kept within the dock's visible span. */
    private float clampToDock(float centerX, float width) {
        Rect d = new Rect(0, 0, grid.getWidth(), grid.getHeight());
        root.offsetDescendantRectToMyCoords(grid, d);
        float x = centerX - width / 2f;
        if (d.width() <= 0 || width >= d.width()) return x;
        return Math.max(d.left, Math.min(x, d.right - width));
    }

    /** Puts the glow exactly over the focused icon, from where it is laid out now, with no spring. */
    private void pinGlassToFocus() {
        View card = getCurrentFocus();
        if (card == null || card.getId() != R.id.card || card.getWidth() == 0) return;
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float x = clampToDock(r.exactCenterX(), glass.getWidth());
        float y = r.exactCenterY() - glass.getHeight() / 2f;
        if (glass.getTranslationX() == x && glass.getTranslationY() == y) return;
        springX.cancel();
        springY.cancel();
        glass.setTranslationX(x);
        glass.setTranslationY(y);
    }

    /** The dock moved (padding or width changed): put the highlight back on the focused icon once laid out. */
    private void realignGlassAfterLayout() {
        grid.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                grid.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                View f = getCurrentFocus();
                if (f == null || f.getId() != R.id.card) return;
                RecyclerView.ViewHolder vh = grid.findContainingViewHolder(f);
                if (vh != null && vh.getBindingAdapterPosition() != RecyclerView.NO_POSITION) {
                    onTileFocused(vh.getBindingAdapterPosition(), f);
                }
            }
        });
    }

    @Override
    public void onOpenLauncherSettings() {
        open(new Intent(this, LauncherSettingsActivity.class));
    }

    /**
     * A black-to-transparent gradient over the bottom of the screen, under the dock and over any wallpaper
     * or glow, so the dock stays legible on a bright background (Home settings: Bottom shade).
     */
    private void addBottomShade(LauncherPrefs prefs) {
        int strength = prefs.getInt(LauncherPrefs.SHADE, LauncherPrefs.DEF_SHADE);
        if (strength <= 0) return;
        int a = Math.round(255 * Math.min(100, strength) / 100f);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, (Math.round(a * 0.18f) << 24), (Math.round(a * 0.6f) << 24), (a << 24)});
        View shade = new View(this);
        shade.setBackground(g);
        View column = (View) findViewById(R.id.leftBlock).getParent().getParent();
        int height = Math.round(prefs.getInt(LauncherPrefs.SHADE_HEIGHT, LauncherPrefs.DEF_SHADE_HEIGHT) * density);
        FrameLayout screen = findViewById(R.id.root);   // runs before the root field is set
        screen.addView(shade, screen.indexOfChild(column),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height, Gravity.BOTTOM));
    }

    /** Clock size and which of clock and date show, from the launcher settings. */
    private void applyClockPrefs(LauncherPrefs prefs) {
        TextView clock = findViewById(R.id.clock);
        TextView date = findViewById(R.id.date);
        int size = prefs.clockSp();
        clock.setTextSize(size);
        clock.setFontVariationSettings("'wght' " + prefs.clockWeight());
        int shadow = prefs.getInt(LauncherPrefs.CLOCK_SHADOW, LauncherPrefs.DEF_CLOCK_SHADOW);
        ((GlassClock) clock).setShadowStrength(shadow);
        ((GlassClock) date).setShadowStrength(shadow);
        date.setTextSize(prefs.dateSp());
        // Header position, measured from the screen's top edge. The screen column's own top padding would
        // clip a header placed above it, so it goes, and the pills keep their old spot with a margin instead.
        View block = findViewById(R.id.leftBlock);
        View column = (View) block.getParent().getParent();
        column.setPadding(column.getPaddingLeft(), 0, column.getPaddingRight(), column.getPaddingBottom());
        ViewGroup.MarginLayoutParams blp = (ViewGroup.MarginLayoutParams) block.getLayoutParams();
        blp.topMargin = Math.round(prefs.clockTopDp() * density);
        block.setLayoutParams(blp);
        View pills = findViewById(R.id.topButtons);
        ViewGroup.MarginLayoutParams tlp = (ViewGroup.MarginLayoutParams) pills.getLayoutParams();
        tlp.topMargin = Math.round((26 + 8) * density);
        pills.setLayoutParams(tlp);
        clock.setVisibility(prefs.showClock() ? View.VISIBLE : View.GONE);
        date.setVisibility(prefs.showDate() ? View.VISIBLE : View.GONE);
        // Pull the digits up under the date by the font's ascent gap (-17dp at the default 88sp).
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) clock.getLayoutParams();
        lp.topMargin = prefs.showDate() ? Math.round(-17f / LauncherPrefs.DEF_CLOCK_SP * size * density) : 0;
        clock.setLayoutParams(lp);
    }

    @Override
    public void onOpenSettings() {
        open(systemIntent(Settings.ACTION_SETTINGS));
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = clampToDock(r.exactCenterX(), w);
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(AppAdapter.cornerRadius(card.getWidth(), card.getHeight(), density) * AppAdapter.FOCUS_SCALE + pad);
        }
        glassTargetX = x;
        glassTargetY = y;
        long now = SystemClock.uptimeMillis();
        boolean fast = now - lastGlassMoveAt < FAST_MOVE_MS;
        lastGlassMoveAt = now;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(reducedMotion ? 100 : 180).start();
            glassShown = true;
        } else if (fast) {
            // Moving quickly (a held or rapidly pressed D-pad): no spring, or the glow trails behind and
            // floats over the wrong icon while the dock scrolls. Snap it onto the icon.
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
        } else {
            springX.animateToFinalPosition(x - glassScrollShiftX);
            springY.animateToFinalPosition(y - glassScrollShift);
        }
        View item = (View) card.getParent();
        if (position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            // A 1px mismatch used to start a scroll on every dock move. Only scroll when the row is
            // actually off; reduced motion does it in one jump so it does not fight the highlight.
            boolean scroll = Math.abs(dy) > (int) (8 * density);
            if (scroll) {
                if (reducedMotion) grid.scrollBy(0, dy);
                else grid.smoothScrollBy(0, dy);
            }
            showHero(position, scroll ? r.top - dy : r.top);
        } else {
            hideHero();
            // Scroll just enough so the whole glass (not only the tile) is visible.
            Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, want, reducedMotion);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());

        if (reducedMotion) {
            // Snap the shelf instead of sliding and fading it: each of those frames repaints the
            // full-screen panel, and this switch exists for the GPUs that cannot afford it.
            heroName.animate().cancel();
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.setAlpha(1f);
            setAmbient(color);
            if (!heroShown) {
                ambient.setAlpha(0f);
                ambient.animate().alpha(1f).setDuration(160).start();
                heroShown = true;
            }
            return;
        }

        if (!heroShown) {
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(260).start();
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(600).start();
            heroShown = true;
        } else if (changed) {
            heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                heroName.setText(label);
                heroName.setY(y);
                heroName.setTranslationX(12 * density);
                heroName.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            if (color != ambientColor) {
                ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(500).start();
                }).start();
            }
        } else {
            heroName.setY(y);
        }
    }

    /**
     * The glow used to be a full-screen radial GradientDrawable. On Android 10 that drawable is
     * drawn on the CPU, so fading it for half a second redrew 1920×1080 every frame and the
     * highlight looked like it was running at a few frames per second. A small bitmap stretched
     * over the screen is one texture; changing apps only uploads it once.
     */
    private void setAmbient(int color) {
        if (color == ambientColor && ambient.getBackground() != null) return;
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        int w = 480;
        int h = 270;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new RadialGradient(
                w * 0.32f, h * 0.42f, w * 0.62f,
                new int[]{0x5C000000 | c, 0x26000000 | c, 0x00000000 | c},
                new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        BitmapDrawable drawable = new BitmapDrawable(getResources(), bmp);
        drawable.setGravity(Gravity.FILL);
        ambient.setBackground(drawable);
    }

    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        heroName.animate().alpha(0f).setDuration(reducedMotion ? 100 : 160).start();
        ambient.animate().alpha(0f).setDuration(reducedMotion ? 140 : 300).start();
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target;
        // Horizontal: swallow the key at a row's ends so focus never wraps to the next row or
        // escapes up into the top bar. Mid-row moves fall through to the default focus search.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            return position % COLUMNS == 0;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return position % COLUMNS == COLUMNS - 1 || position == count - 1;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) return true;           // dock row: stay; holding or double-pressing Up opens the pills
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row: stay put
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    /** Two Up presses closer together than this count as a double press. */
    private static final long DOUBLE_UP_MS = 400;
    /** When Up was last pressed (not held), for the double press; 0 once another key comes between. */
    private long lastUpPress;

    /**
     * Holding Up on the dock (a repeat of the key), or pressing it twice quickly, reveals the top-bar pills
     * and focuses the nearest one.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean doubleUp = false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                doubleUp = lastUpPress != 0 && event.getEventTime() - lastUpPress < DOUBLE_UP_MS;
                lastUpPress = doubleUp ? 0 : event.getEventTime();
            } else {
                lastUpPress = 0;
            }
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            // A held Left/Right repeats every ~50 ms, faster than a smooth scroll: focus would run ahead onto
            // icons still outside the dock (laid out for the scroll, but clipped), with the glow following.
            holdingDpad = event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP && event.getAction() == KeyEvent.ACTION_DOWN
                && (event.getRepeatCount() > 0 || doubleUp) && topButtons.getVisibility() != View.VISIBLE) {
            View f = getCurrentFocus();
            if (f != null && f.getId() == R.id.card) {
                RecyclerView.ViewHolder vh = grid.findContainingViewHolder(f);
                int pos = vh != null ? vh.getBindingAdapterPosition() : RecyclerView.NO_POSITION;
                if (pos != RecyclerView.NO_POSITION && pos < COLUMNS) {
                    showPills(true);
                    focusNearestPill(pos);
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void showPills(boolean show) {
        if (show == (topButtons.getVisibility() == View.VISIBLE)) return;
        topButtons.animate().cancel();
        if (show) {
            topButtons.setAlpha(0f);
            topButtons.setVisibility(View.VISIBLE);
            topButtons.animate().alpha(1f).setDuration(160).start();
        } else {
            topButtons.animate().alpha(0f).setDuration(120)
                    .withEndAction(() -> topButtons.setVisibility(View.INVISIBLE)).start();
        }
    }

    /** Collapsed pills sit far right, out of the focus finder's reach from the left tiles, so pick one by x. */
    private void focusNearestPill(int position) {
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(position);
        int[] at = new int[2];
        float x = 0;
        if (vh != null) {
            vh.itemView.getLocationOnScreen(at);
            x = at[0] + vh.itemView.getWidth() / 2f;
        }
        View best = null;
        float bestDist = Float.MAX_VALUE;
        for (int id : new int[]{R.id.btnInputs, R.id.btnWifi, R.id.btnSettings}) {
            View pill = findViewById(id);
            if (pill.getVisibility() != View.VISIBLE) continue;
            pill.getLocationOnScreen(at);
            float d = Math.abs(at[0] + pill.getWidth() / 2f - x);
            if (d < bestDist) {
                bestDist = d;
                best = pill;
            }
        }
        if (best != null) best.requestFocus();
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) {
            showPills(true);
            btnWifi.requestFocus();
            return;
        }
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(res);
        if (shamsi) shamsiDate.setText(Shamsi.today());
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean vpn = false, online = false;
        int icon = R.drawable.ic_wifi;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, ethernet = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) {
                text = getString(R.string.offline);
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        // With a VPN app configured, a VPN that is down matters too: show a red "VPN off" capsule then.
        netStatus = text;
        netIcon = icon;
        netOnline = online;
        boolean vpnDown = !vpn && online && !vpnApp.isEmpty();
        if (vpn || vpnDown) {
            // Network name, then the green lock-and-VPN (or red "VPN off") capsule drawn in place of the word.
            String badge = getString(vpn ? R.string.vpn : R.string.vpn_off);
            VpnBadge span = new VpnBadge(vpn ? getDrawable(R.drawable.ic_lock) : null, density);
            // The pill has a maximum width and ellipsizes at the end, which would cut the badge off after
            // a long network name, so shorten the name to whatever room the badge leaves. The total paddings
            // include the Wi-Fi icon.
            // Measured against the expanded pill (paddings 16 + 20, icon 22 and its 9dp gap), whatever it shows now.
            TextPaint paint = btnWifi.getPaint();
            float room = btnWifi.getMaxWidth() - 67 * density
                    - span.getSize(paint, badge, 0, badge.length(), null) - paint.measureText(" ") - 2 * density;
            text = TextUtils.ellipsize(text, paint, Math.max(0, room), TextUtils.TruncateAt.END).toString();
            SpannableString label = new SpannableString(text + " " + badge);
            label.setSpan(span, text.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            btnWifi.setTag(label);
        } else {
            btnWifi.setTag(text);
        }
        btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        applyPillLabel(btnWifi);
    }

    /**
     * Shows a top-bar pill's label (kept in its tag) only while it has focus and labels are on in Home
     * settings; otherwise it is a 42dp icon circle and the label is only read out by accessibility.
     */
    private void applyPillLabel(TextView pill) {
        CharSequence label = (CharSequence) pill.getTag();
        boolean open = pillLabels && pill.isFocused();
        pill.setText(open ? label : "");
        pill.setContentDescription(label);
        pill.setCompoundDrawablePadding(open ? Math.round(9 * density) : 0);
        pill.setPaddingRelative(Math.round((open ? 16 : 10) * density), 0, Math.round((open ? 20 : 10) * density), 0);
    }

    /** The network pill opens the VPN app when one is configured and installed, otherwise Wi-Fi settings. */
    private void openNetworkPill() {
        Intent i = vpnApp.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        if (i == null && !vpnApp.isEmpty()) i = getPackageManager().getLaunchIntentForPackage(vpnApp);
        open(i != null ? i : systemIntent(Settings.ACTION_WIFI_SETTINGS));
    }


    /**
     * China-market MIUI TV firmware answers no standard Settings intents at all; open Xiaomi's own
     * settings app (com.xiaomi.mitv.settings) there, so the Settings and Wi-Fi pills keep working.
     * On firmware with a standard settings handler the behaviour is unchanged.
     */
    private Intent systemIntent(String standardAction) {
        Intent i = new Intent(standardAction);
        if (i.resolveActivity(getPackageManager()) != null) return i;
        i = new Intent().setComponent(new ComponentName(
                "com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity"));
        if (i.resolveActivity(getPackageManager()) != null) return i;
        return new Intent(standardAction);   // nothing found: let open() show the can't-open toast
    }

    /** Hides the Inputs pill when it would have nothing to list: streaming boxes have no HDMI-in, AV or tuner. */
    private void updateInputsButton() {
        boolean any = liveTvIntent() != null || !sourcePanelService.isEmpty();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput()) {
                    any = true;
                    break;
                }
            }
        }
        btnInputs.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    /** The TV's own source-selection panel, for TVs that have no TvInputManager (TCL Android TV 9). */
    private void openSourcePanel() {
        Intent i = new Intent(sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(sourcePanelService);
        if (c != null) i.setComponent(c);
        try {
            startService(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            // SecurityException: the target needs a permission we lack (Google TV's accessory pairing wants
            // BLUETOOTH_CONNECT). Uncaught it would crash Home, and Android then drops the home preference.
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Switches to a passthrough input (HDMI, AV). The standard way is ACTION_VIEW on its channel URI,
     *  which the Live TV app handles; where nothing takes that, the configured `input_action` is sent
     *  with the same URI instead (XGIMI's HDMI player only answers com.xgimi.action.hdmiPlayer). */
    private void openInput(String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
            return;
        } catch (ActivityNotFoundException e) {
            if (inputAction.isEmpty()) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        open(new Intent(inputAction).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Liquid glass, like the Control Center tiles: smoky see-through fill under a bright rim.
        GradientDrawable glassBg = new GradientDrawable();
        LauncherPrefs glassPrefs = LauncherPrefs.get(this);
        glassBg.setColor(glassPrefs.uiGlassFill());
        glassBg.setCornerRadius(26 * density);
        panel.setBackground(glassBg);
        panel.setForeground(glassPrefs.uiRim(density, false, 26 * density));
        // Compact, hanging right below the Inputs pill; wide enough for "HDMI 1" beside its badge.
        int width = Math.max(anchor.getWidth(), (int) (200 * density));
        final PopupWindow popup = glassPopup(panel, width);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        boolean tunerIsHw = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    if (tunerId == null || (hw && !tunerIsHw) || (hw == tunerIsHw && info.getId().compareTo(tunerId) < 0)) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                    }
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> openInput(id));
            }
        }
        // Nothing to list where there is no TvInputManager: the TV's own source panel switches inputs.
        if (rows.getChildCount() == 0 && !sourcePanelService.isEmpty()) {
            addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, this::openSourcePanel);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            liveTv = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, panel.getMeasuredWidth()));

        // Right-aligned with the pill row, like the Control Center: wider than the Inputs pill, it would run off screen.
        dropIn(popup, panel, topButtons, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private PopupWindow glassPopup(View panel, int width) {
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
        });
        openPopup = popup;
        return popup;
    }

    /** Drop in: fade and unfold from the top edge, hanging under the anchor pill. */
    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), gravity);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        // The badge is blue for a connected input, grey otherwise, like a joined network in the macOS menu.
        // This TV reports "standby" for every port whether or not something is plugged in, so only a
        // definite "connected" turns it blue.
        boolean connected = state == TvInputManager.INPUT_STATE_CONNECTED;
        GradientDrawable badge = new GradientDrawable();
        badge.setShape(GradientDrawable.OVAL);
        badge.setColor(connected ? ControlCenter.ACCENT : 0x38FFFFFF);
        row.findViewById(R.id.iconBg).setBackground(badge);
        if (connected) row.setContentDescription(title + ", " + getString(R.string.connected));
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }

    // ---- module cards ----------------------------------------------------------------------------

    /**
     * Asks about the next card that has no decision for its current signing key. One prompt at a
     * time, only over a laid out grid with window focus (not under a popup or another app), and
     * the next one follows as soon as this one is answered. Nothing is shown until the owner says Show.
     */
    private void showCardPrompt() {
        if (!moduleCards || !tilesReady || promptsPaused || cardPrompt != null || !hasWindowFocus() || isFinishing()) return;
        if (cards.isEmpty() || !CardFeature.working()) return;
        CardFeature.run(() -> {
            showCardPromptNow();
            return null;
        }, null);
    }

    private void showCardPromptNow() {
        final Cards.Card card = Cards.firstPending(cards);
        if (card == null) return;

        View panel = getLayoutInflater().inflate(R.layout.dialog_card_prompt, root, false);
        ((TextView) panel.findViewById(R.id.title)).setText(getString(R.string.card_prompt_title, card.appLabel));
        ((TextView) panel.findViewById(R.id.body)).setText(card.state == Cards.State.RESIGNED
                ? getString(R.string.card_prompt_resigned, card.appLabel)
                : card.state == Cards.State.RELABELLED
                ? getString(R.string.card_prompt_relabelled, card.appLabel)
                : getString(R.string.card_prompt_body, card.appLabel));
        TextView names = panel.findViewById(R.id.names);
        if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) names.setVisibility(View.GONE);
        else names.setText(getString(R.string.card_prompt_names, card.label, card.appLabel));
        ((TextView) panel.findViewById(R.id.identity)).setText(
                getString(R.string.card_prompt_identity, card.pkg, Cards.shortDigest(card.digest)));
        TextView keep = panel.findViewById(R.id.btnKeep);
        TextView show = panel.findViewById(R.id.btnShow);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (440 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        // One decision per prompt: the buttons and Back (cancel) all end here.
        final boolean[] decided = {false};
        View.OnClickListener choose = v -> {
            if (decided[0]) return;
            decided[0] = true;
            cardPrompt = null;
            dialog.dismiss();
            decideCard(card, v.getId() == R.id.btnShow);   // guards its own card calls; the tile reload stays outside
            restoreFocus();   // the tiles changed under the prompt; this also chains the next prompt
        };
        keep.setOnClickListener(choose);
        show.setOnClickListener(choose);
        dialog.setOnCancelListener(d -> choose.onClick(keep));
        dialog.setOnDismissListener(d -> {
            if (cardPrompt == dialog) cardPrompt = null;
        });
        // Left and Right move between the two buttons; Up and Down stay put; the ends don't wrap.
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == R.id.btnKeep;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnShow;
        };
        keep.setOnKeyListener(keys);
        show.setOnKeyListener(keys);
        cardPrompt = dialog;
        dialog.show();
        keep.requestFocus();
    }

    /** Writes the owner's decision and rebuilds the tiles; focus is left where it is. */
    private void decideCard(Cards.Card card, boolean show) {
        boolean saved = CardFeature.run(() -> Cards.decide(Cards.storeFile(this), card, show), false);
        if (!saved) {
            Toast.makeText(this, R.string.card_not_saved, Toast.LENGTH_SHORT).show();
            promptsPaused = true;
        }
        reloadTiles();
    }

    /** Dropdown under the Settings pill (hold OK): every discovered card, its package and key; OK toggles Shown and Hidden. */
    private void showModuleCards() {
        if (cards.isEmpty() || !CardFeature.working()) {
            Toast.makeText(this, R.string.no_module_cards, Toast.LENGTH_SHORT).show();
            return;
        }
        CardFeature.run(() -> {
            showModuleCardsNow();
            return null;
        }, null);
    }

    private void showModuleCardsNow() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (400 * density));
        List<Cards.Card> listed = new ArrayList<>(cards);
        Collections.sort(listed, Cards::byLabel);
        for (Cards.Card card : listed) {
            View row = getLayoutInflater().inflate(R.layout.item_card_row, rows, false);
            row.setTag(card.pkg);
            ((TextView) row.findViewById(R.id.title)).setText(card.label);
            TextView app = row.findViewById(R.id.app);
            if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) app.setVisibility(View.GONE);
            else app.setText(getString(R.string.card_app_name, card.appLabel));
            ((TextView) row.findViewById(R.id.pkg)).setText(card.pkg);
            ((TextView) row.findViewById(R.id.digest)).setText(Cards.longDigest(card.digest));
            final String pkg = card.pkg;
            row.setOnClickListener(v -> CardFeature.run(() -> {
                // The list stays open; the decision is for the card as it is now, not as it was listed.
                Cards.Card now = cardFor(pkg);
                if (now == null) return null;
                decideCard(now, now.state != Cards.State.SHOWN);
                // Every row, not only this one: a toggle can move another card across the limit of 8.
                updateCardStates(rows);
                return null;
            }, null));
            rows.addView(row);
        }
        updateCardStates(rows);
        // Scroll inside the panel instead of running off the bottom of a 720p screen.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int room = root.getHeight() - (at[1] + anchor.getHeight()) - (int) (34 * density);
        popup.setHeight(Math.min(panel.getMeasuredHeight(), Math.max(room, (int) (120 * density))));
        dropIn(popup, panel, anchor, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    // ---- control center --------------------------------------------------------------------------

    /**
     * The Settings pill's panel: Wi-Fi, Bluetooth, audio output, accessibility and the screensaver, plus
     * All settings for the TV's own settings app. Each tile opens the matching settings screen.
     */
    private void showControlCenter() {
        View anchor = findViewById(R.id.btnSettings);
        final PopupWindow[] popup = new PopupWindow[1];
        // Every action closes the panel first, so coming back to Home finds it gone.
        java.util.function.Function<Runnable, Runnable> closing = r -> () -> {
            if (popup[0] != null) popup[0].dismiss();
            r.run();
        };
        updateWifiLabel();

        String bt;
        boolean btOn = false;
        try {
            android.bluetooth.BluetoothManager bm = getSystemService(android.bluetooth.BluetoothManager.class);
            android.bluetooth.BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
            btOn = ad != null && ad.isEnabled();
            bt = getString(ad == null ? R.string.not_available : btOn ? R.string.ls_on : R.string.ls_off);
        } catch (SecurityException e) {
            bt = null;
        }

        String audio = null;
        try {
            android.media.MediaRouter mr = (android.media.MediaRouter) getSystemService(MEDIA_ROUTER_SERVICE);
            CharSequence name = mr.getSelectedRoute(android.media.MediaRouter.ROUTE_TYPE_LIVE_AUDIO).getName(this);
            if (name != null && name.length() > 0) audio = name.toString();
        } catch (Exception ignored) {
        }
        if (audio == null) audio = getString(R.string.cc_tv_speakers);

        ControlCenter.Tile[][] rows = {
                {new ControlCenter.Tile(netIcon, getString(R.string.wifi), netStatus, netOnline,
                        closing.apply(this::openNetworkPill),
                        closing.apply(() -> open(systemIntent(Settings.ACTION_WIFI_SETTINGS)))),
                 new ControlCenter.Tile(R.drawable.ic_bluetooth, getString(R.string.cc_bluetooth), bt, btOn,
                        closing.apply(this::openBluetooth), null)},
                {new ControlCenter.Tile(R.drawable.ic_speaker, getString(R.string.cc_audio_output), audio, false,
                        closing.apply(() -> open(systemIntent(Settings.ACTION_SOUND_SETTINGS))), null),
                 new ControlCenter.Tile(R.drawable.ic_accessibility, getString(R.string.cc_accessibility), null, false,
                        closing.apply(() -> open(systemIntent(Settings.ACTION_ACCESSIBILITY_SETTINGS))), null)},
                {new ControlCenter.Tile(R.drawable.ic_screensaver, getString(R.string.cc_screensaver),
                        getString(R.string.cc_screensaver_hint), false,
                        closing.apply(this::startScreensaver),
                        closing.apply(() -> open(firstResolvable(new Intent("com.google.android.tv.settings.ambient"),
                                new Intent(Settings.ACTION_DREAM_SETTINGS), systemIntent(Settings.ACTION_SETTINGS))))),
                 new ControlCenter.Tile(R.drawable.ic_settings, getString(R.string.cc_all_settings), null, false,
                        closing.apply(() -> open(systemIntent(Settings.ACTION_SETTINGS))), null)},
        };
        View panel = new ControlCenter(this).build(rows);
        popup[0] = glassPopup(panel, (int) (ControlCenter.WIDTH_DP * density));
        dropIn(popup[0], panel, anchor, Gravity.END);
        View first = panel.findViewWithTag("first");
        if (first != null) first.requestFocus();
    }

    private static final int REQ_BLUETOOTH = 41;

    /** Google TV's Remotes & Accessories page: TV Settings' slice screen showing the accessories slice. */
    private static final String ACCESSORIES_SLICE = "content://com.android.tv.settings.accessories.sliceprovider/general";

    /**
     * Bluetooth: the TV's Bluetooth settings where it has them, else Google TV's Remotes & Accessories page.
     * Only where neither opens, the Pair accessory screen, which needs BLUETOOTH_CONNECT (granted by the
     * install scripts), so ask for it once if it is missing.
     */
    private void openBluetooth() {
        Intent bt = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        if (bt.resolveActivity(getPackageManager()) != null) {
            open(bt);
            return;
        }
        Intent remotes = new Intent("android.settings.SLICE_SETTINGS")
                .setComponent(new ComponentName("com.android.tv.settings", "com.android.tv.settings.slice.SliceActivity"))
                .setData(Uri.parse(ACCESSORIES_SLICE))
                .putExtra("slice_uri", ACCESSORIES_SLICE);
        // TV Settings only lets an app open its slice screen with WRITE_SECURE_SETTINGS (granted by the
        // install scripts over adb); without it the start is refused, so skip to the pairing screen.
        if (remotes.resolveActivity(getPackageManager()) != null
                && checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
            open(remotes);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.BLUETOOTH_CONNECT}, REQ_BLUETOOTH);
            return;
        }
        open(firstResolvable(new Intent("com.google.android.intent.action.CONNECT_INPUT"),
                systemIntent(Settings.ACTION_SETTINGS)));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_BLUETOOTH) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) openBluetooth();
        else open(systemIntent(Settings.ACTION_SETTINGS));   // refused: the TV's settings still reach Bluetooth
    }

    /** Starts the screensaver now: Google TV's sleep action, else AOSP's Somnambulator. */
    private void startScreensaver() {
        Intent sleep = firstResolvable(new Intent("com.google.android.pano.action.SLEEP"),
                new Intent().setComponent(new ComponentName("com.android.systemui", "com.android.systemui.Somnambulator")));
        open(sleep);
    }

    /** The first intent something on the TV answers, else the last one (open() then shows can't-open). */
    private Intent firstResolvable(Intent... intents) {
        for (Intent i : intents) if (i.resolveActivity(getPackageManager()) != null) return i;
        return intents[intents.length - 1];
    }

    private Cards.Card cardFor(String pkg) {
        for (Cards.Card c : cards) if (c.pkg.equals(pkg)) return c;
        return null;
    }

    /** Writes the state of every row in the Module cards list from the current card list. */
    private void updateCardStates(LinearLayout rows) {
        List<Cards.Card> drawn = Cards.shown(cards);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            Cards.Card card = row.getTag() instanceof String ? cardFor((String) row.getTag()) : null;
            ((TextView) row.findViewById(R.id.state)).setText(card != null ? stateText(card, drawn.contains(card)) : "");
        }
    }

    /** The row's state: a card the owner set to Shown but which is beyond the eight drawn says so. */
    private String stateText(Cards.Card card, boolean drawn) {
        switch (card.state) {
            case SHOWN:
                return getString(drawn ? R.string.card_state_shown : R.string.card_state_over_limit);
            case HIDDEN:
                return getString(R.string.card_state_hidden);
            case RESIGNED:
                return getString(R.string.card_state_new_key);
            case RELABELLED:
                return getString(R.string.card_state_new_label);
            default:
                return getString(R.string.card_state_new);
        }
    }
}
