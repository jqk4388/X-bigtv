package io.github.xbigtv.tv.net;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Random;

import io.github.xbigtv.tv.login.LanLinkServer;

/**
 * 手机扫码填代理：电视上起一个极简表单服务，手机扫码后在页面上填
 * 类型/地址/端口/用户名/密码，提交即写回电视的代理设置。
 * 省得用遥控器一个个字符敲。
 */
public final class LanProxyFormServer {

    public interface Controller {
        /** 返回 "ok" 或错误文案。 */
        String applyProxy(int type, String host, int port, String user, String pass);
    }

    private final Controller controller;
    private ServerSocket server;
    private Thread acceptThread;
    private volatile boolean running;
    private int port;
    private String key;

    public LanProxyFormServer(Controller controller) {
        this.controller = controller;
    }

    public int port() {
        return port;
    }

    public String url() {
        String ip = LanLinkServer.lanIp();
        if (ip == null || !running) {
            return null;
        }
        return "http://" + ip + ":" + port + "/p/" + key;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        key = randomKey();
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("0.0.0.0", 0));
            port = server.getLocalPort();
            running = true;
            acceptThread = new Thread(this::acceptLoop, "x-proxy-form");
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

    private void acceptLoop() {
        while (running) {
            try {
                final Socket client = server.accept();
                Thread t = new Thread(() -> handle(client), "x-proxy-form-conn");
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
            if (contentLength > 0 && contentLength < 16 * 1024) {
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
            if (!path.endsWith("/" + key)) {
                write(out, 403, "text/plain; charset=utf-8", "forbidden".getBytes(StandardCharsets.UTF_8));
                client.close();
                return;
            }

            if (path.startsWith("/p/")) {
                write(out, 200, "text/html; charset=utf-8", PAGE.getBytes(StandardCharsets.UTF_8));
            } else if (path.startsWith("/c/") && "POST".equals(method)) {
                int type = "socks".equalsIgnoreCase(param(body, "type")) ? 1 : 0;
                String host = param(body, "host").trim();
                int portValue = 0;
                try {
                    portValue = Integer.parseInt(param(body, "port").trim());
                } catch (Exception ignored) {
                }
                String result = controller.applyProxy(type, host, portValue,
                        param(body, "user"), param(body, "pass"));
                write(out, 200, "text/plain; charset=utf-8", result.getBytes(StandardCharsets.UTF_8));
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
            if (sb.length() > 8192) {
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
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>代理设置 · X 大屏</title><style>"
            + "*{box-sizing:border-box}body{margin:0;background:#000;color:#e7e9ea;"
            + "font-family:-apple-system,'PingFang SC',sans-serif}"
            + ".w{padding:16px}h1{font-size:17px;margin:4px 0 12px}h1 span{color:#1d9bf0}"
            + "label{display:block;font-size:13px;color:#71767b;margin:12px 0 6px}"
            + "input,select{width:100%;padding:12px;border-radius:10px;border:1px solid #2f3336;"
            + "background:#16181c;color:#fff;font-size:16px}"
            + "button{width:100%;margin-top:18px;padding:14px;border:none;border-radius:10px;"
            + "background:#1d9bf0;color:#000;font-size:16px;font-weight:700}"
            + ".st{margin-top:12px;font-size:13px;color:#71767b;min-height:18px}"
            + ".ok{color:#00ba7c;font-weight:700}"
            + ".tip{font-size:12.5px;color:#71767b;line-height:1.6;margin-top:14px}"
            + "</style></head><body><div class=\"w\">"
            + "<h1><span>X 大屏</span> · 代理设置</h1>"
            + "<label>类型</label><select id=\"type\"><option value=\"http\">HTTP / HTTPS</option>"
            + "<option value=\"socks\">SOCKS5</option></select>"
            + "<label>服务器地址（跑代理的电脑/路由 IP）</label>"
            + "<input id=\"host\" placeholder=\"192.168.1.48\" autocomplete=\"off\">"
            + "<label>端口</label><input id=\"port\" placeholder=\"7890\" inputmode=\"numeric\" autocomplete=\"off\">"
            + "<label>用户名（可选）</label><input id=\"user\" autocomplete=\"off\">"
            + "<label>密码（可选）</label><input id=\"pass\" type=\"password\" autocomplete=\"off\">"
            + "<button id=\"go\">保存到电视</button>"
            + "<div class=\"st\" id=\"st\"></div>"
            + "<div class=\"tip\">填完点保存，电视会立刻应用并退出本页。代理只作用于这个 App，"
            + "不影响电视系统代理。</div></div><script>"
            + "var K=location.pathname.split('/').pop();"
            + "document.getElementById('go').onclick=function(){"
            + "var s=document.getElementById('st');"
            + "var b='type='+encodeURIComponent(document.getElementById('type').value)"
            + "+'&host='+encodeURIComponent(document.getElementById('host').value.trim())"
            + "+'&port='+encodeURIComponent(document.getElementById('port').value.trim())"
            + "+'&user='+encodeURIComponent(document.getElementById('user').value)"
            + "+'&pass='+encodeURIComponent(document.getElementById('pass').value);"
            + "s.textContent='正在保存…';"
            + "fetch('/c/'+K,{method:'POST',headers:{'content-type':'application/x-www-form-urlencoded'},body:b})"
            + ".then(function(r){return r.text()}).then(function(t){"
            + "if(t==='ok'){s.innerHTML='<span class=\"ok\">✔ 已保存，电视代理已生效</span>';}"
            + "else{s.textContent='失败：'+t;}}).catch(function(e){s.textContent='请求失败：'+e;});};"
            + "</script></body></html>";
}
