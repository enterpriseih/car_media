// ICarMediaSourceListener.aidl
package com.faw.car.media.service;

// Declare any non-default types here with import statements

oneway interface ICarMediaSourceListener {
    void onMediaSourceChanged(in String newSource) = 0;
}
