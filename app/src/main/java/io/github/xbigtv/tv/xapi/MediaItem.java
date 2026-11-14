package io.github.xbigtv.tv.xapi;

import java.util.ArrayList;
import java.util.List;

/** 一条可播放/可展示的媒体（视频、动图、图片）。 */
public final class MediaItem {

    public static final int TYPE_PHOTO = 0;
    public static final int TYPE_VIDEO = 1;
    public static final int TYPE_GIF = 2;

    public static final class Variant {
        public final String url;
        public final int bitrate;
        public final String mime;

        public Variant(String url, int bitrate, String mime) {
            this.url = url;
            this.bitrate = bitrate;
            this.mime = mime;
        }

        public boolean isHls() {
            return "application/x-mpegURL".equalsIgnoreCase(mime) || url.contains(".m3u8");
        }

        public boolean isMp4() {
            return "video/mp4".equalsIgnoreCase(mime) || url.contains(".mp4");
        }
    }

    public int type = TYPE_PHOTO;
    public String thumbUrl = "";
    public int width;
    public int height;
    public long durationMs;
    public String title = "";
    public boolean monetizable;
    public final List<Variant> variants = new ArrayList<>();

    public boolean isPlayable() {
        return type == TYPE_VIDEO || type == TYPE_GIF;
    }

    public Variant hls() {
        for (Variant v : variants) {
            if (v.isHls()) {
                return v;
            }
        }
        return null;
    }

    /** 清晰度档位：0=自动 1=1080 2=720 3=480 4=360 */
    public static int bitrateCap(int quality) {
        switch (quality) {
            case 1:
                return 6000000;
            case 2:
                return 2500000;
            case 3:
                return 1000000;
            case 4:
                return 600000;
            default:
                return Integer.MAX_VALUE;
        }
    }

    public Variant mp4Under(int capBps) {
        Variant best = null;
        for (Variant v : variants) {
            if (!v.isMp4()) {
                continue;
            }
            if (v.bitrate <= capBps && (best == null || v.bitrate > best.bitrate)) {
                best = v;
            }
        }
        if (best == null) {
            for (Variant v : variants) {
                if (v.isMp4() && (best == null || v.bitrate < best.bitrate)) {
                    best = v;
                }
            }
        }
        return best;
    }

    public Variant best() {
        return mp4Under(Integer.MAX_VALUE);
    }

    public String durationText() {
        long s = durationMs / 1000L;
        return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
