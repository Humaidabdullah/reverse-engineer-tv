package com.gathin.tvremote;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The TV remote as a phone app: the web remote the TV serves, full screen with no browser around it,
 * plus what a page cannot do on its own: the phone's volume buttons drive the TV's volume, voice uses
 * the phone's recogniser, haptics are real, presets become home-screen shortcuts, and the TV is found
 * again on the Wi-Fi when its address changes.
 */
public class MainActivity extends Activity {
    static final String PREFS = "remote", KEY_URL = "url";
    static final String EXTRA_PRESET = "preset";
    private static final String SERVICE_TYPE = "_tvremote._tcp";
    private static final int PORT = 8642;
    private static final int PICK_IMAGES = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private FrameLayout root;
    private WebView web;
    private Bridge bridge;
    private View setup;
    private TextView setupStatus;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private ValueCallback<Uri[]> pickCallback;
    private boolean pageOk;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0B0E);
        setContentView(root);

        web = new WebView(this);
        web.setBackgroundColor(0xFF0B0B0E);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        bridge = new Bridge(this, web);
        web.addJavascriptInterface(bridge, "TVRemoteNative");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String tv = prefs.getString(KEY_URL, "");
                if (u.toString().startsWith(tv)) return false;   // the TV's own pages stay inside the app
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (RuntimeException ignored) {
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (pageOk) runPendingPreset(getIntent());
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    pageOk = false;
                    showSetup("Can't reach the TV. Is it on, and is this phone on the same Wi-Fi?");
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                // "Add from your phone" in Looks: the system photo picker, several at once.
                if (pickCallback != null) pickCallback.onReceiveValue(null);
                pickCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                        .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(i, "Wallpapers for the TV"), PICK_IMAGES);
                } catch (RuntimeException e) {
                    pickCallback = null;
                    return false;
                }
                return true;
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        nsd = getSystemService(NsdManager.class);
        String url = prefs.getString(KEY_URL, null);
        if (url != null) load(url);
        else showSetup("Looking for your TV on this Wi-Fi…");
        discover();
        if (url == null) scan();
    }

    void load(String url) {
        prefs.edit().putString(KEY_URL, url).apply();
        hideSetup();
        pageOk = true;
        web.loadUrl(url + "/");
    }

    // ---- finding the TV --------------------------------------------------------------------------

    /** The launcher announces itself as _tvremote._tcp; a new address (the router changed it) is picked up here. */
    private void discover() {
        if (discovery != null) return;
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onDiscoveryStopped(String t) { }
            @Override public void onStartDiscoveryFailed(String t, int code) { discovery = null; }   // scan() still runs
            @Override public void onStopDiscoveryFailed(String t, int code) { }
            @Override public void onServiceLost(NsdServiceInfo info) { }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                nsd.resolveService(info, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo i, int code) { }

                    @Override
                    public void onServiceResolved(NsdServiceInfo i) {
                        if (i.getHost() == null) return;
                        String found = "http://" + i.getHost().getHostAddress() + ":" + i.getPort();
                        main.post(() -> {
                            if (!found.equals(prefs.getString(KEY_URL, null)) || setup != null) load(found);
                        });
                    }
                });
            }
        };
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (RuntimeException e) {
            discovery = null;
        }
    }

    /**
     * The fallback when the TV's announcement never arrives (some phones and routers drop multicast):
     * knock on port 8642 of every address on this Wi-Fi at once and ask whoever answers if it is a TV
     * remote. A /24 takes a second or two.
     */
    private void scan() {
        new Thread(() -> {
            java.net.Inet4Address me = null;
            int prefix = 24;
            try {
                android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
                android.net.LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
                if (lp != null) {
                    for (android.net.LinkAddress a : lp.getLinkAddresses()) {
                        if (a.getAddress() instanceof java.net.Inet4Address) {
                            me = (java.net.Inet4Address) a.getAddress();
                            prefix = a.getPrefixLength();
                        }
                    }
                }
            } catch (RuntimeException ignored) {
            }
            if (me == null) {
                main.post(() -> showSetup("This phone isn't on Wi-Fi. Join the same Wi-Fi as the TV."));
                return;
            }
            byte[] b = me.getAddress();
            String base = (b[0] & 0xFF) + "." + (b[1] & 0xFF) + "." + (b[2] & 0xFF) + ".";
            int self = b[3] & 0xFF;
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(48);
            java.util.concurrent.atomic.AtomicBoolean found = new java.util.concurrent.atomic.AtomicBoolean();
            for (int host = 1; host < 255; host++) {
                if (host == self) continue;
                String ip = base + host;
                pool.execute(() -> {
                    if (found.get()) return;
                    String name = hello(ip);
                    if (name != null && found.compareAndSet(false, true)) {
                        main.post(() -> {
                            if (setup != null) load("http://" + ip + ":" + PORT);
                        });
                    }
                });
            }
            pool.shutdown();
            try {
                pool.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            if (!found.get()) {
                String where = prefix < 24 ? " on this part of the Wi-Fi" : "";
                main.post(() -> showSetup("Couldn't find the TV" + where
                        + ". Is it on? Type its address below: it's shown on the TV when you pair."));
            }
        }, "find-tv").start();
    }

    /** The TV's name when this address runs the remote, else null. */
    private static String hello(String ip) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(ip, PORT), 450);
            s.setSoTimeout(1500);
            java.io.OutputStream o = s.getOutputStream();
            o.write(("GET /api/hello HTTP/1.1\r\nHost: " + ip + "\r\nConnection: close\r\n\r\n").getBytes());
            o.flush();
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            java.io.InputStream in = s.getInputStream();
            while ((n = in.read(buf)) > 0 && body.size() < 8192) body.write(buf, 0, n);
            String r = body.toString("UTF-8");
            int at = r.indexOf("\r\n\r\n");
            if (at < 0 || !r.contains("\"paired\"")) return null;
            return new org.json.JSONObject(r.substring(at + 4)).optString("name", "TV");
        } catch (Exception e) {
            return null;
        }
    }

    private void showSetup(String message) {
        if (setup != null) {
            setupStatus.setText(message);
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding((int) (28 * d), 0, (int) (28 * d), 0);
        box.setBackgroundColor(0xFF0B0B0E);
        box.setClickable(true);
        TextView title = new TextView(this);
        title.setText("TV Remote");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        setupStatus = new TextView(this);
        setupStatus.setText(message);
        setupStatus.setTextColor(0xFF8E8F99);
        setupStatus.setTextSize(15);
        setupStatus.setGravity(Gravity.CENTER);
        setupStatus.setPadding(0, (int) (10 * d), 0, (int) (22 * d));
        EditText address = new EditText(this);
        address.setHint("Or type the TV's address, e.g. 192.168.1.6");
        address.setHintTextColor(0xFF6B6C75);
        address.setTextColor(Color.WHITE);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        String saved = prefs.getString(KEY_URL, "");
        if (!saved.isEmpty()) address.setText(Uri.parse(saved).getHost());
        Button go = new Button(this);
        go.setText("Connect");
        Runnable connect = () -> {
            String a = address.getText().toString().trim();
            if (a.isEmpty()) return;
            if (!a.startsWith("http")) a = "http://" + a;
            if (Uri.parse(a).getPort() < 0) a = a + ":8642";
            load(a.replaceAll("/+$", ""));
        };
        go.setOnClickListener(v -> connect.run());
        address.setOnEditorActionListener((v, id, e) -> {
            connect.run();
            return true;
        });
        box.addView(title);
        box.addView(setupStatus);
        box.addView(address, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(go, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setup = box;
        root.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void hideSetup() {
        if (setup == null) return;
        root.removeView(setup);
        setup = null;
    }

    // ---- the phone's own buttons -----------------------------------------------------------------

    /** Volume buttons turn the TV up and down while the remote is open, as the iPhone's Apple TV remote does. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (setup == null && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            String k = keyCode == KeyEvent.KEYCODE_VOLUME_UP ? "volup" : "voldown";
            web.evaluateJavascript("window.remoteKey && window.remoteKey('" + k + "')", null);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (setup == null && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) return true;
        return super.onKeyUp(keyCode, event);
    }

    /** Back closes an open sheet first, then leaves the app without unloading the remote. */
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(function(){var b=document.getElementById('sheetBg');"
                + "if(b&&!b.hidden){closeSheet();return true}return false})()", open -> {
            if (!"true".equals(open)) moveTaskToBack(true);
        });
    }

    // ---- presets from home-screen shortcuts ------------------------------------------------------

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        runPendingPreset(intent);
    }

    private void runPendingPreset(Intent intent) {
        String id = intent == null ? null : intent.getStringExtra(EXTRA_PRESET);
        if (id == null) return;
        intent.removeExtra(EXTRA_PRESET);
        String safe = id.replaceAll("[^A-Za-z0-9_-]", "");
        // The page may still be starting: it runs the preset as soon as it is paired and loaded.
        web.evaluateJavascript("window.applyPresetById ? window.applyPresetById('" + safe + "') : (window.pendingPreset='" + safe + "')", null);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request == PICK_IMAGES && pickCallback != null) {
            Uri[] uris = null;
            if (result == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    uris = new Uri[data.getClipData().getItemCount()];
                    for (int i = 0; i < uris.length; i++) uris[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    uris = new Uri[]{data.getData()};
                }
            }
            pickCallback.onReceiveValue(uris);
            pickCallback = null;
            return;
        }
        super.onActivityResult(request, result, data);
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        bridge.onPermission(request, results);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        web.evaluateJavascript("document.dispatchEvent(new Event('visibilitychange'))", null);
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (discovery != null) {
            try {
                nsd.stopServiceDiscovery(discovery);
            } catch (RuntimeException ignored) {
            }
        }
        bridge.release();
        web.destroy();
        super.onDestroy();
    }
}
