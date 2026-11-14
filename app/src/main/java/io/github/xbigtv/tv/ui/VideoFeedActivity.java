package io.github.xbigtv.tv.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.xbigtv.tv.data.Prefs;
import io.github.xbigtv.tv.net.Http;
import io.github.xbigtv.tv.xapi.MediaItem;
import io.github.xbigtv.tv.xapi.Post;
import io.github.xbigtv.tv.xapi.XApi;

/**
 * 抖音式上下翻页视频流：遥控器上/下切换视频，OK 播放暂停，左右快进快退，
 * 菜单键呼出「倍速 / 清晰度 / 画面比例 / 连播 / 静音 / 点赞」面板。
 */
@OptIn(markerClass = UnstableApi.class)
public class VideoFeedActivity extends Activity {

    private static List<Post> sFeed = new ArrayList<>();
    private static int sIndex = 0;

    public static void start(Activity from, List<Post> feed, int index, String source) {
        if (feed == null || feed.isEmpty()) {
            Ui.toast(from, "没有可播放的视频");
            return;
        }
        sFeed = new ArrayList<>(feed);
        sIndex = Math.max(0, Math.min(index, feed.size() - 1));
        Intent it = new Intent(from, VideoFeedActivity.class);
        it.putExtra("source", source == null ? "" : source);
        from.startActivity(it);
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private ExoPlayer player;
    private AspectRatioFrameLayout aspect;
    private android.view.TextureView textureView;
    private FrameLayout stage;
    private ImageView transition;

    private TextView idxText;
    private TextView authorText;
    private TextView bodyText;
    private TextView statText;
    private TextView timeText;
    private TextView hintText;
    private TextView pauseGlyph;
    private TextView errorText;
    private ProgressBar spinner;
    private ProgressBarLine progressLine;

    private LinearLayout panel;

    private int index;
    private boolean prepared;
    private boolean panelOpen;
    private boolean userPaused;
    private boolean loadingMore;
    private String feedCursor = "";

    private final Runnable hideHud = () -> setHudVisible(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        index = sIndex;
        if (sFeed.isEmpty()) {
            finish();
            return;
        }
        buildUi();
        createPlayer();
        ViewParentHost.sInstance = new ViewParentHost();
        playIndex(index, 0);
        showHud();
        main.post(ticker);
    }

    /* ---------------- UI ---------------- */

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BLACK);

