package io.github.libxposed.service;

import android.content.Context;
import android.content.SharedPreferences;

public final class XposedService {
    private final Context context;
    public XposedService(Context context) { this.context = context; }
    public SharedPreferences getRemotePreferences(String name) {
        return context.getSharedPreferences(name, Context.MODE_PRIVATE);
    }
}
