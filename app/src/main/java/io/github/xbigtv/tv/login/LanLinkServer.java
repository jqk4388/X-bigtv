package io.github.xbigtv.tv.login;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Random;

/**
 * 手机扫码登录用的局域网桥。
 *
 * 电视上起一个极简 HTTP 服务，手机上打开二维码里的地址即可：
 *  - 实时看到电视上 WebView 的画面（JPEG 轮询）
 *  - 点画面 = 点电视（接管点击），所以验证码/二次验证都能用
 *  - 下方的输入框可把账号密码/验证码直接填进电视里获得焦点的输入框
 *  - 登录成功后页面会自动提示，电视端随即进入主界面
 */
public final class LanLinkServer {

    public interface Controller {
        byte[] snapshotJpeg(int maxWidth, int quality);

        void tap(float nx, float ny);

        void sendKey(String name);

        boolean typeText(String text);

        /** 手机上粘贴 Cookie 直接登录电视；返回 "ok" 或错误文案。 */
        String loginWithCookie(String authToken, String ct0, String twid);

        String currentUrl();

        boolean loggedIn();

        void refreshPage();
    }

    private final Controller controller;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 固定端口：二维码/地址不再变化，方便调试。 */
    public static final int FIXED_PORT = 18930;

    private ServerSocket server;
    private Thread acceptThread;
    private volatile boolean running;
    private int port;

    public LanLinkServer(Controller controller) {
        this.controller = controller;
    }

    public String key() {
        return "";
    }

    public int port() {
        return port;
    }

