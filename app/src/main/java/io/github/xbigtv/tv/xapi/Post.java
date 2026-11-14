package io.github.xbigtv.tv.xapi;

import java.util.ArrayList;
import java.util.List;

/** 时间线上的一条帖子（已把 X 的新旧两套 user/legacy 结构归一化）。 */
public final class Post {

    public String id = "";
    public String text = "";
    public String authorName = "";
    public String handle = "";
    public String avatarUrl = "";
    public String retweetedBy = "";
    public long createdAtMs;
    public int likes;
    public int retweets;
    public int replies;
    public int quotes;
    public long views;
    public boolean verified;
    public boolean sensitive;
    public boolean liked;

    public final List<MediaItem> media = new ArrayList<>();

    /** 引用帖（可选） */
    public String quoteName = "";
    public String quoteHandle = "";
    public String quoteText = "";
    public String quoteThumb = "";

    public boolean hasVideo() {
        for (MediaItem m : media) {
            if (m.isPlayable()) {
                return true;
            }
        }
        return false;
    }

    public MediaItem firstVideo() {
        for (MediaItem m : media) {
            if (m.isPlayable()) {
                return m;
            }
        }
        return null;
    }

    public MediaItem firstPhoto() {
        for (MediaItem m : media) {
            if (m.type == MediaItem.TYPE_PHOTO) {
                return m;
            }
        }
        return null;
    }

    public String timeAgo() {
        if (createdAtMs <= 0) {
            return "";
        }
        long diff = System.currentTimeMillis() - createdAtMs;
        if (diff < 0) {
            diff = 0;
        }
        long min = diff / 60000L;
        if (min < 1) {
            return "刚刚";
        }
        if (min < 60) {
            return min + " 分钟";
        }
        long hour = min / 60;
        if (hour < 24) {
            return hour + " 小时";
        }
        long day = hour / 24;
        if (day < 30) {
            return day + " 天";
        }
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        return f.format(new java.util.Date(createdAtMs));
    }

    public static String count(long v) {
        if (v >= 100000000L) {
            return String.format(java.util.Locale.US, "%.1f亿", v / 100000000.0);
        }
        if (v >= 10000L) {
            return String.format(java.util.Locale.US, "%.1f万", v / 10000.0);
        }
        return String.valueOf(v);
    }
}
