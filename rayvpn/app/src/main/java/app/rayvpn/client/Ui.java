package app.rayvpn.client;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * Small UI toolkit: bundled Persian typeface, spring-y touch feedback, tiny
 * snackbar and a few shared interpolators.
 */
final class Ui {

    static final Interpolator EASE = new PathInterpolator(0.22f, 0.9f, 0.24f, 1f);
    static final Interpolator SPRING = new OvershootInterpolator(1.15f);
    static final Interpolator SOFT = new DecelerateInterpolator(1.6f);
    static final Interpolator SWING = new AccelerateDecelerateInterpolator();

    private static final Map<String, Typeface> FONTS = new HashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static View currentSnack;

    private Ui() {
    }

    // ── typeface ────────────────────────────────────────────────────────────
    static Typeface font(Context context, String file) {
        Typeface cached = FONTS.get(file);
        if (cached != null) return cached;
        Typeface tf = null;
        try {
            tf = Typeface.createFromAsset(context.getAssets(), "fonts/" + file);
        } catch (Throwable ignored) {
        }
        if (tf == null) tf = Typeface.DEFAULT;
        FONTS.put(file, tf);
        return tf;
    }

    static Typeface regular(Context c) {
        return font(c, "vazirmatn_regular.ttf");
    }

    static Typeface medium(Context c) {
        return font(c, "vazirmatn_medium.ttf");
    }

    static Typeface semibold(Context c) {
        return font(c, "vazirmatn_semibold.ttf");
    }

    static Typeface bold(Context c) {
        return font(c, "vazirmatn_bold.ttf");
    }

    /** Replaces the typeface of every TextView below {@code root}. */
    static void applyFont(View root) {
        if (root instanceof TextView) {
            TextView tv = (TextView) root;
            Typeface current = tv.getTypeface();
            boolean isBold = current != null && current.isBold();
            tv.setTypeface(isBold ? bold(root.getContext()) : regular(root.getContext()));
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyFont(group.getChildAt(i));
            }
        }
    }

    static float dp(Context c, float value) {
        return value * c.getResources().getDisplayMetrics().density;
    }

    // ── touch feedback ──────────────────────────────────────────────────────
    /** Squishes the view slightly on touch-down and springs back on release. */
    static void pressable(final View view) {
        if (view == null) return;
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.animate().cancel();
                        v.animate().scaleX(0.94f).scaleY(0.94f)
                                .setDuration(110).setInterpolator(SOFT).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.animate().cancel();
                        v.animate().scaleX(1f).scaleY(1f)
                                .setDuration(320).setInterpolator(SPRING).start();
                        break;
                }
                return false;
            }
        });
    }

    static void bounce(View view) {
        if (view == null) return;
        view.animate().cancel();
        view.setScaleX(0.9f);
        view.setScaleY(0.9f);
        view.animate().scaleX(1f).scaleY(1f)
                .setDuration(420).setInterpolator(SPRING).start();
    }

    static void fadeInUp(View view, long delay) {
        if (view == null) return;
        float d = dp(view.getContext(), 12);
        view.setAlpha(0f);
        view.setTranslationY(d);
        view.animate().alpha(1f).translationY(0f)
                .setStartDelay(delay).setDuration(460).setInterpolator(EASE).start();
    }

    /**
     * Swaps the text with a soft cross-fade when it actually changes.
     */
    static void setTextSmooth(final TextView view, final String text) {
        if (view == null) return;
        final String value = text == null ? "" : text;
        if (value.equals(view.getText().toString())) return;
        view.animate().cancel();
        view.animate().alpha(0f).setDuration(110).setInterpolator(SOFT)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        view.setText(value);
                        view.setTranslationY(dp(view.getContext(), 3));
                        view.animate().alpha(1f).translationY(0f)
                                .setDuration(220).setInterpolator(EASE).start();
                    }
                }).start();
    }

    // ── snackbar ────────────────────────────────────────────────────────────
    static void snack(View root, String message) {
        if (root == null || message == null || message.isEmpty()) return;
        Context context = root.getContext();
        if (currentSnack != null) {
            ViewGroup parent = (ViewGroup) currentSnack.getParent();
            if (parent != null) parent.removeView(currentSnack);
            currentSnack = null;
        }
        final TextView bar = new TextView(context);
        bar.setText(message);
        bar.setTextColor(0xFFF3F7FF);
        bar.setTextSize(12.5f);
        bar.setTypeface(regular(context));
        bar.setIncludeFontPadding(false);
        bar.setBackgroundResource(R.drawable.bg_snack);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int padH = (int) dp(context, 16);
        int padV = (int) dp(context, 12);
        bar.setPadding(padH, padV, padH, padV);
        bar.setElevation(dp(context, 8));
        bar.setMaxLines(3);
        bar.setAlpha(0f);
        bar.setTranslationY(dp(context, 26));

        if (root instanceof FrameLayout) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            int margin = (int) dp(context, 22);
            lp.setMargins(margin, margin, margin, margin);
            ((FrameLayout) root).addView(bar, lp);
        } else if (root instanceof ViewGroup) {
            ((ViewGroup) root).addView(bar);
        } else {
            return;
        }
        currentSnack = bar;
        bar.animate().alpha(1f).translationY(0f)
                .setDuration(260).setInterpolator(EASE).start();

        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                bar.animate().alpha(0f).translationY(dp(bar.getContext(), 18))
                        .setDuration(220).setInterpolator(SOFT)
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                ViewGroup parent = (ViewGroup) bar.getParent();
                                if (parent != null) parent.removeView(bar);
                                if (currentSnack == bar) currentSnack = null;
                            }
                        }).start();
            }
        }, 2500);
    }
}
