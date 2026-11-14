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
 * 主界面：顶部 tab = 为你推荐 / 关注 / X 网页端 Pin 到首页的分组（列表）；
 * 遥控器上下看帖，OK 看详情（图片帖先进详情看评论），长按 OK 刷新回到顶部，连按两次返回才退出。
 */
public class MainActivity extends Activity implements PostAdapter.Listener {

    /** 一个 tab：名字 + 取数据的方式。 */
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

    private RecyclerView rv;
    private PostAdapter adapter;
    private TextView statusView;
    private LinearLayout tabBar;

    private final List<TabDef> tabs = new ArrayList<>();
    private final List<TextView> tabViews = new ArrayList<>();
    private final List<View> tabBars = new ArrayList<>();
    private int tabIndex = 0;

    private String cursor = "";
    private boolean loading;
    private boolean exhausted;
    private long lastFocusRequest;
    private long lastLongPressAt;
    private long lastBackAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ops.loadCache();
        if (!XSession.loggedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        // 默认 tab（分组稍后异步补上）
        tabs.add(new TabDef("为你推荐", 0, null));
        tabs.add(new TabDef("关注", 1, null));
        tabs.add(new TabDef("视频", 2, null));
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

    /** 从 X 拉「Pin 到首页」的分组，追加成 tab，和网页端顺序一致。 */
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

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BLACK);

        LinearLayout col = Ui.linear(this, LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = Ui.linear(this, LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(this, 22), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 6));

        header.addView(Ui.text(this, "X", 28, Ui.TEXT, true));
        View logoGap = new View(this);
        logoGap.setLayoutParams(Ui.lp(Ui.dp(this, 16), 1));
        header.addView(logoGap);

        // 分组可能很多：横向可滚动
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setLayoutParams(Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabBar = Ui.linear(this, LinearLayout.HORIZONTAL);
        tabBar.setGravity(Gravity.CENTER_VERTICAL);
        hs.addView(tabBar, new android.widget.HorizontalScrollView.LayoutParams(
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
        rebuildTabBar();
    }

    private void rebuildTabBar() {
        tabBar.removeAllViews();
        tabViews.clear();
        tabBars.clear();
        for (int i = 0; i < tabs.size(); i++) {
            final int idx = i;
            TabDef def = tabs.get(i);
            // 直接用 TextView 当 tab：避免嵌套 LinearLayout 在 HorizontalScrollView 里被压窄
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
            tv.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && (keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        || keyCode == KeyEvent.KEYCODE_DPAD_CENTER && false)) {
                    focusFirst();
                    return true;
                }
                return false;
            });
            tv.setOnFocusChangeListener((v, has) ->
                    v.setBackground(has ? Ui.round(0x1FFFFFFF, Ui.dp(this, 18)) : null));
            tabViews.add(tv);
            tabBars.add(new View(this));
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
        tabIndex = idx;
        updateTabStyles();
        rv.scrollToPosition(0);
        load(true);
        main.postDelayed(this::focusFirst, 500);
    }

    /** 当前 tab 对应的取数方式；视频流也用它继续翻页。 */
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

    /** OK：视频进竖屏流；图片/文字先进详情看评论（详情里再点图片才全屏翻页）。 */
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
            TabDef def = tabs.get(tabIndex);
            VideoFeedActivity.start(this, feed, index, def.name, sourceFor(def));
            return;
        }
        DetailActivity.start(this, post.id);
    }

    @Override
    public void onLongPress(Post post) {
        long now = System.currentTimeMillis();
        if (now - lastLongPressAt < 1200) {
            return;
        }
        lastLongPressAt = now;
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
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}
