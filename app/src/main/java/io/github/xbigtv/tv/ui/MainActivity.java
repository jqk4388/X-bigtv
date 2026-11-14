package io.github.xbigtv.tv.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.xbigtv.tv.data.Prefs;
import io.github.xbigtv.tv.login.LoginActivity;
import io.github.xbigtv.tv.xapi.Ops;
import io.github.xbigtv.tv.xapi.Post;
import io.github.xbigtv.tv.xapi.TimelinePage;
import io.github.xbigtv.tv.xapi.XApi;
import io.github.xbigtv.tv.xapi.XSession;
import io.github.xbigtv.tv.update.UpdateManager;

/** 主界面：遥控器上下看帖，OK 打开视频帖进入沉浸视频流。 */
public class MainActivity extends Activity implements PostAdapter.Listener {

    private static final int MODE_FOR_YOU = 0;
    private static final int MODE_FOLLOWING = 1;
    private static final int MODE_VIDEO = 2;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private RecyclerView rv;
    private PostAdapter adapter;
    private TextView statusView;
    private LinearLayout tabBar;
    private final TextView[] tabViews = new TextView[3];
    private final View[] tabBars = new View[3];

    private int mode = MODE_FOR_YOU;
    private String cursor = "";
    private boolean loading;
    private boolean exhausted;
    private long lastFocusRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ops.loadCache();
        if (!XSession.loggedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        buildUi();
        load(true);
        refreshOpsInBackground();
        UpdateManager.checkOnStart(this);
    }

