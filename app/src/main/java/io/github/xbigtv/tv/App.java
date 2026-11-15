package io.github.xbigtv.tv;

import android.app.Application;
import android.content.Context;

import io.github.xbigtv.tv.data.Prefs;
import io.github.xbigtv.tv.net.Http;
import io.github.xbigtv.tv.xapi.XSession;

public class App extends Application {

    private static App sInstance;

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        Prefs.init(this);
        Http.reset();
        XSession.load();
        // 局域网遥控台（固定 18930）随 App 一起起来，随时能扫码改设置
        io.github.xbigtv.tv.net.LanBridge.get().ensureStarted();
    }

    public static Context ctx() {
        return sInstance;
    }

    public static App self() {
        return sInstance;
    }
}
