package com.atakmap.android.localsvctemplate.plugin.model;

import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.Marker;

/**
 * A waypoint of the optimised route: a marker carrying {@link #KEY_IS_WAYPOINT} and its place in
 * the visiting order ({@link #KEY_ORDER}, 0 = start).
 */
public class WaypointDto {

    public static final String KEY_IS_WAYPOINT = "localsvctemplate.waypoint";
    public static final String KEY_ORDER = "localsvctemplate.waypoint.order";

    public final Marker marker;

    public WaypointDto(Marker marker) {
        this.marker = marker;
    }

    public static boolean isWaypoint(MapItem item) {
        return item instanceof Marker && item.hasMetaValue(KEY_IS_WAYPOINT);
    }

    public String uid() {
        return marker.getUID();
    }

    public int order() {
        return marker.getMetaInteger(KEY_ORDER, Integer.MAX_VALUE);
    }
}
