package app.rayvpn.client;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/** Applies the language picked in settings to every screen of the app. */
public final class LocaleHelper {
    private LocaleHelper() {}

    public static Locale locale(Context context) {
        String tag = Prefs.locale(context);
        return "en".equals(tag) ? new Locale("en", "US") : new Locale("fa", "IR");
    }

    public static Context wrap(Context base) {
        Locale locale = locale(base);
        Locale.setDefault(locale);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        config.setLayoutDirection(locale);
        return base.createConfigurationContext(config);
    }

    public static boolean isPersian(Context context) {
        return !"en".equals(Prefs.locale(context));
    }
}
