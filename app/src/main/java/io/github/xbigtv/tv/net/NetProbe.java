package io.github.xbigtv.tv.net;

import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.xbigtv.tv.xapi.XApi;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 诊断探针：在盒子上用 App 真实网络栈（OkHttp/Conscrypt + 我们的代理）打几个请求，
 * 把状态码和响应头打进 logcat，用来区分「Cloudflare 拦非浏览器 TLS 指纹」还是「代理没通」。
 */
public final class NetProbe {

    private static final String TAG = "XBigTV";

    private NetProbe() {
    }

    public static void runAsync() {
        ExecutorService io = Executors.newSingleThreadExecutor();
        io.execute(() -> {
            probeHtml();
            probeGraphql();
            probeMedia();
        });
        io.shutdown();
    }

    private static void probeHtml() {
        try {
            OkHttpClient client = Http.client();
            Request req = new Request.Builder()
                    .url("https://x.com/i/flow/login")
                    .header("user-agent", XApi.UA)
                    .header("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("accept-language", "zh-CN,zh;q=0.9")
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                String body = resp.body() == null ? "" : resp.body().string();
                Log.i(TAG, "PROBE html status=" + resp.code() + " len=" + body.length()
                        + " server=" + resp.header("server") + " cf=" + resp.header("cf-ray")
                        + " head=" + head(body));
            }
        } catch (Throwable t) {
            Log.e(TAG, "PROBE html failed: " + t);
        }
    }

    private static void probeGraphql() {
        try {
            OkHttpClient client = Http.client();
            RequestBody body = RequestBody.create(
                    "{\"variables\":{\"count\":20,\"includePromotedContent\":false,"
                            + "\"withCommunity\":true,\"requestContext\":\"launch\"},"
                            + "\"features\":{\"view_counts_everywhere_api_enabled\":true}}",
                    MediaType.parse("application/json"));
            Request req = new Request.Builder()
                    .url("https://x.com/i/api/graphql/KmJCeZ__ef1fNos7tKN_fg/HomeTimeline")
                    .header("authorization", "Bearer " + io.github.xbigtv.tv.BuildConfig.X_BEARER)
                    .header("content-type", "application/json")
                    .header("user-agent", XApi.UA)
                    .header("origin", "https://x.com")
                    .header("referer", "https://x.com/home")
                    .header("x-twitter-active-user", "yes")
                    .post(body)
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                String text = resp.body() == null ? "" : resp.body().string();
                Log.i(TAG, "PROBE gql status=" + resp.code() + " len=" + text.length()
                        + " cf=" + resp.header("cf-ray") + " head=" + head(text));
            }
        } catch (Throwable t) {
            Log.e(TAG, "PROBE gql failed: " + t);
        }
    }

    private static void probeMedia() {
        try {
            OkHttpClient client = Http.client();
            Request req = new Request.Builder()
                    .url("https://video.twimg.com/amplify_video/2121462488386707456/vid/avc1/640x360/w1p_008jNoVeWk_3.mp4?tag=29")
                    .header("user-agent", XApi.UA)
                    .header("referer", "https://x.com/")
                    .header("range", "bytes=0-1023")
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                Log.i(TAG, "PROBE media status=" + resp.code() + " type=" + resp.header("content-type")
                        + " len=" + resp.header("content-length"));
            }
        } catch (Throwable t) {
            Log.e(TAG, "PROBE media failed: " + t);
        }
    }

    private static String head(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > 120 ? t.substring(0, 120) : t;
    }
}
