package io.github.xbigtv.tv.xapi;

import io.github.xbigtv.tv.data.Prefs;

/**
 * 当前登录态的进程内视图。
 * 单独抽出来，既方便 UI 快速读取，也让 xapi 层在 JVM 单测里可以脱离 Android 运行。
 */
public final class XSession {

    public static volatile String authToken = "";
    public static volatile String ct0 = "";
    public static volatile String twid = "";
    public static volatile String screenName = "";
    public static volatile String displayName = "";
    public static volatile String avatarUrl = "";

    private XSession() {
    }

    public static void load() {
        authToken = Prefs.authToken();
        ct0 = Prefs.ct0();
        twid = Prefs.twid();
        screenName = Prefs.screenName();
        displayName = Prefs.displayName();
        avatarUrl = Prefs.avatarUrl();
    }

    public static void save(String auth, String csrf, String userId) {
        Prefs.saveSession(auth, csrf, userId);
        authToken = auth == null ? "" : auth;
        ct0 = csrf == null ? "" : csrf;
        twid = userId == null ? "" : userId;
    }

    public static void profile(String name, String display, String avatar) {
        Prefs.saveProfile(name, display, avatar);
        screenName = name == null ? "" : name;
        displayName = display == null ? "" : display;
        avatarUrl = avatar == null ? "" : avatar;
    }

    public static boolean loggedIn() {
        return !Str.isEmpty(authToken) && !Str.isEmpty(ct0);
    }

    public static void clear() {
        Prefs.logout();
        authToken = "";
        ct0 = "";
        twid = "";
        screenName = "";
        displayName = "";
        avatarUrl = "";
    }
}
