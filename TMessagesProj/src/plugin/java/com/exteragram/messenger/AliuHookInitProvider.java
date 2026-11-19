package com.exteragram.messenger;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.FileLog;

/**
 * Loads libaliuhook before Application.onCreate so hooks are ready before
 * plugin code runs. Skipped when the plugin engine is off or in safe mode;
 * PluginsController.init initialises it when the engine is turned on at runtime.
 */
public class AliuHookInitProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null || Build.VERSION.SDK_INT < 24) {
            return true;
        }
        SharedPreferences preferences = context.getSharedPreferences("exteraconfig", Context.MODE_PRIVATE);
        if (!preferences.getBoolean("pluginsEngine", false) || preferences.getBoolean("pluginsSafeMode", false)) {
            return true;
        }
        try {
            if (de.robv.android.xposed.XposedBridge.isNativeAvailable()) {
                de.robv.android.xposed.XposedBridge.ensureInitialized();
            }
        } catch (Throwable t) {
            FileLog.e("AliuHookInitProvider: early aliuhook init failed", t);
        }
        return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        return 0;
    }
}
