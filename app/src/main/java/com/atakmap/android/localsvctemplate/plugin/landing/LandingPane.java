package com.atakmap.android.localsvctemplate.plugin.landing;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
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
 * them. Tapping a row pans the map to it. Items that need attention are shown by their status
 * word (bold, white) and counted on their segment, not by colour.
 */
public class LandingPane {

    public enum Category { NODES, WAYPOINTS, ZONES }

    public static final class Item {
        final String title;
        final String detail;
        final String statusText;
        final boolean attention;   // e.g. an offline node or a no-go zone
        final GeoPoint point;      // where tapping the row pans to; null = don't pan

        public Item(String title, String detail, String statusText, boolean attention, GeoPoint point) {
            this.title = title;
            this.detail = detail;
            this.statusText = statusText;
            this.attention = attention;
            this.point = point;
        }
    }

    private static final Category[] ORDER = Category.values();

    private final Context pluginContext;
    private final Map<Category, List<Item>> items = new EnumMap<>(Category.class);
    private final Map<Category, TextView> segments = new EnumMap<>(Category.class);
    private final ListView list;
    private final TextView empty;
    private final TextView caption;
    private final View drawZones;
    private final RowAdapter adapter = new RowAdapter();
    private Category showing = Category.NODES;

    public LandingPane(Context pluginContext, View root) {
        this.pluginContext = pluginContext;
        for (Category c : ORDER)
            items.put(c, new ArrayList<>());

        segments.put(Category.NODES, root.findViewById(R.id.seg_nodes));
        segments.put(Category.WAYPOINTS, root.findViewById(R.id.seg_waypoints));
        segments.put(Category.ZONES, root.findViewById(R.id.seg_zones));
        for (Map.Entry<Category, TextView> e : segments.entrySet())
            e.getValue().setOnClickListener(v -> show(e.getKey()));

        caption = root.findViewById(R.id.list_caption);
        drawZones = root.findViewById(R.id.btn_draw_zones);
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

    /** Segment labels: a small grey name over the count, plus how many need attention. */
    private void refreshSegments() {
        setSegment(Category.NODES, R.string.seg_nodes, R.string.attention_nodes);
        setSegment(Category.WAYPOINTS, R.string.seg_waypoints, 0);
        setSegment(Category.ZONES, R.string.seg_zones, R.string.attention_zones);
    }

    private void setSegment(Category category, int name, int attentionWord) {
        int attention = 0;
        for (Item i : items.get(category))
            if (i.attention)
                attention++;

        SpannableStringBuilder label = new SpannableStringBuilder(pluginContext.getString(name));
        label.setSpan(new RelativeSizeSpan(0.8f), 0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setSpan(new ForegroundColorSpan(secondary()), 0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.append('\n');
        int start = label.length();
        label.append(String.valueOf(items.get(category).size()));
        label.setSpan(new StyleSpan(Typeface.BOLD), start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (attention > 0 && attentionWord != 0)
            label.append("  \u00b7  ").append(String.valueOf(attention)).append(' ')
                    .append(pluginContext.getString(attentionWord));
        segments.get(category).setText(label);
    }

    private static void panTo(Item item) {
        if (item.point != null)
            CameraController.Programmatic.panTo(MapView.getMapView().getRenderer3(), item.point, true);
    }

    private int secondary() {
        return pluginContext.getResources().getColor(R.color.text_secondary);
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
            ((TextView) row.findViewById(R.id.row_title)).setText(item.title);
            ((TextView) row.findViewById(R.id.row_detail)).setText(item.detail);
            TextView status = row.findViewById(R.id.row_status);
            status.setText(item.statusText);
            status.setTextColor(item.attention
                    ? pluginContext.getResources().getColor(R.color.white) : secondary());
            status.setTypeface(null, item.attention ? Typeface.BOLD : Typeface.NORMAL);
            return row;
        }
    }
}
