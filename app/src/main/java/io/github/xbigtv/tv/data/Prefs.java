package io.github.xbigtv.tv.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/**
 * 全量本地设置：登录态 / 代理 / 播放偏好 / 接口版本缓存。
 */
public final class Prefs {

    private static final String NAME = "xbigtv";
    private static SharedPreferences sp;

    private Prefs() {
    }

    public static void init(Context context) {
        if (sp == null) {
            sp = context.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
        }
    }

    private static SharedPreferences p() {
        return sp;
    }

    private static void put(String k, String v) {
        p().edit().putString(k, v).apply();
    }

    private static void put(String k, int v) {
        p().edit().putInt(k, v).apply();
    }

    private static void put(String k, boolean v) {
        p().edit().putBoolean(k, v).apply();
    }

    /* ---------------- 登录态 ---------------- */

    public static String authToken() {
        return p().getString("auth_token", "");
    }

    public static String ct0() {
        return p().getString("ct0", "");
    }

    public static String twid() {
        return p().getString("twid", "");
    }

    public static String screenName() {
        return p().getString("screen_name", "");
    }

    public static String displayName() {
        return p().getString("display_name", "");
    }

    public static String avatarUrl() {
        return p().getString("avatar_url", "");
    }

    public static void saveSession(String authToken, String ct0, String twid) {
        put("auth_token", authToken == null ? "" : authToken);
        put("ct0", ct0 == null ? "" : ct0);
        put("twid", twid == null ? "" : twid);
    }

    public static void saveProfile(String screenName, String displayName, String avatarUrl) {
        put("screen_name", screenName == null ? "" : screenName);
        put("display_name", displayName == null ? "" : displayName);
        put("avatar_url", avatarUrl == null ? "" : avatarUrl);
    }

    public static boolean loggedIn() {
        return !TextUtils.isEmpty(authToken()) && !TextUtils.isEmpty(ct0());
    }

    public static void logout() {
        p().edit().remove("auth_token").remove("ct0").remove("twid")
                .remove("screen_name").remove("display_name").remove("avatar_url").apply();
    }

    /* ---------------- 代理 ---------------- */

    public static final int PROXY_HTTP = 0;
    public static final int PROXY_SOCKS = 1;

    public static boolean proxyEnabled() {
        return p().getBoolean("proxy_enabled", false);
    }

    public static void setProxyEnabled(boolean v) {
        put("proxy_enabled", v);
    }

    public static int proxyType() {
        return p().getInt("proxy_type", PROXY_HTTP);
    }

    public static void setProxyType(int v) {
        put("proxy_type", v);
    }

    public static String proxyHost() {
        return p().getString("proxy_host", "");
    }

    public static int proxyPort() {
        return p().getInt("proxy_port", 7890);
    }

    public static String proxyUser() {
        return p().getString("proxy_user", "");
    }

    public static String proxyPass() {
        return p().getString("proxy_pass", "");
    }

    public static void saveProxy(boolean enabled, int type, String host, int port, String user, String pass) {
        p().edit()
                .putBoolean("proxy_enabled", enabled)
                .putInt("proxy_type", type)
                .putString("proxy_host", host == null ? "" : host.trim())
                .putInt("proxy_port", port)
                .putString("proxy_user", user == null ? "" : user)
                .putString("proxy_pass", pass == null ? "" : pass)
                .apply();
    }

    public static boolean proxyConfigured() {
        return proxyEnabled() && !TextUtils.isEmpty(proxyHost()) && proxyPort() > 0;
    }

    /* ---------------- 播放偏好 ---------------- */

    /** 0=自动 1=1080p 2=720p 3=480p 4=360p */
    public static int quality() {
        return p().getInt("quality", 0);
    }

    public static void setQuality(int q) {
        put("quality", q);
    }

    public static boolean autoNext() {
        return p().getBoolean("auto_next", true);
    }

    public static void setAutoNext(boolean v) {
        put("auto_next", v);
    }

    public static boolean fillScreen() {
        return p().getBoolean("fill_screen", true);
    }

    public static void setFillScreen(boolean v) {
        put("fill_screen", v);
    }

    public static boolean muted() {
        return p().getBoolean("muted", false);
    }

    public static void setMuted(boolean v) {
        put("muted", v);
    }

    public static float speed() {
        return p().getFloat("speed", 1f);
    }

    public static void setSpeed(float v) {
        p().edit().putFloat("speed", v).apply();
    }

    /** 视频记忆位置：tweetId -> 毫秒 */
    public static long resumeOf(String tweetId) {
        return p().getLong("resume_" + tweetId, 0L);
    }

    public static void saveResume(String tweetId, long ms) {
        p().edit().putLong("resume_" + tweetId, ms).apply();
    }

    /* ---------------- 更新检查 ---------------- */

    public static long lastUpdateCheck() {
        return p().getLong("update_check_at", 0L);
    }

    public static void setLastUpdateCheck(long at) {
        p().edit().putLong("update_check_at", at).apply();
    }

    /* ---------------- 接口版本缓存 ---------------- */

    public static String opsJson() {
        return p().getString("ops_json", "");
    }

    public static long opsStamp() {
        return p().getLong("ops_stamp", 0L);
    }

    public static void saveOps(String json) {
        p().edit().putString("ops_json", json).putLong("ops_stamp", System.currentTimeMillis()).apply();
    }
}
