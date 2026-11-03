package com.atakmap.android.localsvctemplate.plugin.landing;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.localsvctemplate.plugin.R;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.CameraController;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The landing page: nodes, optimised waypoints and drawn zones in one list, switched by a
 * segment strip or by swiping the list left/right.
 *
 * Why not a spinner: a spinner hides the other two lists and their state, and takes two taps
 * (open, pick) to change. Here every segment shows its count, and how many need attention, all
 * the time, so the operator gets the whole picture at a glance and changes list with one tap or
 * one swipe. Only framework widgets are used (no androidx / Material), so there are no theme or
 * duplicate-class problems inside ATAK.
 *
 * Callers sort each list (the most urgent first) and call {@link #setItems}; this class only shows
 * them. Tapping a row pans the map to it.
 */
public class LandingPane {

    public enum Category { NODES, WAYPOINTS, ZONES }

    /** Drives the row's colour bar. Always pair it with a status word: colour alone isn't enough. */
    public enum Status { OK, WARN, BAD, NONE }

    public static final class Item {
        final String title;
        final String detail;
        final String statusText;
        final Status status;
        final GeoPoint point;   // where tapping the row pans to; null = don't pan

        public Item(String title, String detail, String statusText, Status status, GeoPoint point) {
            this.title = title;
            this.detail = detail;
            this.statusText = statusText;
            this.status = status;
            this.point = point;
        }
    }

    private static final Category[] ORDER = Category.values();

    private final Context pluginContext;
    private final Map<Category, List<Item>> items = new EnumMap<>(Category.class);
    private final Map<Category, Button> segments = new EnumMap<>(Category.class);
    private final ListView list;
    private final TextView empty;
    private final TextView caption;
    private final RowAdapter adapter = new RowAdapter();
    private Category showing = Category.NODES;

    public LandingPane(Context pluginContext, View root) {
        this.pluginContext = pluginContext;
        for (Category c : ORDER)
            items.put(c, new ArrayList<>());

        segments.put(Category.NODES, root.findViewById(R.id.seg_nodes));
        segments.put(Category.WAYPOINTS, root.findViewById(R.id.seg_waypoints));
        segments.put(Category.ZONES, root.findViewById(R.id.seg_zones));
        for (Map.Entry<Category, Button> e : segments.entrySet())
            e.getValue().setOnClickListener(v -> show(e.getKey()));

        caption = root.findViewById(R.id.list_caption);
        empty = root.findViewById(R.id.landing_empty);
        list = root.findViewById(R.id.landing_list);
        list.setAdapter(adapter);
        list.setEmptyView(empty);
        list.setOnItemClickListener((parent, view, position, id) -> panTo(adapter.getItem(position)));

        // Swipe left/right on the list (or on the empty message) moves to the next/previous list.
        // The listener only watches: the list still scrolls and takes taps as normal.
        GestureDetector swipe = new GestureDetector(pluginContext, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onFling(MotionEvent down, MotionEvent up, float vx, float vy) {
                if (down == null)
                    return false;
                float dx = up.getX() - down.getX();
                float minDx = 48 * pluginContext.getResources().getDisplayMetrics().density;
                // clearly sideways, so a vertical scroll that drifts a little doesn't switch lists
                if (Math.abs(dx) < minDx || Math.abs(vx) < 2 * Math.abs(vy))
                    return false;
                step(dx < 0 ? 1 : -1);
                return true;
            }
        });
        list.setOnTouchListener((v, e) -> {
            swipe.onTouchEvent(e);
            return false;
        });
        empty.setOnTouchListener((v, e) -> swipe.onTouchEvent(e));

        refreshSegments();
        show(showing);
    }

    /** Replaces one list. Call on the UI thread. */
    public void setItems(Category category, List<Item> newItems) {
        List<Item> l = items.get(category);
        l.clear();
        l.addAll(newItems);
        refreshSegments();
        if (category == showing)
            adapter.notifyDataSetChanged();
    }

    public void show(Category category) {
        int step = category.ordinal() - showing.ordinal();
        showing = category;
        for (Map.Entry<Category, Button> e : segments.entrySet())
            e.getValue().setSelected(e.getKey() == category);
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
        adapter.notifyDataSetChanged();
        list.setSelection(0);

        // a short slide in from the side it came from, so the swipe feels connected to the list
        if (step != 0) {
            View content = adapter.isEmpty() ? empty : list;
            content.setTranslationX(Math.signum(step) * content.getWidth() / 4f);
            content.setAlpha(0f);
            content.animate().translationX(0).alpha(1f).setDuration(150).start();
        }
    }

    private void step(int direction) {
        int next = showing.ordinal() + direction;
        if (next >= 0 && next < ORDER.length)
            show(ORDER[next]);
    }

    /** Segment labels: the count, plus a second line when something needs attention. */
    private void refreshSegments() {
        int nodes = items.get(Category.NODES).size();
        int offline = countBad(Category.NODES);
        segments.get(Category.NODES).setText(offline > 0
                ? pluginContext.getString(R.string.seg_nodes_alert, nodes, offline)
                : pluginContext.getString(R.string.seg_nodes, nodes));
        segments.get(Category.NODES).setTextColor(pluginContext.getResources()
                .getColor(offline > 0 ? R.color.status_bad : R.color.white));

        segments.get(Category.WAYPOINTS).setText(pluginContext.getString(R.string.seg_waypoints,
                items.get(Category.WAYPOINTS).size()));

        int zones = items.get(Category.ZONES).size();
        int noGo = countBad(Category.ZONES);
        segments.get(Category.ZONES).setText(noGo > 0
                ? pluginContext.getString(R.string.seg_zones_alert, zones, noGo)
                : pluginContext.getString(R.string.seg_zones, zones));
    }

    private int countBad(Category category) {
        int n = 0;
        for (Item i : items.get(category))
            if (i.status == Status.BAD)
                n++;
        return n;
    }

    private static void panTo(Item item) {
        if (item.point != null)
            CameraController.Programmatic.panTo(MapView.getMapView().getRenderer3(), item.point, true);
    }

    private int statusColor(Status s) {
        switch (s) {
            case OK:
                return pluginContext.getResources().getColor(R.color.status_ok);
            case WARN:
                return pluginContext.getResources().getColor(R.color.status_warn);
            case BAD:
                return pluginContext.getResources().getColor(R.color.status_bad);
            default:
                return pluginContext.getResources().getColor(R.color.status_none);
        }
    }

    private class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.get(showing).size();
        }

        @Override
        public Item getItem(int position) {
            return items.get(showing).get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View row, ViewGroup parent) {
            if (row == null)
                row = PluginLayoutInflater.inflate(pluginContext, R.layout.landing_row, null);
            Item item = getItem(position);
            int colour = statusColor(item.status);
            row.findViewById(R.id.row_bar).setBackgroundColor(colour);
            ((TextView) row.findViewById(R.id.row_title)).setText(item.title);
            ((TextView) row.findViewById(R.id.row_detail)).setText(item.detail);
            TextView status = row.findViewById(R.id.row_status);
            status.setText(item.statusText);
            status.setTextColor(colour);
            return row;
        }
    }
}