        stage = new FrameLayout(this);
        root.addView(stage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 用 TextureView 而不是 SurfaceView：本机（4K 盒子）上 SurfaceView 会被父容器的
        // 背景层盖住，表现为"有声音、画面全黑"。
        aspect = new AspectRatioFrameLayout(this);
        aspect.setResizeMode(Prefs.fillScreen()
                ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                : AspectRatioFrameLayout.RESIZE_MODE_FIT);
        textureView = new android.view.TextureView(this);
        aspect.addView(textureView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stage.addView(aspect, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 切换用缩略图层
        transition = new ImageView(this);
        transition.setScaleType(ImageView.ScaleType.CENTER_CROP);
        transition.setVisibility(View.GONE);
        root.addView(transition, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶部信息
        LinearLayout top = Ui.linear(this, LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(this, 28), Ui.dp(this, 18), Ui.dp(this, 28), Ui.dp(this, 18));
        idxText = Ui.text(this, "", 18, Ui.TEXT, true);
        top.addView(idxText);
        View gap = new View(this);
        gap.setLayoutParams(Ui.lp(Ui.dp(this, 16), 1));
        top.addView(gap);
        hintText = Ui.text(this, "▲▼ 切换视频   OK 播放/暂停   ◀▶ 快退/快进   菜单 更多   返回 退出",
                13, uiDim(), false);
        top.addView(hintText);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.TOP;
        root.addView(top, tlp);
        top.setBackground(Ui.round(0x66000000, 0));

        // 左侧文案
        LinearLayout info = Ui.linear(this, LinearLayout.VERTICAL);
        info.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        info.setBackground(Ui.round(0x77000000, Ui.dp(this, 14)));

        LinearLayout authorRow = Ui.linear(this, LinearLayout.HORIZONTAL);
        authorRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView avatar = new ImageView(this);
        int avs = Ui.dp(this, 40);
        avatar.setLayoutParams(Ui.lp(avs, avs));
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setBackground(Ui.round(0xFF2A2E33, avs / 2f));
        avatar.setClipToOutline(true);
        avatar.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        avatar.setTag("avatar");
        authorRow.addView(avatar);
        authorText = Ui.text(this, "", 18, Ui.TEXT, true);
        LinearLayout.LayoutParams alp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = Ui.dp(this, 10);
        authorText.setLayoutParams(alp);
        authorRow.addView(authorText);
        info.addView(authorRow);

        bodyText = Ui.text(this, "", 16, Ui.TEXT, false);
        bodyText.setMaxLines(4);
        bodyText.setEllipsize(TextUtils.TruncateAt.END);
        bodyText.setLineSpacing(Ui.dp(this, 3), 1f);
        LinearLayout.LayoutParams blp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 8);
        bodyText.setLayoutParams(blp);
        info.addView(bodyText);

        statText = Ui.text(this, "", 14, uiDim(), false);
        LinearLayout.LayoutParams slp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(this, 8);
        statText.setLayoutParams(slp);
        info.addView(statText);

        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(Ui.dp(this, 640),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        ilp.leftMargin = Ui.dp(this, 36);
        ilp.bottomMargin = Ui.dp(this, 46);
        root.addView(info, ilp);

        // 暂停大图标
        pauseGlyph = Ui.text(this, "❚❚", 46, 0xCCFFFFFF, true);
        pauseGlyph.setVisibility(View.GONE);
        FrameLayout.LayoutParams pgp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pgp.gravity = Gravity.CENTER;
        root.addView(pauseGlyph, pgp);

        // 加载圈
        spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        FrameLayout.LayoutParams splp = new FrameLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52));
        splp.gravity = Gravity.CENTER;
        root.addView(spinner, splp);

        errorText = Ui.text(this, "", 18, Ui.AMBER, false);
        errorText.setGravity(Gravity.CENTER);
        errorText.setVisibility(View.GONE);
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(Ui.dp(this, 700),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.gravity = Gravity.CENTER;
        root.addView(errorText, elp);

        // 底部进度条
        progressLine = new ProgressBarLine(this);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 24));
        plp.gravity = Gravity.BOTTOM;
        root.addView(progressLine, plp);

        timeText = Ui.text(this, "", 13, 0xCCFFFFFF, false);
        FrameLayout.LayoutParams ttlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ttlp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        ttlp.rightMargin = Ui.dp(this, 30);
        ttlp.bottomMargin = Ui.dp(this, 18);
        root.addView(timeText, ttlp);

        // 控制面板
        panel = Ui.linear(this, LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER);
        panel.setVisibility(View.GONE);
        panel.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 10));
        panel.setBackground(Ui.round(0xE616181C, Ui.dp(this, 16), Ui.BORDER, Math.max(1, Ui.dp(this, 1))));
        FrameLayout.LayoutParams pllp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pllp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        pllp.bottomMargin = Ui.dp(this, 46);
        root.addView(panel, pllp);

        setContentView(root);
    }

    private int uiDim() {
        return 0xCCD0D4D8;
    }

    /* ---------------- 播放器 ---------------- */

    private void createPlayer() {
        OkHttpDataSource.Factory http = new OkHttpDataSource.Factory(Http.client())
                .setUserAgent(XApi.UA)
                .setDefaultRequestProperties(new java.util.HashMap<String, String>() {{
                    put("referer", "https://x.com/");
                    put("origin", "https://x.com");
                }});
        DefaultMediaSourceFactory msf = new DefaultMediaSourceFactory(http);
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 60000, 1200, 2500)
                .build();
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(msf)
                .setLoadControl(loadControl)
                .build();
        player.setVideoTextureView(textureView);
        player.setVolume(Prefs.muted() ? 0f : 1f);
        player.setPlaybackParameters(new PlaybackParameters(Prefs.speed()));
        applyQuality();

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) {
                    spinner.setVisibility(View.VISIBLE);
                } else {
                    spinner.setVisibility(View.GONE);
                }
                if (state == Player.STATE_READY) {
                    prepared = true;
                    errorText.setVisibility(View.GONE);
                    restoreResume();
                }
                if (state == Player.STATE_ENDED) {
                    onEnded();
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                pauseGlyph.setVisibility(isPlaying ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                spinner.setVisibility(View.GONE);
                prepared = false;
                errorText.setVisibility(View.VISIBLE);
                errorText.setText("这条视频播放失败：" + shortMsg(error) + "\n\n按「上/下」切换到其它视频");
            }
        });
    }

    private String shortMsg(PlaybackException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.length() > 90) {
            m = m.substring(0, 90);
        }
        return m;
    }

    private void applyQuality() {
        if (player == null) {
            return;
        }
        int cap = MediaItem.bitrateCap(Prefs.quality());
        TrackSelectionParameters p = player.getTrackSelectionParameters()
                .buildUpon()
                .setMaxVideoBitrate(cap == Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.min(cap, 8000000))
                .build();
        player.setTrackSelectionParameters(p);
    }

    private void playIndex(int newIndex, int direction) {
        if (newIndex < 0 || newIndex >= sFeed.size()) {
            Ui.toast(this, direction > 0 ? "已经是最后一条" : "已经是第一条");
            return;
        }
        saveResume();
        index = newIndex;
        Post post = sFeed.get(index);
        MediaItem media = post.firstVideo();
        if (media == null) {
            next(direction == 0 ? 1 : direction);
            return;
        }
        prepared = false;
        userPaused = false;
        errorText.setVisibility(View.GONE);
        spinner.setVisibility(View.VISIBLE);
        bindOverlay(post, media);
        animateSwitch(post, direction);

        String url = chooseUrl(media);
        if (TextUtils.isEmpty(url)) {
            errorText.setVisibility(View.VISIBLE);
            errorText.setText("这条视频没有可用的播放地址");
            spinner.setVisibility(View.GONE);
            return;
        }
        player.setMediaItem(androidx.media3.common.MediaItem.fromUri(url));
        player.prepare();
        player.setPlayWhenReady(true);
        showHud();
    }

    private String chooseUrl(MediaItem media) {
        MediaItem.Variant hls = media.hls();
        if (hls != null) {
            return hls.url;
        }
        MediaItem.Variant mp4 = media.mp4Under(MediaItem.bitrateCap(Prefs.quality()));
        if (mp4 == null) {
            mp4 = media.best();
        }
        return mp4 == null ? "" : mp4.url;
    }

    private void animateSwitch(Post post, int direction) {
        MediaItem media = post.firstVideo();
        final String thumb = media == null ? "" : media.thumbUrl;
        if (TextUtils.isEmpty(thumb) || direction == 0) {
            transition.setVisibility(View.GONE);
            return;
        }
        final int h = Ui.screenH(this);
        io.execute(() -> {
            Bitmap bmp = ImageLoader.get().fetch(thumb, Ui.screenW(this));
            main.post(() -> {
                if (bmp == null || isFinishing()) {
                    return;
                }
                transition.setImageBitmap(bmp);
                transition.setVisibility(View.VISIBLE);
                transition.setAlpha(1f);
                transition.setTranslationY(direction > 0 ? h * 0.9f : -h * 0.9f);
                transition.animate().translationY(0f).setDuration(220)
                        .setInterpolator(new DecelerateInterpolator()).start();
                transition.postDelayed(() -> transition.animate().alpha(0f).setDuration(240)
                        .withEndAction(() -> {
                            transition.setVisibility(View.GONE);
                            transition.setAlpha(1f);
                        }).start(), 220);
            });
        });
    }

    private void bindOverlay(Post post, MediaItem media) {
        idxText.setText((index + 1) + " / " + sFeed.size());
        authorText.setText(post.authorName + "   @" + post.handle + "  ·  " + post.timeAgo());
        bodyText.setText(post.text);
        bodyText.setVisibility(TextUtils.isEmpty(post.text) ? View.GONE : View.VISIBLE);
        StringBuilder sb = new StringBuilder();
        sb.append("♥ ").append(Post.count(post.likes))
                .append("    ⇄ ").append(Post.count(post.retweets))
                .append("    💬 ").append(Post.count(post.replies))
                .append("    👁 ").append(Post.count(post.views));
        if (media.durationMs > 0) {
            sb.append("    ⏱ ").append(media.durationText());
        }
        statText.setText(sb.toString());

        View info = (View) authorText.getParent().getParent();
        View avatar = info.findViewWithTag("avatar");
        if (avatar instanceof ImageView && !TextUtils.isEmpty(post.avatarUrl)) {
            ImageLoader.get().load(post.avatarUrl, (ImageView) avatar, Ui.dp(this, 40));
        }
    }

    private void saveResume() {
        if (player == null || !prepared || index >= sFeed.size()) {
            return;
        }
        long dur = player.getDuration();
        long pos = player.getCurrentPosition();
        if (dur > 60000 && pos > 3000 && dur - pos > 5000) {
            Prefs.saveResume(sFeed.get(index).id, pos);
        } else {
            Prefs.saveResume(sFeed.get(index).id, 0L);
        }
    }

    private void restoreResume() {
        long saved = Prefs.resumeOf(sFeed.get(index).id);
        if (saved > 3000 && saved < player.getDuration() - 3000) {
            player.seekTo(saved);
            Ui.toast(this, "从上次位置继续播放");
        }
    }

    private void onEnded() {
        if (Prefs.autoNext()) {
            main.postDelayed(() -> next(1), 350);
        } else {
            pauseGlyph.setVisibility(View.VISIBLE);
        }
    }

    private void next(int direction) {
        int target = index + direction;
        if (target >= sFeed.size() && direction > 0) {
            loadMoreThen(target, direction);
            return;
        }
        playIndex(target, direction);
    }

    /** 刷到队尾时自动续接一页全局视频（filter:native_video），做到无限下滑。 */
    private void loadMoreThen(final int target, final int direction) {
        if (loadingMore) {
            return;
        }
        loadingMore = true;
        Ui.toast(this, "正在加载更多视频…");
        io.execute(() -> {
            io.github.xbigtv.tv.xapi.TimelinePage page = null;
            try {
                page = XApi.videoFeed(null, feedCursor);
            } catch (Throwable ignored) {
            }
            final io.github.xbigtv.tv.xapi.TimelinePage result = page;
            main.post(() -> {
                loadingMore = false;
                if (result != null && !result.posts.isEmpty()) {
                    java.util.HashSet<String> known = new java.util.HashSet<>();
                    for (Post p : sFeed) {
                        known.add(p.id);
                    }
                    for (Post p : result.posts) {
                        if (p.hasVideo() && !known.contains(p.id)) {
                            known.add(p.id);
                            sFeed.add(p);
                        }
                    }
                    feedCursor = result.bottomCursor;
                }
                if (target < sFeed.size()) {
                    playIndex(target, direction);
                } else {
                    Ui.toast(this, "没有更多视频了");
                }
            });
        });
    }

    /* ---------------- 面板 ---------------- */

    private void togglePanel() {
        if (panelOpen) {
            closePanel();
            return;
        }
        panel.removeAllViews();
        panelOpen = true;
        panel.setVisibility(View.VISIBLE);

        panel.addView(panelChip("倍速 " + speedLabel(), v -> cycleSpeed()));
        panel.addView(panelChip("清晰度 " + qualityLabel(), v -> cycleQuality()));
        panel.addView(panelChip(Prefs.fillScreen() ? "画面 铺满" : "画面 适应", v -> {
            Prefs.setFillScreen(!Prefs.fillScreen());
            aspect.setResizeMode(Prefs.fillScreen()
                    ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    : AspectRatioFrameLayout.RESIZE_MODE_FIT);
            closePanel();
        }));
        panel.addView(panelChip(Prefs.autoNext() ? "连播 开" : "连播 关", v -> {
            Prefs.setAutoNext(!Prefs.autoNext());
            closePanel();
        }));
        panel.addView(panelChip(Prefs.muted() ? "声音 关" : "声音 开", v -> {
            Prefs.setMuted(!Prefs.muted());
            player.setVolume(Prefs.muted() ? 0f : 1f);
            closePanel();
        }));
        Post cur = sFeed.get(index);
        panel.addView(panelChip(cur.liked ? "取消赞" : "点赞", v -> doLike(cur)));
        panel.addView(panelChip("查看原帖", v -> {
            closePanel();
            DetailActivity.start(this, sFeed.get(index).id);
        }));
        panel.addView(panelChip("关闭", v -> closePanel()));

        panel.post(() -> {
            if (panel.getChildCount() > 0) {
                panel.getChildAt(0).requestFocus();
            }
        });
        main.removeCallbacks(hideHud);
    }

    private View panelChip(String label, View.OnClickListener onClick) {
        TextView tv = Ui.text(this, label, 15, Ui.TEXT, true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        tv.setBackground(Ui.round(0x1FFFFFFF, Ui.dp(this, 20)));
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(false);
        tv.setOnClickListener(onClick);
        tv.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? Ui.round(Ui.TEXT, Ui.dp(this, 20)) : Ui.round(0x1FFFFFFF, Ui.dp(this, 20)));
            ((TextView) v).setTextColor(has ? Ui.BLACK : Ui.TEXT);
        });
        LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 8);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void closePanel() {
        panelOpen = false;
        panel.setVisibility(View.GONE);
        panel.removeAllViews();
        showHud();
    }

    private String speedLabel() {
        return String.format(java.util.Locale.US, "%.2f×", Prefs.speed());
    }

    private void cycleSpeed() {
        float[] all = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
        float cur = Prefs.speed();
        int idx = 0;
        for (int i = 0; i < all.length; i++) {
            if (Math.abs(all[i] - cur) < 0.01f) {
                idx = i;
            }
        }
        float next = all[(idx + 1) % all.length];
        Prefs.setSpeed(next);
        player.setPlaybackParameters(new PlaybackParameters(next));
        Ui.toast(this, "播放速度 " + String.format(java.util.Locale.US, "%.2f×", next));
        closePanel();
    }

    private String qualityLabel() {
        switch (Prefs.quality()) {
            case 1:
                return "1080P";
            case 2:
                return "720P";
            case 3:
                return "480P";
            case 4:
                return "360P";
            default:
                return "自动";
        }
    }

    private void cycleQuality() {
        int q = (Prefs.quality() + 1) % 5;
        Prefs.setQuality(q);
        applyQuality();
        playIndex(index, 0);
        Ui.toast(this, "清晰度 " + qualityLabel());
    }

    private void doLike(Post post) {
        final boolean target = !post.liked;
        io.execute(() -> {
            try {
                XApi.like(post.id, target);
                post.liked = target;
                post.likes += target ? 1 : -1;
                main.post(() -> {
                    bindOverlay(post, post.firstVideo() == null ? new MediaItem() : post.firstVideo());
                    Ui.toast(this, target ? "已点赞" : "已取消点赞");
                });
            } catch (Exception e) {
                main.post(() -> Ui.toast(this, "操作失败：" + e.getMessage()));
            }
        });
        closePanel();
    }

    /* ---------------- HUD ---------------- */

    private void showHud() {
        setHudVisible(true);
        main.removeCallbacks(hideHud);
        if (!panelOpen) {
            main.postDelayed(hideHud, 4000);
        }
    }

    private void setHudVisible(boolean visible) {
        if (panelOpen) {
            return;
        }
        int v = visible ? View.VISIBLE : View.INVISIBLE;
        idxText.setVisibility(v);
        hintText.setVisibility(v);
        timeText.setVisibility(v);
        progressLine.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    /* ---------------- 按键 ---------------- */

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (panelOpen) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) {
                closePanel();
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                next(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                next(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePlay();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                seekBy(-10000);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                seekBy(10000);
                return true;
            case KeyEvent.KEYCODE_MENU:
                togglePanel();
                return true;
            case KeyEvent.KEYCODE_BACK:
                finish();
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                next(1);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                next(-1);
                return true;
            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void togglePlay() {
        if (!prepared) {
            return;
        }
        if (player.isPlaying()) {
            player.pause();
            userPaused = true;
        } else {
            player.play();
            userPaused = false;
        }
        showHud();
    }

    private void seekBy(long delta) {
        if (player == null || !prepared) {
            return;
        }
        long target = Math.max(0, Math.min(player.getDuration(), player.getCurrentPosition() + delta));
        player.seekTo(target);
        Ui.toast(this, (delta > 0 ? "快进 " : "快退 ") + Math.abs(delta / 1000) + " 秒");
        showHud();
    }

    /* ---------------- 生命周期 ---------------- */

    @Override
    protected void onPause() {
        super.onPause();
        saveResume();
        if (player != null) {
            player.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        ViewParentHost.sInstance = null;
        io.shutdownNow();
        if (player != null) {
            player.release();
            player = null;
        }
    }

    /** 底部细进度条 + 时间码，避免整块 PlayerView 控件在 TV 上过重。 */
    @OptIn(markerClass = UnstableApi.class)
    static class ProgressBarLine extends View {
        private final android.graphics.Paint bg = new android.graphics.Paint();
        private final android.graphics.Paint fg = new android.graphics.Paint();
        private final android.graphics.Paint dot =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        ProgressBarLine(android.content.Context c) {
            super(c);
            bg.setColor(0x33FFFFFF);
            fg.setColor(Ui.BLUE);
            dot.setColor(0xFFFFFFFF);
        }

        void update() {
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            float y = h - Ui.dp(getContext(), 6);
            canvas.drawRect(0, y - Ui.dp(getContext(), 1.5f), w, y + Ui.dp(getContext(), 1.5f), bg);
            ViewParentHost host = ViewParentHost.get();
            float p = 0f;
            if (host != null) {
                p = host.fraction();
            }
            canvas.drawRect(0, y - Ui.dp(getContext(), 1.5f), w * p, y + Ui.dp(getContext(), 1.5f), fg);
            canvas.drawCircle(w * p, y, Ui.dp(getContext(), 3.5f), dot);
        }
    }

    /** 简单桥：让进度条读取播放进度 */
    static class ViewParentHost {
        float pos;
        float dur;

        static ViewParentHost sInstance;

        static ViewParentHost get() {
            return sInstance;
        }

        float fraction() {
            return dur <= 0 ? 0f : Math.max(0f, Math.min(1f, pos / dur));
        }
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (player != null) {
                ViewParentHost host = ViewParentHost.get();
                if (host != null) {
                    host.pos = Math.max(0, player.getCurrentPosition());
                    host.dur = Math.max(0, player.getDuration());
                }
                if (progressLine != null) {
                    progressLine.update();
                }
                if (timeText != null && player.getDuration() > 0) {
                    timeText.setText(fmt(player.getCurrentPosition()) + " / " + fmt(player.getDuration()));
                }
            }
            main.postDelayed(this, 300);
        }
    };

    private static String fmt(long ms) {
        long s = ms / 1000;
        if (s >= 3600) {
            return String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
        }
        return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
