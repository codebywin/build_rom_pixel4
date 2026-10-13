package com.payout.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action
        Log.i(TAG, "Nhận được sự kiện hệ thống: $action")

        if (context != null && (
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        )) {
            Log.i(TAG, "Điện thoại đã bật nguồn -> Tự động khởi động Remote Service ngầm!")
            RemoteForegroundService.startService(context)
        }
    }
}

