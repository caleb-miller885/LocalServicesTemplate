package com.atakmap.android.localsvctemplate.plugin.coverage;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.drawing.DrawingToolsMapComponent;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.layers.RegionShapeTool;
import com.atakmap.android.localsvctemplate.plugin.model.NodeDto;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Polyline;
import com.atakmap.android.maps.Shape;
import com.atakmap.android.toolbar.ToolManagerBroadcastReceiver;
import com.atakmap.android.util.ATAKUtilities;
import com.atakmap.android.util.AfterTextChangedWatcher;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The coverage dialog: frequency, range and the area to cover, for a list of nodes.
 * <p>
 * The area starts as the box around the nodes, widened by the range (one node: the range is the
 * radius around it). It is drawn on the map as a dashed outline behind the dialog and follows
 * the range as it is typed. "Change area" opens ATAK's own Select Area tool (the one map
 * downloads use: rectangle, free-form, lasso, or tap an existing shape such as an AO), then the
 * dialog comes back with that area. Generate sends the request.
 */
public class CoverageDialog {

    private static final String TAG = "CoverageDialog";
    private static final String ACTION_AREA_PICKED = "com.atakmap.android.localsvctemplate.COVERAGE_AREA";

    private static final double DEFAULT_FREQ_MHZ = 433;
    private static final double DEFAULT_RANGE_M = 5000;
    private static final double MARGIN = 1.1;   // 10% beyond the range so edges aren't cut off

    private final MapView mapView;
    private final List<NodeDto> nodes;
    private double freqMhz = DEFAULT_FREQ_MHZ;
    private double rangeM = DEFAULT_RANGE_M;
    private GeoBounds customArea;   // null: fit to the nodes
    private Polyline preview;

    public static void show(MapView mapView, List<NodeDto> nodes) {
        if (nodes.isEmpty()) {
            Toast.makeText(mapView.getContext(), "Select at least one node", Toast.LENGTH_SHORT).show();
            return;
        }
        new CoverageDialog(mapView, nodes).open();
    }

    private CoverageDialog(MapView mapView, List<NodeDto> nodes) {
        this.mapView = mapView;
        this.nodes = nodes;
    }

