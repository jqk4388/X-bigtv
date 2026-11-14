package io.github.xbigtv.tv.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 遥控器软键盘：电视上填代理地址、搜索词等不用外接键盘。 */
public final class TvKeyboardDialog {

    public interface OnText {
        void onText(String text);
    }

    private TvKeyboardDialog() {
    }

    public static void show(final Context ctx, String title, String initial, boolean password,
                            final OnText callback) {
        final Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final StringBuilder buf = new StringBuilder(initial == null ? "" : initial);

        LinearLayout root = Ui.linear(ctx, LinearLayout.VERTICAL);
        int pad = Ui.dp(ctx, 22);
        root.setPadding(pad, Ui.dp(ctx, 16), pad, Ui.dp(ctx, 16));
        root.setBackground(Ui.round(0xFF111418, Ui.dp(ctx, 18), Ui.BORDER, Math.max(1, Ui.dp(ctx, 1))));

        TextView titleView = Ui.text(ctx, title == null ? "输入" : title, 17, Ui.TEXT_DIM, true);
        root.addView(titleView);

        final TextView display = Ui.text(ctx, "", 24, Ui.TEXT, true);
        display.setSingleLine(true);
        display.setPadding(0, Ui.dp(ctx, 10), 0, Ui.dp(ctx, 10));
        display.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(display);

        final Runnable refresh = () -> {
            String s = buf.toString();
            if (password && s.length() > 0) {
                StringBuilder dots = new StringBuilder();
                for (int i = 0; i < s.length(); i++) {
                    dots.append('•');
                }
                display.setText(dots);
            } else {
                display.setText(s.length() == 0 ? " " : s);
            }
        };
        refresh.run();

        String[][] rows = new String[][]{
                {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
                {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
                {"a", "s", "d", "f", "g", "h", "j", "k", "l", "@"},
                {"z", "x", "c", "v", "b", "n", "m", ".", ":", "/"},
                {"-", "_", "=", "?", "&", "%", "#", "+", "~", ","}
        };

        for (String[] row : rows) {
            LinearLayout rl = Ui.linear(ctx, LinearLayout.HORIZONTAL);
            rl.setPadding(0, Ui.dp(ctx, 3), 0, 0);
            for (String k : row) {
                rl.addView(key(ctx, k, v -> {
                    buf.append(k);
                    refresh.run();
                }));
            }
            root.addView(rl);
        }

        LinearLayout actions = Ui.linear(ctx, LinearLayout.HORIZONTAL);
        actions.setPadding(0, Ui.dp(ctx, 8), 0, 0);
        actions.addView(key(ctx, "空格", v -> {
            buf.append(' ');
            refresh.run();
        }));
        actions.addView(key(ctx, "删除", v -> {
            if (buf.length() > 0) {
                buf.deleteCharAt(buf.length() - 1);
            }
            refresh.run();
        }));
        actions.addView(key(ctx, "清空", v -> {
            buf.setLength(0);
            refresh.run();
        }));
        actions.addView(key(ctx, "取消", v -> dialog.dismiss()));
        actions.addView(key(ctx, "确定", v -> {
            dialog.dismiss();
            callback.onText(buf.toString());
        }));
        root.addView(actions);

        dialog.setContentView(root);
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    dialog.dismiss();
                    return true;
                }
                if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9
                        && event.getUnicodeChar() != 0 && !event.hasModifiers(KeyEvent.META_SHIFT_ON)) {
                    buf.append((char) event.getUnicodeChar());
                    refresh.run();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DEL) {
                    if (buf.length() > 0) {
                        buf.deleteCharAt(buf.length() - 1);
                    }
                    refresh.run();
                    return true;
                }
            }
            return false;
        });

        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (Ui.screenW(ctx) * 0.78f), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        View first = root.getChildAt(2);
        if (first != null) {
            first.requestFocus();
        }
    }

    private static View key(final Context ctx, final String label, final View.OnClickListener onClick) {
        TextView tv = Ui.text(ctx, label, 16, Ui.TEXT, true);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setPadding(Ui.dp(ctx, 2), Ui.dp(ctx, 10), Ui.dp(ctx, 2), Ui.dp(ctx, 10));
        tv.setBackground(Ui.round(0x1AFFFFFF, Ui.dp(ctx, 8)));
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(false);
        tv.setOnClickListener(onClick);
        tv.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? Ui.round(Ui.BLUE, Ui.dp(ctx, 8)) : Ui.round(0x1AFFFFFF, Ui.dp(ctx, 8)));
            ((TextView) v).setTextColor(has ? Ui.BLACK : Ui.TEXT);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = Ui.dp(ctx, 2);
        lp.rightMargin = Ui.dp(ctx, 2);
        tv.setLayoutParams(lp);
        return tv;
    }
}
