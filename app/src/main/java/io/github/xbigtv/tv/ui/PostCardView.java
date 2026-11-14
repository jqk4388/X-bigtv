package io.github.xbigtv.tv.ui;

import android.content.Context;
import android.graphics.Outline;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

import io.github.xbigtv.tv.xapi.MediaItem;
import io.github.xbigtv.tv.xapi.Post;

/** 时间线里的一张帖子卡片：可聚焦、可点击，遥控器上下移动焦点，长按 OK 刷新。 */
public class PostCardView extends LinearLayout {

    public interface Listener {
        void onOpen(Post post);

        void onLongPress(Post post);
    }

    private final Context c;
    private Post post;
    private final Listener listener;

    public PostCardView(Context context, Listener listener) {
        super(context);
        this.c = context;
        this.listener = listener;
        setOrientation(VERTICAL);
        setFocusable(true);
        setFocusableInTouchMode(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        int pad = Ui.dp(c, 16);
        setPadding(pad, Ui.dp(c, 12), pad, Ui.dp(c, 12));
        setBackground(Ui.round(Ui.SURFACE, Ui.dp(c, 14)));
        setOnClickListener(v -> {
            if (post != null && listener != null) {
                listener.onOpen(post);
            }
        });
        setOnLongClickListener(v -> {
            if (post != null && listener != null) {
                listener.onLongPress(post);
                return true;
            }
            return false;
        });
        setOnFocusChangeListener((v, has) -> {
            v.setBackground(has
                    ? Ui.round(Ui.SURFACE_HI, Ui.dp(c, 14), Ui.BLUE, Math.max(2, Ui.dp(c, 2)))
                    : Ui.round(Ui.SURFACE, Ui.dp(c, 14)));
            v.animate().translationX(has ? Ui.dp(c, 4) : 0).setDuration(120).start();
        });
    }

    public void bind(Post p) {
        this.post = p;
        removeAllViews();

        if (!TextUtils.isEmpty(p.retweetedBy)) {
            LinearLayout rt = Ui.linear(c, HORIZONTAL);
            rt.setGravity(Gravity.CENTER_VERTICAL);
            rt.addView(Icons.view(c, Icons.REPOST, 14, Ui.TEXT_DIM));
            TextView t = Ui.text(c, "  " + p.retweetedBy + " 转推", 13, Ui.TEXT_DIM, true);
            rt.addView(t);
            addView(rt);
            addView(Ui.spacer(c, 6));
        }

        // 头部
        LinearLayout head = Ui.linear(c, HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        ImageView avatar = new ImageView(c);
        int av = Ui.dp(c, 44);
        avatar.setLayoutParams(Ui.lp(av, av));
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setBackground(Ui.round(0xFF2A2E33, av / 2f));
        avatar.setClipToOutline(true);
        avatar.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        ImageLoader.get().load(p.avatarUrl, avatar, Ui.dp(c, 44));
        head.addView(avatar);

        LinearLayout nameCol = Ui.linear(c, VERTICAL);
        LinearLayout.LayoutParams nameLp = Ui.lp(0, LayoutParams.WRAP_CONTENT, 1f);
        nameLp.leftMargin = Ui.dp(c, 10);
        nameCol.setLayoutParams(nameLp);

        LinearLayout nameRow = Ui.linear(c, HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = Ui.text(c, p.authorName, 17, Ui.TEXT, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        nameRow.addView(name);
        if (p.verified) {
            TextView v = Ui.text(c, " ✓", 13, Ui.BLUE, true);
            v.setBackground(Ui.round(0x1A1D9BF0, Ui.dp(c, 4)));
            v.setPadding(Ui.dp(c, 4), 0, Ui.dp(c, 4), 0);
            nameRow.addView(v);
        }
        nameCol.addView(nameRow);

        TextView handle = Ui.text(c, "@" + p.handle + " · " + p.timeAgo(), 13, Ui.TEXT_DIM, false);
        handle.setSingleLine(true);
        nameCol.addView(handle);
        head.addView(nameCol);
        addView(head);

        addView(Ui.spacer(c, 10));

        // 正文
        if (!TextUtils.isEmpty(p.text)) {
            TextView body = Ui.text(c, p.text, 16, Ui.TEXT, false);
            body.setLineSpacing(Ui.dp(c, 3), 1f);
            body.setMaxLines(9);
            body.setEllipsize(TextUtils.TruncateAt.END);
            addView(body);
            addView(Ui.spacer(c, 6));
        }

        // X 原生翻译
        if (p.hasTranslation()) {
            LinearLayout tr = Ui.linear(c, VERTICAL);
            tr.setPadding(Ui.dp(c, 1), Ui.dp(c, 2), Ui.dp(c, 1), Ui.dp(c, 2));
            TextView tag = Ui.text(c, "翻译自 " + langName(p.sourceLang) + " · Grok", 12, Ui.BLUE, true);
            tr.addView(tag);
            TextView body = Ui.text(c, p.translation, 15, 0xFFC7CBD1, false);
            body.setLineSpacing(Ui.dp(c, 2), 1f);
            body.setMaxLines(8);
            body.setEllipsize(TextUtils.TruncateAt.END);
            body.setPadding(0, Ui.dp(c, 3), 0, 0);
            tr.addView(body);
            addView(tr);
            addView(Ui.spacer(c, 6));
        }

        // 媒体
        MediaItem video = p.firstVideo();
        List<MediaItem> photos = p.photos();
        if (video != null) {
            addView(mediaFrame(video, true, p));
            addView(Ui.spacer(c, 10));
        } else if (!photos.isEmpty()) {
            addView(photoStrip(photos));
            addView(Ui.spacer(c, 10));
        }

        // 引用帖
        if (!TextUtils.isEmpty(p.quoteText)) {
            LinearLayout quote = Ui.linear(c, HORIZONTAL);
            quote.setBackground(Ui.round(0xFF0F1115, Ui.dp(c, 10), Ui.BORDER, Math.max(1, Ui.dp(c, 1))));
            quote.setPadding(Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8));
            LinearLayout qcol = Ui.linear(c, VERTICAL);
            qcol.setLayoutParams(Ui.lp(0, LayoutParams.WRAP_CONTENT, 1f));
            qcol.addView(Ui.text(c, p.quoteName + "  @" + p.quoteHandle, 13, Ui.TEXT_DIM, true));
            TextView qt = Ui.text(c, p.quoteText, 14, Ui.TEXT, false);
            qt.setMaxLines(3);
            qt.setEllipsize(TextUtils.TruncateAt.END);
            qcol.addView(qt);
            quote.addView(qcol);
            if (!TextUtils.isEmpty(p.quoteThumb)) {
                ImageView qiv = new ImageView(c);
                int w = Ui.dp(c, 96);
                qiv.setLayoutParams(Ui.lp(w, Ui.dp(c, 64)));
                qiv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                ImageLoader.get().load(p.quoteThumb, qiv, w);
                quote.addView(qiv);
            }
            addView(quote);
            addView(Ui.spacer(c, 8));
        }

        // 底部计数
        LinearLayout foot = Ui.linear(c, HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.addView(iconStat(Icons.REPLY, Post.count(p.replies)));
        foot.addView(iconStat(Icons.REPOST, Post.count(p.retweets)));
        foot.addView(iconStat(Icons.HEART, Post.count(p.likes)));
        foot.addView(iconStat(Icons.EYE, Post.count(p.views)));
        addView(foot);
    }

    private View iconStat(int icon, String value) {
        LinearLayout row = Ui.linear(c, HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Icons.view(c, icon, 15, Ui.TEXT_DIM));
        TextView t = Ui.text(c, " " + value, 13, Ui.TEXT_DIM, false);
        row.addView(t);
        LinearLayout.LayoutParams lp = Ui.lp(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(c, 22);
        row.setLayoutParams(lp);
        return row;
    }

    static String langName(String code) {
        if (code == null) {
            return "外语";
        }
        switch (code) {
            case "ja":
                return "日语";
            case "en":
                return "英语";
            case "ko":
                return "韩语";
            case "fr":
                return "法语";
            case "de":
                return "德语";
            case "es":
                return "西班牙语";
            case "ru":
                return "俄语";
            case "ar":
                return "阿拉伯语";
            case "pt":
                return "葡萄牙语";
            default:
                return TextUtils.isEmpty(code) ? "外语" : code;
        }
    }

    /** 多图：一行等宽缩略图（最多 4 张，多出来的用 +N 角标提示）。 */
    private View photoStrip(List<MediaItem> photos) {
        int count = photos.size();
        int shown = Math.min(count, 4);
        LinearLayout row = Ui.linear(c, HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int height = Ui.dp(c, count == 1 ? 300 : 180);
        for (int i = 0; i < shown; i++) {
            MediaItem m = photos.get(i);
            FrameLayout cell = new FrameLayout(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, height, 1f);
            if (i > 0) {
                lp.leftMargin = Ui.dp(c, 6);
            }
            cell.setLayoutParams(lp);
            cell.setBackground(Ui.round(0xFF0C0E11, Ui.dp(c, 10)));
            cell.setClipToOutline(true);
            cell.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), Ui.dp(c, 10));
                }
            });

            ImageView iv = new ImageView(c);
            iv.setLayoutParams(new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            ImageLoader.get().load(thumb(m, 640), iv, Ui.dp(c, 400));
            cell.addView(iv);

            if (i == shown - 1 && count > shown) {
                TextView more = Ui.text(c, "+" + (count - shown), 18, 0xFFFFFFFF, true);
                more.setGravity(Gravity.CENTER);
                final FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
                more.setLayoutParams(mlp);
                more.setBackground(Ui.round(0x99000000, 0));
                cell.addView(more);
            }
            if (count > 1) {
                TextView idx = Ui.pill(c, (i + 1) + "/" + count);
                FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                ilp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
                ilp.rightMargin = Ui.dp(c, 6);
                ilp.bottomMargin = Ui.dp(c, 6);
                idx.setLayoutParams(ilp);
                cell.addView(idx);
            }
            row.addView(cell);
        }
        return row;
    }

    static String thumb(MediaItem m, int width) {
        String url = m.thumbUrl;
        if (TextUtils.isEmpty(url)) {
            return url;
        }
        if (url.contains("pbs.twimg.com/media/")) {
            return url + (url.contains("?") ? "&" : "?") + "name=" + (width >= 1000 ? "large" : "small");
        }
        return url;
    }

    private View mediaFrame(MediaItem m, boolean isVideo, Post p) {
        int maxW = Ui.dp(c, 620);
        int maxH = Ui.dp(c, 340);
        int w = Math.max(1, m.width);
        int h = Math.max(1, m.height);
        float scale = Math.min(Math.min((float) maxW / w, (float) maxH / h), 2.4f);
        int fw = Math.max(Ui.dp(c, 120), Math.round(w * scale));
        int fh = Math.max(Ui.dp(c, 80), Math.round(h * scale));

        FrameLayout frame = new FrameLayout(c);
        frame.setLayoutParams(Ui.lp(fw, fh));
        frame.setBackground(Ui.round(0xFF0C0E11, Ui.dp(c, 10)));
        frame.setClipToOutline(true);
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), Ui.dp(c, 10));
            }
        });

        ImageView iv = new ImageView(c);
        iv.setLayoutParams(new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ImageLoader.get().load(m.thumbUrl, iv, fw);
        frame.addView(iv);

        if (isVideo) {
            View play = Icons.view(c, Icons.PLAY, 28, 0xFFFFFFFF);
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(Ui.dp(c, 56), Ui.dp(c, 56));
            plp.gravity = Gravity.CENTER;
            play.setLayoutParams(plp);
            play.setBackground(Ui.round(0xB3000000, Ui.dp(c, 28)));
            play.setPadding(Ui.dp(c, 14), Ui.dp(c, 14), Ui.dp(c, 14), Ui.dp(c, 14));
            frame.addView(play);

            if (m.durationMs > 0) {
                TextView dur = Ui.pill(c, m.durationText());
                FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                dlp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
                dlp.rightMargin = Ui.dp(c, 8);
                dlp.bottomMargin = Ui.dp(c, 8);
                dur.setLayoutParams(dlp);
                frame.addView(dur);
            }
        }
        return frame;
    }
}
