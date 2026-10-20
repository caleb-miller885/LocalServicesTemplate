package com.example.calc;

import com.example.calc.proto.CalcGrpc;
import com.example.calc.proto.GeoBounds;
import com.example.calc.proto.HeatmapImage;
import com.example.calc.proto.LatLon;
import com.example.calc.proto.OptimiseRouteRequest;
import com.example.calc.proto.OptimiseRouteResult;
import com.example.calc.proto.OptimiseRouteUpdate;
import com.example.calc.proto.Progress;
import com.example.calc.proto.RangeBearingRequest;
import com.example.calc.proto.RangeBearingResult;
import com.example.calc.proto.RenderHeatmapRequest;
import com.example.calc.proto.RenderHeatmapUpdate;
import com.google.protobuf.ByteString;

import java.io.IOException;
import java.util.Random;

import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

/**
 * Example calculation service, one rpc per shape of the old REST API (see calc.proto):
 * <ol>
 *   <li>{@link #rangeBearing}: request in, answer out (was a plain POST);</li>
 *   <li>{@link #optimiseRoute}: long calculation, small result (was POST + poll for JSON);</li>
 *   <li>{@link #renderHeatmap}: long calculation, large binary result (was POST + poll + image bytes).</li>
 * </ol>
 * It's plain Java, so the same class runs on the server and on the phone. On the server, Spring
 * registers it with @GrpcService, which is the only Spring-specific part.
 *
 * The long rpcs run on a gRPC worker thread and check {@code isCancelled()} as they go, so a
 * client that cancels (or whose deadline passes, or that disconnects) stops the work.
 */
public class CalcService extends CalcGrpc.CalcImplBase {

    static final double EARTH_RADIUS_M = 6_371_008.8;
    static final int PROGRESS_STEPS = 20;
    static final int MAX_POINTS = 500;
    static final int MAX_IMAGE_SIDE = 2048;

    // ---- 1. a plain request/answer -----------------------------------------------------------

    @Override
    public void rangeBearing(RangeBearingRequest request, StreamObserver<RangeBearingResult> reply) {
        String bad = !request.hasFrom() || !request.hasTo() ? "from and to are required"
                : invalid(request.getFrom()) != null ? invalid(request.getFrom()) : invalid(request.getTo());
        if (bad != null) {
            reply.onError(Status.INVALID_ARGUMENT.withDescription(bad).asRuntimeException());
            return;
        }
        reply.onNext(RangeBearingResult.newBuilder()
                .setRangeM(distanceM(request.getFrom(), request.getTo()))
                .setBearingDeg(bearingDeg(request.getFrom(), request.getTo()))
                .build());
        reply.onCompleted();
    }

    // ---- 2. a long calculation with a small result: progress, then the result ----------------

