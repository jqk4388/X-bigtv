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
    }

    public static Context ctx() {
        return sInstance;
    }

    public static App self() {
        return sInstance;
    }
}
