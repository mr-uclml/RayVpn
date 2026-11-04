package app.rayvpn.client;

import android.app.Application;
import android.content.Context;

import go.Seq;
import libv2ray.Libv2ray;

public class RayApp extends Application {
    public static String coreVersion = "";
    public static String coreError = "";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            Seq.setContext(this);
            Libv2ray.initCoreEnv(getFilesDir().getAbsolutePath(), "");
            coreVersion = Libv2ray.checkVersionX();
        } catch (Throwable t) {
            coreError = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        ConfigSync.start(this);
    }
}
