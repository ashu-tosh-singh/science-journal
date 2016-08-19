package com.google.android.apps.forscience.whistlepunk.api.scalarinput;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.support.annotation.NonNull;

import com.google.android.apps.forscience.javalib.Consumer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds services that implement the scalarinput API
 */
public class ScalarSensorServiceFinder extends
        Consumer<ScalarInputDiscoverer.AppDiscoveryCallbacks> {
    public static final String INTENT_ACTION =
            "com.google.android.apps.forscience.whistlepunk.SCALAR_SENSOR";

    private final Context mContext;
    private final Map<String, ServiceConnection> mConnections = new HashMap<>();

    public ScalarSensorServiceFinder(Context context) {
        mContext = context;
    }

    @Override
    public void take(ScalarInputDiscoverer.AppDiscoveryCallbacks callbacks) {
        PackageManager pm = mContext.getPackageManager();
        List<ResolveInfo> resolveInfos = pm.queryIntentServices(new Intent(INTENT_ACTION), 0);
        for (ResolveInfo info : resolveInfos) {
            ServiceInfo serviceInfo = info.serviceInfo;
            String packageName = serviceInfo.packageName;
            ComponentName name = new ComponentName(packageName, serviceInfo.name);
            Intent intent = new Intent();
            intent.setComponent(name);
            final ServiceConnection conn = makeServiceConnection(packageName, callbacks);
            mConnections.put(packageName, conn);
            mContext.bindService(intent, conn, Context.BIND_AUTO_CREATE);
        }
    }

    @NonNull
    private ServiceConnection makeServiceConnection(final String packageName,
            final ScalarInputDiscoverer.AppDiscoveryCallbacks callbacks) {
        return new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                // TODO: think harder about threading here?
                callbacks.onServiceFound(ISensorDiscoverer.Stub.asInterface(service));
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                // TODO: when should disconnection happen?
                mConnections.remove(packageName);
            }
        };
    }
}