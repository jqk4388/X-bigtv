package io.github.xbigtv.tv.ui;

import android.content.Context;
import android.graphics.Outline;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.xbigtv.tv.xapi.MediaItem;
import io.github.xbigtv.tv.xapi.Post;

/** 时间线里的一张帖子卡片：可聚焦、可点击，遥控器上下移动焦点。 */
public class PostCardView extends LinearLayout {

    public interface Listener {
        void onOpen(Post post);
    }

    private final Context c;
    private Post post;
    private Listener listener;

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

        // 头部：头像 + 昵称 + @handle · 时间
        LinearLayout head = Ui.linear(c, HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        ImageView avatar = new ImageView(c);
        int av = Ui.dp(c, 44);
        LinearLayout.LayoutParams avLp = Ui.lp(av, av);
        avatar.setLayoutParams(avLp);
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
            addView(Ui.spacer(c, 10));
        }

        // 媒体
        MediaItem video = p.firstVideo();
        MediaItem photo = p.firstPhoto();
        if (video != null) {
            addView(mediaFrame(video, true, p));
            addView(Ui.spacer(c, 10));
        } else if (photo != null) {
            addView(mediaFrame(photo, false, p));
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

    private View mediaFrame(MediaItem m, boolean isVideo, Post p) {
        int maxW = Ui.dp(c, 620);
        int maxH = Ui.dp(c, 340);
        int w = Math.max(1, m.width);
        int h = Math.max(1, m.height);
        float scale = Math.min(Math.min((float) maxW / w, (float) maxH / h), 2.4f);
        int fw = Math.max(Ui.dp(c, 120), Math.round(w * scale));
        int fh = Math.max(Ui.dp(c, 80), Math.round(h * scale));

        FrameLayout frame = new FrameLayout(c);
        LinearLayout.LayoutParams flp = Ui.lp(fw, fh);
        frame.setLayoutParams(flp);
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
            if (p.media.size() > 1) {
                TextView cnt = Ui.pill(c, p.media.size() + " 个视频");
                FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                clp.gravity = Gravity.TOP | Gravity.LEFT;
                clp.leftMargin = Ui.dp(c, 8);
                clp.topMargin = Ui.dp(c, 8);
                cnt.setLayoutParams(clp);
                frame.addView(cnt);
            }
        } else if (p.media.size() > 1) {
            TextView cnt = Ui.pill(c, "1/" + p.media.size());
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            clp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
            clp.rightMargin = Ui.dp(c, 8);
            clp.bottomMargin = Ui.dp(c, 8);
            cnt.setLayoutParams(clp);
            frame.addView(cnt);
        }
        return frame;
    }
}
