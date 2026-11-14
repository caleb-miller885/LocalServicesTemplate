package com.atakmap.android.localsvctemplate.plugin.model;

import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.maps.Shape;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every node, waypoint and zone the plugin knows, by map item UID. It's fed by one map listener
 * (ITEM_ADDED / ITEM_REFRESH / ITEM_REMOVED → {@link #onItem} / {@link #remove}); the landing pane
 * and the details views read from here, and every change notifies the listeners so they redraw.
 *
 * Assignment: a new zone starts with the nodes ticked in the node list; after that only
 * {@link #assign} changes it (the zone's tick boxes). A node that appears later starts unassigned
 * in every zone; a node that goes is taken off every zone.
 *
 * Call on the UI thread.
 */
public class Registry {

    public interface Listener {
        void onRegistryChanged();
    }

    private final Map<String, NodeDto> nodes = new LinkedHashMap<>();
    private final Map<String, WaypointDto> waypoints = new LinkedHashMap<>();
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

    /** In visiting order. */
    public List<WaypointDto> waypoints() {
        List<WaypointDto> l = new ArrayList<>(waypoints.values());
        l.sort(Comparator.comparingInt(WaypointDto::order));
        return l;
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

    /** True if the UID is a node, waypoint or zone here. */
    public boolean contains(String uid) {
        return nodes.containsKey(uid) || waypoints.containsKey(uid) || zones.containsKey(uid);
    }

    /**
     * A map item was added or refreshed: files it as a node, waypoint or zone by its flags, or
     * ignores it. A refresh of a known item keeps its state (a node's tick, a zone's nodes).
     */
    public void onItem(MapItem item) {
        String uid = item.getUID();
        if (NodeDto.isNode(item)) {
            NodeDto known = nodes.get(uid);
            if (known != null)
                known.update();
            else
                nodes.put(uid, new NodeDto((Marker) item));
        } else if (WaypointDto.isWaypoint(item)) {
            if (!waypoints.containsKey(uid))
                waypoints.put(uid, new WaypointDto((Marker) item));
        } else if (ZoneDto.kindOf(item) != null) {
            if (!zones.containsKey(uid)) {
                ZoneDto zone = new ZoneDto((Shape) item, ZoneDto.kindOf(item));
                for (NodeDto n : nodes.values())
                    if (n.selected)
                        zone.nodes.add(n);
                zones.put(uid, zone);
            }
        } else {
            return;   // not ours
        }
        changed();
    }

    /** Ticks or unticks a node in the node list. */
    public void select(NodeDto node, boolean selected) {
        if (node.selected == selected)
            return;
        node.selected = selected;
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

    /** Removes a node, waypoint or zone by UID; does nothing if it isn't one of ours. */
    public void remove(String uid) {
        NodeDto node = nodes.remove(uid);
        if (node != null)
            for (ZoneDto z : zones.values())
                z.nodes.remove(node);
        if (node != null | waypoints.remove(uid) != null | zones.remove(uid) != null)
            changed();
    }

    public void clear() {
        nodes.clear();
        waypoints.clear();
        zones.clear();
        changed();
    }

    private void changed() {
        for (Listener l : new ArrayList<>(listeners))
            l.onRegistryChanged();
    }
}
