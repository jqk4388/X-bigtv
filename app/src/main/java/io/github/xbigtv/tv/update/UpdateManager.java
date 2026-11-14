package io.github.xbigtv.tv.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.xbigtv.tv.BuildConfig;
import io.github.xbigtv.tv.data.Prefs;
import io.github.xbigtv.tv.net.Http;
import io.github.xbigtv.tv.ui.Ui;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 自动更新：读 GitHub Release（公开仓库），比较版本号，下载 APK 并拉起系统安装器。
 * 下载走的是 App 自己的代理设置（Http.client()），所以翻墙环境下也能更新。
 */
public final class UpdateManager {

    private static final String TAG = "XBigTV";
    public static final String API_LATEST =
            "https://api.github.com/repos/jqk4388/X-bigtv/releases/latest";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UpdateManager() {
    }

    public static final class Release {
        public String tag = "";
        public int code;
        public String name = "";
        public String notes = "";
        public String apkUrl = "";
        public long sizeBytes;

        public String versionName() {
            return TextUtils.isEmpty(tag) ? String.format(Locale.US, "%d", code)
                    : tag.replaceFirst("^v", "");
        }
    }

    public interface Listener {
        void onResult(Release release, String error);
    }

    /** 版本号统一：major*10000 + minor*100 + patch，和 build.gradle 的 versionCode 对齐。 */
    public static int codeOf(String tag) {
        try {
            String s = tag.trim().replaceFirst("^[vV]", "");
            String[] p = s.split("\\.");
            int major = p.length > 0 ? Integer.parseInt(p[0].replaceAll("[^0-9]", "")) : 0;
            int minor = p.length > 1 ? Integer.parseInt(p[1].replaceAll("[^0-9]", "")) : 0;
            int patch = p.length > 2 ? Integer.parseInt(p[2].replaceAll("[^0-9]", "")) : 0;
            return major * 10000 + minor * 100 + patch;
        } catch (Exception e) {
            return 0;
        }
    }

    public static void checkAsync(final Listener listener) {
        IO.execute(() -> {
            Release found = null;
            String error = null;
            try {
                Request req = new Request.Builder()
                        .url(API_LATEST)
                        .header("user-agent", "X-BigTV-Updater")
                        .header("accept", "application/vnd.github+json")
                        .build();
                try (Response resp = Http.client().newCall(req).execute()) {
                    if (resp.code() == 404) {
                        error = "仓库还没有发布版本";
                    } else if (!resp.isSuccessful() || resp.body() == null) {
                        error = "HTTP " + resp.code();
                    } else {
                        found = parse(resp.body().string());
                        if (found == null) {
                            error = "发布信息解析失败";
                        }
                    }
                }
            } catch (Throwable t) {
                error = t.getMessage();
            }
            final Release r = found;
            final String err = error;
            MAIN.post(() -> listener.onResult(r, err));
        });
    }

