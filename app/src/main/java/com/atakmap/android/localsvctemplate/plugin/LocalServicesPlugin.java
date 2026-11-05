
package com.atakmap.android.localsvctemplate.plugin;

import android.content.Context;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.localsvctemplate.plugin.coverage.CoverageLayer;
import com.atakmap.android.localsvctemplate.plugin.coverage.CoverageShare;
import com.atakmap.android.localsvctemplate.plugin.coverage.GLCoverageLayer;
import com.atakmap.android.localsvctemplate.plugin.landing.LandingPane;
import com.atakmap.android.localsvctemplate.plugin.zones.ZoneToolbar;
import com.atakmap.android.localsvctemplate.plugin.zones.ZoneTools;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.layer.opengl.GLLayerFactory;
import com.example.calc.proto.CalcGrpc;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.StreamObserver;

public class LocalServicesPlugin implements IPlugin {

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    ToolbarItem zonesToolbarItem;
    Pane templatePane;

    // The exclusion / ops zone tools and the toolbar that holds them (zones/).
    private ZoneTools zoneTools;
    private ZoneToolbar zoneToolbar;

    private CheckBox localToggle;
    private EditText remoteAddress;
    private TextView output;
    private ProgressBar progressBar;
    private View coverageControls;
    private LandingPane landing;

    // The image from 3., drawn on the map (null until the first result).
    private CoverageLayer coverage;

    // The current connection, and which address it is for ("local" or "host:port").
    private ManagedChannel channel;
    private String channelAddress;

    // The long calculation in progress, so Cancel can stop it (null when none is running).
    private ClientCallStreamObserver<?> running;

    public LocalServicesPlugin(IServiceController serviceController) {
        this.serviceController = serviceController;
        final PluginContextProvider ctxProvider = serviceController
                .getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }

        // obtain the UI service
        uiService = serviceController.getService(IHostUIService.class);

        // initialize the toolbar button for the plugin

