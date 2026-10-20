package com.example.calc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.calc.proto.CalcGrpc;
import com.example.calc.proto.GeoBounds;
import com.example.calc.proto.HeatmapImage;
import com.example.calc.proto.LatLon;
import com.example.calc.proto.OptimiseRouteRequest;
import com.example.calc.proto.OptimiseRouteResult;
import com.example.calc.proto.OptimiseRouteUpdate;
import com.example.calc.proto.RangeBearingRequest;
import com.example.calc.proto.RangeBearingResult;
import com.example.calc.proto.RenderHeatmapRequest;
import com.example.calc.proto.RenderHeatmapUpdate;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;

/** Runs on the PC, no device: a real gRPC server and client talking in memory. */
public class CalcServiceTest {

    private Server server;
    private ManagedChannel channel;
    private CalcGrpc.CalcBlockingStub calc;

    @Before
    public void setUp() throws Exception {
        String name = InProcessServerBuilder.generateName();
        // Not directExecutor: the long calls must run on their own thread, as they do for real.
        server = InProcessServerBuilder.forName(name).addService(new CalcService()).build().start();
        channel = InProcessChannelBuilder.forName(name)
                .maxInboundMessageSize(32 * 1024 * 1024)   // as GrpcChannels does: images can be over 4 MB
                .build();
        calc = CalcGrpc.newBlockingStub(channel);
    }

    @After
    public void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    private static LatLon at(double lat, double lon) {
        return LatLon.newBuilder().setLat(lat).setLon(lon).build();
    }

    // ---- 1. unary ----------------------------------------------------------------------------

    @Test
    public void rangeBearingOneDegreeNorth() {
        RangeBearingResult r = calc.rangeBearing(RangeBearingRequest.newBuilder()
                .setFrom(at(0, 0)).setTo(at(1, 0)).build());
        assertEquals(111_195, r.getRangeM(), 1);   // 1 degree of latitude
        assertEquals(0, r.getBearingDeg(), 1e-9);
    }

    @Test
    public void rangeBearingEast() {
        RangeBearingResult r = calc.rangeBearing(RangeBearingRequest.newBuilder()
                .setFrom(at(0, 0)).setTo(at(0, 1)).build());
        assertEquals(90, r.getBearingDeg(), 1e-9);
    }

    @Test
    public void rangeBearingRejectsBadLatitude() {
        try {
            calc.rangeBearing(RangeBearingRequest.newBuilder().setFrom(at(91, 0)).setTo(at(0, 0)).build());
            fail("expected INVALID_ARGUMENT");
        } catch (StatusRuntimeException e) {
            assertEquals(Status.Code.INVALID_ARGUMENT, e.getStatus().getCode());
        }
    }

    // ---- 2. progress stream, then a result ---------------------------------------------------

    @Test
    public void optimiseStreamsProgressThenAShorterRoute() {
        Random random = new Random(7);
        OptimiseRouteRequest.Builder request = OptimiseRouteRequest.newBuilder();
        for (int i = 0; i < 40; i++)
            request.addPoints(at(-35 + random.nextDouble() * 0.1, 138.5 + random.nextDouble() * 0.1));

        int progress = 0;
        OptimiseRouteResult result = null;
        Iterator<OptimiseRouteUpdate> updates = calc.optimiseRoute(request.build());
        while (updates.hasNext()) {
            OptimiseRouteUpdate u = updates.next();
            if (u.hasProgress()) progress++;
            if (u.hasResult()) result = u.getResult();
        }

        assertEquals(CalcService.PROGRESS_STEPS, progress);
        assertTrue(result != null);
        assertTrue(result.getLengthM() < result.getInitialLengthM());
        assertEquals(0, (int) result.getOrder(0));                       // start stays first
        int[] sorted = result.getOrderList().stream().mapToInt(Integer::intValue).sorted().toArray();
        int[] all = new int[40];
        Arrays.setAll(all, i -> i);
        assertArrayEquals(all, sorted);                                  // every point exactly once
    }