    private static Release parse(String json) {
        try {
            JSONObject o = new JSONObject(json);
            Release r = new Release();
            r.tag = o.optString("tag_name", "");
            r.name = o.optString("name", r.tag);
            r.notes = o.optString("body", "");
            r.code = codeOf(r.tag);
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) {
                        continue;
                    }
                    String name = a.optString("name", "");
                    if (name.endsWith(".apk")) {
                        r.apkUrl = a.optString("browser_download_url", "");
                        r.sizeBytes = a.optLong("size", 0);
                        break;
                    }
                }
            }
            if (r.apkUrl.isEmpty()) {
                return null;
            }
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    /** 启动时静默检查：一天最多一次，发现新版本才提示。 */
    public static void checkOnStart(Activity activity) {
        long last = Prefs.lastUpdateCheck();
        if (System.currentTimeMillis() - last < 24L * 3600_000L) {
            return;
        }
        Prefs.setLastUpdateCheck(System.currentTimeMillis());
        checkAsync((release, error) -> {
            if (release != null && release.code > BuildConfig.VERSION_CODE) {
                prompt(activity, release, false);
            }
        });
    }

    /** 手动检查：无论有没有新版本都给反馈。 */
    public static void checkManually(Activity activity) {
        Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show();
        checkAsync((release, error) -> {
            if (release == null) {
                Toast.makeText(activity, "检查失败：" + (error == null ? "未知错误" : error),
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (release.code <= BuildConfig.VERSION_CODE) {
                Toast.makeText(activity, "已是最新版本 v" + BuildConfig.VERSION_NAME,
                        Toast.LENGTH_SHORT).show();
            } else {
                prompt(activity, release, true);
            }
        });
    }

    private static void prompt(final Activity activity, final Release release, boolean manual) {
        if (activity.isFinishing()) {
            return;
        }
        StringBuilder msg = new StringBuilder();
        msg.append("当前版本：v").append(BuildConfig.VERSION_NAME).append("\n")
                .append("最新版本：v").append(release.versionName()).append("\n\n");
        String notes = release.notes == null ? "" : release.notes.trim();
        if (notes.length() > 400) {
            notes = notes.substring(0, 400) + "…";
        }
        msg.append(notes);

        new AlertDialog.Builder(activity)
                .setTitle("发现新版本")
                .setMessage(msg.toString())
                .setPositiveButton("立即更新", (d, w) -> downloadAndInstall(activity, release))
                .setNegativeButton("稍后", null)
                .show();
    }

    private static void downloadAndInstall(final Activity activity, final Release release) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                    .setTitle("需要安装权限")
                    .setMessage("请在系统设置里允许「" + activity.getString(io.github.xbigtv.tv.R.string.app_name)
                            + "」安装应用，然后重试。")
                    .setPositiveButton("去设置", (d, w) -> {
                        try {
                            Intent it = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName()));
                            activity.startActivity(it);
                        } catch (Exception ignored) {
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }

        final ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        final TextView label = Ui.text(activity, "正在下载 v" + release.versionName() + " …", 14, Ui.TEXT, false);
        label.setGravity(Gravity.CENTER);
        LinearLayout box = Ui.linear(activity, LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(activity, 24), Ui.dp(activity, 18), Ui.dp(activity, 24), Ui.dp(activity, 18));
        box.addView(label);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(activity, 12);
        bar.setLayoutParams(blp);
        box.addView(bar);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("软件更新")
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("取消", null)
                .create();
        dialog.show();

        IO.execute(() -> {
            File out = new File(activity.getCacheDir(), "update-" + release.code + ".apk");
            String error = null;
            try {
                Request req = new Request.Builder()
                        .url(release.apkUrl)
                        .header("user-agent", "X-BigTV-Updater")
                        .build();
                try (Response resp = Http.client().newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        throw new java.io.IOException("HTTP " + resp.code());
                    }
                    ResponseBody body = resp.body();
                    long total = body.contentLength() > 0 ? body.contentLength() : release.sizeBytes;
                    InputStream in = body.byteStream();
                    FileOutputStream fos = new FileOutputStream(out);
                    byte[] buf = new byte[32768];
                    long done = 0;
                    int n;
                    int lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            final int pct = (int) (done * 100 / total);
                            if (pct != lastPct && pct % 2 == 0) {
                                lastPct = pct;
                                MAIN.post(() -> bar.setProgress(pct));
                            }
                        }
                    }
                    fos.flush();
                    fos.close();
                    in.close();
                }
            } catch (Throwable t) {
                error = t.getMessage();
            }
            final String err = error;
            MAIN.post(() -> {
                try {
                    dialog.dismiss();
                } catch (Exception ignored) {
                }
                if (err != null) {
                    Toast.makeText(activity, "下载失败：" + err, Toast.LENGTH_LONG).show();
                    return;
                }
                install(activity, out);
            });
        });
    }

    private static void install(Activity activity, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", apk);
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(uri, "application/vnd.android.package-archive");
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(it);
            Log.i(TAG, "installer launched for " + apk);
        } catch (Throwable t) {
            Toast.makeText(activity, "无法拉起安装器：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public static String currentVersionLine() {
        return "v" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")";
    }

    public static void colorNoop(Context c) {
        // 占位：保持 AlertDialog 在深色主题下可读
        Color.class.getName();
    }
}
