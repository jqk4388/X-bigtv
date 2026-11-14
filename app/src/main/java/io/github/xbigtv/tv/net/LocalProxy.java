package io.github.xbigtv.tv.net;

import android.text.TextUtils;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import io.github.xbigtv.tv.data.Prefs;

/**
 * 本机 127.0.0.1 上的一个小型正向代理（HTTP CONNECT 隧道）。
 *
 * 为什么要它：
 *  1) WebView 的系统代理只能填 HTTP 代理，无法配 SOCKS；把上游 SOCKS/HTTP 统一收口到本地，
 *     WebView 和 OkHttp 都只认 127.0.0.1，行为完全一致。
 *  2) 上游代理的账号密码、DNS 远端解析（createUnresolved）都在这里一次做对，
 *     避免 DNS 污染影响 x.com 解析。
 */
public final class LocalProxy {

    private static LocalProxy sInstance;

    private ServerSocket server;
    private Thread acceptThread;
    private volatile boolean running;
    private int port;

    public static synchronized LocalProxy get() {
        if (sInstance == null) {
            sInstance = new LocalProxy();
        }
        return sInstance;
    }

    public static synchronized void restart() {
        LocalProxy lp = get();
        lp.stop();
        if (Prefs.proxyConfigured()) {
            try {
                lp.start();
            } catch (IOException ignored) {
                // 端口占用等情况：调用方会回退到直连
            }
        }
    }

