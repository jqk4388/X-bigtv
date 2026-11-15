package io.github.xbigtv.tv.ui;

/** 所有界面统一：快速按两下返回键才真正退出，避免误触。 */
public final class BackGuard {

    private static final long WINDOW_MS = 1800L;
    private long lastAt;

    /** @return true 表示可以退出 */
    public boolean shouldExit(android.app.Activity activity) {
        long now = System.currentTimeMillis();
        if (now - lastAt < WINDOW_MS) {
            return true;
        }
        lastAt = now;
        Ui.toast(activity, "再按一次返回键退出");
        return false;
    }
}
