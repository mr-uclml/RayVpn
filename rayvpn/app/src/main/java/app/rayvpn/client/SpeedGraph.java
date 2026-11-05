package app.rayvpn.client;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Minimal two-series sparkline for the last minute of traffic. The download
 * series gets a soft gradient fill, the upload series stays a thin hairline.
 */
public class SpeedGraph extends View {

    private static final int SAMPLES = 60;

    private final float[] down = new float[SAMPLES];
    private final float[] up = new float[SAMPLES];
    private int count;
    private int head = -1;
    private float peak = 1024f;

    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lineDown = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lineUp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path pathDown = new Path();
    private final Path pathUp = new Path();
    private final Path pathFill = new Path();

    public SpeedGraph(Context context) {
        this(context, null);
    }

    public SpeedGraph(Context context, AttributeSet attrs) {
        super(context, attrs);
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(Math.max(1f, Ui.dp(context, 0.7f)));
        grid.setColor(0x14FFFFFF);

        lineDown.setStyle(Paint.Style.STROKE);
        lineDown.setStrokeWidth(Ui.dp(context, 1.6f));
        lineDown.setStrokeCap(Paint.Cap.ROUND);
        lineDown.setStrokeJoin(Paint.Join.ROUND);

        lineUp.setStyle(Paint.Style.STROKE);
        lineUp.setStrokeWidth(Ui.dp(context, 1.1f));
        lineUp.setStrokeCap(Paint.Cap.ROUND);
        lineUp.setStrokeJoin(Paint.Join.ROUND);

        fill.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        grid.setColor(adjustAlpha(Ui.color(getContext(), R.attr.rayLineSoft), 1f));
        lineDown.setColor(Ui.color(getContext(), R.attr.rayAccent));
        lineUp.setColor(Ui.color(getContext(), R.attr.rayTextDim));
        pathFill.setFillType(Path.FillType.WINDING);
        invalidate();
    }

    /** Adds one sample for the current second. */
    public void push(long downBps, long upBps) {
        head = (head + 1) % SAMPLES;
        down[head] = Math.max(0, downBps);
        up[head] = Math.max(0, upBps);
        if (count < SAMPLES) count++;
        float observed = Math.max(down[head], up[head]);
        if (observed > peak) {
            animatePeak(observed * 1.3f);
        } else if (observed < peak * 0.25f) {
            float target = Math.max(1024f, observed * 2.2f);
            animatePeak(target);
        }
        invalidate();
    }

    public void clear() {
        count = 0;
        head = -1;
        peak = 1024f;
        invalidate();
    }

    private void animatePeak(final float target) {
        ValueAnimator anim = ValueAnimator.ofFloat(peak, target);
        anim.setDuration(320);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                peak = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        // hairline grid: three quiet rows
        float pad = Ui.dp(getContext(), 6);
        for (int i = 1; i <= 3; i++) {
            float y = pad + (h - pad * 2) * i / 4f;
            canvas.drawLine(pad, y, w - pad, y, grid);
        }

        if (count < 2) return;
        float stepX = (w - pad * 2) / (float) (SAMPLES - 1);
        int first = (head - count + 1 + SAMPLES) % SAMPLES;

        pathDown.reset();
        pathUp.reset();
        for (int i = 0; i < count; i++) {
            int idx = (first + i) % SAMPLES;
            float x = pad + (SAMPLES - count + i) * stepX;
            float yDown = h - pad - (h - pad * 2) * Math.min(1f, down[idx] / peak);
            float yUp = h - pad - (h - pad * 2) * Math.min(1f, up[idx] / peak);
            if (i == 0) {
                pathDown.moveTo(x, yDown);
                pathUp.moveTo(x, yUp);
            } else {
                pathDown.lineTo(x, yDown);
                pathUp.lineTo(x, yUp);
            }
        }

        pathFill.set(pathDown);
        pathFill.lineTo(pad + (SAMPLES - 1) * stepX, h - pad);
        pathFill.lineTo(pad + (SAMPLES - count) * stepX, h - pad);
        pathFill.close();
        fill.setShader(new LinearGradient(0, pad, 0, h - pad,
                adjustAlpha(Ui.color(getContext(), R.attr.rayAccent), 0.22f),
                adjustAlpha(Ui.color(getContext(), R.attr.rayAccent), 0f),
                Shader.TileMode.CLAMP));
        canvas.drawPath(pathFill, fill);

        canvas.drawPath(pathUp, lineUp);
        canvas.drawPath(pathDown, lineDown);
    }

    private static int adjustAlpha(int color, float factor) {
        int alpha = Math.min(255, Math.round(((color >> 24) & 0xFF) * factor));
        return (alpha << 24) | (color & 0x00FFFFFF);
    }
}
