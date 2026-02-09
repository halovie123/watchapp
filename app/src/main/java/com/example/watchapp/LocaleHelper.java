package com.example.watchapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import java.util.Locale;

public class LocaleHelper {
    private static final String SELECTED_LANGUAGE = "language";
    private static final String PREFS_NAME = "WatchSettings";

    public static Context setLocale(Context context, String language) {
        persist(context, language);
        return updateResources(context, language);
    }

    public static Context setLocale(Context context) {
        String language = getPersistedLanguage(context);
        return updateResources(context, language);
    }

    public static String getPersistedLanguage(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(SELECTED_LANGUAGE, "vi"); // Default Tiếng Việt
    }

    private static void persist(Context context, String language) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(SELECTED_LANGUAGE, language).apply();
    }

    private static Context updateResources(Context context, String language) {
        Locale locale = new Locale(language);
        Locale.setDefault(locale);

        Resources resources = context.getResources();
        Configuration configuration = new Configuration(resources.getConfiguration());
        configuration.setLocale(locale);

        return context.createConfigurationContext(configuration);
    }
}