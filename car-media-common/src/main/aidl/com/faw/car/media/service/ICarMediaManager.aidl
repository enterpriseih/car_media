// ICarMediaManager.aidl
package com.faw.car.media.service;
import com.faw.car.media.service.ICarMediaSourceListener;

// Declare any non-default types here with import statements

interface ICarMediaManager {
    String getMediaSource();
    void setMediaSource(in String mediaSource);
    void registerMediaSourceListener(in ICarMediaSourceListener callback);
    void unregisterMediaSourceListener(in ICarMediaSourceListener callback);
}