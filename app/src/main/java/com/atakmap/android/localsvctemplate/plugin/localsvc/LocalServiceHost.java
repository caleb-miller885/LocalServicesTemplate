package com.atakmap.android.localsvctemplate.plugin.localsvc;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.atakmap.android.localsvctemplate.plugin.BuildConfig;
import com.example.calc.CalcService;
import com.example.calc.proto.CalcGrpc;

import java.io.IOException;

import io.grpc.Server;
import io.grpc.binder.AndroidComponentAddress;
import io.grpc.binder.BinderServerBuilder;
import io.grpc.binder.IBinderReceiver;
import io.grpc.binder.ServerSecurityPolicy;

/**
 * Runs the gRPC services on the device. Declared in AndroidManifest.xml with
 * android:process=":svc", so Android runs it in its own process, outside ATAK. It starts when the
 * plugin first calls a local service and stops when ATAK closes.
 *
 * This process has NO ATAK classes: never use com.atakmap.* (other than this plugin's own
 * packages) from here or from the services.
 */
public class LocalServiceHost extends Service {
    private static final String TAG = "LocalServiceHost";

    private final IBinderReceiver binderReceiver = new IBinderReceiver();
    private Server server;

    @Override
    public void onCreate() {
        super.onCreate();

        // Only ATAK itself (and this plugin) may call in.
        AllowPackages callers = new AllowPackages(this, BuildConfig.ATAK_PACKAGE_NAME, getPackageName());

        server = BinderServerBuilder
                .forAddress(AndroidComponentAddress.forContext(this), binderReceiver)
                .securityPolicy(ServerSecurityPolicy.newBuilder()
                        .servicePolicy(CalcGrpc.SERVICE_NAME, callers) // one line per service
                        .build())
                .addService(new CalcService()) // one line per service - the same classes the server runs
                .build();
        try {
            server.start();
            Log.i(TAG, "local gRPC services started");
        } catch (IOException e) {
            Log.e(TAG, "could not start local gRPC services", e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binderReceiver.get();
    }

    @Override
    public void onDestroy() {
        if (server != null)
            server.shutdownNow();
        super.onDestroy();
    }
}
