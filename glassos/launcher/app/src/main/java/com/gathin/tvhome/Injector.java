package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The launcher's side of InjectServer: one open connection, commands sent in order on one thread, and the
 * helper (re)started through AdbLoopback whenever it is not answering (after a reboot, or the first time).
 */
final class Injector {
    private static final String TAG = "PhoneRemote";
    static final int PORT = 8643;

    /** What the phone shows when keys cannot be sent. */
    enum State { STARTING, READY, NEEDS_APPROVAL, NO_ADB }

    private final Context ctx;
    private final String token;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> new Thread(r, "remote-keys"));
    private Socket sock;
    private OutputStream out;
    private volatile State state = State.STARTING;

    Injector(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        SharedPreferences sp = ctx.getSharedPreferences("phone_remote", Context.MODE_PRIVATE);
        String t = sp.getString("helper_token", null);
        if (t == null) {
            t = randomHex(16);
            sp.edit().putString("helper_token", t).apply();
        }
        token = t;
    }

    State state() {
        return state;
    }

    /** Make sure the helper is up, so the first key from a phone is not the one that waits for it. */
    void warmUp() {
        exec.execute(() -> {
            try {
                connect();
            } catch (IOException e) {
                Log.w(TAG, "key helper not ready: " + e.getMessage());
            }
        });
    }

    void tap(int keyCode) {
        send("k " + keyCode);
    }

    void longPress(int keyCode) {
        send("l " + keyCode);
    }

    void type(String text) {
        send("t " + Base64.encodeToString(text.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
    }

    private void send(String line) {
        exec.execute(() -> {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    connect();
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    return;
                } catch (IOException e) {
                    close();
                    if (attempt == 1) Log.w(TAG, "key not sent: " + e.getMessage());
                }
            }
        });
    }

    private void connect() throws IOException {
        if (sock != null && !sock.isClosed()) return;
        if (tryConnect()) return;
        start();
        // A Java VM takes a second or two to boot on a TV.
        for (int i = 0; i < 50; i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            if (tryConnect()) return;
        }
        throw new IOException("key helper did not start");
    }

    private boolean tryConnect() {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 300);
            s.setTcpNoDelay(true);
            s.setSoTimeout(1500);
            OutputStream o = s.getOutputStream();
            o.write((token + "\n").getBytes(StandardCharsets.US_ASCII));
            o.flush();
            String answer = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            if ("ok".equals(answer)) {
                s.setSoTimeout(0);
                sock = s;
                out = o;
                state = State.READY;
                return true;
            }
            s.close();   // a helper started with another token (the launcher's data was cleared): start() replaces it
            return false;
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            return false;
        }
    }

    /** Starts InjectServer as the shell user, replacing one that is already running. */
    private void start() throws IOException {
        String apk = ctx.getApplicationInfo().sourceDir;
        // From a subshell, and the session kept open a moment: when adbd closes the session right after a plain
        // `&`, the helper dies before setsid has detached it (seen on the Vu 4K).
        String cmd = "kill $(cat " + InjectServer.PID_FILE + " 2>/dev/null) 2>/dev/null; "
                + "(CLASSPATH=" + apk + " setsid nohup app_process /system/bin " + InjectServer.class.getName()
                + " " + token + " " + PORT + " >/dev/null 2>&1 </dev/null &); sleep 0.3";
        state = State.STARTING;
        try {
            String said = AdbLoopback.shell(ctx, cmd, 90_000);
            Log.i(TAG, "started the key helper" + (said.trim().isEmpty() ? "" : ": " + said.trim()));
        } catch (java.net.ConnectException e) {
            state = State.NO_ADB;
            throw e;
        } catch (IOException e) {
            Log.w(TAG, "ADB on this TV refused the launcher: " + e);
            state = State.NEEDS_APPROVAL;
            throw e;
        }
    }

    private void close() {
        if (sock != null) {
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
        sock = null;
        out = null;
    }

    static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
