package com.atakmap.android.localsvctemplate.plugin.model;

import com.atakmap.android.maps.Shape;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every node and zone the plugin knows, by map item UID. The landing pane and the details views
 * both read from here; every change notifies the listeners so they redraw.
 *
 * Assignment is explicit: a new zone starts with the nodes inside it, then only
 * {@link #assign} changes it (the landing pane's tick boxes).
 *
 * Call on the UI thread.
 */
public class Registry {

    public interface Listener {
        void onRegistryChanged();
    }

    private final Map<String, NodeDto> nodes = new LinkedHashMap<>();
    private final Map<String, ZoneDto> zones = new LinkedHashMap<>();
    private final List<Listener> listeners = new ArrayList<>();

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public Collection<NodeDto> nodes() {
        return nodes.values();
    }

    public Collection<ZoneDto> zones() {
        return zones.values();
    }

    public NodeDto node(String uid) {
        return nodes.get(uid);
    }

    public ZoneDto zone(String uid) {
        return zones.get(uid);
    }

    public void putNode(NodeDto node) {
        nodes.put(node.uid(), node);
        changed();
    }

    /**
     * Adds a zone, or - if the shape is already known (it was edited, so ATAK refreshed it) -
     * just tells the listeners, keeping its assignment.
     */
    public void putZone(Shape shape, ZoneDto.Kind kind) {
        if (!zones.containsKey(shape.getUID())) {
            ZoneDto zone = new ZoneDto(shape, kind);
            for (NodeDto n : nodes.values())
                if (inside(n.marker.getPoint(), shape))
                    zone.nodes.add(n);
            zones.put(zone.uid(), zone);
        }
        changed();
    }

    /** Assigns a node to a zone, or takes it off. */
    public void assign(ZoneDto zone, NodeDto node, boolean assigned) {
        if (assigned == zone.nodes.contains(node))
            return;
        if (assigned)
            zone.nodes.add(node);
        else
            zone.nodes.remove(node);
        changed();
    }

    /** Removes a node or zone by UID; does nothing if it isn't one of ours. */
    public void remove(String uid) {
        NodeDto node = nodes.remove(uid);
        if (node != null)
            for (ZoneDto z : zones.values())
                z.nodes.remove(node);
        if (node != null || zones.remove(uid) != null)
            changed();
    }

    public void clear() {
        nodes.clear();
        zones.clear();
        changed();
    }

    private void changed() {
        for (Listener l : new ArrayList<>(listeners))
            l.onRegistryChanged();
    }

    /** Ray casting, flat-earth: fine for zones a few km across. */
    private static boolean inside(GeoPoint p, Shape shape) {
        GeoPoint[] poly = shape.getPoints();
        boolean in = false;
        double x = p.getLongitude(), y = p.getLatitude();
        for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
            double xi = poly[i].getLongitude(), yi = poly[i].getLatitude();
            double xj = poly[j].getLongitude(), yj = poly[j].getLatitude();
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi)
                in = !in;
        }
        return in;
    }
}
