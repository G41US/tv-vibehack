package com.gathin.tvhome;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * A tiny ADB client that talks to this TV's own debugging service (localhost:5555), so TV Home can do
 * what the setup script does from a phone: the TV shows "Allow USB debugging?" once, and with that
 * permission TV Home runs the same few settings commands itself.
 *
 * Classic ADB protocol (the one `adb connect ip:5555` uses): CNXN, AUTH with an RSA key kept in TV
 * Home's private files, then a shell stream per command.
 */
final class AdbSelf implements AutoCloseable {

    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000000, MAX_DATA = 4096;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;

    /** SHA-1 DigestInfo header: the token adbd sends is signed as if it were a SHA-1 digest. */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02,
            0x1a, 0x05, 0x00, 0x04, 0x14};

    interface Status {
        /** The TV is showing (or about to show) "Allow USB debugging?". */
        void askingOnScreen();
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private int nextLocalId = 1;

    private AdbSelf(Socket s) throws IOException {
        socket = s;
        in = s.getInputStream();
        out = s.getOutputStream();
    }

    /** Connects and authenticates, waiting up to {@code waitMs} for the person to allow it on screen. */
    static AdbSelf connect(Context ctx, int port, long waitMs, Status status) throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
        s.setSoTimeout((int) Math.max(5000, waitMs));
        AdbSelf a = new AdbSelf(s);
        try {
            a.handshake(ctx, status);
            s.setSoTimeout(15000);
            return a;
        } catch (Exception e) {
            a.close();
            throw e;
        }
    }

    /** Is anything listening on the debugging port? (Debugging over the network is on.) */
    static boolean reachable(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 1500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void handshake(Context ctx, Status status) throws Exception {
        send(CNXN, VERSION, MAX_DATA, "host::tvhome\0".getBytes(StandardCharsets.UTF_8));
        KeyPair keys = keys(ctx);
        boolean signed = false;
        while (true) {
            Msg m = read();
            if (m.cmd == CNXN) return;   // connected
            if (m.cmd != AUTH || m.arg0 != AUTH_TOKEN) continue;
            if (!signed) {
                signed = true;
                send(AUTH, AUTH_SIGNATURE, 0, sign(keys.getPrivate(), m.data));
            } else {
                // Key not known yet: send it, and the TV asks the person to allow it.
                send(AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBlob((RSAPublicKey) keys.getPublic()));
                if (status != null) status.askingOnScreen();
            }
        }
    }

    /** Runs a shell command and returns its output. */
    String shell(String command) throws IOException {
        int local = nextLocalId++;
        send(OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        int remote = 0;
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        while (true) {
            Msg m = read();
            if (m.arg1 != local && m.cmd != CNXN) continue;
            if (m.cmd == OKAY) {
                remote = m.arg0;
            } else if (m.cmd == WRTE) {
                remote = m.arg0;
                result.write(m.data);
                send(OKAY, local, remote, new byte[0]);
            } else if (m.cmd == CLSE) {
                if (remote != 0) send(CLSE, local, remote, new byte[0]);
                return result.toString("UTF-8").trim();
            }
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    // ---- wire format ------------------------------------------------------------------------------

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte x : data) sum += x & 0xff;
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd);
        b.put(data);
        out.write(b.array());
        out.flush();
    }

    private Msg read() throws IOException {
        DataInputStream d = new DataInputStream(in);
        byte[] head = new byte[24];
        d.readFully(head);
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        if (len < 0 || len > 1024 * 1024) throw new IOException("bad length " + len);
        m.data = new byte[len];
        d.readFully(m.data);
        return m;
    }

    // ---- keys -------------------------------------------------------------------------------------

    /** TV Home's own ADB key, made once and kept in its private files. */
    private static synchronized KeyPair keys(Context ctx) throws Exception {
        File dir = new File(ctx.getFilesDir(), "adb");
        File priv = new File(dir, "key"), pub = new File(dir, "key.pub");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv.isFile() && pub.isFile()) {
            try {
                return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pub.toPath()))),
                        kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(priv.toPath()))));
            } catch (Exception ignored) {
                // unreadable: make a new one
            }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        Files.write(priv.toPath(), kp.getPrivate().getEncoded());
        Files.write(pub.toPath(), kp.getPublic().getEncoded());
        return kp;
    }

    static byte[] sign(PrivateKey key, byte[] token) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, key);
        c.update(SHA1_PREFIX);
        return c.doFinal(token);
    }

    /**
     * The public key in adbd's format: Android's RSAPublicKey struct (word count, n0inv, modulus and
     * R^2 mod n as little-endian 32-bit words, exponent), base64, then a name.
     */
    static byte[] publicKeyBlob(RSAPublicKey key) {
        int words = 2048 / 32;
        BigInteger n = key.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.valueOf(2), n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.intValue());
        b.put(littleEndian(n, 256));
        b.put(littleEndian(rr, 256));
        b.putInt(key.getPublicExponent().intValue());
        String text = android.util.Base64.encodeToString(b.array(), android.util.Base64.NO_WRAP) + " tvhome@tv\0";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] littleEndian(BigInteger v, int size) {
        byte[] big = v.toByteArray();   // big-endian, maybe with a leading 0
        byte[] le = new byte[size];
        for (int i = 0; i < size && i < big.length; i++) le[i] = big[big.length - 1 - i];
        return le;
    }
}
