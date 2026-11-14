package io.github.xbigtv.tv.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 图片全屏查看：左右键翻页，OK 键在 1x / 2x 之间缩放，返回键退出。 */
public class ImageViewerActivity extends Activity {

    private static List<String> sUrls = new ArrayList<>();
    private static int sIndex = 0;

    public static void start(Activity from, List<String> urls, int index) {
        if (urls == null || urls.isEmpty()) {
            Ui.toast(from, "没有可查看的图片");
            return;
        }
        sUrls = new ArrayList<>(urls);
        sIndex = Math.max(0, Math.min(index, urls.size() - 1));
        from.startActivity(new Intent(from, ImageViewerActivity.class));
    }

    private ImageView imageView;
    private TextView counter;
    private TextView hint;
    private int index;
    private boolean zoomed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        index = sIndex;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        imageView = new ImageView(this);
        imageView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(imageView);

        counter = Ui.text(this, "", 18, 0xFFFFFFFF, true);
        counter.setBackground(Ui.round(0x99000000, Ui.dp(this, 14)));
        counter.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        clp.topMargin = Ui.dp(this, 22);
        root.addView(counter, clp);

        hint = Ui.text(this, "◀ ▶ 翻页    OK 放大/还原    返回 退出", 14, 0xFFB0B4B9, false);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = Ui.dp(this, 22);
        root.addView(hint, hlp);

        setContentView(root);
        show();
    }

    private void show() {
        counter.setText((index + 1) + " / " + sUrls.size());
        zoomed = false;
        imageView.setScaleX(1f);
        imageView.setScaleY(1f);
        imageView.setImageDrawable(null);
        ImageLoader.get().load(sUrls.get(index), imageView, Ui.screenW(this));
    }

    private void toggleZoom() {
        zoomed = !zoomed;
        float s = zoomed ? 2f : 1f;
        imageView.animate().scaleX(s).scaleY(s).setDuration(150).start();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (index > 0) {
                    index--;
                    show();
                } else {
                    Ui.toast(this, "已经是第一张");
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (index < sUrls.size() - 1) {
                    index++;
                    show();
                } else {
                    Ui.toast(this, "已经是最后一张");
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                toggleZoom();
                return true;
            case KeyEvent.KEYCODE_BACK:
                finish();
                return true;
            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }
}
