package io.github.xbigtv.tv.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

/** 帖子详情：正文 + 评论，视频帖可直接进入沉浸视频流。 */
public class DetailActivity extends Activity implements PostAdapter.Listener {

    private static String sPendingId = "";

    public static void start(Activity from, String tweetId) {
        sPendingId = tweetId;
        from.startActivity(new Intent(from, DetailActivity.class));
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private RecyclerView rv;
    private PostAdapter adapter;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        load();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BLACK);

        LinearLayout col = Ui.linear(this, LinearLayout.VERTICAL);

        LinearLayout head = Ui.linear(this, LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(this, 26), Ui.dp(this, 12), Ui.dp(this, 26), Ui.dp(this, 12));
        TextView back = Ui.text(this, "‹ 返回", 16, Ui.TEXT, true);
        back.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        back.setFocusable(true);
        back.setOnClickListener(v -> finish());
        back.setOnFocusChangeListener((v, has) -> v.setBackground(has
                ? Ui.round(0x26FFFFFF, Ui.dp(this, 16)) : null));
        head.addView(back);
        head.addView(Ui.text(this, "   帖子详情", 20, Ui.TEXT, true));
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

        status = Ui.text(this, "正在加载…", 18, Ui.TEXT_DIM, false);
        status.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.CENTER;
        wrap.addView(status, slp);

        col.addView(wrap, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private void load() {
        final String id = sPendingId;
        status.setVisibility(View.VISIBLE);
        io.execute(() -> {
            TimelinePage page;
            try {
                page = XApi.detail(id);
            } catch (Throwable t) {
                page = new TimelinePage();
                page.error = t.getMessage();
            }
            final TimelinePage result = page;
            main.post(() -> {
                if (result.posts.isEmpty()) {
                    status.setText("加载失败：" + (result.error == null ? "无内容" : result.error));
                    return;
                }
                status.setVisibility(View.GONE);
                adapter.replace(result.posts);
                rv.post(() -> {
                    RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(0);
                    if (vh != null) {
                        vh.itemView.requestFocus();
                    }
                });
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
                VideoFeedActivity.start(this, feed, index, "帖子详情");
            }
        }
    }

    @Override
    public void onNeedMore() {
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
