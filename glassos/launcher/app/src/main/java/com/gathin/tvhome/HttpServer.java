package com.gathin.tvhome;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A small HTTP/1.1 server for the phone remote, with keep-alive so a run of taps reuses one connection
 * (a new TCP handshake per key would add tens of milliseconds on Wi-Fi). No framework: the launcher stays
 * small and has nothing else that needs one.
 */
final class HttpServer {
    private static final String TAG = "PhoneRemote";
    private static final int MAX_BODY = 64 * 1024, MAX_LINE = 8 * 1024;

    interface Handler {
        Response handle(Request r) throws Exception;
    }

    static final class Request {
        String method, path, query = "";
        final Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];

        String header(String name) {
            String v = headers.get(name.toLowerCase(Locale.ROOT));
            return v == null ? "" : v;
        }

        JSONObject json() throws JSONException {
            return body.length == 0 ? new JSONObject() : new JSONObject(new String(body, StandardCharsets.UTF_8));
        }
    }

    static final class Response {
        final int code;
        final String type;
        final byte[] body;
        String cache = "no-store";

        Response(int code, String type, byte[] body) {
            this.code = code;
            this.type = type;
            this.body = body;
        }

        static Response json(int code, Object json) {
            return new Response(code, "application/json; charset=utf-8", json.toString().getBytes(StandardCharsets.UTF_8));
        }

        static Response ok(Object json) {
            return json(200, json);
        }

        static Response error(int code, String message) {
            try {
                return json(code, new JSONObject().put("error", message));
            } catch (JSONException e) {
                return new Response(code, "text/plain", message.getBytes(StandardCharsets.UTF_8));
            }
        }

        Response cached(int seconds) {
            cache = "max-age=" + seconds;
            return this;
        }
    }

    private final int port;
    private final Handler handler;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> new Thread(r, "remote-http"));
    private ServerSocket server;

    HttpServer(int port, Handler handler) {
        this.port = port;
        this.handler = handler;
    }

    void start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port), 16);
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    pool.execute(() -> serve(s));
                } catch (IOException e) {
                    if (!server.isClosed()) Log.w(TAG, "accept failed", e);
                }
            }
        }, "remote-accept");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private void serve(Socket s) {
        try (Socket sock = s) {
            sock.setTcpNoDelay(true);
            sock.setSoTimeout(30_000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = new BufferedOutputStream(sock.getOutputStream());
            while (true) {
                Request r = read(in);
                if (r == null) return;
                Response res;
                try {
                    res = handler.handle(r);
                } catch (Exception e) {
                    Log.w(TAG, r.method + " " + r.path + " failed", e);
                    res = Response.error(500, String.valueOf(e.getMessage()));
                }
                boolean close = "close".equalsIgnoreCase(r.header("connection"));
                write(out, res, close);
                if (close) return;
            }
        } catch (SocketTimeoutException ignored) {
            // an idle keep-alive connection
        } catch (IOException e) {
            Log.d(TAG, "connection ended: " + e.getMessage());
        }
    }

    private static Request read(InputStream in) throws IOException {
        String first = line(in);
        if (first == null || first.isEmpty()) return null;
        String[] parts = first.split(" ");
        if (parts.length < 2) throw new IOException("bad request line");
        Request r = new Request();
        r.method = parts[0].toUpperCase(Locale.ROOT);
        String target = parts[1];
        int q = target.indexOf('?');
        r.path = q >= 0 ? target.substring(0, q) : target;
        if (q >= 0) r.query = target.substring(q + 1);
        String h;
        while ((h = line(in)) != null && !h.isEmpty()) {
            int c = h.indexOf(':');
            if (c > 0) r.headers.put(h.substring(0, c).trim().toLowerCase(Locale.ROOT), h.substring(c + 1).trim());
        }
        int len = 0;
        try {
            len = Integer.parseInt(r.header("content-length").isEmpty() ? "0" : r.header("content-length"));
        } catch (NumberFormatException ignored) {
        }
        if (len < 0 || len > MAX_BODY) throw new IOException("body too large");
        r.body = new byte[len];
        int got = 0;
        while (got < len) {
            int n = in.read(r.body, got, len - got);
            if (n < 0) throw new IOException("short body");
            got += n;
        }
        return r;
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > MAX_LINE) throw new IOException("line too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static void write(OutputStream out, Response r, boolean close) throws IOException {
        String head = "HTTP/1.1 " + r.code + " " + reason(r.code) + "\r\n"
                + "Content-Type: " + r.type + "\r\n"
                + "Content-Length: " + r.body.length + "\r\n"
                + "Cache-Control: " + r.cache + "\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Connection: " + (close ? "close" : "keep-alive") + "\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(r.body);
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 429: return "Too Many Requests";
            default: return code >= 500 ? "Server Error" : "Status";
        }
    }
}
