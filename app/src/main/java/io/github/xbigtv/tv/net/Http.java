package io.github.xbigtv.tv.net;

import android.text.TextUtils;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;

import io.github.xbigtv.tv.data.Prefs;
import okhttp3.OkHttpClient;

/**
 * 全局网络出口：所有请求（GraphQL / 图片 / 视频 / 登录 WebView）都走同一个代理设置。
 */
public final class Http {

    private static volatile OkHttpClient sClient;
    private static volatile OkHttpClient sOverride;
    private static volatile String sKey = "";

    private Http() {
    }

    /** 仅用于 JVM 单测：注入一个不走 Android 设置的客户端。 */
    public static void setOverrideClient(OkHttpClient client) {
        sOverride = client;
    }

    private static String key() {
        return Prefs.proxyEnabled() + "|" + Prefs.proxyType() + "|" + Prefs.proxyHost() + "|"
                + Prefs.proxyPort() + "|" + Prefs.proxyUser() + "|" + Prefs.proxyPass();
    }

    public static synchronized void reset() {
        sClient = null;
        sKey = "";
        LocalProxy.restart();
    }

    public static OkHttpClient client() {
        if (sOverride != null) {
            return sOverride;
        }
        String k = key();
        OkHttpClient c = sClient;
        if (c != null && k.equals(sKey)) {
            return c;
        }
        synchronized (Http.class) {
            if (sClient == null || !k.equals(sKey)) {
                sClient = build();
                sKey = k;
            }
            return sClient;
        }
    }

    private static OkHttpClient build() {
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(35, TimeUnit.SECONDS)
                .writeTimeout(25, TimeUnit.SECONDS)
                .followRedirects(true)
                .retryOnConnectionFailure(true);

        if (Prefs.proxyConfigured()) {
            LocalProxy lp = LocalProxy.get();
            if (lp.port() <= 0) {
                try {
                    lp.start();
                } catch (Exception ignored) {
                    return b.build();
                }
            }
            if (lp.port() > 0) {
                b.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", lp.port())));
            }
        }
        return b.build();
    }

    /** 给 WebView 用：返回 127.0.0.1:port，无代理返回 null。 */
    public static String localProxyAddress() {
        if (!Prefs.proxyConfigured()) {
            return null;
        }
        LocalProxy lp = LocalProxy.get();
        if (lp.port() <= 0) {
            try {
                lp.start();
            } catch (Exception e) {
                return null;
            }
        }
        return lp.port() > 0 ? ("127.0.0.1:" + lp.port()) : null;
    }

    public static boolean reachable(String host, int port) {
        if (TextUtils.isEmpty(host)) {
            return false;
        }
        try {
            java.net.Socket s = new java.net.Socket();
            s.connect(new InetSocketAddress(host, port), 4000);
            s.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
