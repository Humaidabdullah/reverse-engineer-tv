package com.gathin.tvremote;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * What the web remote can ask of the phone, as window.TVRemoteNative: haptics, voice through the phone's
 * own recogniser, and presets as home-screen shortcuts. Calls arrive on a WebView thread; anything that
 * touches the UI or the recogniser hops to the main thread.
 */
final class Bridge {
    private static final int MIC_REQUEST = 7;

    private final Activity activity;
    private final WebView web;
    private SpeechRecognizer recognizer;
    private boolean listenAfterPermission;

    Bridge(Activity activity, WebView web) {
        this.activity = activity;
        this.web = web;
    }

    // ---- haptics ---------------------------------------------------------------------------------

    /** A short tick for a move, a firmer one for a press; the page passes how long it wanted. */
    @JavascriptInterface
    public void haptic(int ms) {
        Vibrator v = activity.getSystemService(Vibrator.class);
        if (v == null || !v.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 29) {
            v.vibrate(VibrationEffect.createPredefined(ms <= 8 ? VibrationEffect.EFFECT_TICK
                    : ms <= 14 ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_HEAVY_CLICK));
        } else {
            v.vibrate(VibrationEffect.createOneShot(Math.max(5, ms), VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    // ---- voice -----------------------------------------------------------------------------------

    @JavascriptInterface
    public boolean canListen() {
        return SpeechRecognizer.isRecognitionAvailable(activity);
    }

    @JavascriptInterface
    public void listen() {
        activity.runOnUiThread(() -> {
            if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                listenAfterPermission = true;
                activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
                return;
            }
            startRecognizer();
        });
    }

    void onPermission(int request, int[] results) {
        if (request != MIC_REQUEST || !listenAfterPermission) return;
        listenAfterPermission = false;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startRecognizer();
        else deliver(null, false);
    }

    private void startRecognizer() {
        if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rms) { }
            @Override public void onBufferReceived(byte[] buf) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onEvent(int type, Bundle b) { }

            @Override
            public void onError(int error) {
                deliver(null, false);
            }

            @Override
            public void onPartialResults(Bundle b) {
                String t = best(b);
                if (t != null) deliver(t, true);
            }

            @Override
            public void onResults(Bundle b) {
                deliver(best(b), false);
            }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        recognizer.startListening(i);
    }

    private static String best(Bundle b) {
        ArrayList<String> r = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return r == null || r.isEmpty() ? null : r.get(0);
    }

    private void deliver(String text, boolean partial) {
        String js = "window.onNativeVoice && window.onNativeVoice(" + (text == null ? "null" : JSONObject.quote(text)) + "," + partial + ")";
        activity.runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    @JavascriptInterface
    public void stopListening() {
        activity.runOnUiThread(() -> {
            if (recognizer != null) recognizer.cancel();
        });
    }

    void release() {
        if (recognizer != null) recognizer.destroy();
    }

    // ---- presets as shortcuts --------------------------------------------------------------------

    /** The page's presets, newest list each time it loads them: long-press the app icon to run one. */
    @JavascriptInterface
    public void setPresets(String json) {
        try {
            ShortcutManager sm = activity.getSystemService(ShortcutManager.class);
            JSONArray a = new JSONArray(json);
            List<ShortcutInfo> list = new ArrayList<>();
            for (int i = 0; i < a.length() && list.size() < sm.getMaxShortcutCountPerActivity() - 1 && list.size() < 4; i++) {
                list.add(shortcut(a.getJSONObject(i)));
            }
            sm.setDynamicShortcuts(list);
        } catch (Exception ignored) {
            // shortcuts are a convenience; the remote works without them
        }
    }

    /** "Add to home screen" on a preset: one tap on the phone's home screen runs it. */
    @JavascriptInterface
    public boolean pinPreset(String json) {
        try {
            ShortcutManager sm = activity.getSystemService(ShortcutManager.class);
            if (!sm.isRequestPinShortcutSupported()) return false;
            return sm.requestPinShortcut(shortcut(new JSONObject(json)), null);
        } catch (Exception e) {
            return false;
        }
    }

    private ShortcutInfo shortcut(JSONObject p) throws Exception {
        String id = p.getString("id"), name = p.optString("name", "Preset");
        Intent run = new Intent(activity, MainActivity.class).setAction(Intent.ACTION_VIEW)
                .putExtra(MainActivity.EXTRA_PRESET, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return new ShortcutInfo.Builder(activity, "preset-" + id)
                .setShortLabel(name)
                .setLongLabel(name + " on the TV")
                .setIcon(Icon.createWithAdaptiveBitmap(dot(p.optString("color", "#3a3d47"), name)))
                .setIntent(run)
                .build();
    }

    /** The preset's colour with its initial, as an adaptive icon. */
    private static Bitmap dot(String color, String name) {
        int n = 216;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int col;
        try {
            col = Color.parseColor(color);
        } catch (IllegalArgumentException e) {
            col = 0xFF3A3D47;
        }
        c.drawColor(col);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setTextSize(n * 0.34f);
        p.setFakeBoldText(true);
        p.setTextAlign(Paint.Align.CENTER);
        String initial = name.isEmpty() ? "★" : name.substring(0, 1).toUpperCase();
        c.drawText(initial, n / 2f, n / 2f - (p.descent() + p.ascent()) / 2f, p);
        return b;
    }
}
