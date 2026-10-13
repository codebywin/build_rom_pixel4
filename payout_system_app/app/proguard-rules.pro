# ==============================================================================
# CHỐNG DỊCH NGƯỢC NÂNG CAO (ANTI-DECOMPILE / OBFUSCATION)
# ==============================================================================
# 1. Xóa sạch tên file mã nguồn gốc và số dòng lỗi trong Bytecode
-renamesourcefileattribute ""
-keepattributes !SourceFile,!LineNumberTable,!LocalVariableTable,!LocalVariableTypeTable

# 2. Giữ nguyên package com.payout.app để chống lỗi reflection
-keeppackagenames com.payout.app**

# 3. Cho phép gộp và tối ưu quyền truy cập để inline tối đa
-allowaccessmodification
-optimizationpasses 5

# 4. Tự động xóa sạch toàn bộ lệnh Log để không lộ chuỗi debug
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}

# ==============================================================================
# BẢO VỆ CÁC THÀNH PHẦN HỆ THỐNG VÀ THƯ VIỆN BẮT BUỘC
# ==============================================================================
# Xposed Hook Rules (Không làm đổi tên class Xposed để LSPosed nạp được)
-keep class com.payout.app.Dfs { *; }
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**

# Stub / Hidden Android Framework Classes (InputChannel, InputEventReceiver,...)
-keep class android.view.** { *; }
-dontwarn android.view.**

# Java-WebSocket Library Rules
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**

# Giữ lại các Component bắt buộc của Android trong Manifest
-keep class com.payout.app.RemoteForegroundService { *; }
-keep class com.payout.app.RemoteForegroundService$Companion { *; }
-keep class com.payout.app.BootReceiver { *; }
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver

