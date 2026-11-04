package app.rayvpn.client;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

public final class LocaleHelper {
    private LocaleHelper() {}

    public static Context wrap(Context base) {
        Locale fa = new Locale("fa", "IR");
        Locale.setDefault(fa);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(fa);
        config.setLayoutDirection(fa);
        return base.createConfigurationContext(config);
    }
}