    @Override
    public void optimiseRoute(OptimiseRouteRequest request, StreamObserver<OptimiseRouteUpdate> reply) {
        ServerCallStreamObserver<OptimiseRouteUpdate> out = (ServerCallStreamObserver<OptimiseRouteUpdate>) reply;
        int n = request.getPointsCount();
        int iterations = request.getIterations() == 0 ? 20_000 : request.getIterations();
        String bad = n < 2 || n > MAX_POINTS ? "need 2.." + MAX_POINTS + " points, got " + n
                : iterations < 0 || iterations > 1_000_000 ? "iterations must be 0..1000000"
                : null;
        for (int i = 0; bad == null && i < n; i++)
            bad = invalid(request.getPoints(i));
        if (bad != null) {
            out.onError(Status.INVALID_ARGUMENT.withDescription(bad).asRuntimeException());
            return;
        }

        double[][] d = new double[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                d[i][j] = distanceM(request.getPoints(i), request.getPoints(j));
        int[] order = new int[n];
        for (int i = 0; i < n; i++)
            order[i] = i;
        double initial = length(order, d);

        // Random 2-opt: reverse a stretch of the route whenever that makes it shorter. The start
        // point (index 0) stays first; the route doesn't return to it.
        Random random = new Random(1);
        int perStep = Math.max(1, iterations / PROGRESS_STEPS);
        for (int it = 0; it < iterations; it++) {
            if (n >= 3) {
                int i = 1 + random.nextInt(n - 1), j = 1 + random.nextInt(n - 1);
                if (i > j) { int t = i; i = j; j = t; }
                if (i < j) {
                    int a = order[i - 1], b = order[i], c = order[j];
                    double delta = d[a][c] - d[a][b];
                    if (j + 1 < n) delta += d[b][order[j + 1]] - d[c][order[j + 1]];
                    if (delta < -1e-9) reverse(order, i, j);
                }
            }
            if ((it + 1) % perStep == 0) {
                if (out.isCancelled() || !pause(request.getSimulateMs())) return;   // client gave up: stop
                out.onNext(OptimiseRouteUpdate.newBuilder()
                        .setProgress(progress((it + 1) / (double) iterations,
                                String.format("%.0f m", length(order, d))))
                        .build());
            }
        }

        OptimiseRouteResult.Builder result = OptimiseRouteResult.newBuilder()
                .setLengthM(length(order, d))
                .setInitialLengthM(initial);
        for (int i : order)
            result.addOrder(i);
        out.onNext(OptimiseRouteUpdate.newBuilder().setResult(result).build());
        out.onCompleted();
    }

    // ---- 3. a long calculation with a large binary result: progress, then the image ----------

    @Override
    public void renderHeatmap(RenderHeatmapRequest request, StreamObserver<RenderHeatmapUpdate> reply) {
        ServerCallStreamObserver<RenderHeatmapUpdate> out = (ServerCallStreamObserver<RenderHeatmapUpdate>) reply;
        int w = request.getWidth() == 0 ? 1024 : request.getWidth();
        int h = request.getHeight() == 0 ? 1024 : request.getHeight();
        String bad = w < 1 || h < 1 || w > MAX_IMAGE_SIDE || h > MAX_IMAGE_SIDE
                ? "width and height must be 1.." + MAX_IMAGE_SIDE
                : invalid(request.hasBounds() ? request.getBounds() : null);
        if (bad != null) {
            out.onError(Status.INVALID_ARGUMENT.withDescription(bad).asRuntimeException());
            return;
        }

        // A made-up "signal strength" map over the requested area: a few sources, falling off
        // with distance, plus noise. Weak signal (below NO_COVERAGE) is left transparent.
        Random random = new Random(request.getSeed());
        int sources = 6;
        double[] sx = new double[sources], sy = new double[sources], power = new double[sources];
        for (int k = 0; k < sources; k++) {
            sx[k] = random.nextDouble() * w;
            sy[k] = random.nextDouble() * h;
            power[k] = 0.4 + random.nextDouble() * 0.6;
        }
        double scale2 = Math.pow(Math.min(w, h) / 6.0, 2);

        byte[] rgba = new byte[w * h * 4];
        int rowsPerStep = Math.max(1, h / PROGRESS_STEPS);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double v = 0;
                for (int k = 0; k < sources; k++)
                    v += power[k] / (1 + ((x - sx[k]) * (x - sx[k]) + (y - sy[k]) * (y - sy[k])) / scale2);
                v += (random.nextDouble() - 0.5) * 0.15;
                colour(Math.max(0, Math.min(1, v)), rgba, (y * w + x) * 4);
            }
            if ((y + 1) % rowsPerStep == 0) {
                if (out.isCancelled() || !pause(request.getSimulateMs())) return;
                out.onNext(RenderHeatmapUpdate.newBuilder()
                        .setProgress(progress(0.9 * (y + 1) / h, "rendering"))
                        .build());
            }
        }

