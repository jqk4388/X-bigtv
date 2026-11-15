package io.github.xbigtv.tv.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
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
import io.github.xbigtv.tv.update.UpdateManager;
import io.github.xbigtv.tv.xapi.Ops;
import io.github.xbigtv.tv.xapi.Post;
import io.github.xbigtv.tv.xapi.TimelinePage;
import io.github.xbigtv.tv.xapi.XApi;
import io.github.xbigtv.tv.xapi.XSession;

/**
 * 主界面（平板平行窗口）：
 * 左列表 + 右侧帖子详情分屏；tab 与网页端一致（推荐/关注/视频/Pin 的分组）；
 * 列表内视频静音自动播放；连按两次返回退出。
 */
public class MainActivity extends Activity implements PostAdapter.Listener {

    private static final class TabDef {
        final String name;
        final int mode;      // 0 为你推荐 1 关注 2 视频搜索 3 列表
        final String listId;

        TabDef(String name, int mode, String listId) {
            this.name = name;
            this.mode = mode;
            this.listId = listId;
        }
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout listCol;
    private LinearLayout detailPane;
    private RecyclerView rv;
    private RecyclerView paneRv;
    private PostAdapter adapter;
    private PostAdapter paneAdapter;
    private TextView statusView;
    private TextView paneStatus;
    private LinearLayout tabBar;

    private final List<TabDef> tabs = new ArrayList<>();
    private final List<TextView> tabViews = new ArrayList<>();
    private int tabIndex = 0;

    private String cursor = "";
    private boolean loading;
    private boolean exhausted;
    private boolean splitOpen;
    private String panePostId = "";
    private long lastFocusRequest;
    private long lastLongPressAt;
    private long lastBackAt;

    private ListAutoPlayer autoPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ops.loadCache();
        if (!XSession.loggedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        tabs.add(new TabDef("为你推荐", 0, null));
        tabs.add(new TabDef("关注", 1, null));
        tabs.add(new TabDef("视频", 2, null));
        autoPlayer = new ListAutoPlayer();
        buildUi();
        load(true);
        refreshOpsInBackground();
        UpdateManager.checkOnStart(this);
        loadTabsAsync();
    }

