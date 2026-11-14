package io.github.xbigtv.tv.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.xbigtv.tv.BuildConfig;
import io.github.xbigtv.tv.data.Prefs;
import io.github.xbigtv.tv.login.LoginActivity;
import io.github.xbigtv.tv.xapi.Ops;
import io.github.xbigtv.tv.xapi.XSession;
import io.github.xbigtv.tv.update.UpdateManager;

/** 设置总入口：代理单列一个界面，其余播放/账号项都在这。 */
public class SettingsActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private TextView proxyValue;
    private TextView qualityValue;
    private TextView autoNextValue;
    private TextView fillValue;
    private TextView opsValue;
    private TextView updateValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BLACK);

        LinearLayout page = Ui.linear(this, LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(this, 40), Ui.dp(this, 24), Ui.dp(this, 40), Ui.dp(this, 20));

        LinearLayout head = Ui.linear(this, LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(this, "设置", 26, Ui.TEXT, true));
        View flex = new View(this);
        head.addView(flex, Ui.lp(0, 1, 1f));
        head.addView(action("返回", v -> finish()));
        page.addView(head);
        page.addView(Ui.spacer(this, 14));

        ScrollView scroll = new ScrollView(this);
        LinearLayout col = Ui.linear(this, LinearLayout.VERTICAL);
        scroll.addView(col);

        proxyValue = Ui.text(this, "", 15, Ui.BLUE, true);
        updateValue = Ui.text(this, "", 15, Ui.BLUE, true);
        col.addView(row("检查更新", updateValue, v ->
                UpdateManager.checkManually(this)));

        col.addView(row("代理设置（仅本 App）", proxyValue, v ->
                startActivity(new Intent(this, ProxyActivity.class))));

        qualityValue = Ui.text(this, "", 15, Ui.BLUE, true);
        col.addView(row("默认清晰度", qualityValue, v -> {
            Prefs.setQuality((Prefs.quality() + 1) % 5);
            syncValues();
        }));

        autoNextValue = Ui.text(this, "", 15, Ui.BLUE, true);
        col.addView(row("播完自动下一条", autoNextValue, v -> {
            Prefs.setAutoNext(!Prefs.autoNext());
            syncValues();
        }));

        fillValue = Ui.text(this, "", 15, Ui.BLUE, true);
        col.addView(row("视频画面（铺满 / 适应）", fillValue, v -> {
            Prefs.setFillScreen(!Prefs.fillScreen());
            syncValues();
        }));

        String account = XSession.screenName.isEmpty() ? "未登录"
                : (XSession.displayName + " · @" + XSession.screenName);
        col.addView(row("当前账号", Ui.text(this, account, 15, Ui.TEXT_DIM, false), v ->
                Ui.toast(this, "切换账号请用下面的「重新扫码登录」")));

        col.addView(row("重新扫码登录", Ui.text(this, "手机扫码", 15, Ui.BLUE, true), v -> {
            XSession.clear();
            Intent it = new Intent(this, LoginActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(it);
            finish();
        }));

        opsValue = Ui.text(this, "", 13, Ui.TEXT_DIM, false);
        opsValue.setMaxLines(3);
        col.addView(row("刷新接口版本（GraphQL queryId）", opsValue, v -> {
            opsValue.setText("刷新中…");
            io.execute(() -> {
                int changed = Ops.refresh();
                main.post(() -> {
                    opsValue.setText(Ops.describe());
                    Ui.toast(this, changed > 0 ? ("已更新 " + changed + " 个接口 ID") : "接口 ID 已是最新");
                });
            });
        }));

        col.addView(Ui.spacer(this, 12));
        LinearLayout about = Ui.linear(this, LinearLayout.VERTICAL);
        about.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        about.setBackground(Ui.round(Ui.SURFACE, Ui.dp(this, 12)));
        about.addView(Ui.text(this, "X 大屏  v" + BuildConfig.VERSION_NAME, 15, Ui.TEXT, true));
        String[] lines = {
                "· 大屏端遥控器刷 X：上下移动看帖，OK 打开视频进入沉浸式短视频流。",
                "· 视频播放基于 Media3/ExoPlayer，支持 HLS 自适应、倍速、清晰度、快进快退、续播。",
                "· 手机扫码登录：手机上操作电视画面完成官方登录，Cookie 保存在本机。",
                "· 代理只作用于本 App，接口 / 图片 / 视频 / 登录页统一走代理。",
                "· 仅供个人在大屏上浏览使用。"
        };
        for (String l : lines) {
            TextView tv = Ui.text(this, l, 13, Ui.TEXT_DIM, false);
            tv.setPadding(0, Ui.dp(this, 4), 0, 0);
            about.addView(tv);
        }
        col.addView(about);

        page.addView(scroll, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        syncValues();
    }

    private void syncValues() {
        proxyValue.setText(Prefs.proxyConfigured()
                ? ("已开启 · " + Prefs.proxyHost() + ":" + Prefs.proxyPort()
                + (Prefs.proxyType() == Prefs.PROXY_HTTP ? " HTTP" : " SOCKS5"))
                : "未开启（直连）");
        String q;
        switch (Prefs.quality()) {
            case 1:
                q = "1080P";
                break;
            case 2:
                q = "720P";
                break;
            case 3:
                q = "480P";
                break;
            case 4:
                q = "360P";
                break;
            default:
                q = "自动";
                break;
        }
        qualityValue.setText(q);
        autoNextValue.setText(Prefs.autoNext() ? "开" : "关");
        fillValue.setText(Prefs.fillScreen() ? "铺满" : "适应");
        if (updateValue != null) {
            updateValue.setText("当前 " + UpdateManager.currentVersionLine());
        }
        if (opsValue != null && opsValue.getText().length() == 0) {
            opsValue.setText(Ops.describe());
        }
    }

    private View row(String label, TextView value, View.OnClickListener onClick) {
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
        row.addView(value);
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
        return tv;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncValues();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}