    @Test
    public void optimiseRejectsTooFewPoints() {
        try {
            calc.optimiseRoute(OptimiseRouteRequest.newBuilder().addPoints(at(0, 0)).build()).hasNext();
            fail("expected INVALID_ARGUMENT");
        } catch (StatusRuntimeException e) {
            assertEquals(Status.Code.INVALID_ARGUMENT, e.getStatus().getCode());
        }
    }

    @Test
    public void cancellingStopsTheCalculation() throws Exception {
        OptimiseRouteRequest request = OptimiseRouteRequest.newBuilder()
                .addPoints(at(0, 0)).addPoints(at(0, 1)).addPoints(at(1, 1))
                .setSimulateMs(200)                                  // ~4 s if left to run
                .build();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Status> status = new AtomicReference<>();
        CalcGrpc.newStub(channel).optimiseRoute(request, new ClientResponseObserver<OptimiseRouteRequest, OptimiseRouteUpdate>() {
            @Override
            public void beforeStart(ClientCallStreamObserver<OptimiseRouteRequest> call) {
                new Thread(() -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ignored) {
                    }
                    call.cancel("user cancelled", null);
                }).start();
            }

            @Override
            public void onNext(OptimiseRouteUpdate u) {
                if (u.hasResult()) status.set(Status.OK);
            }

            @Override
            public void onError(Throwable t) {
                status.set(Status.fromThrowable(t));
                done.countDown();
            }

            @Override
            public void onCompleted() {
                done.countDown();
            }
        });
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertEquals(Status.Code.CANCELLED, status.get().getCode());
    }

    // ---- 3. progress stream, then a large image ----------------------------------------------

    @Test
    public void heatmapArrivesAsAValidPngOver4MB() {
        Iterator<RenderHeatmapUpdate> updates = calc.renderHeatmap(RenderHeatmapRequest.newBuilder()
                .setWidth(2048).setHeight(2048).setSeed(42).setBounds(AREA).build());
        HeatmapImage result = null;
        while (updates.hasNext()) {
            RenderHeatmapUpdate u = updates.next();
            if (u.hasResult()) result = u.getResult();
        }

        assertTrue(result != null);
        byte[] png = result.getData().toByteArray();
        assertTrue("over the 4 MB default, so the raised limit is needed: " + png.length, png.length > 4 * 1024 * 1024);
        assertArrayEquals(new byte[] {(byte) 137, 'P', 'N', 'G'}, Arrays.copyOf(png, 4));
        assertEquals(2048, readInt(png, 16));   // IHDR width
        assertEquals(2048, readInt(png, 20));   // IHDR height
        assertEquals(6, png[25]);               // IHDR colour type: RGBA, for the no-coverage pixels
        assertEquals(AREA, result.getBounds()); // the area comes back with the image
    }

    @Test
    public void heatmapNeedsBounds() {
        try {
            calc.renderHeatmap(RenderHeatmapRequest.newBuilder().setWidth(16).setHeight(16).build()).hasNext();
            fail("expected INVALID_ARGUMENT");
        } catch (StatusRuntimeException e) {
            assertEquals(Status.Code.INVALID_ARGUMENT, e.getStatus().getCode());
        }
    }

    @Test
    public void heatmapRejectsHugeImages() {
        try {
            calc.renderHeatmap(RenderHeatmapRequest.newBuilder().setWidth(10_000).build()).hasNext();
            fail("expected INVALID_ARGUMENT");
        } catch (StatusRuntimeException e) {
            assertEquals(Status.Code.INVALID_ARGUMENT, e.getStatus().getCode());
        }
    }

    private static final GeoBounds AREA = GeoBounds.newBuilder()
            .setNorth(-34.88).setSouth(-34.97).setEast(138.65).setWest(138.55).build();

    private static int readInt(byte[] b, int at) {
        return (b[at] & 0xff) << 24 | (b[at + 1] & 0xff) << 16 | (b[at + 2] & 0xff) << 8 | (b[at + 3] & 0xff);
    }
}
