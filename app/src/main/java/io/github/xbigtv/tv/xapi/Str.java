package io.github.xbigtv.tv.xapi;

/** 极小字符串工具：让 xapi 层不依赖 android.*，可以直接在 JVM 单测里跑。 */
public final class Str {

    private Str() {
    }

    public static boolean isEmpty(String s) {
        return s == null || s.length() == 0;
    }

    public static String or(String s, String fallback) {
        return isEmpty(s) ? fallback : s;
    }

    public static int toInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
