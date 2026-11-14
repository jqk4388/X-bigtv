package io.github.xbigtv.tv.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** 手绘图标（避免矢量资源与字体依赖，保证老盒子上渲染一致）。 */
public final class Icons {

    public static final int PLAY = 0;
    public static final int PAUSE = 1;
    public static final int HEART = 2;
    public static final int REPOST = 3;
    public static final int REPLY = 4;
    public static final int EYE = 5;
    public static final int BACK = 6;
    public static final int SETTINGS = 7;
    public static final int MUTE = 8;
    public static final int SEARCH = 9;

    private Icons() {
    }

    public static void draw(Canvas canvas, int type, float cx, float cy, float size, Paint p) {
        float s = size / 2f;
        switch (type) {
            case PLAY: {
                Path path = new Path();
                path.moveTo(cx - s * 0.55f, cy - s);
                path.lineTo(cx + s * 0.9f, cy);
                path.lineTo(cx - s * 0.55f, cy + s);
                path.close();
                canvas.drawPath(path, p);
                break;
            }
            case PAUSE: {
                float w = s * 0.42f;
                RectF a = new RectF(cx - s * 0.7f, cy - s, cx - s * 0.7f + w, cy + s);
                RectF b = new RectF(cx + s * 0.28f, cy - s, cx + s * 0.28f + w, cy + s);
                canvas.drawRect(a, p);
                canvas.drawRect(b, p);
                break;
            }
            case HEART: {
                Path path = new Path();
                path.moveTo(cx, cy + s * 0.85f);
                path.cubicTo(cx - s * 1.5f, cy - s * 0.2f, cx - s * 0.6f, cy - s * 1.25f, cx, cy - s * 0.4f);
                path.cubicTo(cx + s * 0.6f, cy - s * 1.25f, cx + s * 1.5f, cy - s * 0.2f, cx, cy + s * 0.85f);
                path.close();
                canvas.drawPath(path, p);
                break;
            }
            case REPOST: {
                Paint stroke = new Paint(p);
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(Math.max(2f, size * 0.12f));
                stroke.setStrokeCap(Paint.Cap.ROUND);
                RectF r1 = new RectF(cx - s * 0.9f, cy - s * 0.9f, cx + s * 0.9f, cy + s * 0.9f);
                canvas.drawArc(r1, 200, 120, false, stroke);
                canvas.drawArc(r1, 20, 120, false, stroke);
                Path a = new Path();
                a.moveTo(cx + s * 0.95f, cy - s * 0.2f);
                a.lineTo(cx + s * 0.45f, cy - s * 0.55f);
                a.lineTo(cx + s * 0.95f, cy - s * 0.95f);
                a.close();
                canvas.drawPath(a, p);
                Path b = new Path();
                b.moveTo(cx - s * 0.95f, cy + s * 0.2f);
                b.lineTo(cx - s * 0.45f, cy + s * 0.55f);
                b.lineTo(cx - s * 0.95f, cy + s * 0.95f);
                b.close();
                canvas.drawPath(b, p);
                break;
            }
            case REPLY: {
                Paint stroke = new Paint(p);
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(Math.max(2f, size * 0.12f));
                RectF r = new RectF(cx - s * 0.95f, cy - s * 0.8f, cx + s * 0.95f, cy + s * 0.5f);
                canvas.drawRoundRect(r, s * 0.45f, s * 0.45f, stroke);
                Path tail = new Path();
                tail.moveTo(cx - s * 0.35f, cy + s * 0.45f);
                tail.lineTo(cx - s * 0.55f, cy + s * 0.95f);
                tail.lineTo(cx + s * 0.05f, cy + s * 0.45f);
                canvas.drawPath(tail, stroke);
                break;
            }
            case EYE: {
                Paint stroke = new Paint(p);
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(Math.max(2f, size * 0.1f));
                RectF r = new RectF(cx - s, cy - s * 0.62f, cx + s, cy + s * 0.62f);
                canvas.drawOval(r, stroke);
                canvas.drawCircle(cx, cy, s * 0.3f, p);
                break;
            }
            case BACK: {
                Paint stroke = new Paint(p);
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(Math.max(2f, size * 0.14f));
                stroke.setStrokeCap(Paint.Cap.ROUND);
                Path path = new Path();
                path.moveTo(cx + s * 0.5f, cy - s * 0.8f);
                path.lineTo(cx - s * 0.5f, cy);
                path.lineTo(cx + s * 0.5f, cy + s * 0.8f);
                canvas.drawPath(path, stroke);
                break;
            }
            case SETTINGS: {
                Paint stroke = new Paint(p);
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(Math.max(2f, size * 0.12f));
                canvas.drawCircle(cx, cy, s * 0.42f, stroke);
                canvas.drawCircle(cx, cy, s * 0.95f, stroke);
                break;
            }
            case MUTE: {
                Path path = new Path();
                path.moveTo(cx - s * 0.9f, cy - s * 0.35f);
                path.lineTo(cx - s * 0.45f, cy - s * 0.35f);
                path.lineTo(cx + s * 0.1f, cy - s * 0.9f);
                path.lineTo(cx + s * 0.1f, cy + s * 0.9f);
                path.lineTo(cx - s * 0.45f, cy + s * 0.35f);
                path.lineTo(cx - s * 0.9f, cy + s * 0.35f);
                path.close();
                canvas.drawPath(path, p);
                Paint stroke = new Paint(p);
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(Math.max(2f, size * 0.12f));
                canvas.drawLine(cx + s * 0.35f, cy - s * 0.5f, cx + s * 1.0f, cy + s * 0.5f, stroke);
                break;
            }
            case SEARCH: {
                Paint stroke = new Paint(p);
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(Math.max(2f, size * 0.14f));
                canvas.drawCircle(cx - s * 0.2f, cy - s * 0.2f, s * 0.65f, stroke);
                canvas.drawLine(cx + s * 0.3f, cy + s * 0.3f, cx + s * 0.95f, cy + s * 0.95f, stroke);
                break;
            }
            default:
                break;
        }
    }

    /** 固定尺寸图标 View */
    public static View view(final Context c, final int type, final float sizeDp, final int color) {
        View v = new View(c) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                paint.setColor(color);
                paint.setStyle(Paint.Style.FILL);
                float size = Math.min(getWidth(), getHeight()) * 0.72f;
                Icons.draw(canvas, type, getWidth() / 2f, getHeight() / 2f, size, paint);
            }
        };
        int px = Ui.dp(c, sizeDp);
        v.setLayoutParams(Ui.lp(px, px));
        return v;
    }
}
