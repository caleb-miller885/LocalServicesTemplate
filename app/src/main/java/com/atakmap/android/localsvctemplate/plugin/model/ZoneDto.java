package com.atakmap.android.localsvctemplate.plugin.model;

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

    public String uid() {
        return shape.getUID();
    }
}
