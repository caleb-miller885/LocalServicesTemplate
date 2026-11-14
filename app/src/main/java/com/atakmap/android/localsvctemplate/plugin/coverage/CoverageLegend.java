package com.atakmap.android.localsvctemplate.plugin.coverage;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.widgets.DrawableWidget;
import com.atakmap.android.widgets.LinearLayoutWidget;
import com.atakmap.android.widgets.RootLayoutWidget;

import java.util.Locale;

/**
 * The signal scale in the bottom-left corner of the map: a small vertical colour bar from
 * strongest (top) to weakest (bottom), labelled in dBm. The widget is sized to its content, so
 * the dark background only covers the bar and its labels. It's an ATAK map widget, so it stacks
 * with ATAK's own bottom-left widgets instead of covering them.
 */
final class CoverageLegend {

    /**
     * Strongest to weakest. Must match the colours the service paints the PNG with
     * (CalcService.colour: red, yellow, green, cyan, blue).
     */
    static final int[] DEFAULT_COLOURS = {
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE };

    // sizes in dp
    private static final float PAD = 4, BAR_W = 7, BAR_H = 56, TICK = 3, GAP = 2, TEXT = 9;

    private final LinearLayoutWidget corner;
    private final DrawableWidget widget;
    private final float density;

    CoverageLegend(MapView mapView) {
        density = mapView.getContext().getResources().getDisplayMetrics().density;
        RootLayoutWidget root = (RootLayoutWidget) mapView.getComponentExtra("rootLayoutWidget");
        corner = root.getLayout(RootLayoutWidget.BOTTOM_LEFT);
        widget = new DrawableWidget();
        widget.setVisible(false);
        corner.addChildWidget(widget);
    }

    /** @param strongDbm top of the bar (e.g. -50); weakDbm bottom (e.g. -100) */
    void setScale(double strongDbm, double weakDbm, int[] colours) {
        Bar bar = new Bar(strongDbm, weakDbm, colours);
        widget.setSize(bar.width, bar.height);
        widget.setDrawable(bar);
    }

    void setVisible(boolean visible) {
        widget.setVisible(visible);
    }

    void dispose() {
        corner.removeChildWidget(widget);
    }

    private float dp(float v) {
        return v * density;
    }

    /** Draws the bar and its labels; the widget calls draw() with bounds set to its size. */
    private final class Bar extends Drawable {
        final String[] labels = new String[3];   // top, middle, bottom
        final int[] colours;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float width, height;

        Bar(double strong, double weak, int[] colours) {
            this.colours = colours.clone();
            for (int i = 0; i < 3; i++)
                labels[i] = String.format(Locale.US, "%.0f", strong + (weak - strong) * i / 2.0);
            paint.setTextSize(dp(TEXT));

            float labelW = paint.measureText("dBm");
            for (String l : labels)
                labelW = Math.max(labelW, paint.measureText(l));
            width = dp(PAD + BAR_W + TICK + GAP) + labelW + dp(PAD);
            height = dp(PAD + TEXT + GAP + BAR_H + PAD);
        }

        @Override
        public void draw(Canvas c) {
            final float w = getBounds().width(), h = getBounds().height();
            final float text = dp(TEXT);

            // background, just around the content
            paint.setShader(null);
            paint.setColor(0xA0000000);
            c.drawRoundRect(new RectF(0, 0, w, h), dp(3), dp(3), paint);

            // bar
            final float left = dp(PAD), right = left + dp(BAR_W);
            final float top = dp(PAD) + text + dp(GAP), bottom = h - dp(PAD);
            paint.setShader(new LinearGradient(0, top, 0, bottom, colours, null, Shader.TileMode.CLAMP));
            c.drawRect(left, top, right, bottom, paint);
            paint.setShader(null);

            // unit, top left; labels with a tick each, top / middle / bottom
            paint.setColor(Color.WHITE);
            paint.setStrokeWidth(dp(1));
            c.drawText("dBm", left, dp(PAD) + text * 0.85f, paint);
            final float labelX = right + dp(TICK + GAP);
            for (int i = 0; i < 3; i++) {
                float y = top + (bottom - top) * i / 2f;
                c.drawLine(right, y, right + dp(TICK), y, paint);
                float baseline = Math.max(top + text * 0.7f, Math.min(bottom, y + text * 0.35f));
                c.drawText(labels[i], labelX, baseline, paint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
