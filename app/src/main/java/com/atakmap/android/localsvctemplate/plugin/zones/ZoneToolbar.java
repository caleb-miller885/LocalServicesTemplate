package com.atakmap.android.localsvctemplate.plugin.zones;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import com.atakmap.android.drawing.tools.ShapeCreationTool;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.localsvctemplate.plugin.R;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.toolbar.IToolbarExtension2;
import com.atakmap.android.toolbar.Tool;
import com.atakmap.android.toolbar.ToolManagerBroadcastReceiver;
import com.atakmap.android.toolbar.ToolbarBroadcastReceiver;
import com.atakmap.android.tools.ActionBarView;

import java.util.List;

/**
 * A toolbar like ATAK's drawing toolbar, with just the two zone tools: one plugin button opens
 * it ({@link #toggle}), then Exclusion or Ops starts drawing. While drawing, Undo and Done show:
 * the shape tool's own Undo/Done live on ATAK's drawing toolbar, so they aren't on screen here.
 * Without them the only way to finish is tapping the first point again.
 *
 * Built in code rather than inflated: ActionBarView is an ATAK class, and building it here
 * avoids depending on how the plugin inflater resolves it.
 */
public class ZoneToolbar implements IToolbarExtension2 {

    public static final String ID = "com.atakmap.android.localsvctemplate.ZONE_TOOLBAR";

    private final Context pluginContext;
    private final ZoneTools tools;
    private final ActionBarView bar;
    private final ImageButton exclusion;
    private final ImageButton ops;
    private final ImageButton undo;
    private final ImageButton done;

    public ZoneToolbar(Context pluginContext, MapView mv, ZoneTools tools) {
        this.pluginContext = pluginContext;
        this.tools = tools;

        bar = new ActionBarView(mv.getContext());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bar.setPosition(ActionBarView.TOP_LEFT);

        exclusion = button(R.drawable.ic_zone_exclusion, R.string.zone_exclusion,
                v -> tools.startDrawing(ZoneTools.Type.EXCLUSION));
        ops = button(R.drawable.ic_zone_ops, R.string.zone_ops,
                v -> tools.startDrawing(ZoneTools.Type.OPS));
        undo = button(R.drawable.ic_zone_undo, R.string.zone_undo, v -> {
            Tool t = ToolManagerBroadcastReceiver.getInstance().getActiveTool();
            if (t instanceof ShapeCreationTool)
                ((ShapeCreationTool) t).undo();
        });
        // ending the tool saves the shape (if it has 2+ points) and fires ZoneTools' callback
        done = button(R.drawable.ic_zone_done, R.string.zone_done,
                v -> ToolManagerBroadcastReceiver.getInstance().endCurrentTool());

        tools.onDrawingChanged = this::refresh;
        refresh();
        ToolbarBroadcastReceiver.getInstance().registerToolbarComponent(ID, this);
    }

    /** The plugin's toolbar button: opens this toolbar, or closes it if it's open. */
    public static void toggle() {
        Intent i = new Intent(ToolbarBroadcastReceiver.SET_TOOLBAR);
        i.putExtra("toolbar", ID);
        AtakBroadcast.getInstance().sendBroadcast(i);
    }

    /** Opens this toolbar (leaves it open if it already is), e.g. from a button in the pane. */
    public static void open() {
        Intent i = new Intent(ToolbarBroadcastReceiver.OPEN_TOOLBAR);
        i.putExtra("toolbar", ID);
        AtakBroadcast.getInstance().sendBroadcast(i);
    }

    public void dispose() {
        if (ID.equals(ToolbarBroadcastReceiver.getInstance().getActive()))
            AtakBroadcast.getInstance().sendBroadcast(new Intent(ToolbarBroadcastReceiver.UNSET_TOOLBAR));
        ToolbarBroadcastReceiver.getInstance().unregisterToolbarComponent(ID);
        tools.onDrawingChanged = null;
    }

    /** Like ATAK's drawing toolbar: the zone buttons while idle, the one being drawn plus Undo/Done while drawing. */
    private void refresh() {
        ZoneTools.Type drawing = tools.drawing();
        exclusion.setVisibility(drawing != ZoneTools.Type.OPS ? View.VISIBLE : View.GONE);
        ops.setVisibility(drawing != ZoneTools.Type.EXCLUSION ? View.VISIBLE : View.GONE);
        exclusion.setEnabled(drawing == null);
        ops.setEnabled(drawing == null);
        undo.setVisibility(drawing != null ? View.VISIBLE : View.GONE);
        done.setVisibility(drawing != null ? View.VISIBLE : View.GONE);
        if (ID.equals(ToolbarBroadcastReceiver.getInstance().getActive()))
            ToolbarBroadcastReceiver.getInstance().repositionToolbar();   // the bar changed width
    }

    private ImageButton button(int icon, int description, View.OnClickListener onClick) {
        float density = pluginContext.getResources().getDisplayMetrics().density;
        int size = Math.round(40 * density);   // ATAK's nav_child_button_size
        int pad = Math.round(6 * density);
        ImageButton b = new ImageButton(pluginContext);
        b.setImageDrawable(pluginContext.getResources().getDrawable(icon));
        b.setScaleType(ImageButton.ScaleType.FIT_CENTER);
        b.setBackgroundColor(0);
        b.setPadding(pad, pad, pad, pad);
        b.setContentDescription(pluginContext.getString(description));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            b.setTooltipText(pluginContext.getString(description));
        b.setOnClickListener(onClick);
        bar.addView(b, new LinearLayout.LayoutParams(size, size));
        return b;
    }

    @Override
    public ActionBarView getToolbarView() {
        return bar;
    }

    @Override
    public boolean hasToolbar() {
        return true;
    }

    @Override
    public List<Tool> getTools() {
        return null;   // uses ATAK's own shape tool, which ATAK registers
    }

    @Override
    public Drawable getToolbarIcon() {
        return pluginContext.getResources().getDrawable(R.drawable.ic_zone_toolbar);
    }

    @Override
    public void onToolbarVisible(boolean visible) {
    }
}
