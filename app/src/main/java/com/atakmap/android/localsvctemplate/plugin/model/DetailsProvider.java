package com.atakmap.android.localsvctemplate.plugin.model;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.atakmap.android.cotdetails.extras.ExtraDetailsManager;
import com.atakmap.android.cotdetails.extras.ExtraDetailsProvider;
import com.atakmap.android.maps.MapItem;

/**
 * Adds a section to ATAK's own details page. ATAK asks every registered provider for a view
 * whenever a details page opens - marker details (CoTInfoView) and shape details
 * (GenericDetailsView) both do - so one provider covers nodes and zones:
 *
 *  - a node:  "Equipment Profile"  /  Relay kit A
 *  - a zone:  "Assigned Nodes (2)" /  RF-01 hill 212, EO-01 tower (one per line)
 *
 * It is styled like ATAK's own fields (yellow heading, plain value). ATAK decides where the
 * section goes: the plugin slot, below Remarks.
 *
 * Returns null for any item that isn't ours, so other markers and shapes are left alone.
 */
public class DetailsProvider implements ExtraDetailsProvider, Registry.Listener {

    // ATAK's details heading colour and font sizes (heading_yellow, draper_font, draper_title_font)
    private static final int HEADING = 0xFFDFB228;
    private static final float HEADING_SP = 10;
    private static final float VALUE_SP = 15;

    private final Context context;   // ATAK's context: these views live in ATAK's details page
    private final Registry registry;

    public DetailsProvider(Context context, Registry registry) {
        this.context = context;
        this.registry = registry;
    }

    public void register() {
        ExtraDetailsManager.getInstance().addProvider(this);
        registry.addListener(this);
    }

    public void unregister() {
        registry.removeListener(this);
        ExtraDetailsManager.getInstance().removeProvider(this);
    }

    /** Redraws any details page that is open, e.g. a node was assigned to the zone shown. */
    @Override
    public void onRegistryChanged() {
        ExtraDetailsManager.getInstance().updateProvider(this);
    }

    @Override
    public View getExtraView(MapItem item, View existing) {
        Section s = existing instanceof Section ? (Section) existing : null;
        String heading = null, value = null;

        NodeDto node = item == null ? null : registry.node(item.getUID());
        ZoneDto zone = item == null ? null : registry.zone(item.getUID());
        if (node != null) {
            heading = "Equipment Profile";
            value = node.profile;
        } else if (zone != null) {
            heading = "Assigned Nodes (" + zone.nodes.size() + ")";
            StringBuilder sb = new StringBuilder();
            for (NodeDto n : zone.nodes)
                sb.append(sb.length() == 0 ? "" : "\n").append(n.callsign());
            value = sb.length() == 0 ? "None" : sb.toString();
        }

        if (heading == null) {
            // not ours. ATAK only hands `existing` back if we gave it a view before, so hide it
            if (s != null)
                s.setVisibility(View.GONE);
            return s;
        }
        if (s == null)
            s = new Section(context);
        s.setVisibility(View.VISIBLE);
        s.heading.setText(heading);
        s.value.setText(value);
        return s;
    }

    /** A heading over a value, like ATAK's own fields. */
    private static class Section extends LinearLayout {
        final TextView heading;
        final TextView value;

        Section(Context c) {
            super(c);
            setOrientation(VERTICAL);
            int pad = Math.round(6 * c.getResources().getDisplayMetrics().density);
            setPadding(pad, pad, pad, pad);

            heading = new TextView(c);
            heading.setTextColor(HEADING);
            heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, HEADING_SP);
            addView(heading);

            value = new TextView(c);
            value.setTextColor(Color.WHITE);
            value.setTextSize(TypedValue.COMPLEX_UNIT_SP, VALUE_SP);
            value.setLineSpacing(0, 1.15f);
            addView(value);
        }
    }
}
