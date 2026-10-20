package com.atakmap.android.localsvctemplate.plugin;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.atakmap.android.localsvctemplate.plugin.localsvc.AllowPackages;
import com.atakmap.android.localsvctemplate.plugin.localsvc.LocalServiceHost;

import java.util.concurrent.Executor;

import io.grpc.ManagedChannel;
import io.grpc.binder.AndroidComponentAddress;
import io.grpc.binder.BinderChannelBuilder;
import io.grpc.okhttp.OkHttpChannelBuilder;

/**
 * A channel is the connection a gRPC stub talks through. Build one of these, then use the same
 * generated stub either way:
 *
 *   ManagedChannel channel = local ? GrpcChannels.local(pluginContext, atakContext)
 *                                  : GrpcChannels.remote("10.0.0.5:9090");
 *   CalcGrpc.CalcStub calc = CalcGrpc.newStub(channel);
 *
 * Replies (StreamObserver onNext/onError/onCompleted) arrive on the UI thread, so they can update
 * views directly - just don't do heavy work in them.
 * Shut channels down when finished (channel.shutdownNow() in onStop).
 */
public final class GrpcChannels {

    private static final Executor UI_THREAD = new Handler(Looper.getMainLooper())::post;

    /** Largest reply accepted. gRPC's default is 4 MB; image results (Calc.RenderHeatmap) can be more. */
    public static final int MAX_MESSAGE_BYTES = 32 * 1024 * 1024;

    private GrpcChannels() {
    }

    /** The services running on this device (LocalServiceHost). Starts them on first use. */
    public static ManagedChannel local(Context pluginContext, Context atakContext) {
        return BinderChannelBuilder
                .forAddress(AndroidComponentAddress.forRemoteComponent(
                        pluginContext.getPackageName(), LocalServiceHost.class.getName()),
                        atakContext) // bind with ATAK's context, as TPC's samples do
                .securityPolicy(new AllowPackages(atakContext, pluginContext.getPackageName()))
                .maxInboundMessageSize(MAX_MESSAGE_BYTES)
                .executor(UI_THREAD)
                .build();
    }

    /** A remote service over the network, e.g. remote("10.0.0.5:9090"). */
    public static ManagedChannel remote(String hostAndPort) {
        return OkHttpChannelBuilder.forTarget(hostAndPort)
                .usePlaintext() // no TLS - add TLS for anything beyond a lab network
                .maxInboundMessageSize(MAX_MESSAGE_BYTES)
                .executor(UI_THREAD)
                .build();
    }
}