    private void open() {
        Context ctx = mapView.getContext();
        EditText freq = number(ctx, freqMhz);
        EditText range = number(ctx, rangeM);
        TextView areaText = new TextView(ctx);

        range.addTextChangedListener(new AfterTextChangedWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                rangeM = parse(range, rangeM);
                updatePreview(areaText);
            }
        });
        updatePreview(areaText);
        zoomTo(area());

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * ctx.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);
        layout.addView(label(ctx, "Frequency (MHz)"));
        layout.addView(freq);
        layout.addView(label(ctx, nodes.size() == 1 ? "Range (m)" : "Range per node (m)"));
        layout.addView(range);
        layout.addView(label(ctx, "Area"));
        layout.addView(areaText);

        String title = nodes.size() == 1
                ? "Coverage: " + nodes.get(0).callsign()
                : "Coverage: " + nodes.size() + " nodes";

        new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(layout)
                .setPositiveButton("Generate", (d, w) -> {
                    freqMhz = parse(freq, freqMhz);
                    removePreview();
                    buildCoverageRequest(nodes, freqMhz, rangeM, area());
                })
                .setNeutralButton("Change area", (d, w) -> {
                    freqMhz = parse(freq, freqMhz);
                    pickArea();   // the dialog reopens when the tool ends
                })
                .setNegativeButton("Cancel", (d, w) -> removePreview())
                .setOnCancelListener(d -> removePreview())
                .show();
    }

    /**
     * Example only: in the real plugin this builds the request and sends it.
     * One node: a radius of rangeM around it. Several: a coverage map over the area.
     */
    private void buildCoverageRequest(List<NodeDto> nodes, double freqMhz, double rangeM, GeoBounds area) {
        String msg = String.format(Locale.US,
                "Coverage request: %d node(s), %.3f MHz, %.0f m, LL %.5f,%.5f UR %.5f,%.5f",
                nodes.size(), freqMhz, rangeM,
                area.getSouth(), area.getWest(), area.getNorth(), area.getEast());
        Log.d(TAG, msg);
        Toast.makeText(mapView.getContext(), msg, Toast.LENGTH_LONG).show();
    }

    // ---- the area ------------------------------------------------------------------------------

    /** The area to send: the one picked on the map, or the nodes' box widened by the range. */
    private GeoBounds area() {
        if (customArea != null)
            return customArea;
        double n = -90, s = 90, e = -180, w = 180;
        for (NodeDto node : nodes) {
            GeoPoint p = node.marker.getPoint();
            n = Math.max(n, p.getLatitude());
            s = Math.min(s, p.getLatitude());
            e = Math.max(e, p.getLongitude());
            w = Math.min(w, p.getLongitude());
        }
        return widen(new GeoBounds(n, w, s, e), rangeM * MARGIN);
    }

    /** Widen a box by a distance on every side. */
    private static GeoBounds widen(GeoBounds b, double metres) {
        double dLat = metres / 111_320.0;
        double maxLat = Math.min(89, Math.max(Math.abs(b.getNorth()), Math.abs(b.getSouth())));
        double dLon = dLat / Math.cos(Math.toRadians(maxLat));
        return new GeoBounds(
                Math.min(90, b.getNorth() + dLat), b.getWest() - dLon,
                Math.max(-90, b.getSouth() - dLat), b.getEast() + dLon);
    }

    /** Start ATAK's Select Area tool; it broadcasts ACTION_AREA_PICKED when it ends. */
    private void pickArea() {
        AtakBroadcast.getInstance().registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                AtakBroadcast.getInstance().unregisterReceiver(this);
                onAreaPicked(intent);
            }
        }, new AtakBroadcast.DocumentedIntentFilter(ACTION_AREA_PICKED));

        Bundle extras = new Bundle();
        extras.putParcelable("callback", new Intent(ACTION_AREA_PICKED));
        ToolManagerBroadcastReceiver.getInstance().startTool(RegionShapeTool.TOOL_ID, extras);
    }

    private void onAreaPicked(Intent intent) {
        String uid = intent.getStringExtra("uid");   // none if the user backed out
        MapItem item = uid == null ? null : mapView.getRootGroup().deepFindUID(uid);
        if (item instanceof Shape) {
            Shape shape = (Shape) item;
            GeoBounds b = shape.getBounds(null);
            // an open line or route comes with the distance to widen it by
            double expand = intent.getDoubleExtra("expandDistance", 0);
            customArea = expand > 0 ? widen(b, expand) : b;
            // shapes the tool drew are throwaway; a shape the user tapped (e.g. an AO) stays
            if (shape.getMetaBoolean("layerDownload", false))
                shape.removeFromGroup();
        }
        open();
    }

    // ---- the outline on the map ----------------------------------------------------------------

    private void updatePreview(TextView areaText) {
        GeoBounds b = area();
        GeoPoint[] corners = {
                new GeoPoint(b.getNorth(), b.getWest()), new GeoPoint(b.getNorth(), b.getEast()),
                new GeoPoint(b.getSouth(), b.getEast()), new GeoPoint(b.getSouth(), b.getWest()),
        };
        if (preview == null) {
            preview = new Polyline(UUID.randomUUID().toString());
            preview.setStyle(Polyline.STYLE_CLOSED_MASK | Shape.STYLE_STROKE_MASK);
            preview.setStrokeColor(Color.YELLOW);
            preview.setStrokeWeight(3);
            preview.setBasicLineStyle(Shape.BASIC_LINE_STYLE_DASHED);
            preview.setClickable(false);
            preview.setMetaBoolean("addToObjList", false);   // not in Overlay Manager
            preview.setMetaBoolean("nevercot", true);        // never sent to others
            DrawingToolsMapComponent.getGroup().addItem(preview);
        }
        preview.setPoints(corners);

        double widthKm = corners[0].distanceTo(corners[1]) / 1000;
        double heightKm = corners[1].distanceTo(corners[2]) / 1000;
        areaText.setText(String.format(Locale.US, "%s, %.1f x %.1f km",
                customArea == null ? "Fit to nodes" : "Picked on map", widthKm, heightKm));
    }

    private void removePreview() {
        if (preview != null) {
            preview.removeFromGroup();
            preview = null;
        }
    }

    private void zoomTo(GeoBounds b) {
        GeoPoint[] pts = {
                new GeoPoint(b.getNorth(), b.getWest()), new GeoPoint(b.getSouth(), b.getEast()) };
        ATAKUtilities.scaleToFit(mapView, pts, mapView.getWidth(), mapView.getHeight());
    }

    // ---- small view helpers --------------------------------------------------------------------

    private static EditText number(Context ctx, double value) {
        EditText et = new EditText(ctx);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setText(String.format(Locale.US, "%s", value % 1 == 0 ? String.valueOf((long) value) : value));
        return et;
    }

    private static TextView label(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        return tv;
    }

    private static double parse(EditText et, double fallback) {
        try {
            double v = Double.parseDouble(et.getText().toString());
            return v > 0 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
