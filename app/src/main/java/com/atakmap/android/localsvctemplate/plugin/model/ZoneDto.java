package com.atakmap.android.localsvctemplate.plugin.model;

import com.atakmap.android.localsvctemplate.plugin.zones.AoZoneTool;
import com.atakmap.android.localsvctemplate.plugin.zones.ExclusionZoneTool;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.Shape;

import java.util.ArrayList;
import java.util.List;

/** A drawn zone: its shape on the map, what kind it is, and the nodes assigned to it. */
public class ZoneDto {

    public enum Kind { AO, EXCLUSION }

    public final Shape shape;
    public final Kind kind;
    public final List<NodeDto> nodes = new ArrayList<>();

    public ZoneDto(Shape shape, Kind kind) {
        this.shape = shape;
        this.kind = kind;
    }

    /** The kind from the flag the zone tools put on the shape, or null if it isn't a zone. */
    public static Kind kindOf(MapItem item) {
        if (!(item instanceof Shape))
            return null;
        if (item.hasMetaValue(AoZoneTool.KEY_IS_AO))
            return Kind.AO;
        if (item.hasMetaValue(ExclusionZoneTool.KEY_IS_EXCLUSION))
            return Kind.EXCLUSION;
        return null;
    }

    public String uid() {
        return shape.getUID();
    }
}
