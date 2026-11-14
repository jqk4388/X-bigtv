package io.github.xbigtv.tv.login;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.github.xbigtv.tv.BuildConfig;
import io.github.xbigtv.tv.data.Prefs;
import io.github.xbigtv.tv.net.NetProbe;
import io.github.xbigtv.tv.net.Http;
import io.github.xbigtv.tv.ui.Icons;
import io.github.xbigtv.tv.ui.MainActivity;
import io.github.xbigtv.tv.ui.ProxyActivity;
import io.github.xbigtv.tv.ui.TvKeyboardDialog;
import io.github.xbigtv.tv.ui.Ui;
import io.github.xbigtv.tv.update.UpdateManager;
import io.github.xbigtv.tv.xapi.XApi;
import io.github.xbigtv.tv.xapi.Str;
import io.github.xbigtv.tv.xapi.XSession;

/**
 * 手机扫码登录：电视上跑 WebView 加载 x.com 官方登录页，同时开一个局域网小服务，
 * 手机扫二维码后即可看到电视画面、点按操作、并把账号密码直接填进电视的输入框。
 */
public class LoginActivity extends Activity implements LanLinkServer.Controller {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private WebView web;
    private FrameLayout webHost;
    private LanLinkServer server;
    private LinearLayout qrCard;
    private ImageView qrImage;
    private TextView urlText;
    private TextView statusText;
    private TextView proxyState;
    private TextView compatNote;
    private LinearLayout errorPanel;
    private TextView errorText;
    private TextView errorProxyBtn;
    private String appliedProxyKey = "";
    private boolean finishingLogin;
    private boolean qrVisible = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (XSession.loggedIn()) {
            goMain();
            return;
        }
        buildUi();
        startLanServer();
        if (BuildConfig.DEBUG) {
            NetProbe.runAsync();
        }
        applyProxyThenStartWeb();
        main.postDelayed(cookiePoll, 1500);
    }

    /* ---------------- 界面 ---------------- */

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BLACK);

        webHost = new FrameLayout(this);
        root.addView(webHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶部提示
        LinearLayout top = Ui.linear(this, LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(this, 26), Ui.dp(this, 14), Ui.dp(this, 26), Ui.dp(this, 14));
        top.setBackground(Ui.round(0xCC000000, 0));
        TextView t = Ui.text(this, "手机扫码登录 X", 20, Ui.TEXT, true);
        top.addView(t);
        TextView hint = Ui.text(this, "   ·   扫码后在手机上直接操作电视画面   ·   菜单键 显示/隐藏二维码",
                13, Ui.TEXT_DIM, false);
        top.addView(hint);
        View flex = new View(this);
        top.addView(flex, Ui.lp(0, 1, 1f));
        top.addView(accentChip("设置代理", v -> openProxy()));
        top.addView(accentChip("粘贴 Cookie", v -> askCookieOnTv()));
        top.addView(chip("遥控器输入", v -> {
            TvKeyboardDialog.show(this, "填入电视输入框", "", false, text -> typeText(text));
        }));
        top.addView(chip("刷新", v -> {
            hideError();
            web.reload();
        }));
        top.addView(chip("退出", v -> finish()));
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.TOP;
        root.addView(top, tlp);

        // 二维码卡片
        qrCard = Ui.linear(this, LinearLayout.HORIZONTAL);
        qrCard.setGravity(Gravity.CENTER_VERTICAL);
        qrCard.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 16));
        qrCard.setBackground(Ui.round(0xF2000000, Ui.dp(this, 16), Ui.BORDER, Math.max(1, Ui.dp(this, 1))));

        qrImage = new ImageView(this);
        int qs = Ui.dp(this, 180);
        qrImage.setLayoutParams(Ui.lp(qs, qs));
        qrImage.setBackgroundColor(Color.WHITE);
        qrCard.addView(qrImage);

        LinearLayout qrCol = Ui.linear(this, LinearLayout.VERTICAL);
        LinearLayout.LayoutParams qlp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        qlp.leftMargin = Ui.dp(this, 16);
        qrCol.setLayoutParams(qlp);

        TextView qrTitle = Ui.text(this, "① 手机扫码", 19, Ui.TEXT, true);
        qrCol.addView(qrTitle);
        TextView qrTip = Ui.text(this, "同一个 WiFi 下，用手机相机/浏览器扫码", 13, Ui.TEXT_DIM, false);
        qrTip.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        qrCol.addView(qrTip);

        urlText = Ui.text(this, "", 13, Ui.BLUE, true);
        urlText.setSingleLine(false);
        qrCol.addView(urlText);

        statusText = Ui.text(this, "② 在手机上登录 x.com，电视自动进入", 13, Ui.TEXT_DIM, false);
        statusText.setPadding(0, Ui.dp(this, 8), 0, 0);
        qrCol.addView(statusText);

        proxyState = Ui.text(this, "", 13, Ui.TEXT_DIM, false);
        proxyState.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        qrCol.addView(proxyState);

        compatNote = Ui.text(this, "", 13, Ui.AMBER, true);
        compatNote.setPadding(0, Ui.dp(this, 8), 0, 0);
        compatNote.setVisibility(View.GONE);
        qrCol.addView(compatNote);

        TextView proxyBtn = chip("网络打不开？点这里设置代理", v -> openProxy());
        proxyBtn.setTextSize(13);
        proxyBtn.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 7));
        LinearLayout.LayoutParams pblp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        pblp.leftMargin = 0;
        proxyBtn.setLayoutParams(pblp);
        qrCol.addView(proxyBtn);

        qrCard.addView(qrCol);

        FrameLayout.LayoutParams qlp2 = new FrameLayout.LayoutParams(
                Ui.dp(this, 500), ViewGroup.LayoutParams.WRAP_CONTENT);
        qlp2.gravity = Gravity.BOTTOM | Gravity.START;
        qlp2.leftMargin = Ui.dp(this, 26);
        qlp2.bottomMargin = Ui.dp(this, 26);
        root.addView(qrCard, qlp2);

        buildErrorPanel(root);

        setContentView(root);
        updateProxyState();
        applyWebViewCompatNote();
    }

    /** 老盒子的 WebView（如 Chromium 83）解析不了 x.com 现在的前端 JS，提示走 Cookie 登录。 */
    private void applyWebViewCompatNote() {
        if (compatNote == null) {
            return;
        }
        int major = 0;
        try {
            String ua = WebSettings.getDefaultUserAgent(this);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Chrome/([0-9]+)").matcher(ua);
            if (m.find()) {
                major = Integer.parseInt(m.group(1));
            }
        } catch (Throwable ignored) {
        }
        android.util.Log.i("XBigTV", "system WebView major=" + major);
        if (major > 0 && major < 100) {
            compatNote.setVisibility(View.VISIBLE);
            compatNote.setText("本机 WebView 版本 " + major
                    + "，无法渲染 x.com 登录页；请用手机扫码页面里的「粘贴 Cookie」登录，或升级系统 WebView。");
        }
    }

    /** 网页加载失败时的兜底面板：主界面在这之前是进不去的，所以代理入口必须就在这。 */
    private void buildErrorPanel(FrameLayout root) {
        errorPanel = Ui.linear(this, LinearLayout.VERTICAL);
        errorPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        errorPanel.setPadding(Ui.dp(this, 34), Ui.dp(this, 26), Ui.dp(this, 34), Ui.dp(this, 26));
        errorPanel.setBackground(Ui.round(0xF2101418, Ui.dp(this, 18), Ui.BORDER, Math.max(1, Ui.dp(this, 1))));
        errorPanel.setVisibility(View.GONE);

        errorText = Ui.text(this, "", 15, Ui.TEXT_DIM, false);
        errorText.setGravity(Gravity.CENTER);
        errorPanel.addView(errorText);

        LinearLayout btns = Ui.linear(this, LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER);
        btns.setPadding(0, Ui.dp(this, 16), 0, 0);
        errorProxyBtn = accentChip("去设置代理", v -> openProxy());
        btns.addView(errorProxyBtn);
        btns.addView(chip("重新加载", v -> {
            hideError();
            if (HAS_PROXY_CHANGED()) {
                restartWebWithProxy();
            } else if (web != null) {
                web.reload();
            } else {
                createWebView();
            }
        }));
        errorPanel.addView(btns);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Ui.dp(this, 620), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        root.addView(errorPanel, lp);
    }

    private boolean HAS_PROXY_CHANGED() {
        return !proxyKey().equals(appliedProxyKey);
    }

    private void showLoadError(String msg) {
        if (errorPanel == null || finishingLogin) {
            return;
        }
        errorPanel.setVisibility(View.VISIBLE);
        errorText.setText("登录页加载失败\n" + msg
                + "\n\n在国内网络下 x.com 需要代理，请先设置代理后重试。");
        errorPanel.post(() -> {
            if (errorProxyBtn != null) {
                errorProxyBtn.requestFocus();
            }
        });
    }

    private void hideError() {
        if (errorPanel != null) {
            errorPanel.setVisibility(View.GONE);
        }
    }

    /** 电视上直接粘 Cookie（没有手机/局域网不通时的兜底）。 */
    private void askCookieOnTv() {
        TvKeyboardDialog.show(this, "粘贴 auth_token（可整段粘 cookie）", "", false, auth ->
                TvKeyboardDialog.show(this, "ct0（可留空自动获取）", "", false, ct0 -> {
                    statusText.setText("正在用 Cookie 登录…");
                    io.execute(() -> {
                        String r = loginWithCookie(auth, ct0, "");
                        if (!"ok".equals(r)) {
                            main.post(() -> {
                                statusText.setText("登录失败：" + r);
                                Ui.toast(this, r);
                            });
                        }
                    });
                }));
    }

    private void openProxy() {
        startActivity(new Intent(this, ProxyActivity.class));
    }

    private String proxyKey() {
        return Prefs.proxyEnabled() + "|" + Prefs.proxyType() + "|" + Prefs.proxyHost() + "|"
                + Prefs.proxyPort() + "|" + Prefs.proxyUser() + "|" + Prefs.proxyPass();
    }

    private void updateProxyState() {
        if (proxyState == null) {
            return;
        }
        if (Prefs.proxyConfigured()) {
            proxyState.setText("当前代理：" + Prefs.proxyHost() + ":" + Prefs.proxyPort()
                    + (Prefs.proxyType() == Prefs.PROXY_HTTP ? " · HTTP" : " · SOCKS5"));
            proxyState.setTextColor(Ui.GREEN);
        } else {
            proxyState.setText("当前代理：未开启（直连）");
            proxyState.setTextColor(Ui.AMBER);
        }
    }

    private TextView accentChip(String label, View.OnClickListener onClick) {
        TextView tv = Ui.text(this, label, 14, Ui.BLACK, true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        tv.setBackground(Ui.round(Ui.BLUE, Ui.dp(this, 18)));
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(false);
        tv.setOnClickListener(onClick);
        tv.setOnFocusChangeListener((v, has) ->
                v.setBackground(Ui.round(has ? Ui.TEXT : Ui.BLUE, Ui.dp(this, 18))));
        LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 8);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView chip(String label, View.OnClickListener onClick) {
        TextView tv = Ui.text(this, label, 14, Ui.TEXT, true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        tv.setBackground(Ui.round(0x33FFFFFF, Ui.dp(this, 18)));
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(false);
        tv.setOnClickListener(onClick);
        tv.setOnFocusChangeListener((v, has) ->
                v.setBackground(Ui.round(has ? Ui.BLUE : 0x33FFFFFF, Ui.dp(this, 18))));
        LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 8);
        tv.setLayoutParams(lp);
        return tv;
    }

    /* ---------------- WebView ---------------- */

    private void createWebView() {
        if (web != null || isFinishing()) {
            return;
        }
        web = new WebView(this);
        // 硬件渲染（默认）；手机镜像走 PixelCopy 抓屏，不需要牺牲渲染质量
        webHost.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUserAgentString(XApi.UA);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                statusText.setText("当前页面：" + shortUrl(url));
                checkCookies();
            }

            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (Build.VERSION.SDK_INT >= 23 && request != null && request.isForMainFrame()) {
                    showLoadError(String.valueOf(error == null ? "网络不可达" : error.getDescription()));
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                if (Build.VERSION.SDK_INT < 23) {
                    showLoadError(description == null ? "网络不可达" : description);
                }
            }
        });

        web.loadUrl("https://x.com/i/flow/login");
    }

    /**
     * 顺序很关键：WebView 83（很多电视盒子就是这个版本）会在 WebView 创建时锁定代理配置，
     * 先建 WebView 再 setProxyOverride 只对下一次「新建的」WebView 生效 —— 表现就是
     * 页面直连、ERR_EMPTY_RESPONSE、而本地转发端口一条 CONNECT 都收不到。
     * 所以这里先把代理落地，回调回来再创建 WebView 并加载。
     */
    private void applyProxyThenStartWeb() {
        appliedProxyKey = proxyKey();
        String addr = Http.localProxyAddress();
        boolean supported = false;
        try {
            supported = androidx.webkit.WebViewFeature.isFeatureSupported(
                    androidx.webkit.WebViewFeature.PROXY_OVERRIDE);
        } catch (Throwable ignored) {
        }
        android.util.Log.i("XBigTV", "proxy-then-web addr=" + addr + " PROXY_OVERRIDE=" + supported);
        if (addr == null) {
            safeClearProxyOverride();
            createWebView();
            return;
        }
        try {
            ProxyConfig config = new ProxyConfig.Builder()
                    .addProxyRule(addr)
                    .build();
            ProxyController.getInstance().setProxyOverride(config, command -> main.post(command), () -> {
                android.util.Log.i("XBigTV", "setProxyOverride done -> createWebView");
                main.post(this::createWebView);
            });
            // 兜底：回调异常时也要把页面拉起来
            main.postDelayed(this::createWebView, 1500);
        } catch (Throwable t) {
            android.util.Log.e("XBigTV", "proxy override failed: " + t, t);
            createWebView();
        }
    }

    private void safeClearProxyOverride() {
        try {
            ProxyController.getInstance().clearProxyOverride(command -> main.post(command), () -> {
            });
        } catch (Throwable ignored) {
        }
    }

    /** 代理改了：WebView 需要重建才能吃到新配置。 */
    private void restartWebWithProxy() {
        if (web != null) {
            webHost.removeAllViews();
            try {
                web.destroy();
            } catch (Throwable ignored) {
            }
            web = null;
        }
        hideError();
        applyProxyThenStartWeb();
    }

    private String shortUrl(String url) {
        if (url == null) {
            return "";
        }
        return url.length() > 46 ? url.substring(0, 46) + "…" : url;
    }

    /* ---------------- 局域网桥 ---------------- */

    private void startLanServer() {
        server = new LanLinkServer(this);
        server.start();
        String url = server.url();
        if (url == null) {
            urlText.setText("未检测到局域网 IP（请在电视上手动输入）");
            statusText.setText("可以按「遥控器输入」直接登录");
            return;
        }
        Bitmap qr = QrBitmap.make(url, Ui.dp(this, 200));
        if (qr != null) {
            qrImage.setImageBitmap(qr);
        }
        urlText.setText(url);
    }

    /* ---------------- 登录态轮询 ---------------- */

    private final Runnable cookiePoll = new Runnable() {
        @Override
        public void run() {
            if (finishingLogin || isFinishing()) {
                return;
            }
            if (checkCookies()) {
                return;
            }
            main.postDelayed(this, 1200);
        }
    };

    private boolean checkCookies() {
        String raw = CookieManager.getInstance().getCookie("https://x.com");
        if (TextUtils.isEmpty(raw)) {
            return false;
        }
        String auth = cookieValue(raw, "auth_token");
        String ct0 = cookieValue(raw, "ct0");
        String twid = cookieValue(raw, "twid");
        if (TextUtils.isEmpty(auth) || TextUtils.isEmpty(ct0)) {
            return false;
        }
        finishingLogin = true;
        XSession.save(auth, ct0, twid);
        statusText.setText("✔ 登录成功，正在读取账号信息…");
        if (server != null) {
            server.stop();
        }
        io.execute(() -> {
            String[] viewer = XApi.viewer();
            if (viewer != null && !TextUtils.isEmpty(viewer[0])) {
                XSession.profile(viewer[0], viewer[1], viewer[2]);
            }
            main.post(this::goMain);
        });
        return true;
    }

    private static String cookieValue(String raw, String name) {
        for (String part : raw.split(";")) {
            String p = part.trim();
            if (p.startsWith(name + "=")) {
                return p.substring(name.length() + 1);
            }
        }
        return "";
    }

    private void goMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    /* ---------------- LanLinkServer.Controller ---------------- */

    @Override
    public String loginWithCookie(String authIn, String ct0In, String twidIn) {
        String auth = cleanCookie(authIn, "auth_token");
        String ct0 = cleanCookie(ct0In, "ct0");
        String twid = cleanCookie(twidIn, "twid");
        if (auth.isEmpty()) {
            return "auth_token 不能为空";
        }
        android.util.Log.i("XBigTV", "cookie login: authLen=" + auth.length()
                + " ct0Given=" + (!ct0.isEmpty()));
        if (ct0.isEmpty()) {
            String[] boot = XApi.bootstrap(auth);
            if (boot == null) {
                return "auth_token 无效或已过期（换不到 ct0）";
            }
            ct0 = boot[1];
            if (twid.isEmpty()) {
                twid = boot[2];
            }
        }
        XSession.save(auth, ct0, twid);
        String err = XApi.verifySession();
        if (err != null) {
            XSession.clear();
            return "校验失败：" + err;
        }
        String[] me = XApi.viewer();
        if (me != null && !Str.isEmpty(me[0])) {
            XSession.profile(me[0], me[1], me[2]);
        }
        finishingLogin = true;
        if (server != null) {
            server.stop();
        }
        main.post(this::goMain);
        return "ok";
    }

    /** 允许直接粘 "auth_token=xxx; ct0=yyy" 这种整段 cookie。 */
    private static String cleanCookie(String raw, String name) {
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

    @Override
    public byte[] snapshotJpeg(int maxWidth, int quality) {
        final byte[][] out = new byte[1][];
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(() -> {
            try {
                if (web == null) {
                    return;
                }
                int w = web.getWidth();
                int h = web.getHeight();
                if (w <= 0 || h <= 0) {
                    return;
                }
                float scale = Math.min(1f, (float) maxWidth / w);
                Bitmap bmp = Bitmap.createBitmap(Math.round(w * scale), Math.round(h * scale),
                        Bitmap.Config.RGB_565);
                Canvas canvas = new Canvas(bmp);
                canvas.scale(scale, scale);
                web.draw(canvas);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos);
                bmp.recycle();
                out[0] = bos.toByteArray();
            } catch (Throwable ignored) {
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(2500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        return out[0];
    }

    @Override
    public void tap(float nx, float ny) {
        if (web == null) {
            return;
        }
        int w = web.getWidth();
        int h = web.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        float x = Math.max(1, Math.min(w - 1, nx * w));
        float y = Math.max(1, Math.min(h - 1, ny * h));
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0);
        web.dispatchTouchEvent(down);
        web.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
        web.requestFocus();
    }

    @Override
    public void sendKey(String name) {
        if (web == null) {
            return;
        }
        if ("enter".equalsIgnoreCase(name)) {
            web.evaluateJavascript("(function(){"
                    + "var t=['LoginForm_Login_Button','ocfEnterTextNextButton','ocfSelectAccountSubmit'];"
                    + "for(var i=0;i<t.length;i++){var b=document.querySelector('[data-testid=\"'+t[i]+'\"]');"
                    + "if(b){b.click();return;}}"
                    + "var el=document.activeElement;"
                    + "if(el&&(el.tagName==='INPUT'||el.tagName==='TEXTAREA')){"
                    + "el.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));return;}"
                    + "var ns=document.querySelectorAll('div[role=button],span,button');"
                    + "for(var j=0;j<ns.length;j++){var s=(ns[j].innerText||'').trim();"
                    + "if(s==='下一步'||s==='登录'||s==='Next'||s==='Log in'){ns[j].click();return;}}"
                    + "})()", null);
        } else if ("backspace".equalsIgnoreCase(name)) {
            web.evaluateJavascript("(function(){var el=document.activeElement;"
                    + "if(el&&(el.tagName==='INPUT'||el.tagName==='TEXTAREA')){"
                    + "var proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;"
                    + "var setter=Object.getOwnPropertyDescriptor(proto,'value').set;"
                    + "setter.call(el,(el.value||'').slice(0,-1));"
                    + "el.dispatchEvent(new Event('input',{bubbles:true}));}})()", null);
        }
    }

    @Override
    public boolean typeText(String text) {
        if (text == null || web == null) {
            return false;
        }
        String escaped = text.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\n", "\\n").replace("\r", "");
        String js = "(function(t){"
                + "var el=document.activeElement;"
                + "if(!el||(el.tagName!=='INPUT'&&el.tagName!=='TEXTAREA')){"
                + "el=document.querySelector('input[type=text],input[type=password],input[name=text]');}"
                + "if(!el)return false;el.focus();"
                + "var proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;"
                + "var setter=Object.getOwnPropertyDescriptor(proto,'value').set;"
                + "setter.call(el,t);"
                + "el.dispatchEvent(new Event('input',{bubbles:true}));"
                + "el.dispatchEvent(new Event('change',{bubbles:true}));"
                + "return true;})('" + escaped + "')";
        web.evaluateJavascript(js, null);
        return true;
    }

    @Override
    public String currentUrl() {
        // WebView.getUrl() 必须在主线程调用
        final String[] out = {""};
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(() -> {
            try {
                if (web != null) {
                    String u = web.getUrl();
                    out[0] = u == null ? "" : u;
                }
            } catch (Throwable ignored) {
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(1200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        return out[0];
    }

    @Override
    public boolean loggedIn() {
        return finishingLogin || XSession.loggedIn();
    }

    @Override
    public void refreshPage() {
        if (web != null) {
            web.reload();
        }
    }

    /* ---------------- 生命周期 ---------------- */

    @Override
    protected void onResume() {
        super.onResume();
        updateProxyState();
        // 从代理设置返回：代理变了就重建 WebView（旧 WebView 会锁住旧代理配置）
        if (!finishingLogin && HAS_PROXY_CHANGED()) {
            statusText.setText("已应用新代理，正在重新加载…");
            restartWebWithProxy();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            qrVisible = !qrVisible;
            qrCard.setVisibility(qrVisible ? View.VISIBLE : View.GONE);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (web.canGoBack()) {
                web.goBack();
            } else {
                finish();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        if (server != null) {
            server.stop();
        }
        if (web != null) {
            web.destroy();
        }
    }
}
