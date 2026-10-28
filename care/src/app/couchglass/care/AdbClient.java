package app.couchglass.care;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Minimal ADB client that talks to the TV's own adbd on 127.0.0.1:5555.
 * Gives the app the same powers as `adb shell` (force-stop, svc power, dumpsys).
 * The first connection makes the TV show "Allow debugging?" for this app's key.
 */
public class AdbClient {
    private static final int A_SYNC = 0x434e5953;
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int A_AUTH = 0x48545541;

    private static final int A_VERSION = 0x01000001;
    private static final int MAX_DATA = 256 * 1024;
    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    /** PKCS#1 v1.5 type-1 padding plus the SHA-1 DigestInfo prefix, for a 2048-bit key. */
    private static final byte[] SIGNATURE_PADDING;
    static {
        byte[] digestInfo = {
                0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        };
        int total = 256 - 20; // token (SHA-1 sized) fills the rest
        SIGNATURE_PADDING = new byte[total];
        SIGNATURE_PADDING[0] = 0x00;
        SIGNATURE_PADDING[1] = 0x01;
        int ffEnd = total - digestInfo.length - 1;
        for (int i = 2; i < ffEnd; i++) SIGNATURE_PADDING[i] = (byte) 0xff;
        SIGNATURE_PADDING[ffEnd] = 0x00;
        System.arraycopy(digestInfo, 0, SIGNATURE_PADDING, ffEnd + 1, digestInfo.length);
    }

    public static class AuthPendingException extends IOException {
        AuthPendingException() {
            super("Waiting for you to allow debugging on the TV");
        }
    }

    private final Context context;
    private final String host;
    private final int port;
    private KeyPair keyPair;
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private int nextLocalId = 1;

    public AdbClient(Context context, String host, int port) {
        this.context = context.getApplicationContext();
        this.host = host;
        this.port = port;
    }

    public synchronized boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    /**
     * Connects and authenticates. If the TV has not yet trusted this app's key,
     * sends the public key (TV shows the popup) and waits up to waitForUserMs.
     */
    public synchronized void connect(int waitForUserMs) throws IOException {
        if (isConnected()) return;
        ensureKeys();
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 3000);
        s.setTcpNoDelay(true);
        socket = s;
        in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();
        try {
            s.setSoTimeout(5000);
            send(A_CNXN, A_VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
            boolean sentSignature = false;
            while (true) {
                Message m = read();
                if (m.command == A_CNXN) break;
                if (m.command != A_AUTH || m.arg0 != AUTH_TOKEN) {
                    throw new IOException("Unexpected reply from TV debugging: " + Integer.toHexString(m.command));
                }
                if (!sentSignature) {
                    send(A_AUTH, AUTH_SIGNATURE, 0, sign(m.data));
                    sentSignature = true;
                } else {
                    // Key not trusted yet. Background work (wait 0) never pops the prompt.
                    if (waitForUserMs <= 0) throw new AuthPendingException();
                    // Offer the key so the TV shows "Allow debugging?".
                    send(A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBytes());
                    s.setSoTimeout(Math.max(waitForUserMs, 1000));
                    try {
                        Message r = read();
                        if (r.command != A_CNXN) throw new IOException("TV refused the connection");
                    } catch (java.net.SocketTimeoutException e) {
                        throw new AuthPendingException();
                    }
                    break;
                }
            }
            s.setSoTimeout(0);
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    /** Runs a shell command and returns its output (stdout and stderr). */
    public synchronized String shell(String command) throws IOException {
        if (!isConnected()) throw new IOException("Not connected");
        int localId = nextLocalId++;
        int remoteId = 0;
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try {
            socket.setSoTimeout(30000);
            send(A_OPEN, localId, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
            while (true) {
                Message m = read();
                if (m.arg1 != localId && m.command != A_CNXN) continue; // stale packet from an older stream
                if (m.command == A_OKAY) {
                    remoteId = m.arg0;
                } else if (m.command == A_WRTE) {
                    remoteId = m.arg0;
                    result.write(m.data);
                    send(A_OKAY, localId, remoteId, null);
                } else if (m.command == A_CLSE) {
                    if (remoteId != 0) send(A_CLSE, localId, remoteId, null);
                    break;
                }
            }
            socket.setSoTimeout(0);
        } catch (IOException e) {
            close();
            throw e;
        }
        return result.toString("UTF-8").replace("\r", "");
    }

    public synchronized void close() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
        in = null;
        out = null;
    }

    // ---- wire format ----

    private static class Message {
        int command, arg0, arg1;
        byte[] data;
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        int sum = 0;
        if (data != null) for (byte b : data) sum += b & 0xff;
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(~command);
        out.write(header.array());
        if (len > 0) out.write(data);
        out.flush();
    }

    private Message read() throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer header = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = header.getInt();
        m.arg0 = header.getInt();
        m.arg1 = header.getInt();
        int len = header.getInt();
        header.getInt(); // checksum
        int magic = header.getInt();
        if (magic != ~m.command) throw new IOException("Corrupt packet from TV debugging");
        if (len < 0 || len > MAX_DATA * 4) throw new IOException("Packet too large");
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    // ---- keys ----

    private void ensureKeys() throws IOException {
        if (keyPair != null) return;
        File priv = new File(context.getFilesDir(), "adbkey");
        File pub = new File(context.getFilesDir(), "adbkey.pub.der");
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            if (priv.exists() && pub.exists()) {
                PrivateKey pk = kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(priv)));
                RSAPublicKey pubKey = (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(readAll(pub)));
                keyPair = new KeyPair(pubKey, pk);
                return;
            }
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            keyPair = gen.generateKeyPair();
            writeAll(priv, keyPair.getPrivate().getEncoded());
            writeAll(pub, keyPair.getPublic().getEncoded());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not create the app's debugging key: " + e.getMessage());
        }
    }

    private byte[] sign(byte[] token) throws IOException {
        try {
            Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, keyPair.getPrivate());
            c.update(SIGNATURE_PADDING);
            return c.doFinal(token);
        } catch (Exception e) {
            throw new IOException("Signing failed: " + e.getMessage());
        }
    }

    /** Android's adb public key format: base64(RSAPublicKey struct) + " name\0". */
    private byte[] publicKeyBytes() {
        RSAPublicKey key = (RSAPublicKey) keyPair.getPublic();
        int words = 2048 / 32;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n = key.getModulus();
        BigInteger r = BigInteger.ONE.shiftLeft(2048);
        BigInteger rr = r.multiply(r).mod(n);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);

        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.intValue());
        BigInteger tmp = n;
        for (int i = 0; i < words; i++) {
            b.putInt(tmp.mod(r32).intValue());
            tmp = tmp.shiftRight(32);
        }
        tmp = rr;
        for (int i = 0; i < words; i++) {
            b.putInt(tmp.mod(r32).intValue());
            tmp = tmp.shiftRight(32);
        }
        b.putInt(key.getPublicExponent().intValue());
        String encoded = Base64.encodeToString(b.array(), Base64.NO_WRAP);
        return (encoded + " Couchglass Care@tv\0").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream is = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(data);
        }
    }
}
