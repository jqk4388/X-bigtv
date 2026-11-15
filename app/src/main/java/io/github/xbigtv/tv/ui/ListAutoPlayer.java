package io.github.xbigtv.tv.ui;

import android.content.Context;
import android.view.TextureView;

import androidx.annotation.OptIn;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.util.HashMap;

import io.github.xbigtv.tv.data.Prefs;
import io.github.xbigtv.tv.net.Http;
import io.github.xbigtv.tv.xapi.MediaItem;
import io.github.xbigtv.tv.xapi.Post;
import io.github.xbigtv.tv.xapi.XApi;

/**
 * 列表里「焦点在哪个视频卡片，就在那张卡片里静音自动播放」。
 * 全屏播放器（VideoFeedActivity）另有一套，互不干扰。
 */
@OptIn(markerClass = UnstableApi.class)
public final class ListAutoPlayer {

    private ExoPlayer player;
    private String currentUrl = "";
    private PostCardView attached;

    /** 播放器报出真实画面尺寸后，把卡片里的媒体框改回正确比例 */
    private final Player.Listener sizeListener = new Player.Listener() {
        @Override
        public void onVideoSizeChanged(VideoSize size) {
            if (attached == null || size.width <= 0 || size.height <= 0) {
                return;
            }
            float ratio = size.width * size.pixelWidthHeightRatio / size.height;
            attached.applyVideoAspect(ratio);
        }
    };

    public void attach(Context context, Post post, PostCardView card) {
        if (post == null || card == null || !post.hasVideo()) {
            detach();
            return;
        }
        MediaItem media = post.firstVideo();
        if (media == null) {
            detach();
            return;
        }
        String url = chooseUrl(media);
        if (url.isEmpty()) {
            detach();
            return;
        }
        ensurePlayer(context);
        TextureView surface = card.videoSurface();
        if (surface == null) {
            detach();
            return;
        }
        if (attached != null && attached != card) {
            attached.setAutoplayActive(false);
        }
        attached = card;
        card.setAutoplayActive(true);
        player.setVideoTextureView(surface);
        if (!url.equals(currentUrl)) {
            currentUrl = url;
            player.setMediaItem(androidx.media3.common.MediaItem.fromUri(url));
            player.prepare();
        }
        player.setVolume(0f);          // 列表内静音
        player.setPlayWhenReady(true);
    }

    public void detach() {
        if (attached != null) {
            attached.setAutoplayActive(false);
            attached = null;
        }
        if (player != null) {
            player.setPlayWhenReady(false);
            try {
                player.stop();
            } catch (Throwable ignored) {
            }
            player.clearVideoTextureView(null);
            currentUrl = "";
        }
    }

    public void pause() {
        if (player != null) {
            player.setPlayWhenReady(false);
        }
    }

    public void release() {
        detach();
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void ensurePlayer(Context context) {
        if (player != null) {
            return;
        }
        OkHttpDataSource.Factory http = new OkHttpDataSource.Factory(Http.client())
                .setUserAgent(XApi.UA)
                .setDefaultRequestProperties(new HashMap<String, String>() {{
                    put("referer", "https://x.com/");
                    put("origin", "https://x.com");
                }});
        player = new ExoPlayer.Builder(context)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .build();
        player.setVolume(0f);
        player.addListener(sizeListener);
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
}