        // create the button and set the identifier to be well known
        // if you fail to do this, the toolbar configuration will never
        // be able to find it again after the user moves the icon.
        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        pluginContext.getResources().getDrawable(R.drawable.ic_launcher),
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        showPane();
                    }
                }).setIdentifier(pluginContext.getPackageName())
                .build();

        // A second button: opens the zone toolbar, like ATAK's drawing button opens its toolbar.
        zonesToolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.zone_toolbar),
                MarshalManager.marshal(
                        pluginContext.getResources().getDrawable(R.drawable.ic_zone_toolbar),
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        ZoneToolbar.toggle();
                    }
                }).setIdentifier(pluginContext.getPackageName() + ".zones")
                .build();
    }

    @Override
    public void onStart() {
        zoneTools = new ZoneTools(pluginContext, MapView.getMapView());
        zoneTools.onZonesChanged = this::showZones;
        zoneTools.register();
        zoneToolbar = new ZoneToolbar(pluginContext, MapView.getMapView(), zoneTools);

        // the plugin is starting, add the buttons to the toolbar
        if (uiService == null)
            return;

        uiService.addToolbarItem(toolbarItem);
        uiService.addToolbarItem(zonesToolbarItem);
    }

    @Override
    public void onStop() {
        // Closes the connection. For the local services this also lets Android stop the :svc
        // process.
        cancelRunning();
        if (channel != null) {
            channel.shutdownNow();
            channel = null;
        }
        removeCoverage();
        zoneToolbar.dispose();
        zoneTools.unregister();

        // the plugin is stopping, remove the buttons from the toolbar
        if (uiService == null)
            return;

        uiService.removeToolbarItem(toolbarItem);
        uiService.removeToolbarItem(zonesToolbarItem);
    }

    private void showPane() {
        // instantiate the plugin view if necessary
        if (templatePane == null) {
            View view = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);
            bindViews(view);

            templatePane = new PaneBuilder(view)
                    // relative location is set to default; pane will switch location dependent on
                    // current orientation of device screen
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    // pane will take up 50% of screen width in landscape mode
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    // pane will take up 50% of screen height in portrait mode
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }

        showZones();   // picks up zones edited or deleted while the pane was closed

        // if the plugin pane is not visible, show it!
        if (!uiService.isPaneVisible(templatePane)) {
            uiService.showPane(templatePane, null);
        }
    }

    // ---- demo UI: the same gRPC calls against the on-device services or a remote server -------

    private void bindViews(View view) {
        bindToolbar(view);
        landing = new LandingPane(pluginContext, view.findViewById(R.id.picture_page));
        view.findViewById(R.id.btn_draw_zones).setOnClickListener(v -> ZoneToolbar.open());
        showSampleNodes();

        localToggle = view.findViewById(R.id.local_toggle);
        remoteAddress = view.findViewById(R.id.remote_address);
        output = view.findViewById(R.id.output);
        progressBar = view.findViewById(R.id.progress);
        coverageControls = view.findViewById(R.id.coverage_controls);

        view.findViewById(R.id.btn_range).setOnClickListener(v -> rangeBearing());
        view.findViewById(R.id.btn_optimise).setOnClickListener(v -> optimiseRoute());
        view.findViewById(R.id.btn_render).setOnClickListener(v -> renderHeatmap());
        view.findViewById(R.id.btn_cancel).setOnClickListener(v -> cancelRunning());

        view.findViewById(R.id.btn_share).setOnClickListener(v -> shareCoverage());
        view.findViewById(R.id.btn_remove).setOnClickListener(v -> removeCoverage());
        SeekBar opacity = view.findViewById(R.id.coverage_opacity);
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (coverage != null)
                    coverage.setOpacity(progress / 100f);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
    }

    /**
     * The icon toolbar along the top: one page showing at a time, the Picture page first. Icons
     * without a page yet share a placeholder; give one a page by adding it to the layout and
     * putting its id in pages[] here.
     */
    private void bindToolbar(View view) {
        int[] tabIds = { R.id.tab_picture, R.id.tab_services, R.id.tab_layers,
                R.id.tab_alerts, R.id.tab_log, R.id.tab_settings };
        View placeholder = view.findViewById(R.id.placeholder_page);
        View[] pages = { view.findViewById(R.id.picture_page), view.findViewById(R.id.services_page),
                placeholder, placeholder, placeholder, placeholder };
        View[] tabs = new View[tabIds.length];
        for (int i = 0; i < tabIds.length; i++)
            tabs[i] = view.findViewById(tabIds[i]);

        for (int i = 0; i < tabs.length; i++) {
            final int shown = i;
            tabs[i].setOnClickListener(v -> {
                for (int j = 0; j < tabs.length; j++) {
                    tabs[j].setSelected(j == shown);
                    tabs[j].setAlpha(j == shown ? 1f : 0.5f);
                    pages[j].setVisibility(View.GONE);
                }
                pages[shown].setVisibility(View.VISIBLE);
                if (pages[shown] == placeholder)
                    ((TextView) placeholder).setText(pluginContext.getString(
                            R.string.placeholder_page, tabs[shown].getContentDescription()));
            });
        }
        tabs[0].performClick();
    }

    // ---- landing page lists ----------------------------------------------------------------

    /**
     * Made-up nodes near the map centre, so the landing page has something to show. Replace
     * with the real node health feed - sorted most urgent first before it goes in.
     */
    private void showSampleNodes() {
        GeoPoint c = MapView.getMapView().getCenterPoint().get();
        List<LandingPane.Item> nodes = new ArrayList<>();
        nodes.add(new LandingPane.Item("RF-03 ridge", "last heard 4 min ago", "OFFLINE",
                true, offset(c, 0.020, -0.015)));
        nodes.add(new LandingPane.Item("AC-02 culvert", "EMCON until 14:30Z", "SILENT",
                false, offset(c, -0.012, 0.008)));
        nodes.add(new LandingPane.Item("RF-01 hill 212", "battery 81%  -  2 s ago", "ONLINE",
                false, offset(c, 0.005, 0.010)));
        nodes.add(new LandingPane.Item("EO-01 tower", "battery 64%  -  1 s ago", "ONLINE",
                false, offset(c, -0.004, -0.006)));
        landing.setItems(LandingPane.Category.NODES, nodes);
    }

    /** The zones drawn with the zone toolbar, exclusion zones first. */
    private void showZones() {
        if (landing == null)
            return;   // pane not built yet; showPane() calls this once it is
        List<LandingPane.Item> zones = new ArrayList<>();
        for (ZoneTools.Zone z : zoneTools.zones()) {
            GeoPoint[] points = z.shape.getPoints();
            boolean exclusion = z.type == ZoneTools.Type.EXCLUSION;
            zones.add(new LandingPane.Item(z.shape.getTitle(),
                    String.format(Locale.US, "%.2f km\u00b2  -  %d points", areaKm2(points), points.length),
                    exclusion ? "NO-GO" : "OPS", exclusion, z.shape.getCenter().get()));
        }
        landing.setItems(LandingPane.Category.ZONES, zones);
    }

    /** Polygon area, flat-earth: close enough for zones a few km across. */
    private static double areaKm2(GeoPoint[] p) {
        if (p.length < 3)
            return 0;
        double kmPerDegLat = 111.32;
        double kmPerDegLon = kmPerDegLat * Math.cos(Math.toRadians(p[0].getLatitude()));
        double twice = 0;
        for (int i = 0, j = p.length - 1; i < p.length; j = i++)
            twice += (p[j].getLongitude() * kmPerDegLon) * (p[i].getLatitude() * kmPerDegLat)
                    - (p[i].getLongitude() * kmPerDegLon) * (p[j].getLatitude() * kmPerDegLat);
        return Math.abs(twice) / 2;
    }

    /** The optimised route from 2., in visiting order, with leg and running distance. */
    private void showWaypoints(List<LatLon> points, OptimiseRouteResult r) {
        List<LandingPane.Item> waypoints = new ArrayList<>();
        GeoPoint previous = null;
        double total = 0;
        for (int i = 0; i < r.getOrderCount(); i++) {
            LatLon p = points.get(r.getOrder(i));
            GeoPoint point = new GeoPoint(p.getLat(), p.getLon());
            double leg = previous == null ? 0 : previous.distanceTo(point);
            total += leg;
            waypoints.add(new LandingPane.Item(
                    i == 0 ? "WP 1  (start)" : "WP " + (i + 1),
                    String.format(Locale.US, "%.5f, %.5f", p.getLat(), p.getLon()),
                    String.format(Locale.US, "+%.1f km  (%.1f)", leg / 1000, total / 1000),
                    false,
                    point));
            previous = point;
        }
        landing.setItems(LandingPane.Category.WAYPOINTS, waypoints);
    }

    private static GeoPoint offset(GeoPoint c, double dLat, double dLon) {
        return new GeoPoint(c.getLatitude() + dLat, c.getLongitude() + dLon);
    }

    /**
     * The connection to the services: on this device, or the remote server. It's reused until
     * the address changes, and one channel carries every service on that server.
     */
    private ManagedChannel channel() {
        String address = localToggle.isChecked() ? "local" : remoteAddress.getText().toString().trim();
        if (!address.equals(channelAddress)) {
            cancelRunning();
            if (channel != null)
                channel.shutdownNow();
            channel = address.equals("local")
                    ? GrpcChannels.local(pluginContext, MapView.getMapView().getContext())
                    : GrpcChannels.remote(address);
            channelAddress = address;
        }
        return channel;
    }

    /** The client for the Calc service. The only difference between local and remote is the
     *  channel it uses. Another service on the same server: XxxGrpc.newStub(channel()). */
    private CalcGrpc.CalcStub calc() {
        return CalcGrpc.newStub(channel());
    }

    // Each call: build the request, then pass a StreamObserver that receives the reply - the gRPC
    // equivalent of Retrofit's Callback. Replies arrive on the UI thread (see GrpcChannels).
    //
    // withWaitForReady() = if the service isn't reachable yet (e.g. the local one is still
    // starting), wait for it instead of failing straight away. withDeadlineAfter() caps the wait.

    // ---- calculation examples: the three shapes of the old REST API (see calc.proto) ---------

    /** 1. Was a plain POST returning JSON. Now a normal call: request in, answer out. */
    private void rangeBearing() {
        MapView mapView = MapView.getMapView();
        Marker self = mapView.getSelfMarker();
        GeoPoint from = self != null ? self.getPoint() : mapView.getCenterPoint().get();
        GeoPoint to = mapView.getCenterPoint().get();
        RangeBearingRequest request = RangeBearingRequest.newBuilder()
                .setFrom(latLon(from))
                .setTo(latLon(to))
                .build();

        output.setText("range/bearing self -> map centre...");
        calc().withWaitForReady().withDeadlineAfter(5, TimeUnit.SECONDS)
                .rangeBearing(request, new StreamObserver<RangeBearingResult>() {
                    @Override
                    public void onNext(RangeBearingResult reply) {
                        output.setText(String.format(Locale.US, "range/bearing -> %.0f m at %.1f\u00b0",
                                reply.getRangeM(), reply.getBearingDeg()));
                    }

                    @Override
                    public void onError(Throwable t) {
                        showError("range/bearing", t);
                    }

                    @Override
                    public void onCompleted() {
                    }
                });
    }

    /**
     * 2. Was POST /optimise, then polling GET /optimise/{id} until the JSON result was ready.
     * Now one streaming call: Progress messages arrive while the server works, then the result,
     * then onCompleted. No job id and no polling. Cancel button = cancel the call.
     */
    private void optimiseRoute() {
        // 30 made-up points within ~5 km of the map centre; the first is the start.
        GeoPoint centre = MapView.getMapView().getCenterPoint().get();
        Random random = new Random();
        OptimiseRouteRequest.Builder request = OptimiseRouteRequest.newBuilder()
                .setSimulateMs(100);   // demo only: makes the ~20 progress steps take ~2 s
        for (int i = 0; i < 30; i++)
            request.addPoints(LatLon.newBuilder()
                    .setLat(centre.getLatitude() + (random.nextDouble() - 0.5) * 0.09)
                    .setLon(centre.getLongitude() + (random.nextDouble() - 0.5) * 0.09));

        startLong("optimise route...");
        // A deadline sized for the job: long calculations need far longer than normal calls.
        calc().withWaitForReady().withDeadlineAfter(2, TimeUnit.MINUTES)
                .optimiseRoute(request.build(), new LongCall<OptimiseRouteRequest, OptimiseRouteUpdate>("optimise route") {
                    @Override
                    public void onNext(OptimiseRouteUpdate update) {
                        if (update.hasProgress()) {
                            showProgress(update.getProgress());
                        } else if (update.hasResult()) {
                            OptimiseRouteResult r = update.getResult();
                            showWaypoints(request.getPointsList(), r);
                            output.setText(String.format(Locale.US,
                                    "optimise route -> %.0f m (was %.0f m)\norder: %s",
                                    r.getLengthM(), r.getInitialLengthM(), r.getOrderList()));
                        }
                    }
                });
    }

    /**
     * 3. Was POST /render, polling, then GET of the image bytes (several MB). Now one streaming
     * call, as 2: Progress while it renders, then the result with the image file as bytes and
     * the area it covers. The image can be over gRPC's 4 MB default, so GrpcChannels raises the
     * limit. Here it stands in for a coverage map: the result is drawn on the map.
     */
    private void renderHeatmap() {
        // a 10 km square around the map centre
        GeoPoint centre = MapView.getMapView().getCenterPoint().get();
        double dLat = 5000 / 111_320.0;
        double dLon = dLat / Math.cos(Math.toRadians(centre.getLatitude()));
        RenderHeatmapRequest request = RenderHeatmapRequest.newBuilder()
                .setWidth(1024).setHeight(1024)
                .setSeed(System.currentTimeMillis())
                .setSimulateMs(50)
                .setBounds(com.example.calc.proto.GeoBounds.newBuilder()
                        .setNorth(centre.getLatitude() + dLat).setSouth(centre.getLatitude() - dLat)
                        .setEast(centre.getLongitude() + dLon).setWest(centre.getLongitude() - dLon))
                .build();

        startLong("render heatmap...");
        calc().withWaitForReady().withDeadlineAfter(5, TimeUnit.MINUTES)
                .renderHeatmap(request, new LongCall<RenderHeatmapRequest, RenderHeatmapUpdate>("render heatmap") {
                    @Override
                    public void onNext(RenderHeatmapUpdate update) {
                        if (update.hasProgress()) {
                            showProgress(update.getProgress());
                        } else if (update.hasResult()) {
                            HeatmapImage r = update.getResult();
                            byte[] file = r.getData().toByteArray();
                            showCoverage(file, r.getBounds());
                            output.setText(String.format(Locale.US, "render heatmap -> %dx%d %s, %d KB, on the map",
                                    r.getWidth(), r.getHeight(), r.getMimeType(), file.length / 1024));
                        }
                    }
                });
    }

    // ---- the coverage map on the map (coverage/) ----------------------------------------------

    /** Puts the image on the map, replacing the previous one. */
    private void showCoverage(byte[] png, com.example.calc.proto.GeoBounds b) {
        if (coverage == null) {
            GLLayerFactory.register(GLCoverageLayer.SPI);   // tells ATAK how to draw a CoverageLayer
            coverage = new CoverageLayer("Coverage");
            SeekBar opacity = coverageControls.findViewById(R.id.coverage_opacity);
            coverage.setOpacity(opacity.getProgress() / 100f);
            MapView.getMapView().addLayer(MapView.RenderStack.MAP_SURFACE_OVERLAYS, coverage);
        }
        // Decoding ~1 MP is quick enough for a demo; decode bigger images off the UI thread.
        coverage.setImage(png, new GeoBounds(b.getNorth(), b.getWest(), b.getSouth(), b.getEast()));
        coverageControls.setVisibility(View.VISIBLE);
    }

    /** Sends it as a data package: a KMZ that any ATAK shows as an image overlay. */
    private void shareCoverage() {
        if (coverage == null)
            return;
        try {
            CoverageShare.send(MapView.getMapView(), coverage);
        } catch (IOException e) {
            output.setText("share failed: " + e.getMessage());
        }
    }

    private void removeCoverage() {
        if (coverage == null)
            return;
        MapView.getMapView().removeLayer(MapView.RenderStack.MAP_SURFACE_OVERLAYS, coverage);
        GLLayerFactory.unregister(GLCoverageLayer.SPI);
        coverage = null;
        if (coverageControls != null)
            coverageControls.setVisibility(View.GONE);
    }

    /**
     * The observer for the long calls. ClientResponseObserver adds beforeStart(), which hands us
     * the call so Cancel can stop it; cancelling reaches the service, which stops working.
     */
    private abstract class LongCall<Req, Resp> implements ClientResponseObserver<Req, Resp> {
        private final String label;
        private ClientCallStreamObserver<Req> call;

        LongCall(String label) {
            this.label = label;
        }

        @Override
        public void beforeStart(ClientCallStreamObserver<Req> call) {
            this.call = call;
            running = call;
        }

        @Override
        public void onError(Throwable t) {
            if (finished())
                showError(label, t);   // CANCELLED if the user pressed Cancel
        }

        @Override
        public void onCompleted() {
            finished();
        }

        /** False if a newer calculation has replaced this one: then leave the pane alone. */
        private boolean finished() {
            if (running != call)
                return false;
            running = null;
            progressBar.setVisibility(View.GONE);
            return true;
        }
    }

    private void startLong(String label) {
        cancelRunning();   // one long calculation at a time in this demo
        output.setText(label);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.VISIBLE);
    }

    private void showProgress(Progress p) {
        progressBar.setProgress((int) Math.round(p.getFraction() * 100));
        output.setText(String.format(Locale.US, "%.0f%%  %s", p.getFraction() * 100, p.getMessage()));
    }

    /** Cancels the call; its observer then gets onError(CANCELLED). */
    private void cancelRunning() {
        if (running != null)
            running.cancel("cancelled by user", null);
    }

    private static LatLon latLon(GeoPoint p) {
        return LatLon.newBuilder().setLat(p.getLatitude()).setLon(p.getLongitude()).build();
    }

    /** gRPC errors carry a Status: a code (NOT_FOUND, UNAVAILABLE, DEADLINE_EXCEEDED, ...) and a
     *  description. */
    private void showError(String label, Throwable t) {
        Status status = Status.fromThrowable(t);
        output.setText(label + " failed: " + status.getCode()
                + (status.getDescription() == null ? "" : " - " + status.getDescription()));
    }
}
