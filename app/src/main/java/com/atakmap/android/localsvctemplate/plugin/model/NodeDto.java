package com.atakmap.android.localsvctemplate.plugin.model;

import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.Marker;

/**
 * A node: its marker on the map plus what the plugin knows about it.
 *
 * A marker is a node when it carries {@link #KEY_IS_NODE} (the string "true", like the zone
 * flags). Whatever puts nodes on the map (the
 * node feed) sets that flag, plus {@link #KEY_PROFILE} and {@link #KEY_STATUS}, before adding the
 * marker to a map group; the plugin's map listener then picks it up.
 */
public class NodeDto {

    public static final String KEY_IS_NODE = "localsvctemplate.node";
    public static final String KEY_PROFILE = "localsvctemplate.node.profile";   // e.g. "Relay kit A"
    public static final String KEY_STATUS = "localsvctemplate.node.status";     // ONLINE / OFFLINE / SILENT

    public final Marker marker;
    public String profile;
    public String status;
    /** Ticked in the node list: every area drawn while it's ticked gets this node. */
    public boolean selected;

    public NodeDto(Marker marker) {
        this.marker = marker;
        update();
    }

    /** Re-reads profile and status from the marker (after the feed changed them). */
    public void update() {
        profile = marker.getMetaString(KEY_PROFILE, "");
        status = marker.getMetaString(KEY_STATUS, "");
    }

    public static boolean isNode(MapItem item) {
        return item instanceof Marker && item.hasMetaValue(KEY_IS_NODE);
    }

    public String uid() {
        return marker.getUID();
    }

    public String callsign() {
        return marker.getTitle();
    }
}
