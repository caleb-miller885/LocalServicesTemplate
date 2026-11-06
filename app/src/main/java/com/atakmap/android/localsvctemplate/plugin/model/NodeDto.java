package com.atakmap.android.localsvctemplate.plugin.model;

import com.atakmap.android.maps.Marker;

/** A node: its marker on the map plus what the plugin knows about it. */
public class NodeDto {

    public final Marker marker;
    public String profile;   // the equipment profile name, e.g. "Relay kit A"
    public String status;    // e.g. ONLINE / OFFLINE / SILENT

    public NodeDto(Marker marker, String profile, String status) {
        this.marker = marker;
        this.profile = profile;
        this.status = status;
    }

    public String uid() {
        return marker.getUID();
    }

    public String callsign() {
        return marker.getTitle();
    }
}
