/*
 *  Copyright 2016 Google Inc. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.google.android.apps.forscience.whistlepunk.devicemanager;

import android.content.Context;
import android.support.annotation.NonNull;
import android.util.ArrayMap;

import com.google.android.apps.forscience.whistlepunk.R;
import com.google.android.apps.forscience.whistlepunk.api.scalarinput.InputDeviceSpec;
import com.google.android.apps.forscience.whistlepunk.metadata.ExternalSensorSpec;

import java.util.Map;

/**
 * Keeps track of devices found by all ExternalSensorProviders.
 */
public class DeviceRegistry {
    private Map<String, InputDeviceSpec> mDevices = new ArrayMap<>();
    private InputDeviceSpec mBuiltInDevice;

    public DeviceRegistry(InputDeviceSpec builtInDevice) {
        mBuiltInDevice = builtInDevice;
    }

    // TODO: store and retrieve "My Devices" from database
    public void addDevice(String type, InputDeviceSpec spec) {
        mDevices.put(InputDeviceSpec.joinAddresses(type, spec.getDeviceAddress()), spec);
    }

    public InputDeviceSpec getDevice(String type, String deviceAddress) {
        String key = InputDeviceSpec.joinAddresses(type, deviceAddress);
        InputDeviceSpec spec = getDevice(key);
        if (spec == null) {
            throw new IllegalArgumentException(key + " not found in " + mDevices.keySet());
        }
        return spec;
    }

    @NonNull
    private InputDeviceSpec getDevice(String deviceGlobalAddress) {
        return mDevices.get(deviceGlobalAddress);
    }

    @Override
    public String toString() {
        return "DeviceRegistry{" +
                "mDevices=" + mDevices +
                '}';
    }

    InputDeviceSpec getDevice(ExternalSensorSpec spec) {
        if (spec == null) {
            return mBuiltInDevice;
        }
        InputDeviceSpec device = getDevice(spec.getGlobalDeviceAddress());
        if (device == null) {
            // generate imaginary device to hold the sensor
            return new InputDeviceSpec(spec.getDeviceAddress(), spec.getName());
        }
        return device;
    }

    public int getDeviceCount() {
        return mDevices.size();
    }

    public InputDeviceSpec getBuiltInDevice() {
        return mBuiltInDevice;
    }
}
