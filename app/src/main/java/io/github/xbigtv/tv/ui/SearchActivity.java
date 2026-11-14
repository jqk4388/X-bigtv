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
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.xbigtv.tv.xapi.Post;
import io.github.xbigtv.tv.xapi.TimelinePage;
import io.github.xbigtv.tv.xapi.XApi;

/** 搜索：关键词 + 热门/最新/视频 三种结果，视频结果可直接进短视频流。 */
public class SearchActivity extends Activity implements PostAdapter.Listener {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private RecyclerView rv;
    private PostAdapter adapter;
    private TextView keywordView;
    private TextView status;

    private String keyword = "";
    private String product = "Top";
    private String cursor = "";
    private boolean loading;
    private boolean exhausted;
    private final List<TextView> tabs = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BLACK);
        LinearLayout col = Ui.linear(this, LinearLayout.VERTICAL);

        LinearLayout head = Ui.linear(this, LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(this, 26), Ui.dp(this, 12), Ui.dp(this, 26), Ui.dp(this, 12));

        TextView back = Ui.text(this, "‹ 返回", 16, Ui.TEXT, true);
        back.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        back.setFocusable(true);
        back.setOnClickListener(v -> finish());
        back.setOnFocusChangeListener((v, has) -> v.setBackground(has
                ? Ui.round(0x26FFFFFF, Ui.dp(this, 16)) : null));
        head.addView(back);

        LinearLayout box = Ui.linear(this, LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        box.setBackground(Ui.round(Ui.SURFACE, Ui.dp(this, 20)));
        box.setFocusable(true);
        box.setOnClickListener(v -> askKeyword());
        box.setOnFocusChangeListener((v, has) -> v.setBackground(has
                ? Ui.round(Ui.SURFACE_HI, Ui.dp(this, 20), Ui.BLUE, Math.max(2, Ui.dp(this, 2)))
                : Ui.round(Ui.SURFACE, Ui.dp(this, 20))));
        box.addView(Icons.view(this, Icons.SEARCH, 16, Ui.TEXT_DIM));
        keywordView = Ui.text(this, "  按 OK 输入关键词，例如：猫", 15, Ui.TEXT_DIM, false);
        box.addView(keywordView);
        LinearLayout.LayoutParams blp = Ui.lp(Ui.dp(this, 460), ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = Ui.dp(this, 14);
        box.setLayoutParams(blp);
        head.addView(box);

        head.addView(tab("热门", "Top"));
        head.addView(tab("最新", "Latest"));
        head.addView(tab("视频", "Media"));
        col.addView(head);
        col.addView(Ui.divider(this));

        FrameLayout wrap = new FrameLayout(this);
        rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 24));
        adapter = new PostAdapter(this);
        rv.setAdapter(adapter);
        wrap.addView(rv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        status = Ui.text(this, "输入关键词开始搜索", 18, Ui.TEXT_DIM, false);
        status.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.CENTER;
        wrap.addView(status, slp);

        col.addView(wrap, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        syncTabs();
    }

    private View tab(String label, String prod) {
        TextView tv = Ui.text(this, label, 15, Ui.TEXT_DIM, true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        tv.setFocusable(true);
        tv.setOnClickListener(v -> {
            product = prod;
            syncTabs();
            load(true);
        });
        tv.setOnFocusChangeListener((v, has) -> v.setBackground(has
                ? Ui.round(0x26FFFFFF, Ui.dp(this, 18)) : null));
        LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 6);
        tv.setLayoutParams(lp);
        tv.setTag(prod);
        tabs.add(tv);
        return tv;
    }

    private void syncTabs() {
        for (TextView tv : tabs) {
            boolean active = product.equals(tv.getTag());
            tv.setTextColor(active ? Ui.TEXT : Ui.TEXT_DIM);
        }
    }

    private void askKeyword() {
        TvKeyboardDialog.show(this, "搜索关键词", keyword, false, text -> {
            keyword = text.trim();
            keywordView.setText(TextUtils.isEmpty(keyword)
                    ? "  按 OK 输入关键词，例如：猫" : "  " + keyword);
            keywordView.setTextColor(TextUtils.isEmpty(keyword) ? Ui.TEXT_DIM : Ui.TEXT);
            load(true);
        });
    }

    private void load(boolean reset) {
        if (TextUtils.isEmpty(keyword)) {
            status.setVisibility(View.VISIBLE);
            status.setText("输入关键词开始搜索");
            return;
        }
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
            adapter.replace(new ArrayList<>());
        }
        status.setVisibility(View.VISIBLE);
        status.setText("正在搜索…");
        final String useCursor = reset ? "" : cursor;
        final String useProduct = product;
        io.execute(() -> {
            TimelinePage page;
            try {
                page = XApi.search(keyword, useProduct, useCursor);
            } catch (Throwable t) {
                page = new TimelinePage();
                page.error = t.getMessage();
            }
            final TimelinePage result = page;
            main.post(() -> {
                loading = false;
                if (!result.ok() && result.posts.isEmpty()) {
                    status.setVisibility(View.VISIBLE);
                    status.setText("搜索失败：" + result.error);
                    return;
                }
                if (TextUtils.isEmpty(result.bottomCursor)) {
                    exhausted = true;
                } else {
                    cursor = result.bottomCursor;
                }
                if (reset) {
                    adapter.replace(result.posts);
                    rv.scrollToPosition(0);
                    rv.post(() -> {
                        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(0);
                        if (vh != null) {
                            vh.itemView.requestFocus();
                        } else {
                            status.setVisibility(View.VISIBLE);
                            status.setText("没有搜到结果，换个关键词试试");
                        }
                    });
                } else {
                    adapter.append(result.posts);
                }
                status.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
            });
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
            if (!feed.isEmpty()) {
                VideoFeedActivity.start(this, feed, index, "搜索：" + keyword);
            }
        } else {
            DetailActivity.start(this, post.id);
        }
    }

    @Override
    public void onLongPress(Post post) {
        // 详情/搜索页长按不做刷新，避免误触
    }

    @Override
    public void onNeedMore() {
        if (!loading && !exhausted) {
            load(false);
        }
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
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}
