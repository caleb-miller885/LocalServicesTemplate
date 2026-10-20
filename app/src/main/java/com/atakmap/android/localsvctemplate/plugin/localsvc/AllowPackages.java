package com.atakmap.android.localsvctemplate.plugin.localsvc;

import android.content.Context;
import android.content.pm.PackageManager;

import java.util.Arrays;
import java.util.List;

import io.grpc.Status;
import io.grpc.binder.SecurityPolicy;

/**
 * gRPC-over-Binder security check: only lets in apps with the given package names.
 *
 * By default grpc-binder only trusts the same app on both ends. ATAK and this plugin are different
 * apps, so the service allows ATAK (LocalServiceHost) and ATAK allows the plugin (GrpcChannels).
 */
public final class AllowPackages extends SecurityPolicy {
    private final PackageManager packageManager;
    private final List<String> packages;

    public AllowPackages(Context context, String... packages) {
        this.packageManager = context.getPackageManager();
        this.packages = Arrays.asList(packages);
    }

    @Override
    public Status checkAuthorization(int uid) {
        String[] pkgs = packageManager.getPackagesForUid(uid);
        if (pkgs != null)
            for (String p : pkgs)
                if (packages.contains(p))
                    return Status.OK;
        return Status.PERMISSION_DENIED.withDescription("uid " + uid + " is not one of " + packages);
    }
}
