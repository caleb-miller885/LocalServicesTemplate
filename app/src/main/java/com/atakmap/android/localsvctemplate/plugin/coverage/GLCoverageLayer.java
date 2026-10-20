package com.atakmap.android.localsvctemplate.plugin.coverage;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.util.Pair;

import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.map.MapRenderer;
import com.atakmap.map.layer.Layer;
import com.atakmap.map.layer.control.SurfaceRendererControl;
import com.atakmap.map.layer.opengl.GLAbstractLayer2;
import com.atakmap.map.layer.opengl.GLLayer2;
import com.atakmap.map.layer.opengl.GLLayerSpi2;
import com.atakmap.map.opengl.GLMapView;
import com.atakmap.opengl.GLTexture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;

/**
 * Draws a CoverageLayer: its image as one texture on a quad over the layer's bounds. ATAK creates
 * it (through SPI) when the layer is added to the map. It draws in the surface pass, so the image
 * lies on the terrain when the map is tilted.
 *
 * Everything here runs on the GL thread, except onCoverageChanged.
 */
public final class GLCoverageLayer extends GLAbstractLayer2 implements CoverageLayer.Listener {

    public static final GLLayerSpi2 SPI = new GLLayerSpi2() {
        @Override
        public int getPriority() {
            return 1;
        }

        @Override
        public GLLayer2 create(Pair<MapRenderer, Layer> arg) {
            return arg.second instanceof CoverageLayer
                    ? new GLCoverageLayer(arg.first, (CoverageLayer) arg.second)
                    : null;
        }
    };

    private final CoverageLayer layer;
    private GLTexture texture;
    private float opacity;
    private final DoubleBuffer corners = doubles(4 * 2);    // lon, lat of UL, UR, LR, LL
    private final FloatBuffer vertices = floats(4 * 2);     // the corners in GL x, y
    private final FloatBuffer texCoords = floats(4 * 2);    // the part of the texture the image fills

    private GLCoverageLayer(MapRenderer renderer, CoverageLayer layer) {
        super(renderer, layer, GLMapView.RENDER_PASS_SURFACE);
        this.layer = layer;
    }

    @Override
    protected void init() {
        super.init();
        layer.addListener(this);
        onCoverageChanged(layer);
    }

    /** Called when the image or opacity changes (from the UI thread). */
    @Override
    public void onCoverageChanged(CoverageLayer l) {
        final Bitmap image = l.getImage();
        final GeoBounds bounds = l.getBounds();
        final float alpha = l.getOpacity();
        runOnGLThread(() -> {
            opacity = alpha;
            if (image != null)
                upload(image, bounds);
        });
        // ATAK caches what's drawn on the map surface; mark it out of date so it's redrawn
        renderContext.visitControl(null, SurfaceRendererControl::markDirty,
                SurfaceRendererControl.class);
    }

    private void upload(Bitmap image, GeoBounds b) {
        int w = image.getWidth(), h = image.getHeight();
        if (texture == null || texture.getTexWidth() < w || texture.getTexHeight() < h) {
            if (texture != null)
                texture.release();
            texture = new GLTexture(w, h, image.getConfig());
        }
        texture.load(null, 0, 0, w, h);
        texture.load(image);

        // The texture can be bigger than the image (power-of-two sizes), so use just the image's
        // part of it. v = 0 is the image's top row.
        float u = (float) w / texture.getTexWidth(), v = (float) h / texture.getTexHeight();
        texCoords.clear();
        texCoords.put(new float[] {0, 0, u, 0, u, v, 0, v}).flip();
        corners.clear();
        corners.put(new double[] {
                b.getWest(), b.getNorth(), b.getEast(), b.getNorth(),
                b.getEast(), b.getSouth(), b.getWest(), b.getSouth()}).flip();
    }

    @Override
    protected void drawImpl(GLMapView view, int renderPass) {
        if (texture == null || opacity <= 0f)
            return;
        view.forward(corners, vertices);
        // colour (1, 1, 1, opacity) fades the whole image; ATAK blends by alpha
        GLTexture.draw(texture.getTexId(), GLES20.GL_TRIANGLE_FAN, 4,
                2, GLES20.GL_FLOAT, texCoords,
                2, GLES20.GL_FLOAT, vertices,
                1f, 1f, 1f, opacity);
    }

    @Override
    public void release() {
        layer.removeListener(this);
        if (texture != null) {
            texture.release();
            texture = null;
        }
        super.release();
    }

    private static DoubleBuffer doubles(int n) {
        return ByteBuffer.allocateDirect(n * 8).order(ByteOrder.nativeOrder()).asDoubleBuffer();
    }

    private static FloatBuffer floats(int n) {
        return ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }
}
