package io.github.xbigtv.tv.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
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
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.xbigtv.tv.data.Prefs;
import io.github.xbigtv.tv.net.Http;
import io.github.xbigtv.tv.xapi.MediaItem;
import io.github.xbigtv.tv.xapi.Post;
import io.github.xbigtv.tv.xapi.TimelinePage;
import io.github.xbigtv.tv.xapi.XApi;

/**
 * 抖音式上下翻页视频流：
 * 上下切换视频、OK 播放暂停、左右快退快进、菜单呼出控制面板，
 * 播放 10 秒后自动隐藏作者信息进入沉浸模式，任意按键重新唤出。
 */
@OptIn(markerClass = UnstableApi.class)
public class VideoFeedActivity extends Activity {

    private static List<Post> sFeed = new ArrayList<>();
    private static XApi.TimelineSource sSource;
    private static int sIndex = 0;

    private static final long IMMERSIVE_DELAY_MS = 5_000L;

    public static void start(Activity from, List<Post> feed, int index, String source) {
        start(from, feed, index, source, null);
    }

    /**
     * @param source 继续翻页时使用的来源：从某个分组进来的就继续翻该分组，
     *               这样上下翻视频始终是同一分组的内容。null 表示用全局视频搜索。
     */
    public static void start(Activity from, List<Post> feed, int index, String source,
                            XApi.TimelineSource timelineSource) {
        if (feed == null || feed.isEmpty()) {
            Ui.toast(from, "没有可播放的视频");
            return;
        }
        sFeed = new ArrayList<>(feed);
        sSource = timelineSource;
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

    private LinearLayout infoPanel;
    private LinearLayout topBar;
    private TextView idxText;
    private TextView hintText;
    private TextView authorText;
    private TextView bodyText;
    private TextView statText;
    private TextView timeText;
    private TextView pauseGlyph;
    private TextView errorText;
    private ProgressBar spinner;
    private ProgressBarLine progressLine;
    private LinearLayout panel;
    private TextView speedChip;
    private TextView rotateChip;
    private TextView qualityChip;

    private int index;
    private boolean prepared;
    private boolean panelOpen;
    private boolean loadingMore;
    private boolean rotated;
    private long lastBackAt;
    private boolean resumedOnce;
    private String feedCursor = "";

    private final Runnable hideImmersive = () -> applyChrome(false);
    private final Runnable hideHud = () -> {
        idxText.setVisibility(View.INVISIBLE);
        hintText.setVisibility(View.INVISIBLE);
        timeText.setVisibility(View.INVISIBLE);
        progressLine.setVisibility(View.INVISIBLE);
    };

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
        showImmersive();
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
        aspect.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        textureView = new android.view.TextureView(this);
        aspect.addView(textureView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        alp.gravity = Gravity.CENTER;
        stage.addView(aspect, alp);

        transition = new ImageView(this);
        transition.setScaleType(ImageView.ScaleType.CENTER_CROP);
        transition.setVisibility(View.GONE);
        root.addView(transition, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶部信息条
        topBar = Ui.linear(this, LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(Ui.dp(this, 28), Ui.dp(this, 16), Ui.dp(this, 28), Ui.dp(this, 16));
        idxText = Ui.text(this, "", 18, Ui.TEXT, true);
        topBar.addView(idxText);
        View gap = new View(this);
        gap.setLayoutParams(Ui.lp(Ui.dp(this, 16), 1));
        topBar.addView(gap);
        hintText = Ui.text(this, "▲▼ 切换视频   OK 播放/暂停   ◀▶ 快退/快进   菜单 更多   返回 退出",
                13, 0xCCD0D4D8, false);
        topBar.addView(hintText);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.TOP;
        root.addView(topBar, tlp);

        // 左侧作者信息
        infoPanel = Ui.linear(this, LinearLayout.VERTICAL);
        infoPanel.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        infoPanel.setBackground(Ui.round(0x88000000, Ui.dp(this, 14)));

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
        LinearLayout.LayoutParams alpp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        alpp.leftMargin = Ui.dp(this, 10);
        authorText.setLayoutParams(alpp);
        authorRow.addView(authorText);
        infoPanel.addView(authorRow);

        bodyText = Ui.text(this, "", 16, Ui.TEXT, false);
        bodyText.setMaxLines(4);
        bodyText.setEllipsize(TextUtils.TruncateAt.END);
        bodyText.setLineSpacing(Ui.dp(this, 3), 1f);
        LinearLayout.LayoutParams blp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 8);
        bodyText.setLayoutParams(blp);
        infoPanel.addView(bodyText);

        statText = Ui.text(this, "", 14, 0xCCD0D4D8, false);
        LinearLayout.LayoutParams slpp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slpp.topMargin = Ui.dp(this, 8);
        statText.setLayoutParams(slpp);
        infoPanel.addView(statText);

        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(Ui.dp(this, 640),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        ilp.leftMargin = Ui.dp(this, 36);
        ilp.bottomMargin = Ui.dp(this, 46);
        root.addView(infoPanel, ilp);

        pauseGlyph = Ui.text(this, "❚❚", 46, 0xCCFFFFFF, true);
        pauseGlyph.setVisibility(View.GONE);
        FrameLayout.LayoutParams pgp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pgp.gravity = Gravity.CENTER;
        root.addView(pauseGlyph, pgp);

        spinner = new ProgressBar(this);
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
        player.setVolume(1f);   // 全屏播放始终有声（静音按钮已移除）
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
                    restoreResumeOnce();
                }
                if (state == Player.STATE_ENDED) {
                    onEnded();
                }
            }

            @Override
            public void onVideoSizeChanged(androidx.media3.common.VideoSize size) {
                // AspectRatioFrameLayout 只有被显式告知宽高比才会做 letterbox，
                // 否则竖屏视频会被拉伸铺满（文字/人脸变形）。旋转时按 90° 互换。
                if (size != null && size.height > 0 && size.width > 0) {
                    float ratio = size.width * size.pixelWidthHeightRatio / size.height;
                    if (rotated) {
                        ratio = 1f / ratio;
                    }
                    aspect.setAspectRatio(ratio);
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
            if (direction > 0) {
                loadMoreThen(newIndex, direction);
            } else {
                Ui.toast(this, "已经是第一条");
            }
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
        resumedOnce = false;
        rotated = false;
        errorText.setVisibility(View.GONE);
        spinner.setVisibility(View.VISIBLE);
        bindOverlay(post, media);
        applyVideoLayout(media);
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
        // 倍速跨视频保留
        player.setPlaybackParameters(new PlaybackParameters(Prefs.speed()));
        showImmersive();
    }

    /** 竖屏视频居中不拉伸；横屏按设置铺满/适应。 */
    private void applyVideoLayout(MediaItem media) {
        boolean portrait = media.height > media.width;
        int sw = Ui.screenW(this);
        int sh = Ui.screenH(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(sw, sh);
        lp.gravity = Gravity.CENTER;
        if (rotated) {
            lp = new FrameLayout.LayoutParams(sh, sw);
            lp.gravity = Gravity.CENTER;
            aspect.setRotation(90f);
            aspect.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
        } else {
            aspect.setRotation(0f);
            if (portrait) {
                aspect.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            } else {
                aspect.setResizeMode(Prefs.fillScreen()
                        ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        : AspectRatioFrameLayout.RESIZE_MODE_FIT);
            }
        }
        aspect.setLayoutParams(lp);
        if (rotateChip != null) {
            rotateChip.setVisibility(portrait ? View.VISIBLE : View.GONE);
        }
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
        String text = post.text;
        if (post.hasTranslation()) {
            text = text + "\n\n【Grok 翻译】" + post.translation;
        }
        bodyText.setText(text);
        bodyText.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
        StringBuilder sb = new StringBuilder();
        sb.append("♥ ").append(Post.count(post.likes))
                .append("    ⇄ ").append(Post.count(post.retweets))
                .append("    💬 ").append(Post.count(post.replies))
                .append("    👁 ").append(Post.count(post.views));
        if (media.durationMs > 0) {
            sb.append("    ⏱ ").append(media.durationText());
        }
        statText.setText(sb.toString());

        View avatar = infoPanel.findViewWithTag("avatar");
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
        if (dur > 60000 && pos > 5000 && dur - pos > 5000) {
            Prefs.saveResume(sFeed.get(index).id, pos);
        } else {
            Prefs.saveResume(sFeed.get(index).id, 0L);
        }
    }

    /** 只续播一次，避免 STATE_READY 反复触发导致来回 seek 卡死。 */
    private void restoreResumeOnce() {
        if (resumedOnce || index >= sFeed.size()) {
            return;
        }
        resumedOnce = true;
        long saved = Prefs.resumeOf(sFeed.get(index).id);
        long dur = player.getDuration();
        if (saved > 5000 && dur > 0 && saved < dur - 3000) {
            player.seekTo(saved);
            Ui.toast(this, "从上次位置继续播放");
        }
        // 清掉旧进度，防止下次进入又跳回去
        Prefs.saveResume(sFeed.get(index).id, 0L);
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
            TimelinePage page = null;
            try {
                if (sSource != null) {
                    page = sSource.fetch(feedCursor);
                } else {
                    page = XApi.videoFeed(null, feedCursor);
                }
            } catch (Throwable ignored) {
            }
            final TimelinePage result = page;
            main.post(() -> {
                loadingMore = false;
                if (result != null && !result.posts.isEmpty()) {
                    HashSet<String> known = new HashSet<>();
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
        showImmersive();

        speedChip = panelChip(speedLabel(), v -> closePanel());
        speedChip.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                adjustSpeed(0.1f);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                adjustSpeed(-0.1f);
                return true;
            }
            return false;
        });
        panel.addView(speedChip);

        qualityChip = panelChip("清晰度 " + qualityLabel(), v -> cycleQuality());
        panel.addView(qualityChip);
        panel.addView(panelChip(Prefs.fillScreen() ? "画面 铺满" : "画面 适应", v -> {
            Prefs.setFillScreen(!Prefs.fillScreen());
            Post cur = sFeed.get(index);
            MediaItem m = cur.firstVideo();
            if (m != null) {
                rotated = false;
                applyVideoLayout(m);
            }
            closePanel();
        }));

        rotateChip = panelChip("旋转 90°", v -> {
            rotated = !rotated;
            Post cur = sFeed.get(index);
            MediaItem m = cur.firstVideo();
            if (m != null) {
                applyVideoLayout(m);
            }
            closePanel();
        });
        MediaItem curMedia = sFeed.get(index).firstVideo();
        rotateChip.setVisibility(curMedia != null && curMedia.height > curMedia.width
                ? View.VISIBLE : View.GONE);
        panel.addView(rotateChip);

        panel.addView(panelChip(Prefs.autoNext() ? "连播 开" : "连播 关", v -> {
            Prefs.setAutoNext(!Prefs.autoNext());
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
        main.removeCallbacks(hideImmersive);
    }

    private TextView panelChip(String label, View.OnClickListener onClick) {
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
        LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 8);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void closePanel() {
        panelOpen = false;
        panel.setVisibility(View.GONE);
        panel.removeAllViews();
        showImmersive();
    }

    private String speedLabel() {
        return String.format(Locale.US, "倍速 %.1f×（▲▼）", Prefs.speed());
    }

    private void adjustSpeed(float delta) {
        float cur = Prefs.speed();
        float next = Math.round((cur + delta) * 10f) / 10f;
        if (next < 0.5f) {
            next = 0.5f;
        }
        if (next > 3.0f) {
            next = 3.0f;
        }
        Prefs.setSpeed(next);
        if (player != null) {
            player.setPlaybackParameters(new PlaybackParameters(next));
        }
        if (speedChip != null) {
            speedChip.setText(speedLabel());
        }
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
        // 按钮文案立刻更新，不等重新准备完
        if (qualityChip != null) {
            qualityChip.setText("清晰度 " + qualityLabel());
        }
        playIndex(index, 0);
        if (qualityChip != null) {
            qualityChip.setText("清晰度 " + qualityLabel());
        }
    }

    private void doLike(Post post) {
        final boolean target = !post.liked;
        io.execute(() -> {
            try {
                XApi.like(post.id, target);
                post.liked = target;
                post.likes += target ? 1 : -1;
                main.post(() -> {
                    MediaItem m = post.firstVideo();
                    bindOverlay(post, m == null ? new MediaItem() : m);
                    Ui.toast(this, target ? "已点赞" : "已取消点赞");
                });
            } catch (Exception e) {
                main.post(() -> Ui.toast(this, "操作失败：" + e.getMessage()));
            }
        });
        closePanel();
    }

    /* ---------------- 沉浸模式 ---------------- */

    private void showImmersive() {
        applyChrome(true);
        main.removeCallbacks(hideHud);
        main.removeCallbacks(hideImmersive);
        if (!panelOpen) {
            main.postDelayed(hideHud, 4000);
            // 10 秒后连作者信息一起隐藏，进入沉浸式观看
            main.postDelayed(hideImmersive, IMMERSIVE_DELAY_MS);
        }
    }

    private void applyChrome(boolean visible) {
        if (panelOpen) {
            return;
        }
        int v = visible ? View.VISIBLE : View.INVISIBLE;
        idxText.setVisibility(v);
        hintText.setVisibility(v);
        timeText.setVisibility(v);
        progressLine.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        infoPanel.setVisibility(v);
        if (!visible) {
            topBar.setVisibility(View.INVISIBLE);
        } else {
            topBar.setVisibility(View.VISIBLE);
        }
    }

    /* ---------------- 按键 ---------------- */

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean okKey = keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        if (keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_MENU) {
            if (infoPanel.getVisibility() != View.VISIBLE) {
                if (okKey) {
                    // 沉浸态 OK：暂停 → 露出 UI；继续播放 → UI 一起消失
                    if (prepared && player != null) {
                        if (player.isPlaying()) {
                            player.pause();
                            showImmersive();
                        } else {
                            player.play();
                            main.removeCallbacks(hideHud);
                            main.removeCallbacks(hideImmersive);
                            applyChrome(false);
                        }
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    seekBy(keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -10000 : 10000);
                    return true;
                }
                showImmersive();
                return true;
            }
        }
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
            case KeyEvent.KEYCODE_BACK: {
                long now = System.currentTimeMillis();
                // 1.5 秒内再按一次 → 退出（不受 UI 显隐影响，避免自动连播把 UI 又顶出来导致退不出）
                if (now - lastBackAt < 1500) {
                    finish();
                    return true;
                }
                lastBackAt = now;
                if (infoPanel.getVisibility() == View.VISIBLE) {
                    // 第一次返回：只收起 UI，画面继续播
                    main.removeCallbacks(hideHud);
                    main.removeCallbacks(hideImmersive);
                    applyChrome(false);
                } else {
                    Ui.toast(this, "再按一次返回键退出播放");
                }
                return true;
            }
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
        } else {
            player.play();
        }
        showImmersive();
    }

    private void seekBy(long delta) {
        if (player == null || !prepared) {
            return;
        }
        long target = Math.max(0, Math.min(player.getDuration(), player.getCurrentPosition() + delta));
        player.seekTo(target);
        Ui.toast(this, (delta > 0 ? "快进 " : "快退 ") + Math.abs(delta / 1000) + " 秒");
        showImmersive();
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

    /** 底部细进度条 + 时间码。 */
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
            return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
        }
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
