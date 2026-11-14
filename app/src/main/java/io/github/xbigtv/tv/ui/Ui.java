package io.github.xbigtv.tv.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 轻量 UI 工具：全程序化布局，统一走这里，保证大屏焦点/间距一致。 */
public final class Ui {

    public static final int BLACK = 0xFF000000;
    public static final int SURFACE = 0xFF16181C;
    public static final int SURFACE_HI = 0xFF1F2126;
    public static final int BORDER = 0xFF2F3336;
    public static final int TEXT = 0xFFE7E9EA;
    public static final int TEXT_DIM = 0xFF71767B;
    public static final int BLUE = 0xFF1D9BF0;
    public static final int RED = 0xFFF91880;
    public static final int GREEN = 0xFF00BA7C;
    public static final int AMBER = 0xFFFFD400;

    private Ui() {
    }

    public static float density(Context c) {
        return c.getResources().getDisplayMetrics().density;
    }

    public static int dp(Context c, float v) {
        return Math.round(v * density(c));
    }

    public static int screenW(Context c) {
        return c.getResources().getDisplayMetrics().widthPixels;
    }

    public static int screenH(Context c) {
        return c.getResources().getDisplayMetrics().heightPixels;
    }

    public static GradientDrawable round(int fill, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        return d;
    }

    public static GradientDrawable round(int fill, float radiusPx, int strokeColor, int strokeWidthPx) {
        GradientDrawable d = round(fill, radiusPx);
        d.setStroke(strokeWidthPx, strokeColor);
        return d;
    }

    public static RippleDrawable ripple(Context c, int fill, float radiusPx, int rippleColor) {
        return new RippleDrawable(android.content.res.ColorStateList.valueOf(rippleColor),
                round(fill, radiusPx), null);
    }

    public static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        tv.setIncludeFontPadding(false);
        if (bold) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return tv;
    }

    public static LinearLayout linear(Context c, int orientation) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(orientation);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    public static FrameLayout.LayoutParams flp(int w, int h) {
        return new FrameLayout.LayoutParams(w, h);
    }

    public static void margins(View v, int l, int t, int r, int b) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        if (p instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) p).setMargins(l, t, r, b);
            v.setLayoutParams(p);
        }
    }

    public static void toast(Context c, String msg) {
        Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
    }

    /** 焦点态：蓝色描边 + 轻微放大 */
    public static void focusableCard(final View v, final float radiusPx) {
        v.setFocusable(true);
        v.setFocusableInTouchMode(false);
        v.setBackground(round(SURFACE, radiusPx));
        v.setOnFocusChangeListener((view, has) -> {
            if (has) {
                view.setBackground(round(SURFACE_HI, radiusPx, BLUE, Math.max(2, Math.round(2 * density(view.getContext())))));
                view.animate().scaleX(1.012f).scaleY(1.012f).setDuration(120).start();
                view.setElevation(dp(view.getContext(), 8));
            } else {
                view.setBackground(round(SURFACE, radiusPx));
                view.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                view.setElevation(0);
            }
        });
    }

    /** 遥控器按钮外观 */
    public static TextView chip(Context c, String label, int color) {
        TextView tv = text(c, label, 15, color, true);
        tv.setGravity(Gravity.CENTER);
        int px = dp(c, 14);
        tv.setPadding(px, dp(c, 8), px, dp(c, 8));
        tv.setBackground(round(0x22FFFFFF, dp(c, 20)));
        tv.setFocusable(true);
        tv.setOnFocusChangeListener((v, has) -> v.setBackground(round(has ? 0x44FFFFFF : 0x22FFFFFF, dp(c, 20))));
        return tv;
    }

    public static TextView pill(Context c, String label) {
        TextView tv = text(c, label, 11, 0xFFFFFFFF, true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(c, 6), dp(c, 2), dp(c, 6), dp(c, 2));
        tv.setBackground(round(0xCC000000, dp(c, 6)));
        return tv;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.6f)));
        v.setLayoutParams(p);
        return v;
    }

    public static View spacer(Context c, int h) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, h)));
        return v;
    }

    public static int alpha(int color, float a) {
        int al = Math.round(255 * Math.max(0f, Math.min(1f, a)));
        return (color & 0x00FFFFFF) | (al << 24);
    }

    public static int blend(int c1, int c2, float t) {
        return Color.argb(
                Math.round(Color.alpha(c1) + (Color.alpha(c2) - Color.alpha(c1)) * t),
                Math.round(Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t),
                Math.round(Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t),
                Math.round(Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t));
    }
}
