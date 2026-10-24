package com.gathin.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The phone remote: a web app this TV serves on port 8642 (assets/remote), and the API behind it.
 *
 * Volume, looks (wallpaper, icon style), presets and opening apps are done here directly. Keys for other
 * apps (the touch surface, Back, Play/Pause, Power, typing) go through Injector. A phone pairs once with
 * a 4-digit code shown on the TV and then sends the token it got with every call.
 *
 * A foreground service, so Android keeps the server alive while another app is on screen.
 */
public final class PhoneRemote extends Service {
    private static final String TAG = "PhoneRemote";
    static final int PORT = 8642;
    private static final String STORE = "phone_remote", TOKENS = "tokens", PRESETS = "presets";

    private static final Map<String, Integer> KEYS = new HashMap<>();

    static {
        KEYS.put("up", KeyEvent.KEYCODE_DPAD_UP);
        KEYS.put("down", KeyEvent.KEYCODE_DPAD_DOWN);
        KEYS.put("left", KeyEvent.KEYCODE_DPAD_LEFT);
        KEYS.put("right", KeyEvent.KEYCODE_DPAD_RIGHT);
        KEYS.put("ok", KeyEvent.KEYCODE_DPAD_CENTER);
        KEYS.put("back", KeyEvent.KEYCODE_BACK);
        KEYS.put("home", KeyEvent.KEYCODE_HOME);
        KEYS.put("playpause", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        KEYS.put("rewind", KeyEvent.KEYCODE_MEDIA_REWIND);
        KEYS.put("forward", KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
        KEYS.put("menu", KeyEvent.KEYCODE_MENU);
        KEYS.put("power", KeyEvent.KEYCODE_POWER);
        KEYS.put("mute", KeyEvent.KEYCODE_VOLUME_MUTE);
        KEYS.put("volup", KeyEvent.KEYCODE_VOLUME_UP);
        KEYS.put("voldown", KeyEvent.KEYCODE_VOLUME_DOWN);
        KEYS.put("input", KeyEvent.KEYCODE_TV_INPUT);
        KEYS.put("delete", KeyEvent.KEYCODE_DEL);
        KEYS.put("enter", KeyEvent.KEYCODE_ENTER);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, byte[]> imageCache = new ConcurrentHashMap<>();
    private HttpServer http;
    private Injector injector;
    private SharedPreferences store;

    // Pairing: one code at a time, valid for two minutes, five guesses.
    private String pairCode;
    private long pairUntil;
    private int pairTries;
    private View pairView;

    static void start(Context ctx) {
        try {
            ctx.startForegroundService(new Intent(ctx, PhoneRemote.class));
        } catch (RuntimeException e) {
            Log.w(TAG, "could not start the phone remote", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("remote", "Phone remote", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "remote")
                .setSmallIcon(getApplicationInfo().icon)
                .setContentTitle("Phone remote")
                .setContentText("Open " + url() + " on your phone")
                .build();
        startForeground(1, n);

        store = getSharedPreferences(STORE, MODE_PRIVATE);
        seedPresets();
        injector = new Injector(this);
        injector.warmUp();
        http = new HttpServer(PORT, this::route);
        try {
            http.start();
            Log.i(TAG, "listening on " + url());
        } catch (IOException e) {
            Log.e(TAG, "port " + PORT + " unavailable", e);
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (http != null) http.stop();
        main.post(this::hidePairing);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- routing -------------------------------------------------------------------------------

    private HttpServer.Response route(HttpServer.Request r) throws Exception {
        String p = r.path;
        if (r.method.equals("GET")) {
            if (p.equals("/") || p.equals("/index.html")) return asset("remote/index.html", "text/html; charset=utf-8");
            if (p.equals("/manifest.webmanifest")) return asset("remote/manifest.webmanifest", "application/manifest+json").cached(3600);
            if (p.equals("/icon.png")) return new HttpServer.Response(200, "image/png", appIcon()).cached(86400);
            if (p.equals("/api/hello")) {
                return HttpServer.Response.ok(new JSONObject().put("name", tvName()).put("paired", authorized(r)));
            }
        }
        if (r.method.equals("POST") && p.equals("/api/pair/start")) {
            startPairing();
            return HttpServer.Response.ok(new JSONObject().put("ok", true));
        }
        if (r.method.equals("POST") && p.equals("/api/pair/finish")) {
            JSONObject b = r.json();
            String token = finishPairing(b.optString("code"));
            if (token == null) return HttpServer.Response.error(401, "That code didn't match. Check the TV and try again.");
            return HttpServer.Response.ok(new JSONObject().put("token", token).put("name", tvName()));
        }

        if (!authorized(r)) return HttpServer.Response.error(401, "Pair this phone first.");

        if (r.method.equals("GET")) {
            switch (p) {
                case "/api/state": return HttpServer.Response.ok(state());
                case "/api/apps": return HttpServer.Response.ok(apps());
                case "/api/icon": return image("icon:" + param(r, "pkg"), () -> appIconPng(param(r, "pkg")), "image/png");
                case "/api/looks": return HttpServer.Response.ok(looks());
                case "/api/thumb": return image("thumb:" + param(r, "id") + ":" + thumbStamp(param(r, "id")),
                        () -> thumb(param(r, "id")), "image/jpeg");
                case "/api/presets": return HttpServer.Response.ok(presets());
                default: return HttpServer.Response.error(404, "Not found");
            }
        }
        if (p.equals(HttpServer.UPLOAD_PATH)) return HttpServer.Response.ok(addWallpaper(r.header("x-file-name"), r.body));
        JSONObject b = r.json();
        switch (p) {
            case "/api/forget": {
                // Un-pair the phone that asks: its token stops working everywhere.
                Set<String> tokens = new HashSet<>(store.getStringSet(TOKENS, Collections.emptySet()));
                tokens.remove(r.header("x-remote-token"));
                store.edit().putStringSet(TOKENS, tokens).apply();
                return HttpServer.Response.ok(new JSONObject().put("ok", true));
            }
            case "/api/key": {
                Integer code = KEYS.get(b.optString("key"));
                if (code == null) return HttpServer.Response.error(400, "Unknown key");
                if (b.optBoolean("long")) injector.longPress(code);
                else injector.tap(code);
                return HttpServer.Response.ok(new JSONObject().put("keys", injector.state().name()));
            }
            case "/api/text":
                injector.type(b.optString("text"));
                return HttpServer.Response.ok(new JSONObject().put("ok", true));
            case "/api/volume":
                setVolume(b.optInt("set", -1));
                return HttpServer.Response.ok(state());
            case "/api/launch":
                return launch(b.optString("pkg")) ? HttpServer.Response.ok(new JSONObject().put("ok", true))
                        : HttpServer.Response.error(404, "That app isn't installed.");
            case "/api/look":
                applyLook(b);
                return HttpServer.Response.ok(looks());
            case "/api/wallpapers/delete":
                deleteWallpaper(b.optString("id"));
                return HttpServer.Response.ok(looks());
            case "/api/presets/save":
                return HttpServer.Response.ok(savePreset(b.getJSONObject("preset")));
            case "/api/presets/delete":
                deletePreset(b.optString("id"));
                return HttpServer.Response.ok(presets());
            case "/api/presets/apply": {
                JSONObject preset = findPreset(b.optString("id"));
                if (preset == null) return HttpServer.Response.error(404, "That preset is gone.");
                applyPreset(preset);
                return HttpServer.Response.ok(state());
            }
            default:
                return HttpServer.Response.error(404, "Not found");
        }
    }

    private static String param(HttpServer.Request r, String name) {
        for (String kv : r.query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(name)) {
                try {
                    return URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return "";
                }
            }
        }
        return "";
    }

    private HttpServer.Response asset(String path, String type) throws IOException {
        try (InputStream in = getAssets().open(path)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return new HttpServer.Response(200, type, b.toByteArray());
        }
    }

    private interface Maker {
        byte[] make() throws Exception;
    }

    private HttpServer.Response image(String key, Maker maker, String type) throws Exception {
        byte[] data = imageCache.get(key);
        if (data == null) {
            data = maker.make();
            if (data == null) return HttpServer.Response.error(404, "No image");
            imageCache.put(key, data);
        }
        return new HttpServer.Response(200, type, data).cached(3600);
    }

    // ---- pairing -------------------------------------------------------------------------------

    private boolean authorized(HttpServer.Request r) {
        String t = r.header("x-remote-token");
        // <img> cannot send headers, so icons and thumbnails carry the token in the query instead.
        if (t.isEmpty() && r.method.equals("GET")) t = param(r, "t");
        return !t.isEmpty() && store.getStringSet(TOKENS, Collections.emptySet()).contains(t);
    }

    private synchronized void startPairing() {
        pairCode = String.format(java.util.Locale.ROOT, "%04d", new SecureRandom().nextInt(10000));
        pairUntil = System.currentTimeMillis() + 120_000;
        pairTries = 0;
        String code = pairCode;
        main.post(() -> showPairing(code));
        main.removeCallbacks(hidePairingLater);
        main.postDelayed(hidePairingLater, 120_000);
    }

    private synchronized String finishPairing(String attempt) {
        if (pairCode == null || System.currentTimeMillis() > pairUntil) return null;
        if (!pairCode.equals(attempt.trim())) {
            if (++pairTries >= 5) {
                pairCode = null;
                main.post(this::hidePairing);
            }
            return null;
        }
        pairCode = null;
        main.post(this::hidePairing);
        String token = Injector.randomHex(24);
        Set<String> tokens = new HashSet<>(store.getStringSet(TOKENS, Collections.emptySet()));
        tokens.add(token);
        store.edit().putStringSet(TOKENS, tokens).apply();
        return token;
    }

    private final Runnable hidePairingLater = this::hidePairing;

    /** The code, in a glass card at the top right of whatever is on screen. */
    private void showPairing(String code) {
        hidePairing();
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Phone remote code: " + code, Toast.LENGTH_LONG).show();
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding((int) (28 * d), (int) (20 * d), (int) (28 * d), (int) (22 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE61C1C21);
        bg.setCornerRadius(26 * d);
        bg.setStroke((int) Math.max(1, d), 0x55FFFFFF);
        card.setBackground(bg);
        card.addView(text("Pair your phone", 15, 0xCCFFFFFF, 500));
        TextView big = text(code, 46, Color.WHITE, 300);
        big.setLetterSpacing(0.35f);
        big.setPadding(0, (int) (2 * d), 0, (int) (2 * d));
        card.addView(big);
        card.addView(text(url(), 13, 0x99FFFFFF, 400));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = (int) (40 * d);
        lp.y = (int) (40 * d);
        try {
            getSystemService(WindowManager.class).addView(card, lp);
            pairView = card;
        } catch (RuntimeException e) {
            Toast.makeText(this, "Phone remote code: " + code, Toast.LENGTH_LONG).show();
        }
    }

    private TextView text(String s, int sp, int color, int weight) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        try {
            android.graphics.Typeface outfit = getResources().getFont(R.font.outfit);
            t.setTypeface(android.os.Build.VERSION.SDK_INT >= 28 ? android.graphics.Typeface.create(outfit, weight, false) : outfit);
        } catch (RuntimeException ignored) {
        }
        return t;
    }

    private void hidePairing() {
        if (pairView == null) return;
        try {
            getSystemService(WindowManager.class).removeView(pairView);
        } catch (RuntimeException ignored) {
        }
        pairView = null;
    }

    // ---- state, volume, apps -------------------------------------------------------------------

    private JSONObject state() throws JSONException {
        AudioManager am = getSystemService(AudioManager.class);
        LauncherPrefs prefs = LauncherPrefs.get(this);
        return new JSONObject()
                .put("name", tvName())
                .put("volume", am.getStreamVolume(AudioManager.STREAM_MUSIC))
                .put("maxVolume", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                .put("muted", am.isStreamMute(AudioManager.STREAM_MUSIC))
                .put("keys", injector.state().name())
                .put("wallpaper", currentWallpaperId(prefs))
                .put("iconTheme", prefs.getInt(LauncherPrefs.ICON_THEME, LauncherPrefs.DEF_ICON_THEME));
    }

    private void setVolume(int level) {
        if (level < 0) return;
        AudioManager am = getSystemService(AudioManager.class);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.min(max, level), AudioManager.FLAG_SHOW_UI);
    }

    /** The apps Home shows, in the dock's order. */
    private JSONArray apps() throws JSONException {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> found = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        LauncherPrefs prefs = LauncherPrefs.get(this);
        Config cfg = Config.load(this);
        Set<String> hidden = new HashSet<>(prefs.hidden());
        hidden.addAll(cfg.hiddenApps);
        List<String> order = prefs.order();
        Map<String, String> labels = new HashMap<>();
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || hidden.contains(pkg)) continue;
            labels.put(pkg, String.valueOf(ri.loadLabel(pm)));
        }
        List<String> pkgs = new ArrayList<>(labels.keySet());
        Collections.sort(pkgs, (a, c) -> LauncherPrefs.compare(a, labels.get(a), c, labels.get(c), order, cfg.dockApps));
        JSONArray out = new JSONArray();
        for (String pkg : pkgs) out.put(new JSONObject().put("pkg", pkg).put("label", labels.get(pkg)));
        return out;
    }

    private boolean launch(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent launch = i;
        main.post(() -> {
            try {
                startActivity(launch);
            } catch (RuntimeException e) {
                Log.w(TAG, "could not open " + pkg, e);
            }
        });
        return true;
    }

    private byte[] appIconPng(String pkg) throws Exception {
        Drawable d = getPackageManager().getApplicationIcon(pkg);
        int n = 160;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, n, n);
        d.draw(new Canvas(b));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }

    /** The home-screen icon for the web app: a dark rounded square with a remote's touch ring. */
    private byte[] appIcon() {
        byte[] cached = imageCache.get("appicon");
        if (cached != null) return cached;
        int n = 512;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new android.graphics.LinearGradient(0, 0, 0, n, 0xFF2B2E37, 0xFF0E0F13, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, n, n, p);
        p.setShader(null);
        p.setColor(0xFF3A3D47);
        c.drawRoundRect(new RectF(n * 0.30f, n * 0.14f, n * 0.70f, n * 0.86f), n * 0.2f, n * 0.2f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(n * 0.022f);
        p.setColor(0xCCFFFFFF);
        c.drawCircle(n * 0.5f, n * 0.36f, n * 0.11f, p);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(n * 0.43f, n * 0.62f, n * 0.035f, p);
        c.drawCircle(n * 0.57f, n * 0.62f, n * 0.035f, p);
        c.drawCircle(n * 0.43f, n * 0.74f, n * 0.035f, p);
        c.drawCircle(n * 0.57f, n * 0.74f, n * 0.035f, p);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        imageCache.put("appicon", out.toByteArray());
        return out.toByteArray();
    }

    // ---- looks ---------------------------------------------------------------------------------

    /** Wallpapers as ids the phone can send back: "none", "daily", "p3" (built-in), "f:name.jpg" (a photo). */
    private JSONObject looks() throws JSONException {
        JSONArray walls = new JSONArray();
        for (File f : Wallpapers.files(this)) {
            walls.put(new JSONObject().put("id", "f:" + f.getName()).put("label", Wallpapers.label(f)));
        }
        // Only the owner's pictures: the built-in gradients stay in Home settings on the TV, not on the phone.
        walls.put(new JSONObject().put("id", "daily").put("label", "Change daily"));
        walls.put(new JSONObject().put("id", "none").put("label", "Black"));
        LauncherPrefs prefs = LauncherPrefs.get(this);
        return new JSONObject()
                .put("wallpapers", walls)
                .put("iconThemes", new JSONArray(IconThemes.NAMES))
                .put("wallpaper", currentWallpaperId(prefs))
                .put("iconTheme", prefs.getInt(LauncherPrefs.ICON_THEME, LauncherPrefs.DEF_ICON_THEME));
    }

    private String currentWallpaperId(LauncherPrefs prefs) {
        int choice = prefs.getInt(LauncherPrefs.WALLPAPER, Wallpapers.NONE);
        if (choice == Wallpapers.FILE) return "f:" + prefs.getString(LauncherPrefs.WALL_FILE);
        if (choice == Wallpapers.DAILY) return "daily";
        if (choice >= 1 && choice <= Wallpapers.count()) return "p" + choice;
        return "none";
    }

    private void applyLook(JSONObject b) {
        LauncherPrefs prefs = LauncherPrefs.get(this);
        String wall = b.optString("wallpaper", "");
        if (!wall.isEmpty()) setWallpaper(prefs, wall);
        if (b.has("iconTheme")) {
            int t = b.optInt("iconTheme", 0);
            if (t >= 0 && t < IconThemes.NAMES.length) prefs.putInt(LauncherPrefs.ICON_THEME, t);
        }
    }

    private void setWallpaper(LauncherPrefs prefs, String id) {
        if (id.startsWith("f:")) {
            String name = new File(id.substring(2)).getName();   // no paths from the phone
            // A preset can outlive its picture (deleted from the phone): then it leaves the wallpaper alone.
            if (!new File(getExternalFilesDir(Wallpapers.FOLDER), name).isFile()) return;
            prefs.putWallpaper(Wallpapers.FILE, name);
        } else if (id.equals("daily")) {
            prefs.putWallpaper(Wallpapers.DAILY, null);
        } else if (id.equals("none")) {
            prefs.putWallpaper(Wallpapers.NONE, null);
        } else if (id.startsWith("p")) {
            try {
                int i = Integer.parseInt(id.substring(1));
                if (i >= 1 && i <= Wallpapers.count()) prefs.putWallpaper(i, null);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private byte[] thumb(String id) throws IOException {
        Bitmap b = null;
        if (id.startsWith("f:")) {
            File dir = getExternalFilesDir(Wallpapers.FOLDER);
            File f = new File(dir, new File(id.substring(2)).getName());
            if (!f.isFile()) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            o.inSampleSize = Math.max(1, Integer.highestOneBit(Math.max(1, o.outWidth / 480)));
            o.inJustDecodeBounds = false;
            b = BitmapFactory.decodeFile(f.getPath(), o);
        } else if (id.startsWith("p")) {
            try {
                b = Wallpapers.render(Integer.parseInt(id.substring(1)), 960, 540);
            } catch (NumberFormatException ignored) {
            }
        }
        if (b == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 78, out);
        return out.toByteArray();
    }

    private long thumbStamp(String id) {
        if (!id.startsWith("f:")) return 0;
        return new File(getExternalFilesDir(Wallpapers.FOLDER), new File(id.substring(2)).getName()).lastModified();
    }

    /**
     * A picture from a phone, already cropped and sized to the screen there. Saved under a tidy version of
     * its name ("Luffy Gear 5.jpg" -> luffy_gear_5.jpg), never over another picture.
     */
    private synchronized JSONObject addWallpaper(String rawName, byte[] jpeg) throws Exception {
        if (jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            throw new IllegalArgumentException("Send a JPEG picture.");
        }
        String base = java.net.URLDecoder.decode(rawName, "UTF-8").toLowerCase(java.util.Locale.ROOT);
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        base = base.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (base.isEmpty()) base = "wallpaper";
        if (base.length() > 48) base = base.substring(0, 48);
        File dir = getExternalFilesDir(Wallpapers.FOLDER);
        if (dir == null) throw new IOException("No storage for wallpapers.");
        File f = new File(dir, base + ".jpg");
        for (int i = 2; f.exists(); i++) f = new File(dir, base + "_" + i + ".jpg");
        File tmp = new File(dir, "." + f.getName() + ".part");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
            out.write(jpeg);
        }
        if (!tmp.renameTo(f)) throw new IOException("Could not save the picture.");
        JSONObject looks = looks();
        looks.put("added", "f:" + f.getName());
        return looks;
    }

    private synchronized void deleteWallpaper(String id) {
        if (!id.startsWith("f:")) return;
        String name = new File(id.substring(2)).getName();
        File f = new File(getExternalFilesDir(Wallpapers.FOLDER), name);
        LauncherPrefs prefs = LauncherPrefs.get(this);
        boolean current = prefs.getInt(LauncherPrefs.WALLPAPER, Wallpapers.NONE) == Wallpapers.FILE
                && name.equals(prefs.getString(LauncherPrefs.WALL_FILE));
        if (f.isFile() && f.delete() && current) prefs.putWallpaper(Wallpapers.NONE, null);
    }

    // ---- presets -------------------------------------------------------------------------------

    /** A few to start from, built from what is on this TV; all of them can be edited or deleted. */
    private void seedPresets() {
        if (store.contains(PRESETS)) return;
        try {
            JSONArray a = new JSONArray()
                    .put(new JSONObject().put("id", "movie").put("name", "Movie night").put("color", "#3a2a66")
                            .put("wallpaper", "none").put("iconTheme", IconThemes.DARK).put("app", "com.netflix.ninja"))
                    .put(new JSONObject().put("id", "morning").put("name", "Morning").put("color", "#c47a3d")
                            .put("wallpaper", "f:haikyuu_fly.jpg").put("iconTheme", IconThemes.ORIGINAL)
                            .put("app", "com.google.android.youtube.tv"))
                    .put(new JSONObject().put("id", "cricket").put("name", "Cricket").put("color", "#1f8a6a")
                            .put("app", "in.startv.hotstar"));
            store.edit().putString(PRESETS, a.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    private synchronized JSONArray presets() throws JSONException {
        return new JSONArray(store.getString(PRESETS, "[]"));
    }

    private synchronized JSONObject findPreset(String id) throws JSONException {
        JSONArray a = presets();
        for (int i = 0; i < a.length(); i++) if (a.getJSONObject(i).optString("id").equals(id)) return a.getJSONObject(i);
        return null;
    }

    private synchronized JSONArray savePreset(JSONObject p) throws JSONException {
        JSONObject clean = new JSONObject();
        String id = p.optString("id", "");
        clean.put("id", id.isEmpty() ? Injector.randomHex(6) : id);
        clean.put("name", p.optString("name", "Preset").trim());
        clean.put("color", p.optString("color", "#3a3d47"));
        for (String k : new String[]{"wallpaper", "app"}) if (!p.optString(k, "").isEmpty()) clean.put(k, p.optString(k));
        if (p.has("iconTheme") && !p.isNull("iconTheme")) clean.put("iconTheme", p.optInt("iconTheme"));
        if (p.has("volume") && !p.isNull("volume")) clean.put("volume", p.optInt("volume"));
        JSONArray a = presets(), out = new JSONArray();
        boolean replaced = false;
        for (int i = 0; i < a.length(); i++) {
            if (a.getJSONObject(i).optString("id").equals(clean.getString("id"))) {
                out.put(clean);
                replaced = true;
            } else {
                out.put(a.getJSONObject(i));
            }
        }
        if (!replaced) out.put(clean);
        store.edit().putString(PRESETS, out.toString()).apply();
        return out;
    }

    private synchronized void deletePreset(String id) throws JSONException {
        JSONArray a = presets(), out = new JSONArray();
        for (int i = 0; i < a.length(); i++) if (!a.getJSONObject(i).optString("id").equals(id)) out.put(a.getJSONObject(i));
        store.edit().putString(PRESETS, out.toString()).apply();
    }

    private void applyPreset(JSONObject p) {
        LauncherPrefs prefs = LauncherPrefs.get(this);
        if (p.has("wallpaper")) setWallpaper(prefs, p.optString("wallpaper"));
        if (p.has("iconTheme")) prefs.putInt(LauncherPrefs.ICON_THEME, p.optInt("iconTheme"));
        if (p.has("volume")) setVolume(p.optInt("volume"));
        if (p.has("app")) launch(p.optString("app"));
    }

    // ---- helpers -------------------------------------------------------------------------------

    private String tvName() {
        String name = Settings.Global.getString(getContentResolver(), "device_name");
        return name == null || name.isEmpty() ? android.os.Build.MODEL : name;
    }

    String url() {
        String ip = "this TV's address";
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
            if (lp != null) {
                for (LinkAddress a : lp.getLinkAddresses()) {
                    if (a.getAddress() instanceof Inet4Address) ip = a.getAddress().getHostAddress();
                }
            }
        } catch (RuntimeException ignored) {
        }
        return "http://" + ip + ":" + PORT;
    }
}
