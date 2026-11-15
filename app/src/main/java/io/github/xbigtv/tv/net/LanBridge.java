package io.github.xbigtv.tv.net;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.xbigtv.tv.data.Prefs;
import io.github.xbigtv.tv.login.LanLinkServer;
import io.github.xbigtv.tv.xapi.Str;
import io.github.xbigtv.tv.xapi.XApi;
import io.github.xbigtv.tv.xapi.XSession;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 一个页面搞定的局域网桥（固定 http://<电视IP>:18930/x/）：
 *  - 手机扫码后可以看/操作电视画面（登录页 WebView 还在时）
 *  - 粘贴 auth_token 直接登录
 *  - 设置代理（HTTP/SOCKS5）
 *  - 设置播放偏好（清晰度 / 连播 / 画面 / 倍速）
 * 不用扫两次码，也不再有随机后缀。
 */
public final class LanBridge {

    private static final String TAG = "XBigTV";
    public static final int PORT = LanLinkServer.FIXED_PORT;

    /** 登录页 WebView 的镜像能力（登录页销毁后自动失效）。 */
    public interface Mirror {
        byte[] snapshotJpeg(int maxWidth, int quality);

        void tap(float nx, float ny);

        void sendKey(String name);

        boolean typeText(String text);

        String currentUrl();

        void refreshPage();
    }

    /** 登录成功回调（由登录页设置）。 */
    public interface OnLoggedIn {
        void onLoggedIn();
    }

    private static LanBridge sInstance;