    public int port() {
        return port;
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        port = server.getLocalPort();
        running = true;
        acceptThread = new Thread(this::acceptLoop, "x-local-proxy");
        acceptThread.setDaemon(true);
        acceptThread.start();
        android.util.Log.i("XBigTV", "LocalProxy listening 127.0.0.1:" + port
                + " upstream=" + Prefs.proxyHost() + ":" + Prefs.proxyPort()
                + " type=" + (Prefs.proxyType() == Prefs.PROXY_HTTP ? "HTTP" : "SOCKS5"));
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

    private void acceptLoop() {
        while (running) {
            try {
                final Socket client = server.accept();
                Thread t = new Thread(() -> handle(client), "x-local-proxy-conn");
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
        Socket upstream = null;
        String firstLine = "";
        try {
            client.setSoTimeout(30000);
            BufferedInputStream in = new BufferedInputStream(client.getInputStream(), 16384);
            OutputStream out = client.getOutputStream();

            String requestLine = readLine(in);
            firstLine = requestLine == null ? "<null>" : requestLine;
            android.util.Log.i("XBigTV", "proxy conn: " + firstLine);
            if (requestLine == null || requestLine.isEmpty()) {
                close(client);
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                close(client);
                return;
            }
            String method = parts[0];
            String target = parts[1];

            // 读掉剩余请求头
            String host = null;
            String line;
            int guard = 0;
            while ((line = readLine(in)) != null && !line.isEmpty() && guard++ < 200) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    String name = line.substring(0, idx).trim();
                    String value = line.substring(idx + 1).trim();
                    if ("host".equalsIgnoreCase(name)) {
                        host = value;
                    }
                }
            }

            boolean connect = "CONNECT".equalsIgnoreCase(method);
            String hostPort = connect ? target : host;
            if (hostPort == null) {
                writeShort(out, "HTTP/1.1 400 Bad Request\r\n\r\n");
                close(client);
                return;
            }
            if (hostPort.indexOf(':') < 0) {
                hostPort = hostPort + (connect ? ":443" : ":80");
            }

            upstream = openUpstream(hostPort);
            if (upstream == null) {
                android.util.Log.w("XBigTV", "upstream connect failed: " + hostPort
                        + " via " + Prefs.proxyHost() + ":" + Prefs.proxyPort());
                writeShort(out, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n");
                close(client);
                return;
            }
            android.util.Log.i("XBigTV", "tunnel " + method + " " + hostPort + " ok");

            if (connect) {
                writeShort(out, "HTTP/1.1 200 Connection Established\r\n\r\n");
            } else {
                // 普通 HTTP 请求：改写成 origin-form 转发
                String path = target;
                if (path.startsWith("http://") || path.startsWith("https://")) {
                    int slash = path.indexOf('/', path.indexOf("//") + 2);
                    path = slash > 0 ? path.substring(slash) : "/";
                }
                StringBuilder sb = new StringBuilder();
                sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
                sb.append("Host: ").append(hostPort).append("\r\n");
                sb.append("Connection: close\r\n\r\n");
                upstream.getOutputStream().write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
                upstream.getOutputStream().flush();
            }

            // 隧道必须双向并发泵送：单向 pump 要等读到 EOF 才会返回，
            // 只做一个方向会导致服务端回来的数据一直卡在缓冲区（表现为页面一直白屏）。
            final Socket up = upstream;
            final OutputStream clientOut = out;
            final InputStream upIn = upstream.getInputStream();
            Thread back = new Thread(() -> {
                pump(upIn, clientOut);
                close(up);
                close(client);
            }, "x-proxy-back");
            back.setDaemon(true);
            back.start();

            pump(in, upstream.getOutputStream());
            close(upstream);
            close(client);
        } catch (Exception e) {
            android.util.Log.w("XBigTV", "proxy conn failed (" + firstLine + "): " + e);
        } finally {
            close(upstream);
            close(client);
        }
    }

    private Socket openUpstream(String hostPort) throws IOException {
        int idx = hostPort.lastIndexOf(':');
        String host = hostPort.substring(0, idx);
        int port = Integer.parseInt(hostPort.substring(idx + 1));

        InetSocketAddress remote = InetSocketAddress.createUnresolved(host, port);
        int type = Prefs.proxyType();
        String phost = Prefs.proxyHost();
        int pport = Prefs.proxyPort();
        if (TextUtils.isEmpty(phost) || pport <= 0) {
            return null;
        }

        if (type == Prefs.PROXY_SOCKS) {
            if (!TextUtils.isEmpty(Prefs.proxyUser())) {
                java.net.Authenticator.setDefault(new java.net.Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(Prefs.proxyUser(), Prefs.proxyPass().toCharArray());
                    }
                });
            }
            // 注意：Android 的 Socket.connect() 不接受未解析地址（会抛 UnknownHostException），
            // 所以代理服务器本身要在这里解析；被访问的目标 host 仍然保持 unresolved，交给 SOCKS 远端解析。
            InetSocketAddress proxyAddr = new InetSocketAddress(phost, pport);
            if (proxyAddr.isUnresolved()) {
                return null;
            }
            Proxy p = new Proxy(Proxy.Type.SOCKS, proxyAddr);
            Socket s = new Socket(p);
            s.connect(remote, 20000);
            return s;
        }

        // 上游 HTTP 代理：直接发 CONNECT
        InetSocketAddress proxyAddr = new InetSocketAddress(phost, pport);
        if (proxyAddr.isUnresolved()) {
            android.util.Log.w("XBigTV", "cannot resolve upstream proxy host: " + phost);
            return null;
        }
        Socket s = new Socket();
        s.connect(proxyAddr, 20000);
        StringBuilder req = new StringBuilder();
        req.append("CONNECT ").append(hostPort).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(hostPort).append("\r\n");
        req.append("Proxy-Connection: keep-alive\r\n");
        if (!TextUtils.isEmpty(Prefs.proxyUser())) {
            String raw = Prefs.proxyUser() + ":" + Prefs.proxyPass();
            String enc = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
            req.append("Proxy-Authorization: Basic ").append(enc).append("\r\n");
        }
        req.append("\r\n");
        s.setSoTimeout(20000);
        OutputStream uout = s.getOutputStream();
        uout.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
        uout.flush();

        BufferedInputStream uin = new BufferedInputStream(s.getInputStream(), 8192);
        String status = readLine(uin);
        if (status == null || !status.contains(" 200")) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            return null;
        }
        String h;
        int guard = 0;
        while ((h = readLine(uin)) != null && !h.isEmpty() && guard++ < 200) {
            // 丢弃代理响应头
        }
        return s;
    }

    private static void pump(InputStream in, OutputStream out) {
        byte[] buf = new byte[16384];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (SocketTimeoutException ignored) {
        } catch (IOException ignored) {
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(64);
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

    private static void writeShort(OutputStream out, String s) {
        try {
            out.write(s.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private static void close(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
