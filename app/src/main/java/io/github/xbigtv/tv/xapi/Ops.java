package io.github.xbigtv.tv.xapi;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.xbigtv.tv.data.Prefs;
import io.github.xbigtv.tv.net.Http;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * X 的 GraphQL queryId 会随前端发版变动。内置一份实测可用的表，
 * 启动后异步从官网 JS bundle 里重新抓取覆盖，避免版本一升级就全挂。
 */
public final class Ops {

    private static final Map<String, String> DEFAULT = new HashMap<>();
    private static final Map<String, String> RUNTIME = new HashMap<>();

    private static final Pattern OP_PATTERN =
            Pattern.compile("queryId:\"([A-Za-z0-9_\\-]+)\",operationName:\"([A-Za-z0-9_]+)\"");
    private static final Pattern SCRIPT_PATTERN =
            Pattern.compile("https://abs\\.twimg\\.com/responsive-web/client-web/[A-Za-z0-9._\\-]+\\.js");

    static {
        DEFAULT.put("HomeTimeline", "KmJCeZ__ef1fNos7tKN_fg");
        DEFAULT.put("HomeLatestTimeline", "IVA5BUbeYPTrpYekQH7jiA");
        DEFAULT.put("SearchTimeline", "vLHjqbDv7TPFMBkIIO1nOA");
        DEFAULT.put("TweetDetail", "dQDdKtt882nl-Km98FJGSQ");
        DEFAULT.put("UserTweets", "D6z-FJI2pKuwEeYYxVIGbg");
        DEFAULT.put("UserByScreenName", "AMIBMjtxEEATh4z8V9GtRg");
        DEFAULT.put("TweetResultsByRestIds", "yW_CmYWEKwaOpAEHpNSReg");
        DEFAULT.put("TweetResultByRestId", "U8lc5aomF5krJzZ9vJ9wxg");
        DEFAULT.put("FavoriteTweet", "lI07N6Otwv1PhnEgXILM7A");
        DEFAULT.put("Viewer", "TpQxQtWMTMnIAMPlMGMqNw");
        DEFAULT.put("UserMedia", "iTSlWbsmD00dAE7Sk9xvIw");
        DEFAULT.put("Likes", "ZjyvI1V9gajq5eclHCObzA");
        DEFAULT.put("ExplorePage", "CVExg1KY6Ec9BioRBbI8Jg");
    }

    private Ops() {
    }

    public static synchronized String id(String operationName) {
        String v = RUNTIME.get(operationName);
        if (!Str.isEmpty(v)) {
            return v;
        }
        v = DEFAULT.get(operationName);
        return v == null ? "" : v;
    }

    /** 从缓存恢复上一次抓到的版本表。 */
    public static synchronized void loadCache() {
        String json = Prefs.opsJson();
        if (Str.isEmpty(json)) {
            return;
        }
        try {
            JSONObject o = new JSONObject(json);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                RUNTIME.put(k, o.optString(k, ""));
            }
        } catch (Exception ignored) {
        }
    }

    /** 同步刷新（放后台线程调用）。成功返回新增条数。 */
    public static synchronized int refresh() {
        try {
            OkHttpClient client = Http.client();
            Request req = new Request.Builder()
                    .url("https://x.com/home")
                    .header("User-Agent", XApi.UA)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .build();
            String html;
            try (Response resp = client.newCall(req).execute()) {
                html = resp.body() == null ? "" : resp.body().string();
            }
            if (html.isEmpty()) {
                return 0;
            }
            Matcher sm = SCRIPT_PATTERN.matcher(html);
            java.util.LinkedHashSet<String> scripts = new java.util.LinkedHashSet<>();
            while (sm.find() && scripts.size() < 8) {
                scripts.add(sm.group());
            }
            Map<String, String> found = new HashMap<>();
            for (String url : scripts) {
                Request jsReq = new Request.Builder().url(url)
                        .header("User-Agent", XApi.UA).build();
                try (Response resp = client.newCall(jsReq).execute()) {
                    if (resp.body() == null) {
                        continue;
                    }
                    String js = resp.body().string();
                    Matcher om = OP_PATTERN.matcher(js);
                    while (om.find()) {
                        found.put(om.group(2), om.group(1));
                    }
                } catch (Exception ignored) {
                }
            }
            int changed = 0;
            JSONObject cache = new JSONObject();
            for (Map.Entry<String, String> e : found.entrySet()) {
                String old = DEFAULT.get(e.getKey());
                if (old == null || !old.equals(e.getValue())) {
                    changed++;
                }
                RUNTIME.put(e.getKey(), e.getValue());
                cache.put(e.getKey(), e.getValue());
            }
            for (Map.Entry<String, String> e : RUNTIME.entrySet()) {
                if (!cache.has(e.getKey())) {
                    cache.put(e.getKey(), e.getValue());
                }
            }
            if (cache.length() > 0) {
                Prefs.saveOps(cache.toString());
            }
            return changed;
        } catch (Exception e) {
            return 0;
        }
    }

    public static String describe() {
        StringBuilder sb = new StringBuilder();
        for (String k : new String[]{"HomeTimeline", "HomeLatestTimeline", "SearchTimeline", "TweetDetail"}) {
            sb.append(String.format(Locale.US, "%s=%s ", k, id(k)));
        }
        return sb.toString().trim();
    }
}
