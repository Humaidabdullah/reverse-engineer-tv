package com.gathin.tvhome;

import android.os.SystemClock;
import android.util.Base64;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * The phone remote's key helper. It is not part of the app at runtime: the launcher starts it once per
 * boot through the TV's own ADB, as the shell user, with
 * {@code CLASSPATH=<this apk> app_process /system/bin com.gathin.tvhome.InjectServer <token> <port>}.
 * The shell user may inject input into any app; an ordinary app may not. Injecting from a resident
 * process takes a few milliseconds, where `input keyevent` starts a Java VM for every key (300-500 ms
 * on a TV), which is far too slow for a remote.
 *
 * It listens on 127.0.0.1 only, and the first line of every connection must be the token the launcher
 * started it with, so other apps on the TV cannot use it to press keys.
 *
 * Protocol, one command per line: "k CODE" tap, "d CODE" down, "u CODE" up, "l CODE" long press,
 * "t BASE64" type text, "p" ping (answers "pong").
 */
public final class InjectServer {
    static final String PID_FILE = "/data/local/tmp/glassos-remote.pid";

    private static Object inputManager;
    private static Method inject;

    public static void main(String[] args) throws Exception {
        String token = args[0];
        int port = Integer.parseInt(args[1]);
        try (OutputStream o = new FileOutputStream(PID_FILE)) {
            o.write(String.valueOf(android.os.Process.myPid()).getBytes(StandardCharsets.US_ASCII));
        } catch (Exception ignored) {
        }
        Class<?> im;
        try {
            // Android 14 moved the injector to InputManagerGlobal.
            im = Class.forName("android.hardware.input.InputManagerGlobal");
        } catch (ClassNotFoundException e) {
            im = Class.forName("android.hardware.input.InputManager");
        }
        inputManager = im.getDeclaredMethod("getInstance").invoke(null);
        inject = im.getMethod("injectInputEvent", InputEvent.class, int.class);

        try (ServerSocket server = new ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))) {
            while (true) {
                Socket c = server.accept();
                Thread t = new Thread(() -> serve(c, token), "remote-client");
                t.setDaemon(true);
                t.start();
            }
        }
    }

    private static void serve(Socket c, String token) {
        try (Socket s = c) {
            s.setTcpNoDelay(true);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = s.getOutputStream();
            if (!token.equals(in.readLine())) {
                out.write("denied\n".getBytes(StandardCharsets.US_ASCII));
                return;
            }
            out.write("ok\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) continue;
                char op = line.charAt(0);
                String arg = line.length() > 2 ? line.substring(2) : "";
                try {
                switch (op) {
                    case 'k': tap(Integer.parseInt(arg)); break;
                    case 'd': key(KeyEvent.ACTION_DOWN, Integer.parseInt(arg), SystemClock.uptimeMillis(), 0, 0); break;
                    case 'u': key(KeyEvent.ACTION_UP, Integer.parseInt(arg), SystemClock.uptimeMillis(), 0, 0); break;
                    case 'l': longPress(Integer.parseInt(arg)); break;
                    case 't': type(new String(Base64.decode(arg, Base64.DEFAULT), StandardCharsets.UTF_8)); break;
                    case 'p': out.write("pong\n".getBytes(StandardCharsets.US_ASCII)); out.flush(); break;
                    default: break;
                }
                } catch (Throwable e) {
                    // Report it: the launcher logs it, and a silent failure here looks like a dead remote.
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    out.write(("err " + cause + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        } catch (Exception ignored) {
            // A dropped connection; the launcher reconnects.
        }
    }

    private static void tap(int code) throws Exception {
        long down = SystemClock.uptimeMillis();
        key(KeyEvent.ACTION_DOWN, code, down, 0, 0);
        key(KeyEvent.ACTION_UP, code, down, 0, 0);
    }

    /** Held like a finger on the remote: the repeat with FLAG_LONG_PRESS is what apps listen for. */
    private static void longPress(int code) throws Exception {
        long down = SystemClock.uptimeMillis();
        key(KeyEvent.ACTION_DOWN, code, down, 0, 0);
        Thread.sleep(550);
        key(KeyEvent.ACTION_DOWN, code, down, 1, KeyEvent.FLAG_LONG_PRESS);
        Thread.sleep(80);
        key(KeyEvent.ACTION_UP, code, down, 0, 0);
    }

    private static void type(String text) throws Exception {
        KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
        if (events == null) return;   // characters the virtual keyboard map cannot produce
        for (KeyEvent e : events) {
            inject.invoke(inputManager, KeyEvent.changeTimeRepeat(e, SystemClock.uptimeMillis(), 0), 0);
        }
    }

    private static void key(int action, int code, long downTime, int repeat, int flags) throws Exception {
        KeyEvent e = new KeyEvent(downTime, SystemClock.uptimeMillis(), action, code, repeat, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_KEYBOARD);
        // INJECT_INPUT_EVENT_MODE_ASYNC; false means the input system refused the event.
        if (!Boolean.TRUE.equals(inject.invoke(inputManager, e, 0))) throw new IllegalStateException("injection refused");
    }
}
