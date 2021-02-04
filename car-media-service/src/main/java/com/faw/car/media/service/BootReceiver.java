package com.faw.car.media.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

public class BootReceiver extends BroadcastReceiver {

    final String TAG = BootReceiver.class.getSimpleName();
    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public void onReceive(Context context, Intent intent) {
        // TODO: This method is called when the BroadcastReceiver is receiving
        // an Intent broadcast.
        Log.v(TAG, "onReceive --- ACTION_BOOT_COMPLETED");
        if (intent != null && intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED)) {
            Intent newIntent = new Intent(context, CarMediaService.class);
            context.startForegroundService(newIntent);
        }
    }
}