package com.matrixlab.focustime.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.matrixlab.focustime.service.BlockerForegroundService

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.d(TAG, "Boot completed — starting foreground service")
            val serviceIntent = Intent(context, BlockerForegroundService::class.java)
            try {
                context.startForegroundService(serviceIntent)
            } catch (e: SecurityException) {
                Log.e(TAG, "Failed to start foreground service: SecurityException", e)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Failed to start foreground service: IllegalStateException", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service", e)
            }
        }
    }
}
