package com.atakmap.android.localsvctemplate.plugin.coverage;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PointF;
import android.widget.Toast;

import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Polyline;
import com.atakmap.android.maps.Shape;
import com.atakmap.android.menu.MapMenuReceiver;
import com.atakmap.android.menu.PluginMenuParser;
import com.atakmap.android.widgets.SeekBarControl;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.layer.opengl.GLLayerFactory;

import java.io.IOException;
import java.util.UUID;

/**
 * The one coverage map on the map, with its dBm scale. Showing a new one replaces the old.
 *
 * <pre>
 * CoverageMap coverage = new CoverageMap(mapView, pluginContext);   // plugin start
 * coverage.show(png, lowerLeft, upperRight);              // a box
 * coverage.show(png, centre, radiusMetres);               // or a point + radius
 * coverage.setScale(-50, -100);                           // optional; this is the default
 * coverage.dispose();                                     // plugin stop
 * </pre>
 *
 * Done the way ATAK does its own image overlays (GRGs): the image is a map layer, and a thin
 * outline map item around it carries the radial menu - Remove, Transparency (ATAK's floating
 * slider, the one GRGs use) and Share (ATAK's send dialog). The radial opens by tapping the
 * outline, or a coloured part of the image where there's no map item. Markers and shapes on top
 * still get their own taps: the image isn't a map item, and the outline is only hit on its edge.
 */
public final class CoverageMap implements MapEventDispatcher.MapEventDispatchListener {

    private static final String ACTION_PREFIX = "com.atakmap.android.localsvctemplate.coverage.";
    private static final String ACTION_REMOVE = ACTION_PREFIX + "REMOVE";
    private static final String ACTION_TRANSPARENCY = ACTION_PREFIX + "TRANSPARENCY";
    private static final String ACTION_SHARE = ACTION_PREFIX + "SHARE";

    private final MapView mapView;
    private final String menu;
    private final CoverageLegend legend;
    private final MapGroup group;
    private CoverageLayer layer;
    private Polyline outline;
    private float opacity = 0.7f;   // kept across replacements