    private void refreshOpsInBackground() {
        long stamp = Prefs.opsStamp();
        if (System.currentTimeMillis() - stamp < 12L * 3600_000L) {
            return;
        }
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Ops.refresh();
            } catch (Throwable ignored) {
            }
        });
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BLACK);

        LinearLayout col = Ui.linear(this, LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---------------- 顶部栏 ----------------
        LinearLayout header = Ui.linear(this, LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(this, 26), Ui.dp(this, 10), Ui.dp(this, 22), Ui.dp(this, 6));

        TextView logo = Ui.text(this, "X", 30, Ui.TEXT, true);
        header.addView(logo);

        View logoGap = new View(this);
        logoGap.setLayoutParams(Ui.lp(Ui.dp(this, 26), 1));
        header.addView(logoGap);

        tabBar = Ui.linear(this, LinearLayout.HORIZONTAL);
        tabBar.setGravity(Gravity.CENTER_VERTICAL);
        String[] names = {"为你推荐", "关注", "视频"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            LinearLayout item = Ui.linear(this, LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 4));
            item.setFocusable(true);
            item.setFocusableInTouchMode(false);

            TextView tv = Ui.text(this, names[i], 17, Ui.TEXT_DIM, true);
            item.addView(tv);
            View bar = new View(this);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 3));
            blp.topMargin = Ui.dp(this, 6);
            bar.setLayoutParams(blp);
            bar.setBackground(Ui.round(Ui.BLUE, Ui.dp(this, 2)));
            bar.setVisibility(View.INVISIBLE);
            item.addView(bar);

            tabViews[i] = tv;
            tabBars[i] = bar;
            item.setOnClickListener(v -> switchMode(idx));
            item.setOnFocusChangeListener((v, has) ->
                    v.setBackground(has ? Ui.round(0x14FFFFFF, Ui.dp(this, 10)) : null));
            tabBar.addView(item);
        }
        header.addView(tabBar);

        View flex = new View(this);
        header.addView(flex, Ui.lp(0, 1, 1f));

        header.addView(topButton("刷新", v -> load(true)));
        header.addView(topButton("搜索", v -> startActivity(new Intent(this, SearchActivity.class))));
        header.addView(topButton("代理", v -> startActivity(new Intent(this, ProxyActivity.class))));
        header.addView(topButton("设置", v -> startActivity(new Intent(this, SettingsActivity.class))));

        String who = XSession.screenName.isEmpty() ? "账号" : "@" + Prefs.screenName();
        TextView account = Ui.text(this, who, 14, Ui.TEXT_DIM, true);
        account.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
        header.addView(account);

        col.addView(header);
        col.addView(Ui.divider(this));

        // ---------------- 列表 ----------------
        FrameLayout listWrap = new FrameLayout(this);
        listWrap.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setBackgroundColor(Ui.BLACK);
        rv.setClipToPadding(false);
        rv.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 24));
        adapter = new PostAdapter(this);
        rv.setAdapter(adapter);
        rv.setItemViewCacheSize(12);
        listWrap.addView(rv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        statusView = Ui.text(this, "正在加载…", 18, Ui.TEXT_DIM, false);
        statusView.setGravity(Gravity.CENTER);
        statusView.setFocusable(true);
        statusView.setFocusableInTouchMode(false);
        statusView.setOnClickListener(v -> startActivity(new Intent(this, ProxyActivity.class)));
        statusView.setOnFocusChangeListener((v, has) -> v.setBackground(has
                ? Ui.round(Ui.SURFACE, Ui.dp(this, 12)) : null));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.CENTER;
        listWrap.addView(statusView, slp);

        col.addView(listWrap);
        setContentView(root);
        updateTabs();
    }

    private View topButton(String label, View.OnClickListener onClick) {
        TextView tv = Ui.text(this, label, 15, Ui.TEXT_DIM, true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(false);
        tv.setOnClickListener(onClick);
        tv.setOnFocusChangeListener((v, has) -> {
            ((TextView) v).setTextColor(has ? Ui.BLACK : Ui.TEXT_DIM);
            v.setBackground(has ? Ui.round(Ui.TEXT, Ui.dp(this, 18)) : null);
        });
        LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void updateTabs() {
        for (int i = 0; i < tabViews.length; i++) {
            if (tabViews[i] == null) {
                continue;
            }
            tabViews[i].setTextColor(i == mode ? Ui.TEXT : Ui.TEXT_DIM);
            tabBars[i].setVisibility(i == mode ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void switchMode(int m) {
        if (m == mode) {
            return;
        }
        mode = m;
        updateTabs();
        rv.scrollToPosition(0);
        load(true);
    }

    private void load(boolean reset) {
        if (loading) {
            return;
        }
        if (!reset && exhausted) {
            return;
        }
        loading = true;
        if (reset) {
            cursor = "";
            exhausted = false;
        }
        final String useCursor = reset ? "" : cursor;
        final int useMode = mode;
        statusView.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        statusView.setText("正在加载…");

        io.execute(() -> {
            TimelinePage page;
            try {
                if (useMode == MODE_FOR_YOU) {
                    page = XApi.home(false, useCursor);
                } else if (useMode == MODE_FOLLOWING) {
                    page = XApi.home(true, useCursor);
                } else {
                    page = XApi.videoFeed(null, useCursor);
                }
                if (page.error.contains("404") && Ops.refresh() >= 0) {
                    // 接口 ID 过期：刷新后自动重试一次
                    if (useMode == MODE_FOR_YOU) {
                        page = XApi.home(false, useCursor);
                    } else if (useMode == MODE_FOLLOWING) {
                        page = XApi.home(true, useCursor);
                    } else {
                        page = XApi.videoFeed(null, useCursor);
                    }
                }
            } catch (Throwable t) {
                boolean stale = t.getMessage() != null && t.getMessage().contains("404");
                if (stale) {
                    try {
                        Ops.refresh();
                        if (useMode == MODE_FOR_YOU) {
                            page = XApi.home(false, useCursor);
                        } else if (useMode == MODE_FOLLOWING) {
                            page = XApi.home(true, useCursor);
                        } else {
                            page = XApi.videoFeed(null, useCursor);
                        }
                    } catch (Throwable t2) {
                        page = new TimelinePage();
                        page.error = t2.getMessage() == null ? "网络请求失败" : t2.getMessage();
                    }
                } else {
                    page = new TimelinePage();
                    page.error = t.getMessage() == null ? "网络请求失败" : t.getMessage();
                }
            }
            final TimelinePage result = page;
            main.post(() -> onPage(result, reset, useMode));
        });
    }

    private void onPage(TimelinePage page, boolean reset, int useMode) {
        loading = false;
        if (useMode != mode) {
            return;
        }
        if (!page.ok() && page.posts.isEmpty()) {
            statusView.setVisibility(View.VISIBLE);
            statusView.setText("加载失败：" + page.error
                    + "\n\n按 OK 打开「代理设置」   ·   按顶部「刷新」重试");
            if (reset && adapter.getItemCount() == 0) {
                Ui.toast(this, page.error);
            }
            return;
        }
        if (TextUtils.isEmpty(page.bottomCursor)) {
            exhausted = true;
        } else {
            cursor = page.bottomCursor;
        }
        if (useMode == MODE_VIDEO) {
            List<Post> only = new ArrayList<>();
            for (Post p : page.posts) {
                if (p.hasVideo()) {
                    only.add(p);
                }
            }
            page.posts.clear();
            page.posts.addAll(only);
        }
        if (reset) {
            adapter.replace(page.posts);
            focusFirst();
        } else {
            adapter.append(page.posts);
        }
        statusView.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        if (adapter.getItemCount() == 0) {
            statusView.setText("这里暂时没有内容，换个分类看看");
        }
    }

    private void focusFirst() {
        long now = System.currentTimeMillis();
        if (now - lastFocusRequest < 300) {
            return;
        }
        lastFocusRequest = now;
        rv.post(() -> {
            RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(0);
            if (vh != null && vh.itemView != null) {
                vh.itemView.requestFocus();
            } else {
                rv.scrollToPosition(0);
                rv.post(() -> {
                    RecyclerView.ViewHolder again = rv.findViewHolderForAdapterPosition(0);
                    if (again != null) {
                        again.itemView.requestFocus();
                    }
                });
            }
        });
    }

    @Override
    public void onOpen(Post post) {
        if (post.hasVideo()) {
            List<Post> feed = new ArrayList<>();
            int index = 0;
            for (Post p : adapter.items()) {
                if (!p.hasVideo()) {
                    continue;
                }
                if (p.id.equals(post.id)) {
                    index = feed.size();
                }
                feed.add(p);
            }
            if (feed.isEmpty()) {
                feed.add(post);
                index = 0;
            }
            VideoFeedActivity.start(this, feed, index, null);
        } else {
            DetailActivity.start(this, post.id);
        }
    }

    @Override
    public void onNeedMore() {
        if (!loading && !exhausted) {
            load(false);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!XSession.loggedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}
