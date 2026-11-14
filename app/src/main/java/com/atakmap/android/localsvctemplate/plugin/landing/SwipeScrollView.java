package com.atakmap.android.localsvctemplate.plugin.landing;

import android.content.Context;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.ScrollView;

/**
 * A ScrollView that also reports sideways swipes. It watches every touch on its way down to the
 * rows (a touch listener wouldn't see touches that a clickable row takes), and only watches:
 * scrolling and taps work as normal.
 */
public class SwipeScrollView extends ScrollView {

    public interface OnSwipeListener {
        /** @param direction +1 for a swipe to the left (next), -1 to the right (previous) */
        void onSwipe(int direction);
    }

    private OnSwipeListener listener;
    private final GestureDetector detector;

    public SwipeScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        final float minDx = 48 * context.getResources().getDisplayMetrics().density;
        detector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent down, MotionEvent up, float vx, float vy) {
                if (down == null || listener == null)
                    return false;
                float dx = up.getX() - down.getX();
                // clearly sideways, so a vertical scroll that drifts a little doesn't count
                if (Math.abs(dx) < minDx || Math.abs(vx) < 2 * Math.abs(vy))
                    return false;
                listener.onSwipe(dx < 0 ? 1 : -1);
                return true;
            }
        });
    }

    public void setOnSwipeListener(OnSwipeListener l) {
        listener = l;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        detector.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }
}