    public String url() {
        String ip = lanIp();
        if (ip == null) {
            return null;
        }
        return "http://" + ip + ":" + port + "/x/";
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("0.0.0.0", FIXED_PORT));
            port = FIXED_PORT;
            running = true;
            acceptThread = new Thread(this::acceptLoop, "x-lan-link");
            acceptThread.setDaemon(true);
            acceptThread.start();
        } catch (IOException e) {
            running = false;
        }
    }

    public synchronized void stop() {
        running = false;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
            server = null;
        }
    }

    private static String randomKey() {
        String chars = "abcdefghijkmnpqrstuvwxyz23456789";
        Random r = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(chars.charAt(r.nextInt(chars.length())));
        }
        return sb.toString();
    }

    public static String lanIp() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(ifs)) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket client = server.accept();
                Thread t = new Thread(() -> handle(client), "x-lan-conn");
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
            client.setSoTimeout(8000);
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
            if (contentLength > 0 && contentLength < 64 * 1024) {
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
            String query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                query = target.substring(q + 1);
            }

            if (!authorized(path)) {
                write(out, 403, "text/plain; charset=utf-8", "forbidden".getBytes(StandardCharsets.UTF_8));
                client.close();
                return;
            }

            if (path.startsWith("/x/")) {
                byte[] html = PAGE.getBytes(StandardCharsets.UTF_8);
                write(out, 200, "text/html; charset=utf-8", html);
            } else if (path.startsWith("/f/")) {
                byte[] jpg = controller.snapshotJpeg(720, 62);
                if (jpg == null) {
                    write(out, 503, "text/plain", "no frame".getBytes(StandardCharsets.UTF_8));
                } else {
                    write(out, 200, "image/jpeg", jpg);
                }
            } else if (path.startsWith("/s/")) {
                String json = "{\"logged\":" + controller.loggedIn() + ",\"url\":\""
                        + escape(controller.currentUrl()) + "\"}";
                write(out, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
            } else if (path.startsWith("/t/") && "POST".equals(method)) {
                float nx = floatParam(body, "x");
                float ny = floatParam(body, "y");
                main.post(() -> controller.tap(nx, ny));
                write(out, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
            } else if (path.startsWith("/k/") && "POST".equals(method)) {
                String c = param(body, "c");
                main.post(() -> controller.sendKey(c));
                write(out, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
            } else if (path.startsWith("/y/") && "POST".equals(method)) {
                String text = param(body, "t");
                final boolean[] ok = new boolean[1];
                final Object lock = new Object();
                main.post(() -> {
                    synchronized (lock) {
                        ok[0] = controller.typeText(text);
                        lock.notifyAll();
                    }
                });
                synchronized (lock) {
                    try {
                        lock.wait(3000);
                    } catch (InterruptedException ignored) {
                    }
                }
                write(out, 200, "text/plain; charset=utf-8",
                        (ok[0] ? "ok" : "no-focus").getBytes(StandardCharsets.UTF_8));
            } else if (path.startsWith("/c/") && "POST".equals(method)) {
                String a = param(body, "a");
                String c0 = param(body, "c");
                String t = param(body, "t");
                String result = controller.loginWithCookie(a, c0, t);
                write(out, 200, "text/plain; charset=utf-8", result.getBytes(StandardCharsets.UTF_8));
            } else if (path.startsWith("/r/")) {
                main.post(() -> controller.refreshPage());
                write(out, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
            } else {
                write(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            }
            client.close();
        } catch (Exception ignored) {
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    private boolean authorized(String path) {
        // 固定页面：只开放下面这些入口
        return path.startsWith("/x/") || path.startsWith("/f/") || path.startsWith("/s/")
                || path.startsWith("/t/") || path.startsWith("/k/") || path.startsWith("/y/")
                || path.startsWith("/c/") || path.startsWith("/r/");
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

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(96);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') {
                    sb.setLength(len - 1);
                }
                return sb.toString();
            }
            sb.append((char) c);
            if (sb.length() > 16384) {
                return sb.toString();
            }
        }
        return sb.length() == 0 ? null : sb.toString();
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

    private static final String PAGE = "<!DOCTYPE html><html lang=\"zh-CN\"><head>"
            + "<meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no\">"
            + "<title>X 大屏 · 扫码登录</title><style>"
            + "*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}"
            + "body{margin:0;background:#000;color:#e7e9ea;font-family:-apple-system,'PingFang SC',sans-serif}"
            + ".wrap{padding:12px 12px 28px}"
            + "h1{font-size:17px;margin:4px 0 10px;font-weight:700}"
            + "h1 span{color:#1d9bf0}"
            + ".scr{position:relative;border:1px solid #2f3336;border-radius:12px;overflow:hidden;background:#111;min-height:120px}"
            + ".scr img{display:block;width:100%}"
            + ".scr .loading{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;font-size:13px;color:#71767b}"
            + ".pad{display:flex;gap:8px;margin-top:10px;flex-wrap:wrap}"
            + "button{flex:1 1 auto;min-width:72px;padding:11px 12px;font-size:15px;border-radius:10px;border:1px solid #2f3336;"
            + "background:#16181c;color:#e7e9ea;font-weight:600}"
            + "button:active{background:#1d9bf0;color:#000}"
            + ".row{display:flex;gap:8px;margin-top:10px}"
            + "input{flex:1;padding:12px;border-radius:10px;border:1px solid #2f3336;background:#16181c;color:#fff;font-size:16px}"
            + ".tip{font-size:12.5px;color:#71767b;line-height:1.6;margin-top:12px}"
            + ".ok{color:#00ba7c;font-weight:700}"
            + ".status{margin-top:10px;font-size:13px;color:#71767b;min-height:18px}"
            + ".ck{margin-top:18px;border:1px solid #2f3336;border-radius:12px;padding:12px;background:#0f1115}"
            + ".ck h2{font-size:14px;margin:0 0 8px;color:#1d9bf0}"
            + ".ck textarea{width:100%;min-height:64px;padding:10px;border-radius:10px;border:1px solid #2f3336;"
            + "background:#16181c;color:#fff;font-size:15px;resize:vertical}"
            + ".ck input{width:100%;margin-top:8px}"
            + ".ck .go{width:100%;margin-top:8px;background:#1d9bf0;color:#000;border:none}"
            + "</style></head><body><div class=\"wrap\">"
            + "<h1><span>X</span> 大屏扫码登录</h1>"
            + "<div class=\"scr\" id=\"scr\"><div class=\"loading\">正在读取电视画面…</div><img id=\"img\" alt=\"\"></div>"
            + "<div class=\"row\"><input id=\"txt\" placeholder=\"点一下电视里的输入框，再在这里打字\" autocomplete=\"off\" autocapitalize=\"off\"></div>"
            + "<div class=\"pad\"><button id=\"send\">填入电视</button>"
            + "<button id=\"enter\">回车 / 下一步</button>"
            + "<button id=\"back\">退格</button>"
            + "<button id=\"reload\">刷新页面</button></div>"
            + "<div class=\"status\" id=\"st\">连接中…</div>"
            + "<div class=\"ck\"><h2>已有 Cookie？粘贴即可登录电视</h2>"
            + "<div class=\"tip\">电脑上打开 x.com 并登录 → F12 → Application（应用）→ Cookies → https://x.com，"
            + "复制 <b>auth_token</b> 的值贴到下面；ct0 可以留空，电视会自动换到。</div>"
            + "<textarea id=\"ck1\" placeholder=\"auth_token 的值（也可以整段粘：auth_token=…; ct0=…）\" "
            + "autocomplete=\"off\" autocapitalize=\"off\" spellcheck=\"false\"></textarea>"
            + "<input id=\"ck2\" placeholder=\"ct0（可选，留空自动获取）\" autocomplete=\"off\">"
            + "<button class=\"go\" id=\"ckgo\">用 Cookie 登录电视</button>"
            + "<div class=\"status\" id=\"cks\"></div></div>"
            + "<div class=\"tip\">1. 在手机上直接点上面的电视画面，等同于点电视屏幕（验证码、二次验证都能操作）。<br>"
            + "2. 也可以在电视上按「菜单」呼出扫码卡片，或用遥控器在电视上直接输入。<br>"
            + "3. 登录成功后电视会自动进入主界面，本页面可以关闭。</div>"
            + "</div><script>"
            + "var K='';"
            + "var img=document.getElementById('img');"
            + "function frame(){var i=new Image();i.onload=function(){img.src=i.src;var l=document.querySelector('.loading');if(l)l.remove();};"
            + "i.onerror=function(){};i.src='/f/'+K+'?t='+Date.now();}"
            + "setInterval(frame,900);frame();"
            + "img.addEventListener('click',function(e){var r=img.getBoundingClientRect();"
            + "var x=(e.clientX-r.left)/r.width,y=(e.clientY-r.top)/r.height;"
            + "fetch('/t/'+K,{method:'POST',headers:{'content-type':'application/x-www-form-urlencoded'},"
            + "body:'x='+x.toFixed(4)+'&y='+y.toFixed(4)});});"
            + "function post(p,b){return fetch(p+K,{method:'POST',headers:{'content-type':'application/x-www-form-urlencoded'},body:b});}"
            + "document.getElementById('send').onclick=function(){var v=document.getElementById('txt').value;if(!v)return;"
            + "post('/y/', 't='+encodeURIComponent(v)).then(function(r){return r.text()}).then(function(t){"
            + "document.getElementById('st').textContent=(t==='ok'?'已填入电视':'电视上先点一下输入框再填');"
            + "if(t==='ok')document.getElementById('txt').value='';});};"
            + "document.getElementById('enter').onclick=function(){post('/k/','c=enter');};"
            + "document.getElementById('back').onclick=function(){post('/k/','c=backspace');};"
            + "document.getElementById('reload').onclick=function(){fetch('/r/'+K);};"
            + "document.getElementById('ckgo').onclick=function(){"
            + "var a=document.getElementById('ck1').value.trim();"
            + "var c=document.getElementById('ck2').value.trim();"
            + "var s=document.getElementById('cks');"
            + "if(!a){s.textContent='先粘贴 auth_token';return;}"
            + "s.textContent='正在用这个 Cookie 登录电视，请稍候…';"
            + "document.getElementById('ckgo').disabled=true;"
            + "fetch('/c/'+K,{method:'POST',headers:{'content-type':'application/x-www-form-urlencoded'},"
            + "body:'a='+encodeURIComponent(a)+'&c='+encodeURIComponent(c)}).then(function(r){return r.text();})"
            + ".then(function(t){document.getElementById('ckgo').disabled=false;"
            + "if(t==='ok'){s.innerHTML='<span class=\"ok\">✔ 登录成功，电视即将进入主界面</span>';}"
            + "else{s.textContent='失败：'+t;}}).catch(function(e){"
            + "document.getElementById('ckgo').disabled=false;s.textContent='请求失败：'+e;});};"
            + "setInterval(function(){fetch('/s/'+K).then(function(r){return r.json()}).then(function(j){"
            + "if(j.logged){document.getElementById('st').innerHTML='<span class=\"ok\">✔ 已登录，电视即将进入主界面</span>';}"
            + "else{document.getElementById('st').textContent='等待登录 · 当前页面 '+(j.url||'').slice(0,42);} }).catch(function(){});},1800);"
            + "</script></body></html>";
}
