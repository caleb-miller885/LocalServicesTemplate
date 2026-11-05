package com.atakmap.android.localsvctemplate.plugin.zones;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import com.atakmap.android.drawing.tools.ShapeCreationTool;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.localsvctemplate.plugin.R;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.toolbar.IToolbarExtension2;
import com.atakmap.android.toolbar.Tool;
import com.atakmap.android.toolbar.ToolListener;
import com.atakmap.android.toolbar.ToolManagerBroadcastReceiver;
import com.atakmap.android.toolbar.ToolbarBroadcastReceiver;
import com.atakmap.android.tools.ActionBarView;

import java.util.List;

/**
 * A toolbar like ATAK's drawing toolbar with just the two zone tools. While a zone is being
 * drawn it shows Undo and End Shape, styled like ATAK's own: the shape tool's buttons live on
 * ATAK's drawing toolbar, so they aren't on screen here. Without them the only way to finish is
 * tapping the first point.
 */
public class ZoneToolbar implements IToolbarExtension2, ToolListener {

    public static final String ID = "com.atakmap.android.localsvctemplate.ZONE_TOOLBAR";

    private final Context pluginContext;
    private final ActionBarView bar;
    private final ImageButton exclusion;
    private final ImageButton ao;
    private final Button undo;
    private final Button done;
    private ImageButton drawing;   // the zone button that started the shape tool, null when idle

    public ZoneToolbar(Context pluginContext, MapView mv, ExclusionZoneTool exclusionTool, AoZoneTool aoTool) {
        this.pluginContext = pluginContext;

        // built in code: ActionBarView is an ATAK class, so this avoids the plugin inflater
        bar = new ActionBarView(mv.getContext());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bar.setPosition(ActionBarView.TOP_LEFT);

        exclusion = button(R.drawable.ic_zone_exclusion, R.string.zone_exclusion, v -> {
            drawing = (ImageButton) v;
            exclusionTool.start();
        });
        ao = button(R.drawable.ic_zone_ops, R.string.zone_ops, v -> {
            drawing = (ImageButton) v;
            aoTool.start();
        });
        undo = textButton(R.string.zone_undo, v -> {
            Tool t = ToolManagerBroadcastReceiver.getInstance().getActiveTool();
            if (t instanceof ShapeCreationTool)
                ((ShapeCreationTool) t).undo();
        });
        // ending the tool saves the shape (if it has 2+ points) and fires the zone tool's callback
        done = textButton(R.string.zone_done,
                v -> ToolManagerBroadcastReceiver.getInstance().endCurrentTool());

        refresh();
        ToolManagerBroadcastReceiver.getInstance().registerListener(this);
        ToolbarBroadcastReceiver.getInstance().registerToolbar(ID, this);
    }

    /** Opens this toolbar, or leaves it open if it already is. */
    public static void open() {
        Intent i = new Intent(ToolbarBroadcastReceiver.OPEN_TOOLBAR);
        i.putExtra("toolbar", ID);
        AtakBroadcast.getInstance().sendBroadcast(i);
    }

    /** Opens this toolbar, or closes it if it's open (like ATAK's drawing button). */
    public static void toggle() {
        Intent i = new Intent(ToolbarBroadcastReceiver.SET_TOOLBAR);
        i.putExtra("toolbar", ID);
        AtakBroadcast.getInstance().sendBroadcast(i);
    }

    public void dispose() {
        if (ID.equals(ToolbarBroadcastReceiver.getInstance().getActive()))
            AtakBroadcast.getInstance().sendBroadcast(new Intent(ToolbarBroadcastReceiver.UNSET_TOOLBAR));
        ToolbarBroadcastReceiver.getInstance().unregisterToolbarComponent(ID);
        ToolManagerBroadcastReceiver.getInstance().unregisterListener(this);
    }

    @Override
    public void onToolBegin(Tool tool, Bundle extras) {
    }

    @Override
    public void onToolEnded(Tool tool) {
        if (drawing != null && tool instanceof ShapeCreationTool) {
            drawing = null;
            refresh();
        }
    }

    /** Idle: both zone buttons. Drawing: the zone being drawn, plus Undo and Done. */
    private void refresh() {
        exclusion.setVisibility(drawing == null || drawing == exclusion ? View.VISIBLE : View.GONE);
        ao.setVisibility(drawing == null || drawing == ao ? View.VISIBLE : View.GONE);
        exclusion.setEnabled(drawing == null);
        ao.setEnabled(drawing == null);
        undo.setVisibility(drawing != null ? View.VISIBLE : View.GONE);
        done.setVisibility(drawing != null ? View.VISIBLE : View.GONE);
        if (ID.equals(ToolbarBroadcastReceiver.getInstance().getActive()))
            ToolbarBroadcastReceiver.getInstance().repositionToolbar();   // the bar changed width
    }

    /** Same look as the Undo / End Shape buttons on ATAK's drawing toolbar (btn_gray, bold 13sp). */
    private Button textButton(int text, View.OnClickListener onClick) {
        float density = pluginContext.getResources().getDisplayMetrics().density;
        int pad = Math.round(4 * density);
        Button b = new Button(pluginContext);
        b.setText(text);
        b.setAllCaps(false);
        b.setBackground(pluginContext.getDrawable(R.drawable.btn_gray));
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTypeface(null, Typeface.BOLD);
        b.setShadowLayer(3, 2, 2, 0xFFAAAAAA);
        b.setPadding(pad, 0, pad, 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setOnClickListener(v -> {
            onClick.onClick(v);
            refresh();
        });
        bar.addView(b, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(40 * density)));   // nav_child_button_size
        return b;
    }

    private ImageButton button(int icon, int description, View.OnClickListener onClick) {
        float density = pluginContext.getResources().getDisplayMetrics().density;
        int size = Math.round(40 * density);   // ATAK's nav_child_button_size
        int pad = Math.round(6 * density);
        ImageButton b = new ImageButton(pluginContext);
        b.setImageDrawable(pluginContext.getDrawable(icon));
        b.setScaleType(ImageButton.ScaleType.FIT_CENTER);
        b.setBackgroundColor(0);
        b.setPadding(pad, pad, pad, pad);
        b.setContentDescription(pluginContext.getString(description));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            b.setTooltipText(pluginContext.getString(description));
        b.setOnClickListener(v -> {
            onClick.onClick(v);
            refresh();
        });
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

    /** Still part of the interface, but ATAK no longer uses it; tools register themselves. */
    @Deprecated
    @Override
    public List<Tool> getTools() {
        return null;
    }

    @Override
    public Drawable getToolbarIcon() {
        return pluginContext.getDrawable(R.drawable.ic_zone_toolbar);
    }

    @Override
    public void onToolbarVisible(boolean visible) {
    }
}