        out.onNext(RenderHeatmapUpdate.newBuilder().setProgress(progress(0.9, "encoding")).build());
        byte[] png;
        try {
            png = Png.encodeRgba(w, h, rgba);
        } catch (IOException e) {
            out.onError(Status.INTERNAL.withDescription("PNG encoding failed: " + e).asRuntimeException());
            return;
        }

        // One message with the whole file. It can be over 4 MB, gRPC's default limit for a received
        // message, so clients raise theirs (GrpcChannels.MAX_MESSAGE_BYTES).
        out.onNext(RenderHeatmapUpdate.newBuilder().setResult(HeatmapImage.newBuilder()
                .setMimeType("image/png").setWidth(w).setHeight(h).setData(ByteString.copyFrom(png))
                .setBounds(request.getBounds())).build());
        out.onCompleted();
    }

    // ---- helpers -----------------------------------------------------------------------------

    static String invalid(GeoBounds b) {
        if (b == null) return "bounds are required";
        if (b.getSouth() < -90 || b.getNorth() > 90 || b.getSouth() >= b.getNorth())
            return "bounds need -90 <= south < north <= 90";
        if (b.getWest() < -180 || b.getEast() > 180 || b.getWest() >= b.getEast())
            return "bounds need -180 <= west < east <= 180";
        return null;
    }

    static String invalid(LatLon p) {
        if (p.getLat() < -90 || p.getLat() > 90) return "latitude out of range: " + p.getLat();
        if (p.getLon() < -180 || p.getLon() > 180) return "longitude out of range: " + p.getLon();
        return null;
    }

    /** Great-circle distance (haversine). */
    static double distanceM(LatLon a, LatLon b) {
        double p1 = Math.toRadians(a.getLat()), p2 = Math.toRadians(b.getLat());
        double dp = p2 - p1, dl = Math.toRadians(b.getLon() - a.getLon());
        double s = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(s)));
    }

    /** Initial bearing from a to b, 0..360 degrees from true north. */
    static double bearingDeg(LatLon a, LatLon b) {
        double p1 = Math.toRadians(a.getLat()), p2 = Math.toRadians(b.getLat());
        double dl = Math.toRadians(b.getLon() - a.getLon());
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    private static double length(int[] order, double[][] d) {
        double total = 0;
        for (int i = 1; i < order.length; i++)
            total += d[order[i - 1]][order[i]];
        return total;
    }

    private static void reverse(int[] a, int i, int j) {
        for (; i < j; i++, j--) {
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    }

    private static Progress progress(double fraction, String message) {
        return Progress.newBuilder().setFraction(fraction).setMessage(message).build();
    }

    /** The demo's simulated work. False if the thread was interrupted (the call is being torn down). */
    private static boolean pause(int ms) {
        if (ms <= 0) return true;
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Signal below this is "no coverage": a fully transparent pixel. */
    static final double NO_COVERAGE = 0.25;

    /** 0 → blue, 1 → red, through cyan, green and yellow; transparent below NO_COVERAGE. Pixels are
     *  fully transparent or fully opaque, so ATAK can fade the whole image evenly. */
    private static void colour(double v, byte[] rgba, int at) {
        double hue = (1 - v) * 240 / 60;   // 0..4 sextants
        int i = (int) Math.floor(hue);
        double f = hue - i;
        double r, g, b;
        switch (i) {
            case 0: r = 1; g = f; b = 0; break;          // red → yellow
            case 1: r = 1 - f; g = 1; b = 0; break;      // yellow → green
            case 2: r = 0; g = 1; b = f; break;          // green → cyan
            case 3: r = 0; g = 1 - f; b = 1; break;      // cyan → blue
            default: r = 0; g = 0; b = 1; break;         // blue
        }
        rgba[at] = (byte) Math.round(r * 255);
        rgba[at + 1] = (byte) Math.round(g * 255);
        rgba[at + 2] = (byte) Math.round(b * 255);
        rgba[at + 3] = (byte) (v < NO_COVERAGE ? 0 : 255);
    }
}