    public static synchronized LanBridge get() {
        if (sInstance == null) {
            sInstance = new LanBridge();
        }
        return sInstance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Mirror mirror;
    private volatile OnLoggedIn onLoggedIn;
    private ServerSocket server;
    private Thread acceptThread;
    private volatile boolean running;
    private int port;

    public synchronized void ensureStarted() {
        if (running) {
            return;
        }
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("0.0.0.0", PORT));
            port = PORT;
            running = true;
            acceptThread = new Thread(this::acceptLoop, "x-lan-bridge");
            acceptThread.setDaemon(true);
            acceptThread.start();
            Log.i(TAG, "LanBridge listening " + PORT);
        } catch (IOException e) {
            running = false;
            Log.w(TAG, "LanBridge start failed: " + e);
        }
    }

    public int port() {
        return port > 0 ? port : PORT;
    }

    public String url() {
        String ip = LanLinkServer.lanIp();
        return ip == null ? null : "http://" + ip + ":" + port() + "/x/";
    }

    public void setMirror(Mirror m) {
        this.mirror = m;
    }

    public void clearMirror(Mirror m) {
        if (this.mirror == m) {
            this.mirror = null;
        }
    }

    public void setOnLoggedIn(OnLoggedIn cb) {
        this.onLoggedIn = cb;
    }

    /* ---------------- HTTP ---------------- */

    private void acceptLoop() {
        while (running) {
            try {
                final Socket client = server.accept();
                Thread t = new Thread(() -> handle(client), "x-lan-bridge-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!running) {
                    return;
                }
            }
        }
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(15000);
            BufferedInputStream in = new BufferedInputStream(client.getInputStream(), 8192);
            OutputStream out = client.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null) {
                client.close();
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                client.close();
                return;
            }
            String method = parts[0].toUpperCase(Locale.US);
            String target = parts[1];

            int contentLength = 0;
            String line;
            int guard = 0;
            while ((line = readLine(in)) != null && !line.isEmpty() && guard++ < 100) {
                int idx = line.indexOf(':');
                if (idx > 0 && "content-length".equalsIgnoreCase(line.substring(0, idx).trim())) {
                    try {
                        contentLength = Integer.parseInt(line.substring(idx + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            String body = "";
            if (contentLength > 0 && contentLength < 32 * 1024) {
                byte[] buf = new byte[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int n = in.read(buf, read, contentLength - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
                body = new String(buf, 0, read, StandardCharsets.UTF_8);
            }

            String path = target;
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
            }

            if (path.startsWith("/x/")) {
                write(out, 200, "text/html; charset=utf-8", page().getBytes(StandardCharsets.UTF_8));
            } else if (path.startsWith("/st/")) {
                write(out, 200, "application/json; charset=utf-8",
                        statusJson().getBytes(StandardCharsets.UTF_8));
            } else if (path.startsWith("/f/")) {
                Mirror m = mirror;
                byte[] jpg = m == null ? null : m.snapshotJpeg(720, 62);
                if (jpg == null) {
                    write(out, 503, "text/plain", "no-mirror".getBytes(StandardCharsets.UTF_8));
                } else {
                    write(out, 200, "image/jpeg", jpg);
                }
            } else if (path.startsWith("/t/") && "POST".equals(method)) {
                Mirror m = mirror;
                final float nx = floatParam(body, "x");
                final float ny = floatParam(body, "y");
                if (m != null) {
                    main.post(() -> m.tap(nx, ny));
                }
                write(out, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
            } else if (path.startsWith("/k/") && "POST".equals(method)) {
                Mirror m = mirror;
                final String c = param(body, "c");
                if (m != null) {
                    main.post(() -> m.sendKey(c));
                }
                write(out, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
            } else if (path.startsWith("/y/") && "POST".equals(method)) {
                Mirror m = mirror;
                final String text = param(body, "t");
                boolean ok = false;
                if (m != null) {
                    final boolean[] res = new boolean[1];
                    final CountDownLatch latch = new CountDownLatch(1);
                    main.post(() -> {
                        try {
                            res[0] = m.typeText(text);
                        } catch (Throwable ignored) {
                        } finally {
                            latch.countDown();
                        }
                    });
                    try {
                        latch.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                    }
                    ok = res[0];
                }
                write(out, 200, "text/plain; charset=utf-8",
                        (ok ? "ok" : "no-focus").getBytes(StandardCharsets.UTF_8));
            } else if (path.startsWith("/r/")) {
                Mirror m = mirror;
                if (m != null) {
                    main.post(m::refreshPage);
                }
                write(out, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
            } else if (path.startsWith("/c/") && "POST".equals(method)) {
                String result = loginWithCookie(param(body, "a"), param(body, "c"));
                write(out, 200, "text/plain; charset=utf-8", result.getBytes(StandardCharsets.UTF_8));
            } else if (path.startsWith("/p/") && "POST".equals(method)) {
                String result = applyProxy(body);
                write(out, 200, "text/plain; charset=utf-8", result.getBytes(StandardCharsets.UTF_8));
            } else if (path.startsWith("/g/") && "POST".equals(method)) {
                String result = applyPlayback(body);
                write(out, 200, "text/plain; charset=utf-8", result.getBytes(StandardCharsets.UTF_8));
            } else {
                write(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            }
            client.close();
        } catch (Exception e) {
            Log.w(TAG, "lan bridge conn failed: " + e);
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    /* ---------------- 业务 ---------------- */

    private String loginWithCookie(String authIn, String ct0In) {
        String auth = clean(authIn, "auth_token");
        String ct0 = clean(ct0In, "ct0");
        if (auth.isEmpty()) {
            return "auth_token 不能为空";
        }
        if (ct0.isEmpty()) {
            String[] boot = XApi.bootstrap(auth);
            if (boot == null) {
                return "auth_token 无效或已过期（换不到 ct0）";
            }
            ct0 = boot[1];
            if (Str.isEmpty(XSession.twid)) {
                XSession.save(boot[0], boot[1], boot[2]);
            }
        }
        XSession.save(auth, ct0, XSession.twid);
        String err = XApi.verifySession();
        if (err != null) {
            XSession.clear();
            return "校验失败：" + err;
        }
        String[] me = XApi.viewer();
        if (me != null && !Str.isEmpty(me[0])) {
            XSession.profile(me[0], me[1], me[2]);
        }
        OnLoggedIn cb = onLoggedIn;
        if (cb != null) {
            main.post(cb::onLoggedIn);
        }
        return "ok";
    }

    private String applyProxy(String body) {
        int type = "socks".equalsIgnoreCase(param(body, "type")) ? Prefs.PROXY_SOCKS : Prefs.PROXY_HTTP;
        String host = param(body, "host").trim();
        int p = 0;
        try {
            p = Integer.parseInt(param(body, "port").trim());
        } catch (Exception ignored) {
        }
        if (host.isEmpty()) {
            return "服务器地址不能为空";
        }
        if (p <= 0 || p > 65535) {
            return "端口不合法";
        }
        Prefs.saveProxy(true, type, host, p, param(body, "user"), param(body, "pass"));
        main.post(Http::reset);
        return "ok";
    }

    private String applyPlayback(String body) {
        try {
            String quality = param(body, "quality");
            if (quality != null && !quality.isEmpty()) {
                Prefs.setQuality(Integer.parseInt(quality));
            }
            String autoNext = param(body, "autonext");
            if (autoNext != null && !autoNext.isEmpty()) {
                Prefs.setAutoNext("1".equals(autoNext));
            }
            String fill = param(body, "fill");
            if (fill != null && !fill.isEmpty()) {
                Prefs.setFillScreen("1".equals(fill));
            }
            String speed = param(body, "speed");
            if (speed != null && !speed.isEmpty()) {
                Prefs.setSpeed(Float.parseFloat(speed));
            }
            return "ok";
        } catch (Exception e) {
            return "参数不合法: " + e.getMessage();
        }
    }

    private String statusJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("logged", XSession.loggedIn());
            o.put("screen_name", XSession.screenName);
            o.put("proxy_enabled", Prefs.proxyEnabled());
            o.put("proxy_type", Prefs.proxyType() == Prefs.PROXY_HTTP ? "http" : "socks");
            o.put("proxy_host", Prefs.proxyHost());
            o.put("proxy_port", Prefs.proxyPort());
            o.put("quality", Prefs.quality());
            o.put("autonext", Prefs.autoNext());
            o.put("fill", Prefs.fillScreen());
            o.put("speed", Prefs.speed());
            Mirror m = mirror;
            o.put("webview_url", m == null ? "" : m.currentUrl());
            o.put("mirror", m != null);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /* ---------------- 工具 ---------------- */

    private static String clean(String raw, String name) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        if (s.contains(name + "=")) {
            int i = s.indexOf(name + "=");
            String rest = s.substring(i + name.length() + 1);
            int end = rest.indexOf(';');
            if (end >= 0) {
                rest = rest.substring(0, end);
            }
            s = rest.trim();
        }
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 1) {
            s = s.substring(1, s.length() - 1);
        }
        return s.replace("\n", "").replace("\r", "").replace(" ", "");
    }

    private static String param(String body, String name) {
        if (body == null) {
            return "";
        }
        for (String pair : body.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0 && name.equals(pair.substring(0, idx))) {
                try {
                    return URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                } catch (Exception e) {
                    return pair.substring(idx + 1);
                }
            }
        }
        return "";
    }

    private static float floatParam(String body, String name) {
        try {
            return Float.parseFloat(param(body, name));
        } catch (Exception e) {
            return 0f;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(96);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                byte[] b = buf.toByteArray();
                int len = b.length;
                if (len > 0 && b[len - 1] == '\r') {
                    len--;
                }
                return new String(b, 0, len, StandardCharsets.ISO_8859_1);
            }
            buf.write(c);
            if (buf.size() > 8192) {
                return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
            }
        }
        return buf.size() == 0 ? null : new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static void write(OutputStream out, int code, String type, byte[] body) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(code).append(code == 200 ? " OK" : " ERR").append("\r\n");
        head.append("Content-Type: ").append(type).append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Cache-Control: no-store\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    /** 页面放在 assets 里，避免 Java 字符串转义地狱。 */
    private String cachedPage;

    private String page() {
        if (cachedPage != null) {
            return cachedPage;
        }
        try (InputStream in = io.github.xbigtv.tv.App.ctx().getAssets().open("lan_bridge.html")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            cachedPage = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            cachedPage = "<html><body style='background:#000;color:#eee;font-family:sans-serif'>"
                    + "<p>页面资源缺失 lan_bridge.html</p></body></html>";
        }
        return cachedPage;
    }
}