package io.github.xbigtv.tv.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentHashMap;

import io.github.xbigtv.tv.net.Http;
import io.github.xbigtv.tv.xapi.XApi;
import okhttp3.Request;
import okhttp3.Response;

/** 极简图片加载：内存 LRU + 4 线程 + 复用 tag 防错位。 */
public final class ImageLoader {

    private static final ImageLoader INSTANCE = new ImageLoader();

    private final LruCache<String, Bitmap> cache;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.Map<ImageView, String> pending =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private ImageLoader() {
        int max = (int) Math.min(Runtime.getRuntime().maxMemory() / 6, 96L * 1024 * 1024);
        cache = new LruCache<String, Bitmap>(max) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    public static ImageLoader get() {
        return INSTANCE;
    }

    public void load(String url, ImageView target, int reqWidthPx) {
        if (url == null || url.isEmpty()) {
            target.setImageDrawable(null);
            return;
        }
        pending.put(target, url);
        Bitmap hit = cache.get(url);
        if (hit != null) {
            target.setImageBitmap(hit);
            return;
        }
        target.setImageDrawable(null);
        final String key = url;
        pool.execute(() -> {
            Bitmap bmp = fetch(url, reqWidthPx);
            if (bmp == null) {
                return;
            }
            cache.put(key, bmp);
            main.post(() -> {
                String tag = pending.get(target);
                if (url.equals(tag)) {
                    target.setImageBitmap(bmp);
                }
            });
        });
    }

    public Bitmap fetch(String url, int reqWidthPx) {
        Bitmap hit = cache.get(url);
        if (hit != null) {
            return hit;
        }
        try {
            Request req = new Request.Builder().url(url)
                    .header("user-agent", XApi.UA)
                    .header("referer", "https://x.com/")
                    .build();
            try (Response resp = Http.client().newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    return null;
                }
                InputStream in = resp.body().byteStream();
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(in, null, opts);
                in.close();

                BitmapFactory.Options real = new BitmapFactory.Options();
                int sample = 1;
                if (reqWidthPx > 0 && opts.outWidth > reqWidthPx * 2) {
                    while (opts.outWidth / (sample * 2) >= reqWidthPx) {
                        sample *= 2;
                    }
                }
                real.inSampleSize = sample;
                real.inPreferredConfig = Bitmap.Config.RGB_565;
                try (Response r2 = Http.client().newCall(req).execute()) {
                    if (r2.body() == null) {
                        return null;
                    }
                    return BitmapFactory.decodeStream(r2.body().byteStream(), null, real);
                }
            }
        } catch (Exception e) {
            return null;
        }
    }

    public void clear() {
        cache.evictAll();
    }
}
