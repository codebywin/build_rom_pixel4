package de.robv.android.xposed;

import android.util.Log;
import java.lang.reflect.Member;

public class XposedBridge {
    private static final String TAG = "XposedBridge";

    public static void log(String text) {
        Log.i(TAG, text);
    }

    public static void log(Throwable t) {
        Log.e(TAG, "Xposed Error", t);
    }

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        return new XC_MethodHook.Unhook();
    }
}

