package com.atakmap.android.localsvctemplate.plugin.coverage;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.map.layer.AbstractLayer;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * A map layer showing one image (e.g. a coverage map) stretched over a lat/lon box. It holds the
 * data; GLCoverageLayer draws it. Use it from the UI thread.
 *
 * <pre>
 * GLLayerFactory.register(GLCoverageLayer.SPI);                            // once
 * mapView.addLayer(MapView.RenderStack.MAP_SURFACE_OVERLAYS, layer);
 * layer.setImage(pngBytes, bounds);                                        // each new result
 * </pre>
 */
public final class CoverageLayer extends AbstractLayer {

    interface Listener {
        void onCoverageChanged(CoverageLayer layer);
    }

    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private byte[] png;        // the file as received, kept for sharing
    private Bitmap image;      // decoded, kept so the renderer can upload it again if recreated
    private GeoBounds bounds;
    private float opacity = 0.7f;

    public CoverageLayer(String name) {
        super(name);
    }

    /**
     * @param png    the image file from the service (PNG or JPEG). Its pixels must be evenly
     *               spaced in lat/lon, and fully transparent or fully opaque.
     * @param bounds the area the image covers
     */
    public void setImage(byte[] png, GeoBounds bounds) {
        Bitmap decoded = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (decoded == null)
            throw new IllegalArgumentException("not a PNG or JPEG");
        synchronized (this) {
            this.png = png;
            this.image = decoded;
            this.bounds = new GeoBounds(bounds);
        }
        changed();
    }

    /** 0 (invisible) .. 1 (opaque). */
    public void setOpacity(float opacity) {
        synchronized (this) {
            this.opacity = Math.max(0f, Math.min(1f, opacity));
        }
        changed();
    }

    public synchronized byte[] getPng() {
        return png;
    }

    public synchronized GeoBounds getBounds() {
        return bounds == null ? null : new GeoBounds(bounds);
    }

    public synchronized float getOpacity() {
        return opacity;
    }

    synchronized Bitmap getImage() {
        return image;
    }

    void addListener(Listener l) {
        listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void changed() {
        for (Listener l : listeners)
            l.onCoverageChanged(this);
    }
}
