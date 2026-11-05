package app.rayvpn.client;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** A quiet, hairline-free switch: rounded track + sliding dot. */
public class ToggleView extends View {

    public interface Listener {
        void onToggle(boolean checked);
    }

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private boolean checked;
    private float fraction;
    private Listener listener;

    public ToggleView(Context context) {
        this(context, null);
    }

    public ToggleView(Context context, AttributeSet attrs) {
        super(context, attrs);
        track.setStyle(Paint.Style.FILL);
        thumb.setStyle(Paint.Style.FILL);
        setClickable(true);
        setFocusable(true);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean value, boolean animate) {
        checked = value;
        if (animate) {
            ValueAnimator anim = ValueAnimator.ofFloat(fraction, value ? 1f : 0f);
            anim.setDuration(220);
            anim.setInterpolator(new DecelerateInterpolator(1.6f));
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    fraction = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            anim.start();
        } else {
            fraction = value ? 1f : 0f;
            invalidate();
        }
    }

    @Override
    public boolean performClick() {
        boolean result = super.performClick();
        setChecked(!checked, true);
        Ui.bounce(this);
        if (listener != null) listener.onToggle(checked);
        return result;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float radius = h / 2f;
        int off = Ui.color(getContext(), R.attr.raySurface2);
        int on = Ui.color(getContext(), R.attr.rayAccent);
        int knob = Ui.color(getContext(), R.attr.rayText);

        track.setColor(blend(off, on, fraction));
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, radius, radius, track);

        float inset = h * 0.18f;
        float d = h - inset * 2;
        float x = inset + (w - inset * 2 - d) * fraction;
        thumb.setColor(fraction > 0.5f ? blend(Ui.color(getContext(), R.attr.rayBg), knob, 0.25f) : knob);
        canvas.drawCircle(x + d / 2f, h / 2f, d / 2f, thumb);
    }

    private static int blend(int from, int to, float t) {
        int a = (int) (((from >> 24) & 0xFF) + (((to >> 24) & 0xFF) - ((from >> 24) & 0xFF)) * t);
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