    private void refreshOpsInBackground() {
        if (System.currentTimeMillis() - Prefs.opsStamp() < 12L * 3600_000L) {
            return;
        }
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Ops.refresh();
            } catch (Throwable ignored) {
            }
        });
    }

    private void loadTabsAsync() {
        io.execute(() -> {
            final List<XApi.Tab> pinned = XApi.pinnedTimelines();
            if (pinned.isEmpty()) {
                return;
            }
            main.post(() -> {
                if (isFinishing()) {
                    return;
                }
                for (XApi.Tab t : pinned) {
                    tabs.add(new TabDef(t.name, 3, t.listId));
                }
                rebuildTabBar();
            });
        });
    }

    /* ---------------- UI ---------------- */

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BLACK);

        LinearLayout col = Ui.linear(this, LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶部栏
        LinearLayout header = Ui.linear(this, LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(this, 22), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 6));
        header.addView(Ui.text(this, "X", 28, Ui.TEXT, true));
        View logoGap = new View(this);
        logoGap.setLayoutParams(Ui.lp(Ui.dp(this, 16), 1));
        header.addView(logoGap);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setLayoutParams(Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabBar = Ui.linear(this, LinearLayout.HORIZONTAL);
        tabBar.setGravity(Gravity.CENTER_VERTICAL);
        hs.addView(tabBar, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        header.addView(hs);

        header.addView(topButton("刷新", v -> load(true)));
        header.addView(topButton("搜索", v -> startActivity(new Intent(this, SearchActivity.class))));
        header.addView(topButton("代理", v -> startActivity(new Intent(this, ProxyActivity.class))));
        header.addView(topButton("设置", v -> startActivity(new Intent(this, SettingsActivity.class))));

        String who = XSession.screenName.isEmpty() ? "账号" : "@" + XSession.screenName;
        TextView account = Ui.text(this, who, 14, Ui.TEXT_DIM, true);
        account.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 2), Ui.dp(this, 8));
        header.addView(account);

        col.addView(header);
        col.addView(Ui.divider(this));

        // 主体：左列表 + 右详情（平行窗口）
        LinearLayout body = Ui.linear(this, LinearLayout.HORIZONTAL);
        body.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        listCol = Ui.linear(this, LinearLayout.VERTICAL);
        listCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

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

        listCol.addView(listWrap);
        body.addView(listCol);

        // 右侧详情面板
        detailPane = Ui.linear(this, LinearLayout.VERTICAL);
        detailPane.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        detailPane.setBackgroundColor(0xFF0B0D10);
        detailPane.setVisibility(View.GONE);
        detailPane.setPadding(Ui.dp(this, 8), 0, 0, 0);

        LinearLayout paneHeader = Ui.linear(this, LinearLayout.HORIZONTAL);
        paneHeader.setGravity(Gravity.CENTER_VERTICAL);
        paneHeader.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        TextView paneTitle = Ui.text(this, "帖子详情", 16, Ui.TEXT, true);
        paneHeader.addView(paneTitle);
        View flex2 = new View(this);
        paneHeader.addView(flex2, Ui.lp(0, 1, 1f));
        TextView closeBtn = Ui.text(this, "关闭 ✕", 14, Ui.TEXT, true);
        closeBtn.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        closeBtn.setFocusable(true);
        closeBtn.setOnClickListener(v -> closePane());
        closeBtn.setOnFocusChangeListener((v, has) -> v.setBackground(has
                ? Ui.round(0x26FFFFFF, Ui.dp(this, 14)) : null));
        paneHeader.addView(closeBtn);
        detailPane.addView(paneHeader);
        detailPane.addView(Ui.divider(this));

        FrameLayout paneWrap = new FrameLayout(this);
        paneRv = new RecyclerView(this);
        paneRv.setLayoutManager(new LinearLayoutManager(this));
        paneRv.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 16));
        paneAdapter = new PostAdapter(new PostAdapter.Listener() {
            @Override
            public void onOpen(Post post) {
                // 分屏里点视频：全屏播放
                if (post.hasVideo()) {
                    List<Post> feed = new ArrayList<>();
                    feed.add(post);
                    VideoFeedActivity.start(MainActivity.this, feed, 0, null, null);
                }
            }

            @Override
            public void onLongPress(Post post) {
            }

            @Override
            public void onCardFocused(Post post, PostCardView card) {
            }

            @Override
            public void onNeedMore() {
            }
        });
        paneRv.setAdapter(paneAdapter);
        paneWrap.addView(paneRv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        paneStatus = Ui.text(this, "正在加载…", 15, Ui.TEXT_DIM, false);
        paneStatus.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams pslp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pslp.gravity = Gravity.CENTER;
        paneWrap.addView(paneStatus, pslp);
        detailPane.addView(paneWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        body.addView(detailPane);
        col.addView(body);

        setContentView(root);
        rebuildTabBar();
    }

    private void rebuildTabBar() {
        tabBar.removeAllViews();
        tabViews.clear();
        for (int i = 0; i < tabs.size(); i++) {
            final int idx = i;
            TabDef def = tabs.get(i);
            TextView tv = Ui.text(this, def.name, 16, Ui.TEXT_DIM, true);
            tv.setSingleLine(true);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(Ui.dp(this, 14), Ui.dp(this, 9), Ui.dp(this, 14), Ui.dp(this, 9));
            tv.setFocusable(true);
            tv.setFocusableInTouchMode(false);
            LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 4);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> switchTab(idx));
            tv.setOnFocusChangeListener((v, has) ->
                    v.setBackground(has ? Ui.round(0x1FFFFFFF, Ui.dp(this, 18)) : null));
            tv.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    focusFirst();
                    return true;
                }
                return false;
            });
            tabViews.add(tv);
            tabBar.addView(tv);
        }
        updateTabStyles();
    }

    private View topButton(String label, View.OnClickListener onClick) {
        TextView tv = Ui.text(this, label, 14, Ui.TEXT_DIM, true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(false);
        tv.setOnClickListener(onClick);
        tv.setOnFocusChangeListener((v, has) -> {
            ((TextView) v).setTextColor(has ? Ui.BLACK : Ui.TEXT_DIM);
            v.setBackground(has ? Ui.round(Ui.TEXT, Ui.dp(this, 18)) : null);
        });
        LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 4);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void updateTabStyles() {
        for (int i = 0; i < tabViews.size(); i++) {
            TextView tv = tabViews.get(i);
            boolean active = i == tabIndex;
            tv.setTextColor(active ? Ui.TEXT : Ui.TEXT_DIM);
            tv.setBackground(active ? Ui.round(0x1A1D9BF0, Ui.dp(this, 18)) : null);
        }
    }

    private void switchTab(int idx) {
        if (idx == tabIndex || idx >= tabs.size()) {
            return;
        }
        closePane();
        tabIndex = idx;
        updateTabStyles();
        rv.scrollToPosition(0);
        load(true);
        main.postDelayed(this::focusFirst, 500);
    }

    /* ---------------- 数据 ---------------- */

    private XApi.TimelineSource sourceFor(TabDef def) {
        if (def.mode == 3 && !TextUtils.isEmpty(def.listId)) {
            final String listId = def.listId;
            return cursor -> XApi.listTimeline(listId, cursor);
        }
        if (def.mode == 2) {
            return cursor -> XApi.videoFeed(null, cursor);
        }
        final boolean following = def.mode == 1;
        return cursor -> XApi.home(following, cursor);
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
        final int useTab = tabIndex;
        statusView.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        statusView.setText("正在加载…");

        final XApi.TimelineSource source = sourceFor(tabs.get(useTab));
        io.execute(() -> {
            TimelinePage page;
            try {
                page = source.fetch(useCursor);
                if (page.error.contains("404")) {
                    Ops.refresh();
                    page = source.fetch(useCursor);
                }
            } catch (Throwable t) {
                boolean stale = t.getMessage() != null && t.getMessage().contains("404");
                page = new TimelinePage();
                page.error = t.getMessage() == null ? "网络请求失败" : t.getMessage();
                if (stale) {
                    try {
                        Ops.refresh();
                        page = source.fetch(useCursor);
                    } catch (Throwable t2) {
                        page.error = t2.getMessage() == null ? "网络请求失败" : t2.getMessage();
                    }
                }
            }
            final TimelinePage result = page;
            main.post(() -> onPage(result, reset, useTab));
        });
    }

    private void onPage(TimelinePage page, boolean reset, int useTab) {
        loading = false;
        if (useTab != tabIndex) {
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
        if (reset) {
            adapter.replace(page.posts);
            focusFirst();
            main.postDelayed(this::focusFirst, 400);
        } else {
            adapter.append(page.posts);
        }
        statusView.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        if (adapter.getItemCount() == 0) {
            statusView.setText("这个分组暂时没有内容");
        }
    }

    private void focusFirst() {
        long now = System.currentTimeMillis();
        if (now - lastFocusRequest < 250) {
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

    /* ---------------- 平行窗口 ---------------- */

    private void openPane(final String postId) {
        splitOpen = true;
        panePostId = postId;
        autoPlayer.detach();
        listCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.46f));
        detailPane.setVisibility(View.VISIBLE);
        detailPane.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.54f));
        paneAdapter.replace(new ArrayList<>());
        paneStatus.setVisibility(View.VISIBLE);
        paneStatus.setText("正在加载详情与评论…");

        io.execute(() -> {
            TimelinePage page;
            try {
                page = XApi.detail(postId);
            } catch (Throwable t) {
                page = new TimelinePage();
                page.error = t.getMessage() == null ? "网络请求失败" : t.getMessage();
            }
            final TimelinePage result = page;
            main.post(() -> {
                if (!splitOpen || !postId.equals(panePostId)) {
                    return;
                }
                if (result.posts.isEmpty()) {
                    paneStatus.setText("加载失败：" + result.error);
                    return;
                }
                paneStatus.setVisibility(View.GONE);
                paneAdapter.replace(result.posts);
                paneRv.post(() -> {
                    RecyclerView.ViewHolder vh = paneRv.findViewHolderForAdapterPosition(0);
                    if (vh != null) {
                        vh.itemView.requestFocus();
                    }
                });
            });
        });
    }

    private void closePane() {
        if (!splitOpen) {
            return;
        }
        splitOpen = false;
        panePostId = "";
        detailPane.setVisibility(View.GONE);
        listCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        paneAdapter.replace(new ArrayList<>());
        main.postDelayed(this::focusFirst, 120);
    }

    /* ---------------- 交互 ---------------- */

    @Override
    public void onOpen(Post post) {
        if (post.hasVideo()) {
            autoPlayer.detach();
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
            TabDef def = tabs.get(tabIndex);
            VideoFeedActivity.start(this, feed, index, def.name, sourceFor(def));
            return;
        }
        openPane(post.id);
    }

    @Override
    public void onLongPress(Post post) {
        long now = System.currentTimeMillis();
        if (now - lastLongPressAt < 1200) {
            return;
        }
        lastLongPressAt = now;
        closePane();
        Ui.toast(this, "刷新中…");
        rv.scrollToPosition(0);
        load(true);
        main.postDelayed(() -> {
            if (adapter.getItemCount() > 0) {
                rv.smoothScrollToPosition(0);
                focusFirst();
            }
        }, 600);
    }

    @Override
    public void onCardFocused(Post post, PostCardView card) {
        if (splitOpen) {
            return;
        }
        autoPlayer.attach(this, post, card);
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
            if (splitOpen) {
                closePane();
                return true;
            }
            long now = System.currentTimeMillis();
            if (now - lastBackAt < 2000) {
                finish();
                return true;
            }
            lastBackAt = now;
            Ui.toast(this, "再按一次返回键退出");
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
    protected void onPause() {
        super.onPause();
        if (autoPlayer != null) {
            autoPlayer.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        if (autoPlayer != null) {
            autoPlayer.release();
        }
    }
}
