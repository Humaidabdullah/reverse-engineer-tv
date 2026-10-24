package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Just enough of the ADB protocol to run one shell command on this TV through its own adbd
 * (127.0.0.1:5555), as a computer would. The phone remote uses it once per boot to start
 * InjectServer as the shell user.
 *
 * The launcher has its own RSA key. The first time it connects, the TV asks "Allow USB debugging?"
 * for it; with "Always allow" ticked it never asks again. Turning off USB debugging, or revoking the
 * authorisations, takes this away like it does for a computer.
 */
final class AdbLoopback {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000001, MAX_DATA = 256 * 1024;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    /** PKCS#1 DigestInfo for SHA-1: adbd's token is signed as if it were a SHA-1 digest. */
    private static final byte[] SHA1_INFO = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a,
            0x05, 0x00, 0x04, 0x14};

    private AdbLoopback() {
    }

    /**
     * Runs {@code cmd} in a shell on this TV and returns what it printed. Waits up to
     * {@code authTimeoutMs} for someone to accept the debugging prompt on a first connection.
     */
    static String shell(Context ctx, String cmd, int authTimeoutMs) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
            s.setSoTimeout(authTimeoutMs);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            send(out, CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.US_ASCII));
            KeyPair keys = keys(ctx);
            boolean signed = false, offeredKey = false;
            while (true) {
                Msg m = read(in);
                if (m.cmd == CNXN) {
                    android.util.Log.i("PhoneRemote", "ADB connected (" + (offeredKey ? "approved on the TV" : signed ? "known key" : "no auth") + ")");
                    break;
                }
                if (m.cmd != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("unexpected ADB message " + m.cmd);
                if (!signed) {
                    send(out, AUTH, AUTH_SIGNATURE, 0, sign(keys, m.data));
                    signed = true;
                } else if (!offeredKey) {
                    // Not authorised yet: offer the key, and the TV shows "Allow USB debugging?".
                    send(out, AUTH, AUTH_RSAPUBLICKEY, 0, publicKey(keys));
                    offeredKey = true;
                } else {
                    throw new IOException("ADB authorisation refused");
                }
            }
            s.setSoTimeout(15000);
            final int local = 1;
            send(out, OPEN, local, 0, ("shell:" + cmd + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            int remote = 0;
            while (true) {
                Msg m = read(in);
                if (m.cmd == OKAY) {
                    remote = m.arg0;
                } else if (m.cmd == WRTE) {
                    result.write(m.data);
                    send(out, OKAY, local, m.arg0, null);
                } else if (m.cmd == CLSE) {
                    if (remote != 0) send(out, CLSE, local, remote, null);
                    break;
                }
            }
            return result.toString("UTF-8");
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private static Msg read(DataInputStream in) throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        if (len < 0 || len > MAX_DATA) throw new IOException("bad ADB message length " + len);
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    private static void send(OutputStream out, int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length, sum = 0;
        for (int i = 0; i < len; i++) sum += data[i] & 0xFF;
        ByteBuffer b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(~cmd);
        out.write(b.array());
        if (len > 0) out.write(data);
        out.flush();
    }

    private static byte[] sign(KeyPair keys, byte[] token) throws GeneralSecurityException {
        // PKCS#1 v1.5 type 1 padding by hand, around the token as a SHA-1 DigestInfo, then raw RSA.
        byte[] block = new byte[256];
        block[1] = 1;
        int infoAt = block.length - token.length - SHA1_INFO.length;
        for (int i = 2; i < infoAt - 1; i++) block[i] = (byte) 0xFF;
        System.arraycopy(SHA1_INFO, 0, block, infoAt, SHA1_INFO.length);
        System.arraycopy(token, 0, block, block.length - token.length, token.length);
        Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, keys.getPrivate());
        return c.doFinal(block);
    }

    /** The public key in adbd's own format (android_pubkey_encode), base64, then a name for the prompt. */
    private static byte[] publicKey(KeyPair keys) {
        RSAPublicKey pk = (RSAPublicKey) keys.getPublic();
        BigInteger n = pk.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64);
        b.putInt(r32.subtract(n.mod(r32).modInverse(r32)).intValue());   // -1 / n[0] mod 2^32
        b.put(littleEndian(n));
        b.put(littleEndian(BigInteger.ONE.shiftLeft(4096).mod(n)));         // R^2 mod n
        b.putInt(pk.getPublicExponent().intValue());
        String name = " glassOS-remote@" + Build.MODEL.replaceAll("[^A-Za-z0-9_-]", "_");
        return (Base64.encodeToString(b.array(), Base64.NO_WRAP) + name + "\0").getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] littleEndian(BigInteger v) {
        byte[] be = v.toByteArray(), le = new byte[256];
        for (int i = 0; i < le.length && i < be.length; i++) le[i] = be[be.length - 1 - i];
        return le;
    }

    /** The launcher's ADB key, made once and kept in its private storage. */
    private static synchronized KeyPair keys(Context ctx) throws GeneralSecurityException {
        SharedPreferences sp = ctx.getSharedPreferences("adb_key", Context.MODE_PRIVATE);
        String priv = sp.getString("private", null), pub = sp.getString("public", null);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv != null && pub != null) {
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(Base64.decode(pub, Base64.NO_WRAP))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(priv, Base64.NO_WRAP))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        sp.edit().putString("private", Base64.encodeToString(kp.getPrivate().getEncoded(), Base64.NO_WRAP))
                .putString("public", Base64.encodeToString(kp.getPublic().getEncoded(), Base64.NO_WRAP)).apply();
        return kp;
    }
}
