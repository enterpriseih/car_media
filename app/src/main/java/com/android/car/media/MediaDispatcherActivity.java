package com.android.car.media;

import android.Manifest;
import android.car.Car;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentActivity;

import com.android.car.media.common.source.MediaSource;
import com.android.car.media.common.source.MediaSourceViewModel;


/**
 * A trampoline activity that handles the {@link Car#CAR_INTENT_ACTION_MEDIA_TEMPLATE} implicit
 * intent, and fires up either the Media Center's {@link MediaActivity}, or the specialized
 * application if the selected media source is custom (e.g. the Radio app).
 */
public class MediaDispatcherActivity extends FragmentActivity {

    private static final String TAG = "MediaDispatcherActivity";

    final int MY_PERMISSIONS_REQUEST_MEDIA = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
//        if (permissionChk()) {
            doOnCreate();
//        }
    }

    private void doOnCreate() {
        Intent intent = getIntent();
        String action = intent != null ? intent.getAction() : null;

        MediaSourceViewModel mediaSrcVM = MediaSourceViewModel.get(getApplication());
        MediaSource mediaSrc = null;

        if (Car.CAR_INTENT_ACTION_MEDIA_TEMPLATE.equals(action)) {
            String packageName = intent.getStringExtra(Car.CAR_EXTRA_MEDIA_PACKAGE);
            if (packageName != null) {
                mediaSrc = new MediaSource(this, packageName);
                mediaSrcVM.setPrimaryMediaSource(mediaSrc);
            }
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onCreate packageName : " + packageName);
            }
        }

        if (mediaSrc == null) {
            mediaSrc = mediaSrcVM.getPrimaryMediaSource().getValue();
        }

        Intent newIntent;
        if ((mediaSrc != null) && (!mediaSrc.isBrowsable() || mediaSrc.isCustom())) {
            // Launch custom app (e.g. Radio)
            String srcPackage = mediaSrc.getPackageName();
            newIntent = getPackageManager().getLaunchIntentForPackage(srcPackage);
        } else {
            // Launch media center
            newIntent = new Intent(this, MediaActivity.class);
        }

        newIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(newIntent);
        finish();
    }

    public boolean permissionChk() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.MEDIA_CONTENT_CONTROL) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, android.Manifest.permission.MEDIA_CONTENT_CONTROL)) {
                ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.MEDIA_CONTENT_CONTROL, Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.INTERNET},
                        MY_PERMISSIONS_REQUEST_MEDIA);
            } else {
                // No explanation needed, we can request the permission.
                ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.MEDIA_CONTENT_CONTROL, Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.INTERNET},
                        MY_PERMISSIONS_REQUEST_MEDIA);
            }
            return false;
        } else {
            return true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case MY_PERMISSIONS_REQUEST_MEDIA:
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    doOnCreate();
                }
                break;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}
