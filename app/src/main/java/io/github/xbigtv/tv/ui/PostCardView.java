package io.github.xbigtv.tv.ui;

import android.content.Context;
import android.graphics.Outline;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewGroup;
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

        /** 卡片获得焦点（用于列表内静音自动播放） */
        void onCardFocused(Post post, PostCardView card);

        /** 长文折叠状态变化（左方向键） */
        void onExpandedChanged(Post post, boolean expanded);

        /** 方向键右：打开右侧平行窗口 */
        void onOpenRight(Post post);
    }

    private final Context c;
    private Post post;
    private final Listener listener;
    private android.view.TextureView videoSurface;
    private ImageView videoThumb;
    private TextView bodyView;
    private TextView foldHint;
    private View mediaContent;
    private boolean expanded;
    private boolean foldingEnabled = true;
    private boolean foldNeeded;
    /** 当前折叠后确实还有内容没显示（决定左键是否有意义） */
    private boolean foldActive;
    private int totalBodyLines = 1;
    private int collapsedLineLimit = 6;
    private int mediaNaturalH;
    private TextView translationBody;
    private View translationBlock;
    private static final int DEFAULT_COLLAPSED_LINES = 6;
    /** 有译文时原文只留这么多行，把高度让给译文和媒体 */
    private static final int TRANSLATED_ORIGIN_LINES = 2;

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
            if (has && post != null && listener != null) {
                listener.onCardFocused(post, this);
            }
        });
        // 左键只负责展开/收起正文；OK 负责播放视频/看大图和打开帖子；右键打开详情侧栏。
        // 三个键各管一件事，展开文字和全屏播放不再抢同一个键。
        setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && foldActive && foldingEnabled) {
                setExpanded(!expanded);
                if (listener != null && post != null) {
                    listener.onExpandedChanged(post, expanded);
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (post != null && listener != null) {
                    listener.onOpenRight(post);
                    return true;
                }
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (post != null && listener != null) {
                    listener.onOpen(post);
                    return true;
                }
            }
            return false;
        });
    }

    public void bind(Post p) {
        bind(p, false);
    }

    public void bind(Post p, boolean expandedState) {
        bind(p, expandedState, true);
    }

    public void bind(Post p, boolean expandedState, boolean allowFolding) {
        this.post = p;
        this.foldingEnabled = allowFolding;
        this.expanded = expandedState;
        this.videoSurface = null;
        this.videoThumb = null;
        this.bodyView = null;
        this.foldHint = null;
        this.mediaContent = null;
        this.foldNeeded = false;
        this.foldActive = false;
        this.totalBodyLines = 1;
        this.translationBody = null;
        this.translationBlock = null;
        this.collapsedLineLimit = DEFAULT_COLLAPSED_LINES;
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

        // 正文先建立完整视图；每条长文按卡片内容与媒体高度独立计算折叠行数。
        if (!TextUtils.isEmpty(p.text)) {
            bodyView = Ui.text(c, p.text, 16, Ui.TEXT, false);
            bodyView.setLineSpacing(Ui.dp(c, 3), 1f);
            addView(bodyView);
            if (isLongText(p.text) || p.hasTranslation()) {
                foldNeeded = true;
                foldHint = Ui.text(c, "", 12, Ui.BLUE, true);
                foldHint.setPadding(0, Ui.dp(c, 2), 0, 0);
                addView(foldHint);
            }
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
            translationBlock = tr;
            translationBody = body;
        }

        // 媒体
        MediaItem video = p.firstVideo();
        List<MediaItem> photos = p.photos();
        if (video != null) {
            mediaContent = mediaFrame(video, true, p);
            addView(mediaContent);
            addView(Ui.spacer(c, 10));
        } else if (!photos.isEmpty()) {
            mediaContent = photoStrip(photos);
            addView(mediaContent);
            addView(Ui.spacer(c, 10));
        }
        mediaNaturalH = mediaNaturalHeight();   // 记住原始尺寸，重算时都从这里出发
        if (foldNeeded) {
            foldActive = true;              // 先按默认行数折一下，量完再按本帖内容微调
            applyFold();
        }
        post(this::calculateCollapsedLines);    // 没有长文的帖子也要按整卡高度调媒体

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

    /** 列表内自动播放挂载点 */
    public android.view.TextureView videoSurface() {
        return videoSurface;
    }

    /** 是否属于需要折叠的长文（> 约 6 行）*/
    static boolean isLongText(String text) {
        if (text == null) {
            return false;
        }
        int lineBreaks = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lineBreaks++;
            }
        }
        return lineBreaks >= DEFAULT_COLLAPSED_LINES || text.length() > 260;
    }

    public boolean isExpanded() {
        return expanded;
    }

    /** 左键切换折叠，OK 保留给打开帖子或媒体。 */
    public void setExpanded(boolean value) {
        this.expanded = value;
        applyFold();
    }

    private void applyFold() {
        if (bodyView == null) {
            return;
        }
        boolean folded = foldingEnabled && foldActive && !expanded;
        if (folded) {
            bodyView.setMaxLines(Math.max(1, collapsedLineLimit));
            bodyView.setEllipsize(TextUtils.TruncateAt.END);
        } else {
            bodyView.setMaxLines(Integer.MAX_VALUE);
            bodyView.setEllipsize(null);
        }
        if (foldHint != null) {
            if (!foldingEnabled || !foldActive) {
                foldHint.setVisibility(View.GONE);
            } else {
                foldHint.setVisibility(View.VISIBLE);
                foldHint.setText(expanded ? "收起 ◀" : "… 展开全文 ◀");
            }
        }
        if (mediaContent != null) {
            mediaContent.setVisibility(View.VISIBLE);
        }
    }

    /**
    /**
     * 每帖单独算：正文行数由“视口 − 头像行/译文/提示行/计数 − 媒体 − 间距”推出来，
     * 媒体太高或整卡超屏时按比例压缩，保证图片/视频整块露在屏幕里。
     * 有译文时原文先让位（最多两行），余下高度优先给译文，译文最少留两行。
     * 每个帖子的行数、媒体高度互不影响。
     */
    private void calculateCollapsedLines() {
        if (!foldingEnabled || getWidth() <= 0) {
            return;
        }
        if (bodyView == null && mediaContent == null && translationBlock == null) {
            return;
        }
        int contentWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        if (contentWidth <= 0) {
            return;
        }
        int widthSpec = MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY);
        int heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);

        int totalLines = 0;
        int lineHeight = Ui.dp(c, 18);
        if (bodyView != null) {
            totalLines = measureLines(bodyView, widthSpec, heightSpec);
            lineHeight = Math.max(Ui.dp(c, 14), bodyView.getLineHeight());
        }
        totalBodyLines = totalLines;
        boolean hasTranslation = translationBody != null && !TextUtils.isEmpty(translationBody.getText());

        // 除正文、译文、媒体以外的内容（转推条、头像行、提示行、引用帖、底部计数）
        int prefixHeight = getPaddingTop() + getPaddingBottom();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child == bodyView || child == translationBlock || child == mediaContent) {
                continue;
            }
            child.measure(widthSpec, heightSpec);
            prefixHeight += child.getMeasuredHeight();
            if (child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
                prefixHeight += lp.topMargin + lp.bottomMargin;
            }
        }

        int maxHeight = listViewportHeight();
        int minMediaHeight = Ui.dp(c, 90);
        int mediaH = mediaNaturalH > 0 ? mediaNaturalH : mediaNaturalHeight();
        if (mediaContent != null && mediaH > 0) {
            applyMediaHeight(mediaH);       // 上次可能被压过，先还原成原始尺寸再算
        }
        int trTotal = hasTranslation ? measureLines(translationBody, widthSpec, heightSpec) : 0;
        int trLineHeight = hasTranslation ? Math.max(Ui.dp(c, 14), translationBody.getLineHeight()) : Ui.dp(c, 14);
        int trChrome = hasTranslation ? Ui.dp(c, 20) : 0;   // “翻译自 xx · Grok”标签行

        // 只估一次起点，之后只允许往下减，避免“压媒体→多显示文字→又超高”来回震荡
        int spare = maxHeight - prefixHeight - mediaH - Ui.dp(c, 10) - trChrome;
        int originLines;
        int trLines = 0;
        if (hasTranslation) {
            originLines = Math.min(totalLines, TRANSLATED_ORIGIN_LINES);
            int trRoom = spare - originLines * lineHeight;
            trLines = Math.max(2, Math.min(trTotal, trRoom / Math.max(1, trLineHeight)));
        } else if (totalLines > 0 && mediaH > 0) {
            originLines = Math.max(1, Math.min(totalLines, spare / Math.max(1, lineHeight)));
        } else if (totalLines > 0) {
            originLines = Math.min(totalLines, DEFAULT_COLLAPSED_LINES);
        } else {
            originLines = 0;
        }

        for (int pass = 0; pass < 14; pass++) {
            collapsedLineLimit = totalLines > 0 ? Math.max(1, Math.min(totalLines, originLines)) : 0;
            foldActive = totalLines > 0 && collapsedLineLimit < totalLines;
            if (hasTranslation) {
                translationBody.setMaxLines(Math.max(2, trLines));
                translationBody.setEllipsize(TextUtils.TruncateAt.END);
            }
            applyFold();

            int cardH = measureCardHeight();
            if (cardH <= maxHeight) {
                break;
            }
            int overflow = cardH - maxHeight;
            if (collapsedLineLimit > 1) {
                originLines = collapsedLineLimit - 1;   // 先折正文
                continue;
            }
            if (hasTranslation && trLines > 2) {
                trLines--;                              // 再收译文，最少两行
                continue;
            }
            if (mediaH > minMediaHeight) {
                mediaH = Math.max(minMediaHeight, mediaH - overflow);
                applyMediaHeight(mediaH);               // 最后才压媒体
                continue;
            }
            break;
        }
    }

    /** 播放器报出真实画面比例后，按比例修正媒体框，避免横屏视频被挤成竖屏。 */
    public void applyVideoAspect(float ratio) {
        if (ratio <= 0.05f || mediaContent == null) {
            return;
        }
        ViewGroup.LayoutParams lp = mediaContent.getLayoutParams();
        if (lp == null || lp.height <= 0) {
            return;
        }
        int contentW = Math.max(Ui.dp(c, 120), getWidth() - getPaddingLeft() - getPaddingRight());
        int maxH = Ui.dp(c, 340);
        int h = lp.height;
        int w = Math.round(h * ratio);
        if (w > contentW) {
            w = contentW;
            h = Math.round(w / ratio);
        }
        if (h > maxH) {
            h = maxH;
            w = Math.round(h * ratio);
        }
        if (w == lp.width && h == lp.height) {
            return;
        }
        lp.width = w;
        lp.height = h;
        mediaContent.setLayoutParams(lp);
        mediaNaturalH = h;
        post(this::calculateCollapsedLines);
    }
    /** 媒体当前占的自然高度（像素）。 */
    private int mediaNaturalHeight() {
        if (mediaContent == null) {
            return 0;
        }
        ViewGroup.LayoutParams lp = mediaContent.getLayoutParams();
        if (lp != null && lp.height > 0) {
            return lp.height;
        }
        if (mediaContent instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) mediaContent;
            for (int i = 0; i < g.getChildCount(); i++) {
                ViewGroup.LayoutParams clp = g.getChildAt(i).getLayoutParams();
                if (clp != null && clp.height > 0) {
                    return clp.height;
                }
            }
        }
        return Ui.dp(c, 180);
    }

    /** 把媒体等比压到指定高度（像素），多图行则统一压每个格子。 */
    private void applyMediaHeight(int targetH) {
        if (mediaContent == null || targetH <= 0) {
            return;
        }
        ViewGroup.LayoutParams lp = mediaContent.getLayoutParams();
        if (lp != null && lp.height > 0) {
            int newW = Math.round(lp.width * (float) targetH / Math.max(1, lp.height));
            int maxW = Math.max(Ui.dp(c, 80), getWidth() - getPaddingLeft() - getPaddingRight());
            lp.width = Math.max(Ui.dp(c, 80), Math.min(newW, maxW));
            lp.height = targetH;
            mediaContent.setLayoutParams(lp);
            return;
        }
        if (mediaContent instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) mediaContent;
            for (int i = 0; i < g.getChildCount(); i++) {
                View cell = g.getChildAt(i);
                ViewGroup.LayoutParams clp = cell.getLayoutParams();
                if (clp != null && clp.height > 0) {
                    clp.height = targetH;
                    cell.setLayoutParams(clp);
                }
            }
        }
    }

    /** 按当前配置实测整卡高度（不含外部 margin）。 */
    private int measureCardHeight() {
        int w = getWidth();
        if (w <= 0) {
            return 0;
        }
        measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        return getMeasuredHeight();
    }

    /** 列表可视高度（卡片 margin 之外都算可用）。 */
    private int listViewportHeight() {
        int h = getResources().getDisplayMetrics().heightPixels;
        if (getParent() instanceof View && ((View) getParent()).getHeight() > 0) {
            // 卡片不比列表高就能整块滚进视野，列表自身的 padding 只影响滚动范围
            h = ((View) getParent()).getHeight();
        }
        h -= Ui.dp(c, 6);
        if (getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) getLayoutParams();
            h -= mlp.topMargin + mlp.bottomMargin;
        }
        return Math.max(Ui.dp(c, 120), h);
    }

    /** 去掉 maxLines/ellipsize 后量出真实行数，再恢复原状态。 */
    private int measureLines(TextView tv, int widthSpec, int heightSpec) {
        int savedMax = tv.getMaxLines();
        TextUtils.TruncateAt savedEll = tv.getEllipsize();
        tv.setMaxLines(Integer.MAX_VALUE);
        tv.setEllipsize(null);
        tv.measure(widthSpec, heightSpec);
        int lines = Math.max(1, tv.getLineCount());
        tv.setMaxLines(savedMax);
        tv.setEllipsize(savedEll);
        return lines;
    }

    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0) {
            post(this::calculateCollapsedLines);
        }
    }
    /** 自动播放开启/关闭时切换缩略图与画面 */
    public void setAutoplayActive(boolean active) {
        if (videoSurface != null) {
            videoSurface.setVisibility(active ? View.VISIBLE : View.INVISIBLE);
        }
        if (videoThumb != null) {
            videoThumb.setVisibility(active ? View.INVISIBLE : View.VISIBLE);
        }
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
            // 列表内静音自动播放：把一张 TextureView 铺在缩略图上面，播放器按需挂上来
            videoThumb = iv;
            videoSurface = new android.view.TextureView(c);
            videoSurface.setLayoutParams(new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            videoSurface.setVisibility(View.INVISIBLE);
            frame.addView(videoSurface);

            View play = Icons.view(c, Icons.PLAY, 28, 0xFFFFFFFF);
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(Ui.dp(c, 56), Ui.dp(c, 56));
            plp.gravity = Gravity.CENTER;
            play.setLayoutParams(plp);
            play.setBackground(Ui.round(0xB3000000, Ui.dp(c, 28)));
            play.setPadding(Ui.dp(c, 14), Ui.dp(c, 14), Ui.dp(c, 14), Ui.dp(c, 14));
            play.setVisibility(View.GONE);
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
