package com.atakmap.android.localsvctemplate.plugin.zones;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.atakmap.android.drawing.mapItems.DrawingShape;
import com.atakmap.android.drawing.tools.ShapeCreationTool;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Shape;
import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.android.toolbar.ToolManagerBroadcastReceiver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The two zone tools: exclusion (no-go) zones and areas of operation. Each is an ordinary ATAK
 * drawing polygon. Drawing one just launches ATAK's standard shape tool, with a callback intent
 * per zone type, and tags whatever comes back: colour, title and a meta tag so the plugin knows
 * it. ATAK handles the drawing UX, the editing and the persistence, so all this holds is the
 * shapes' UIDs (in preferences, because the meta tag isn't saved with the shape).
 *
 * Same pattern as pigeonHunter's DetectionZoneService. No subclass of ShapeCreationTool: its
 * constructor needs ATAK's own DrawingToolsToolbar, so a subclass can't run outside it.
 */
public class ZoneTools extends BroadcastReceiver {

    private static final String TAG = "ZoneTools";

    public enum Type {
        EXCLUSION("Exclusion zone", 0xFFE53935, "com.atakmap.android.localsvctemplate.EXCLUSION_DRAWN"),
        OPS("Area of operations", 0xFF43A047, "com.atakmap.android.localsvctemplate.OPS_DRAWN");

        public final String title;
        public final int colour;
        final String callbackAction;

        Type(String title, int colour, String callbackAction) {
            this.title = title;
            this.colour = colour;
            this.callbackAction = callbackAction;
        }
    }

    /** On the shape, so other code can tell a zone from any other drawing: the Type name. */
    public static final String META_ZONE_TYPE = "localsvctemplate.zoneType";

    /** "uid|TYPE" for every zone drawn with these tools. */
    private static final String PREF_ZONES = "localsvctemplate.zones";

    private final MapView mv;
    private final SharedPreferences prefs;
    private Type drawing;   // the zone being drawn, null when the shape tool isn't running

    /** Called when a zone is drawn (or a stale one is dropped), so lists can refresh. */
    public Runnable onZonesChanged;

    /** Called when drawing starts or ends, so the toolbar can show Undo/Done. */
    public Runnable onDrawingChanged;

    public ZoneTools(Context pluginContext, MapView mv) {
        this.mv = mv;
        this.prefs = AtakPreferences.getInstance(pluginContext).getSharedPrefs();
    }

    public void register() {
        AtakBroadcast.DocumentedIntentFilter filter = new AtakBroadcast.DocumentedIntentFilter();
        for (Type t : Type.values())
            filter.addAction(t.callbackAction);
        AtakBroadcast.getInstance().registerReceiver(this, filter);
    }

    public void unregister() {
        AtakBroadcast.getInstance().unregisterReceiver(this);
    }

    /** Launches ATAK's standard polygon tool; onReceive picks the finished shape back up. */
    public void startDrawing(Type type) {
        drawing = type;
        Intent begin = new Intent(ToolManagerBroadcastReceiver.BEGIN_TOOL);
        begin.putExtra("tool", ShapeCreationTool.TOOL_IDENTIFIER);
        begin.putExtra("callback", new Intent(type.callbackAction));
        AtakBroadcast.getInstance().sendBroadcast(begin);
        if (onDrawingChanged != null) onDrawingChanged.run();
    }

    /** The zone being drawn, or null. */
    public Type drawing() {
        return drawing;
    }

    /**
     * The shape tool's callback. It fires however the tool ends (Done, closing the shape, Back,
     * another toolbar opening); "uid" is missing if there was nothing worth keeping.
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        Type type = null;
        for (Type t : Type.values())
            if (t.callbackAction.equals(intent.getAction()))
                type = t;
        drawing = null;
        if (onDrawingChanged != null) onDrawingChanged.run();

        String uid = intent.getStringExtra("uid");
        MapItem item = uid == null ? null : mv.getRootGroup().deepFindUID(uid);
        if (type == null || !(item instanceof Shape)) return;

        Shape shape = (Shape) item;
        // Done (rather than tapping the first point again) leaves an open line; a zone is an area
        if (shape instanceof DrawingShape && shape.getPoints().length >= 3)
            ((DrawingShape) shape).setClosed(true);
        tag(shape, type);
        shape.setTitle(type.title + " " + (count(type) + 1));
        // the tool has already saved the shape; save again so the colours stick
        shape.persist(mv.getMapEventDispatcher(), null, getClass());

        Set<String> saved = new HashSet<>(prefs.getStringSet(PREF_ZONES, new HashSet<>()));
        saved.add(uid + "|" + type.name());
        prefs.edit().putStringSet(PREF_ZONES, saved).apply();

        Log.d(TAG, type + " drawn uid=" + uid + " points=" + shape.getPoints().length);
        if (onZonesChanged != null) onZonesChanged.run();
    }

    private static void tag(Shape shape, Type type) {
        shape.setMetaString(META_ZONE_TYPE, type.name());
        shape.setStrokeColor(type.colour);
        shape.setFillColor((type.colour & 0x00FFFFFF) | 0x40000000);   // same colour, 25% opaque
    }

    /** A zone on the map. */
    public static final class Zone {
        public final Type type;
        public final Shape shape;

        Zone(Type type, Shape shape) {
            this.type = type;
            this.shape = shape;
        }
    }

    /**
     * Every zone still on the map, exclusion zones first. Main thread only - it walks the map
     * group tree. Reading the live shapes each time means an edited zone is picked up with no
     * cache to keep in sync; zones deleted from the map are dropped from the saved list.
     */
    public List<Zone> zones() {
        Set<String> saved = prefs.getStringSet(PREF_ZONES, new HashSet<>());
        Set<String> kept = new HashSet<>();
        List<Zone> exclusion = new ArrayList<>();
        List<Zone> ops = new ArrayList<>();
        for (String entry : saved) {
            int bar = entry.lastIndexOf('|');
            MapItem item = mv.getRootGroup().deepFindUID(entry.substring(0, bar));
            if (!(item instanceof Shape)) continue;
            Type type = Type.valueOf(entry.substring(bar + 1));
            tag((Shape) item, type);   // the meta tag isn't saved with the shape: put it back
            (type == Type.EXCLUSION ? exclusion : ops).add(new Zone(type, (Shape) item));
            kept.add(entry);
        }
        if (kept.size() != saved.size())
            prefs.edit().putStringSet(PREF_ZONES, kept).apply();
        exclusion.addAll(ops);
        return exclusion;
    }

    private int count(Type type) {
        int n = 0;
        for (String entry : prefs.getStringSet(PREF_ZONES, new HashSet<>()))
            if (entry.endsWith("|" + type.name()))
                n++;
        return n;
    }
}
