package org.lineageos.camera.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

public class LocaleManager {
    private static final String PREF_NAME = "vcam_assistant_prefs";
    private static final String KEY_LANG = "app_language";

    public static final String LANG_VI = "vi";
    public static final String LANG_EN = "en";
    public static final String LANG_ZH = "zh";

    public static Context applyLocale(Context context) {
        String lang = getLanguage(context);
        return updateResources(context, lang);
    }

    public static String getLanguage(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getString(KEY_LANG, LANG_VI);
    }

    public static void setLanguage(Context context, String lang) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_LANG, lang).apply();
    }

    public static Context updateResources(Context context, String language) {
        Locale locale;
        if (LANG_ZH.equals(language)) {
            locale = Locale.SIMPLIFIED_CHINESE;
        } else if (LANG_EN.equals(language)) {
            locale = Locale.ENGLISH;
        } else {
            locale = new Locale("vi");
        }
        Locale.setDefault(locale);

        Resources res = context.getResources();
        Configuration config = new Configuration(res.getConfiguration());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(new LocaleList(locale));
            return context.createConfigurationContext(config);
        } else {
            config.locale = locale;
            res.updateConfiguration(config, res.getDisplayMetrics());
            return context;
        }
    }
}