package com.atakmap.android.localsvctemplate.plugin.zones;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.atakmap.android.drawing.mapItems.DrawingShape;
import com.atakmap.android.drawing.tools.ShapeCreationTool;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.toolbar.ToolManagerBroadcastReceiver;
import com.atakmap.coremap.log.Log;

/**
 * Draws exclusion (no-go) zones. Starts ATAK's own polygon tool; the callback tags the finished shape
 * (KEY_IS_EXCLUSION, colour, title) and refreshes it, which tells anything listening for
 * MapEvent.ITEM_REFRESH (e.g. the pane's Zones list) to update.
 */
public class ExclusionZoneTool extends BroadcastReceiver {

    private static final String TAG = "ExclusionZoneTool";
    public static final String ACTION_SHAPE_CREATED = "com.atakmap.android.localsvctemplate.EXCLUSION_CREATED";

    /** Meta key so the plugin knows the shape is an exclusion zone. */
    public static final String KEY_IS_EXCLUSION = "localsvctemplate.isExclusion";

    private static final int COLOUR = 0xFFE53935;

    private final MapView mapView;

    public ExclusionZoneTool(MapView mapView) {
        this.mapView = mapView;
    }

    public void register() {
        AtakBroadcast.getInstance().registerReceiver(this,
                new AtakBroadcast.DocumentedIntentFilter(ACTION_SHAPE_CREATED));
    }

    public void unregister() {
        AtakBroadcast.getInstance().unregisterReceiver(this);
    }

    public void start() {
        Intent intent = new Intent(ToolManagerBroadcastReceiver.BEGIN_TOOL);
        intent.putExtra("tool", ShapeCreationTool.TOOL_IDENTIFIER);
        intent.putExtra("callback", new Intent(ACTION_SHAPE_CREATED));
        AtakBroadcast.getInstance().sendBroadcast(intent);
    }

    /** The tool's callback: fires however the tool ends; no "uid" if nothing was kept. */
    @Override
    public void onReceive(Context context, Intent intent) {
        String uid = intent.getStringExtra("uid");
        MapItem item = uid == null ? null : mapView.getRootGroup().deepFindUID(uid);
        if (!(item instanceof DrawingShape)) {
            Log.w(TAG, "no shape for uid " + uid);
            return;
        }
        DrawingShape shape = (DrawingShape) item;

        // Done (rather than tapping the first point again) leaves an open line
        if (shape.getPoints().length >= 3)
            shape.setClosed(true);
        int n = mapView.getRootGroup().deepFindItems(KEY_IS_EXCLUSION, "true").size() + 1;
        shape.setMetaString(KEY_IS_EXCLUSION, "true");
        shape.setTitle("Exclusion zone " + n);
        shape.setStrokeColor(COLOUR);
        shape.setStrokeWeight(4);
        shape.setFillColor(COLOUR);
        shape.setFillAlpha(64);

        shape.persist(mapView.getMapEventDispatcher(), null, getClass());   // save the new look
        shape.refresh(mapView.getMapEventDispatcher(), null, getClass());   // tell the UI
        Log.d(TAG, "an exclusion zone drawn: " + uid);
    }
}