    private final BroadcastReceiver actions = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (outline == null || !outline.getUID().equals(intent.getStringExtra("uid")))
                return;
            String action = intent.getAction();
            if (ACTION_REMOVE.equals(action))
                remove();
            else if (ACTION_TRANSPARENCY.equals(action))
                showTransparency();
            else if (ACTION_SHARE.equals(action))
                share();
        }
    };

    public CoverageMap(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.menu = PluginMenuParser.getMenu(pluginContext, "menus/coverage_menu.xml");
        GLLayerFactory.register(GLCoverageLayer.SPI);   // tells ATAK how to draw a CoverageLayer
        legend = new CoverageLegend(mapView);
        setScale(-50, -100);

        group = mapView.getRootGroup().addGroup("Coverage");
        group.setMetaBoolean("addToObjList", false);   // not in Overlay Manager

        AtakBroadcast.DocumentedIntentFilter filter = new AtakBroadcast.DocumentedIntentFilter();
        filter.addAction(ACTION_REMOVE);
        filter.addAction(ACTION_TRANSPARENCY);
        filter.addAction(ACTION_SHARE);
        AtakBroadcast.getInstance().registerReceiver(actions, filter);
        mapView.getMapEventDispatcher().addMapEventListener(MapEvent.MAP_CLICK, this);
    }

    /** Shows a PNG covering the box lowerLeft..upperRight, replacing any coverage shown. */
    public void show(byte[] png, GeoPoint lowerLeft, GeoPoint upperRight) {
        show(png, new GeoBounds(upperRight.getLatitude(), lowerLeft.getLongitude(),
                lowerLeft.getLatitude(), upperRight.getLongitude()));
    }

    /** Shows a PNG covering the square radiusM either side of centre (N, S, E and W). */
    public void show(byte[] png, GeoPoint centre, double radiusM) {
        double dLat = radiusM / 111_320.0;
        double dLon = dLat / Math.cos(Math.toRadians(centre.getLatitude()));
        show(png, new GeoBounds(centre.getLatitude() + dLat, centre.getLongitude() - dLon,
                centre.getLatitude() - dLat, centre.getLongitude() + dLon));
    }

    /** Shows a PNG covering the bounds, replacing any coverage shown. UI thread. */
    public void show(byte[] png, GeoBounds b) {
        if (layer == null) {
            layer = new CoverageLayer("Coverage");
            layer.setOpacity(opacity);
            mapView.addLayer(MapView.RenderStack.MAP_SURFACE_OVERLAYS, layer);
        }
        layer.setImage(png, b);   // same layer, new image: the old one is gone
        showOutline(b);
        legend.setVisible(true);
    }

    /** The scale shown in the corner, with the default colours (red strongest .. blue weakest). */
    public void setScale(double strongDbm, double weakDbm) {
        setScale(strongDbm, weakDbm, CoverageLegend.DEFAULT_COLOURS);
    }

    /** @param colours strongest to weakest; must match how the PNG was coloured */
    public void setScale(double strongDbm, double weakDbm, int[] colours) {
        legend.setScale(strongDbm, weakDbm, colours);
    }

    public boolean isShowing() {
        return layer != null;
    }

    public void remove() {
        if (layer == null)
            return;
        SeekBarControl.dismiss();
        mapView.removeLayer(MapView.RenderStack.MAP_SURFACE_OVERLAYS, layer);
        layer = null;
        if (outline != null) {
            outline.removeFromGroup();
            outline = null;
        }
        legend.setVisible(false);
    }

    /** ATAK's send dialog (contacts, TAK Server, ...) with the coverage as a KMZ data package. */
    public void share() {
        if (layer == null)
            return;
        try {
            CoverageShare.send(mapView, layer);
        } catch (IOException e) {
            Toast.makeText(mapView.getContext(), "Share failed: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    public void dispose() {
        mapView.getMapEventDispatcher().removeMapEventListener(MapEvent.MAP_CLICK, this);
        AtakBroadcast.getInstance().unregisterReceiver(actions);
        remove();
        mapView.getRootGroup().removeGroup(group);
        legend.dispose();
        GLLayerFactory.unregister(GLCoverageLayer.SPI);
    }

    // ---- the outline that carries the radial -----------------------------------------------------

    private void showOutline(GeoBounds b) {
        if (outline == null) {
            outline = new Polyline(UUID.randomUUID().toString());
            outline.setTitle("Coverage map");
            outline.setStyle(Polyline.STYLE_CLOSED_MASK | Shape.STYLE_STROKE_MASK);
            outline.setStrokeColor(0x99FFFFFF);
            outline.setStrokeWeight(2);
            outline.setClickable(true);
            outline.setMetaString("menu", menu);
            outline.setMetaBoolean("nevercot", true);       // never sent as CoT
            outline.setMetaBoolean("addToObjList", false);  // not in Overlay Manager
            outline.setMetaBoolean("editable", false);
            group.addItem(outline);
        }
        outline.setPoints(new GeoPoint[] {
                new GeoPoint(b.getNorth(), b.getWest()), new GeoPoint(b.getNorth(), b.getEast()),
                new GeoPoint(b.getSouth(), b.getEast()), new GeoPoint(b.getSouth(), b.getWest()),
        });
    }

    // ---- tapping the coverage ------------------------------------------------------------------

    /**
     * MAP_CLICK: a tap that hit no map item. On a coloured pixel, open the outline's radial at the
     * tap. ATAK suspends this listener while a radial menu or a drawing tool is open.
     */
    @Override
    public void onMapEvent(MapEvent event) {
        if (layer == null || outline == null || layer.getOpacity() <= 0f)
            return;
        PointF p = event.getPointF();
        GeoPoint g = mapView.inverse(p.x, p.y).get();
        if (g == null || !g.isValid() || !hasCoverageAt(g))
            return;
        outline.setClickPoint(g);   // the radial opens where the item was "touched"
        AtakBroadcast.getInstance().sendBroadcast(new Intent(MapMenuReceiver.SHOW_MENU)
                .putExtra("uid", outline.getUID()));
    }

    /** True if the tap is on a coloured (non-transparent) pixel of the image. */
    private boolean hasCoverageAt(GeoPoint g) {
        GeoBounds b = layer.getBounds();
        Bitmap img = layer.getImage();
        if (b == null || img == null || !b.contains(g))
            return false;
        int x = (int) ((g.getLongitude() - b.getWest()) / (b.getEast() - b.getWest()) * img.getWidth());
        int y = (int) ((b.getNorth() - g.getLatitude()) / (b.getNorth() - b.getSouth()) * img.getHeight());
        x = Math.max(0, Math.min(img.getWidth() - 1, x));
        y = Math.max(0, Math.min(img.getHeight() - 1, y));
        return Color.alpha(img.getPixel(x, y)) > 0;
    }

    /** ATAK's own floating slider, the one GRGs use; it hides itself after 5 s untouched. */
    private void showTransparency() {
        SeekBarControl.show(new SeekBarControl.Subject() {
            @Override
            public int getValue() {
                return Math.round(opacity * 100);
            }

            @Override
            public void setValue(int value) {
                opacity = value / 100f;
                if (layer != null)
                    layer.setOpacity(opacity);
            }

            @Override
            public void onControlDismissed() {
            }
        }, 5000L);
    }
}
