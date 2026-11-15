package io.github.xbigtv.tv.ui;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.xbigtv.tv.data.Prefs;
import io.github.xbigtv.tv.login.QrBitmap;
import io.github.xbigtv.tv.net.Http;
import io.github.xbigtv.tv.net.LanBridge;
import io.github.xbigtv.tv.xapi.XApi;

/**
 * 独立代理设置：只作用于本 App。
 * 手机扫码打开的是同一个页面（登录/代理/播放都能设，固定 18930 端口）。
 */
public class ProxyActivity extends Activity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BackGuard backGuard = new BackGuard();

    private TextView stateValue;
    private TextView hostValue;
    private TextView portValue;
    private TextView typeValue;
    private TextView userValue;
    private TextView passValue;
    private TextView enableValue;
    private TextView testResult;
    private ImageView qrView;
    private TextView qrUrl;

    private boolean enabled;
    private int type;
    private String host;
    private int port;
    private String user;
    private String pass;
    private int rowIndex;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        load();
        buildUi();
        showQr();
    }

    private void load() {
        enabled = Prefs.proxyEnabled();
        type = Prefs.proxyType();
        host = Prefs.proxyHost();
        port = Prefs.proxyPort();
        user = Prefs.proxyUser();
        pass = Prefs.proxyPass();
    }

    private void showQr() {
        LanBridge.get().ensureStarted();
        String url = LanBridge.get().url();
        if (url == null) {
            qrUrl.setText("未检测到局域网 IP，请手动填写");
            return;
        }
        qrUrl.setText(url);
        android.graphics.Bitmap qr = QrBitmap.make(url, Ui.dp(this, 150));
        if (qr != null) {
            qrView.setImageBitmap(qr);
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BLACK);

        LinearLayout page = Ui.linear(this, LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(this, 40), Ui.dp(this, 22), Ui.dp(this, 40), Ui.dp(this, 18));

        LinearLayout head = Ui.linear(this, LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(this, "代理设置", 26, Ui.TEXT, true));
        head.addView(Ui.text(this, "    仅对本 App 生效 · 接口 / 图片 / 视频 / 登录页统一走代理",
                13, Ui.TEXT_DIM, false));
        View flex = new View(this);
        head.addView(flex, Ui.lp(0, 1, 1f));
        head.addView(action("返回", v -> finish()));
        head.addView(action("保存并应用", v -> save()));
        page.addView(head);
        page.addView(Ui.spacer(this, 12));

        ScrollView scroll = new ScrollView(this);
        LinearLayout col = Ui.linear(this, LinearLayout.VERTICAL);
        scroll.addView(col);

        // 扫码卡片
        LinearLayout qrCard = Ui.linear(this, LinearLayout.HORIZONTAL);
        qrCard.setGravity(Gravity.CENTER_VERTICAL);
        qrCard.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        qrCard.setBackground(Ui.round(Ui.SURFACE, Ui.dp(this, 12)));
        qrView = new ImageView(this);
        int qs = Ui.dp(this, 150);
        qrView.setLayoutParams(Ui.lp(qs, qs));
        qrView.setBackgroundColor(android.graphics.Color.WHITE);
        qrCard.addView(qrView);

        LinearLayout qrCol = Ui.linear(this, LinearLayout.VERTICAL);
        LinearLayout.LayoutParams qclp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        qclp.leftMargin = Ui.dp(this, 14);
        qrCol.setLayoutParams(qclp);
        qrCol.addView(Ui.text(this, "手机扫码：登录 / 代理 / 播放 都能设", 16, Ui.TEXT, true));
        qrCol.addView(Ui.text(this, "同一个页面，固定地址（端口 18930），不用扫两次", 12, Ui.TEXT_DIM, false));
        qrUrl = Ui.text(this, "", 12, Ui.BLUE, true);
        qrUrl.setPadding(0, Ui.dp(this, 8), 0, 0);
        qrCol.addView(qrUrl);
        qrCard.addView(qrCol);

        LinearLayout.LayoutParams qcLp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        qcLp.bottomMargin = Ui.dp(this, 14);
        qrCard.setLayoutParams(qcLp);
        col.addView(qrCard);

        stateValue = Ui.text(this, "", 14, Ui.TEXT_DIM, false);
        stateValue.setPadding(0, 0, 0, Ui.dp(this, 10));
        col.addView(stateValue);

        col.addView(row("启用代理", () -> enabled ? "开" : "关", v -> {
            enabled = !enabled;
            refreshValues();
        }));
        col.addView(row("代理类型", () -> type == Prefs.PROXY_HTTP ? "HTTP / HTTPS" : "SOCKS5", v -> {
            type = type == Prefs.PROXY_HTTP ? Prefs.PROXY_SOCKS : Prefs.PROXY_HTTP;
            refreshValues();
        }));
        col.addView(row("服务器地址", () -> host, v ->
                TvKeyboardDialog.show(this, "代理服务器地址（IP 或域名）", host, false, t -> {
                    host = t.trim();
                    refreshValues();
                })));
        col.addView(row("端口", () -> String.valueOf(port), v ->
                TvKeyboardDialog.show(this, "端口", String.valueOf(port), false, t -> {
                    try {
                        port = Integer.parseInt(t.trim());
                    } catch (Exception ignored) {
                        Ui.toast(this, "端口要填数字");
                    }
                    refreshValues();
                })));
        col.addView(row("用户名（可选）", () -> user, v ->
                TvKeyboardDialog.show(this, "代理用户名", user, false, t -> {
                    user = t;
                    refreshValues();
                })));
        col.addView(row("密码（可选）", () -> pass.isEmpty() ? "" : "已设置", v ->
                TvKeyboardDialog.show(this, "代理密码", pass, true, t -> {
                    pass = t;
                    refreshValues();
                })));

        col.addView(Ui.spacer(this, 10));

        LinearLayout testRow = Ui.linear(this, LinearLayout.HORIZONTAL);
        testResult = Ui.text(this, "", 15, Ui.TEXT_DIM, false);
        testRow.addView(action("保存并测试连通性", v -> {
            save();
            testResult.setText("正在通过代理请求 x.com…");
            io.execute(() -> {
                String r = XApi.testConnection();
                main.post(() -> testResult.setText("结果：" + r));
            });
        }));
        testResult.setPadding(Ui.dp(this, 14), 0, 0, 0);
        testRow.addView(testResult);
        col.addView(testRow);

        col.addView(Ui.spacer(this, 14));
        LinearLayout tips = Ui.linear(this, LinearLayout.VERTICAL);
        tips.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        tips.setBackground(Ui.round(Ui.SURFACE, Ui.dp(this, 12)));
        tips.addView(Ui.text(this, "说明", 15, Ui.TEXT, true));
        String[] lines = {
                "· 打开开关并填好地址端口后，本 App 的接口、图片、视频、登录 WebView 都走这个代理。",
                "· 支持 HTTP(S)/SOCKS5，带账号密码也行；域名交给代理端解析，可规避本机 DNS 污染。",
                "· 电视系统本身的代理不会被修改，其它 App 不受影响。",
                "· 手机扫码打开的就是上面那个固定地址，同一页面里还能登录账号、改播放设置。"
        };
        for (String l : lines) {
            TextView tv = Ui.text(this, l, 13, Ui.TEXT_DIM, false);
            tv.setPadding(0, Ui.dp(this, 4), 0, 0);
            tips.addView(tv);
        }
        col.addView(tips);

        page.addView(scroll, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        refreshValues();
    }

    private void refreshValues() {
        if (stateValue != null) {
            String local = Http.localProxyAddress();
            String s = enabled ? ("已开启 · 本地转发 " + (local == null ? "未就绪" : local)) : "已关闭（直连）";
            stateValue.setText(s);
            stateValue.setTextColor(enabled ? Ui.GREEN : Ui.TEXT_DIM);
        }
        if (hostValue != null) {
            hostValue.setText(TextUtils.isEmpty(host) ? "未设置" : host);
        }
        if (portValue != null) {
            portValue.setText(String.valueOf(port));
        }
        if (typeValue != null) {
            typeValue.setText(type == Prefs.PROXY_HTTP ? "HTTP / HTTPS" : "SOCKS5");
        }
        if (userValue != null) {
            userValue.setText(TextUtils.isEmpty(user) ? "未设置" : user);
        }
        if (passValue != null) {
            passValue.setText(TextUtils.isEmpty(pass) ? "未设置" : "已设置");
        }
        if (enableValue != null) {
            enableValue.setText(enabled ? "开" : "关");
            enableValue.setTextColor(enabled ? Ui.GREEN : Ui.TEXT_DIM);
        }
    }

    private View row(String label, ValueSupplier value, View.OnClickListener onClick) {
        LinearLayout row = Ui.linear(this, LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 14));
        row.setBackground(Ui.round(Ui.SURFACE, Ui.dp(this, 12)));
        row.setFocusable(true);
        row.setFocusableInTouchMode(false);
        row.setOnClickListener(onClick);
        row.setOnFocusChangeListener((v, has) -> v.setBackground(has
                ? Ui.round(Ui.SURFACE_HI, Ui.dp(this, 12), Ui.BLUE, Math.max(2, Ui.dp(this, 2)))
                : Ui.round(Ui.SURFACE, Ui.dp(this, 12))));
        row.addView(Ui.text(this, label, 17, Ui.TEXT, false));
        View flex = new View(this);
        row.addView(flex, Ui.lp(0, 1, 1f));
        TextView val = Ui.text(this, value.get(), 17, Ui.BLUE, true);
        row.addView(val);

        switch (rowIndex) {
            case 0:
                enableValue = val;
                break;
            case 1:
                typeValue = val;
                break;
            case 2:
                hostValue = val;
                break;
            case 3:
                portValue = val;
                break;
            case 4:
                userValue = val;
                break;
            case 5:
                passValue = val;
                break;
            default:
                break;
        }
        rowIndex++;

        LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 8);
        row.setLayoutParams(lp);
        return row;
    }

    private View action(String label, View.OnClickListener onClick) {
        TextView tv = Ui.text(this, label, 15, Ui.TEXT, true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        tv.setBackground(Ui.round(0x26FFFFFF, Ui.dp(this, 20)));
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(false);
        tv.setOnClickListener(onClick);
        tv.setOnFocusChangeListener((v, has) -> {
            v.setBackground(Ui.round(has ? Ui.BLUE : 0x26FFFFFF, Ui.dp(this, 20)));
            ((TextView) v).setTextColor(has ? Ui.BLACK : Ui.TEXT);
        });
        LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 10);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void save() {
        Prefs.saveProxy(enabled, type, host, port, user, pass);
        Http.reset();
        refreshValues();
        Ui.toast(this, enabled ? "代理已应用" : "已关闭代理，走直连");
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (backGuard.shouldExit(this)) {
                save();
                finish();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 手机上可能刚改过代理/设置，回来时刷新显示
        load();
        refreshValues();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    interface ValueSupplier {
        String get();
    }
}
