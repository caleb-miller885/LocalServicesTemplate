package com.atakmap.android.localsvctemplate.plugin.landing;

import android.content.Context;
import android.graphics.Typeface;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.localsvctemplate.plugin.R;
import com.atakmap.android.localsvctemplate.plugin.model.NodeDto;
import com.atakmap.android.localsvctemplate.plugin.model.Registry;
import com.atakmap.android.localsvctemplate.plugin.model.WaypointDto;
import com.atakmap.android.localsvctemplate.plugin.model.ZoneDto;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.conversions.Area;
import com.atakmap.coremap.conversions.AreaUtilities;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.CameraController;

import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The landing page: nodes, waypoints and zones, one list showing at a time, switched by the
 * segment strip or by swiping the list left/right.
 *
 * Plain views, no adapter: every node, waypoint and zone has its own row view (landing_row.xml),
 * inflated once and kept by UID. addOrUpdateNode / addOrUpdateWaypoint / addOrUpdateZone bind an
 * item to its row; {@link #switchView} puts the rows of one list into the list container (a
 * LinearLayout in a ScrollView). {@link #refresh} runs after any registry change: it binds every
 * item and drops the rows of items that have gone.
 *
 * Tapping a row pans the map to it. Call on the UI thread.
 */
public class LandingPane {

    public enum Category { NODES, WAYPOINTS, ZONES }

    private final Context pluginContext;
    private final Registry registry;
    private final Map<Category, Map<String, View>> rows = new EnumMap<>(Category.class);   // by UID
    private final Map<Category, TextView> segments = new EnumMap<>(Category.class);
    private final LinearLayout list;
    private final SwipeScrollView scroll;
    private final TextView empty;
    private final TextView caption;
    private final View drawZones;
    private Category showing = Category.NODES;

    public LandingPane(Context pluginContext, View root, Registry registry) {
        this.pluginContext = pluginContext;
        this.registry = registry;
        for (Category c : Category.values())
            rows.put(c, new LinkedHashMap<>());

        segments.put(Category.NODES, root.findViewById(R.id.seg_nodes));
        segments.put(Category.WAYPOINTS, root.findViewById(R.id.seg_waypoints));
        segments.put(Category.ZONES, root.findViewById(R.id.seg_zones));
        for (Map.Entry<Category, TextView> e : segments.entrySet())
            e.getValue().setOnClickListener(v -> switchView(e.getKey()));

        caption = root.findViewById(R.id.list_caption);
        drawZones = root.findViewById(R.id.btn_draw_zones);
        scroll = root.findViewById(R.id.landing_scroll);
        list = root.findViewById(R.id.landing_list);
        empty = root.findViewById(R.id.landing_empty);

        // swipe left/right on the list (or on the empty message) for the next/previous list
        scroll.setOnSwipeListener(this::step);
        GestureDetector emptySwipe = new GestureDetector(pluginContext,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onFling(MotionEvent down, MotionEvent up, float vx, float vy) {
                        if (down == null || Math.abs(vx) < 2 * Math.abs(vy))
                            return false;
                        step(up.getX() < down.getX() ? 1 : -1);
                        return true;
                    }
                });
        empty.setOnTouchListener((v, e) -> emptySwipe.onTouchEvent(e));

        refresh();
        switchView(showing);
    }

    // ---- one row per item ------------------------------------------------------------------------

    /** A node's row: callsign, profile, status, and the tick that selects it for the next area. */
    public void addOrUpdateNode(NodeDto n) {
        View row = row(Category.NODES, n.uid());
        bind(row, n.callsign(), n.profile, n.status, "OFFLINE".equals(n.status), n.marker.getPoint());

        CheckBox select = row.findViewById(R.id.row_select);
        select.setVisibility(View.VISIBLE);
        select.setOnCheckedChangeListener(null);   // so setChecked doesn't fire it
        select.setChecked(n.selected);
        select.setOnCheckedChangeListener((b, on) -> registry.select(n, on));
    }

    /** A waypoint's row: name, position, and the leg and running distance to it. */
    public void addOrUpdateWaypoint(WaypointDto w, double legM, double totalM) {
        GeoPoint p = w.marker.getPoint();
        bind(row(Category.WAYPOINTS, w.uid()), w.marker.getTitle(),
                String.format(Locale.US, "%.5f, %.5f", p.getLatitude(), p.getLongitude()),
                String.format(Locale.US, "+%.1f km  (%.1f)", legM / 1000, totalM / 1000),
                false, p);
    }

    /**
     * A zone's row, with the chevron that opens its checklist: every node, ticked if it's assigned
     * to the zone. The checklist is rebuilt from the node list each time, so a new node shows up
     * unticked and a removed node is gone.
     */
    public void addOrUpdateZone(ZoneDto z) {
        View row = row(Category.ZONES, z.uid());
        boolean noGo = z.kind == ZoneDto.Kind.EXCLUSION;
        bind(row, z.shape.getTitle(),
                // the area is ATAK's own for the shape (in m²), formatted in metric units
                String.format(Locale.US, "%d of %d nodes  -  %s", z.nodes.size(),
                        registry.nodes().size(),
                        AreaUtilities.formatArea(Area.METRIC, z.shape.getArea(), Area.METER2)),
                noGo ? "NO-GO" : "AO", noGo, z.shape.getCenter().get());

        // the checklist stays open or closed with its row view; the chevron toggles it
        LinearLayout checks = row.findViewById(R.id.row_checks);
        TextView chevron = row.findViewById(R.id.row_expand);
        chevron.setVisibility(View.VISIBLE);
        chevron.setText(checks.getVisibility() == View.VISIBLE ? "▴" : "▾");
        chevron.setOnClickListener(v -> {
            boolean open = checks.getVisibility() != View.VISIBLE;
            checks.setVisibility(open ? View.VISIBLE : View.GONE);
            chevron.setText(open ? "▴" : "▾");   // up / down triangle
        });

        checks.removeAllViews();
        if (registry.nodes().isEmpty()) {
            TextView none = new TextView(pluginContext);
            none.setText(R.string.empty_nodes);
            none.setTextColor(pluginContext.getColor(R.color.text_secondary));
            none.setPadding(0, 12, 0, 12);
            checks.addView(none);
        }
        for (NodeDto n : registry.nodes()) {
            CheckBox cb = new CheckBox(pluginContext);
            cb.setText(n.callsign());
            cb.setTextColor(pluginContext.getColor(R.color.white));
            cb.setTextSize(14);
            cb.setMinHeight(Math.round(44 * pluginContext.getResources().getDisplayMetrics().density));
            cb.setChecked(z.nodes.contains(n));   // before the listener, so it doesn't fire
            // assigning rebuilds this checklist; post it so the tick box isn't removed mid-click
            cb.setOnCheckedChangeListener((b, on) -> checks.post(() -> registry.assign(z, n, on)));
            checks.addView(cb);
        }
    }

    /** The item's row view: inflated the first time, and added to the list if its list is showing. */
    private View row(Category category, String uid) {
        View row = rows.get(category).get(uid);
        if (row == null) {
            row = PluginLayoutInflater.inflate(pluginContext, R.layout.landing_row, null);
            rows.get(category).put(uid, row);
            if (category == showing)
                list.addView(row);
        }
        return row;
    }

    /** The parts every row has. */
    private void bind(View row, String title, String detail, String statusText, boolean attention,
            GeoPoint point) {
        ((TextView) row.findViewById(R.id.row_title)).setText(title);
        ((TextView) row.findViewById(R.id.row_detail)).setText(detail);
        TextView status = row.findViewById(R.id.row_status);
        status.setText(statusText);
        // items that need attention (an offline node, a no-go zone): bold white status word
        status.setTextColor(pluginContext.getColor(attention ? R.color.white : R.color.text_secondary));
        status.setTypeface(null, attention ? Typeface.BOLD : Typeface.NORMAL);
        row.setOnClickListener(v ->
                CameraController.Programmatic.panTo(MapView.getMapView().getRenderer3(), point, true));
    }

    // ---- the lists --------------------------------------------------------------------------------

    /** Brings the rows in line with the registry. Call after anything in it changes. */
    public void refresh() {
        for (NodeDto n : registry.nodes())
            addOrUpdateNode(n);
        GeoPoint previous = null;
        double total = 0;
        for (WaypointDto w : registry.waypoints()) {   // in visiting order
            GeoPoint p = w.marker.getPoint();
            double leg = previous == null ? 0 : previous.distanceTo(p);
            total += leg;
            addOrUpdateWaypoint(w, leg, total);
            previous = p;
        }
        for (ZoneDto z : registry.zones())
            addOrUpdateZone(z);

        // rows whose item has gone
        for (Category c : Category.values()) {
            Iterator<Map.Entry<String, View>> it = rows.get(c).entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, View> e = it.next();
                if (!registry.contains(e.getKey())) {
                    list.removeView(e.getValue());   // does nothing if its list isn't showing
                    it.remove();
                }
            }
        }
        updateSegments();
        updateEmpty();
    }

    /** Shows one list: its rows go into the list container in place of the other list's. */
    public void switchView(Category category) {
        showing = category;
        list.removeAllViews();
        for (View row : rows.get(category).values())
            list.addView(row);

        for (Map.Entry<Category, TextView> e : segments.entrySet())
            e.getValue().setSelected(e.getKey() == category);
        drawZones.setVisibility(category == Category.ZONES ? View.VISIBLE : View.GONE);
        switch (category) {
            case NODES:
                caption.setText(R.string.caption_nodes);
                empty.setText(R.string.empty_nodes);
                break;
            case WAYPOINTS:
                caption.setText(R.string.caption_waypoints);
                empty.setText(R.string.empty_waypoints);
                break;
            case ZONES:
                caption.setText(R.string.caption_zones);
                empty.setText(R.string.empty_zones);
                break;
        }
        updateEmpty();
    }

    private void step(int direction) {
        int next = showing.ordinal() + direction;
        if (next >= 0 && next < Category.values().length)
            switchView(Category.values()[next]);
    }

    /** The empty message takes the list's place when it has no rows. */
    private void updateEmpty() {
        boolean none = rows.get(showing).isEmpty();
        scroll.setVisibility(none ? View.GONE : View.VISIBLE);
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
    }

    /** Segment labels: the name over the count, plus how many need attention. */
    private void updateSegments() {
        int offline = 0, noGo = 0;
        for (NodeDto n : registry.nodes())
            if ("OFFLINE".equals(n.status))
                offline++;
        for (ZoneDto z : registry.zones())
            if (z.kind == ZoneDto.Kind.EXCLUSION)
                noGo++;
        setSegment(Category.NODES, R.string.seg_nodes, offline, R.string.attention_nodes);
        setSegment(Category.WAYPOINTS, R.string.seg_waypoints, 0, 0);
        setSegment(Category.ZONES, R.string.seg_zones, noGo, R.string.attention_zones);
    }

    private void setSegment(Category category, int name, int attention, int attentionWord) {
        String label = pluginContext.getString(name) + "\n" + rows.get(category).size();
        if (attention > 0)
            label += "  ·  " + attention + " " + pluginContext.getString(attentionWord);
        segments.get(category).setText(label);
    }
}
