package app.rayvpn.client;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
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
 * Small design-system toolkit: bundled Persian typefaces, themed colour lookup,
 * spring-y touch feedback, number formatting and the floating message bar.
 */
final class Ui {

    static final Interpolator EASE = new PathInterpolator(0.22f, 0.9f, 0.24f, 1f);
    static final Interpolator SPRING = new OvershootInterpolator(1.05f);
    static final Interpolator SOFT = new DecelerateInterpolator(1.6f);
    static final Interpolator SWING = new AccelerateDecelerateInterpolator();

    static final int W_REGULAR = 0;
    static final int W_MEDIUM = 1;
    static final int W_SEMIBOLD = 2;
    static final int W_BOLD = 3;

    private static final String[] FILES = {
            "vazirmatn_regular.ttf",
            "vazirmatn_medium.ttf",
            "vazirmatn_semibold.ttf",
            "vazirmatn_bold.ttf",
    };

    private static final Map<String, Typeface> FONTS = new HashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static View currentSnack;

    private Ui() {
    }

    // ── type ────────────────────────────────────────────────────────────────

    static Typeface weight(Context context, int weight) {
        int index = Math.max(0, Math.min(FILES.length - 1, weight));
        String file = FILES[index];
        Typeface cached = FONTS.get(file);
        if (cached != null) return cached;
        Typeface tf;
        try {
            tf = Typeface.createFromAsset(context.getAssets(), "fonts/" + file);
        } catch (Throwable t) {
            tf = Typeface.DEFAULT;
        }
        FONTS.put(file, tf);
        return tf;
    }

    static Typeface regular(Context c) {
        return weight(c, W_REGULAR);
    }

    static Typeface medium(Context c) {
        return weight(c, W_MEDIUM);
    }

    static Typeface semibold(Context c) {
        return weight(c, W_SEMIBOLD);
    }

    /** Walks a view tree and gives every plain TextView the bundled font. */
    static void applyFont(View root) {
        if (root instanceof RayText) return;
        if (root instanceof TextView) {
            TextView tv = (TextView) root;
            Typeface current = tv.getTypeface();
            boolean bold = current != null && current.isBold();
            tv.setTypeface(weight(root.getContext(), bold ? W_BOLD : W_REGULAR));
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyFont(group.getChildAt(i));
            }
        }
    }

    // ── themed colours ──────────────────────────────────────────────────────

    static int color(Context context, int attr) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attr, value, true)) return 0xFFFFFFFF;
        if (value.resourceId != 0) return context.getResources().getColor(value.resourceId);
        return value.data;
    }

    static float dp(Context c, float value) {
        return value * c.getResources().getDisplayMetrics().density;
    }

    static int dpi(Context c, float value) {
        return Math.round(dp(c, value));
    }

    // ── touch feedback ──────────────────────────────────────────────────────

    static void pressable(final View view) {
        if (view == null) return;
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.animate().cancel();
                        v.animate().scaleX(0.955f).scaleY(0.955f)
                                .setDuration(120).setInterpolator(SOFT).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.animate().cancel();
                        v.animate().scaleX(1f).scaleY(1f)
                                .setDuration(320).setInterpolator(SPRING).start();
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    static void bounce(View view) {
        if (view == null) return;
        view.animate().cancel();
        view.setScaleX(0.94f);
        view.setScaleY(0.94f);
        view.animate().scaleX(1f).scaleY(1f)
                .setDuration(340).setInterpolator(SPRING).start();
    }

    static void fadeInUp(View view, long delay) {
        if (view == null) return;
        view.setAlpha(0f);
        view.setTranslationY(dp(view.getContext(), 12));
        view.animate().alpha(1f).translationY(0f)
                .setStartDelay(delay).setDuration(460).setInterpolator(EASE).start();
    }

    /** Swaps the text with a soft cross-fade when it actually changes. */
    static void setTextSmooth(final TextView view, final String text) {
        if (view == null) return;
        final String value = text == null ? "" : text;
        if (value.equals(String.valueOf(view.getText()))) return;
        view.animate().cancel();
        view.animate().alpha(0f).setDuration(90).setInterpolator(SOFT)
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        view.setText(value);
                        view.setTranslationY(dp(view.getContext(), 3));
                        view.animate().alpha(1f).translationY(0f)
                                .setDuration(190).setInterpolator(EASE).start();
                    }
                }).start();
    }

    /** Immediate set, used for values that change every second (no flicker). */
    static void setText(TextView view, String text) {
        if (view == null) return;
        String value = text == null ? "" : text;
        if (!value.equals(String.valueOf(view.getText()))) view.setText(value);
    }

    // ── numbers ─────────────────────────────────────────────────────────────

    private static final char[] FA_DIGITS = "۰۱۲۳۴۵۶۷۸۹".toCharArray();

    static String num(String raw, boolean persian) {
        if (raw == null) return "";
        if (!persian) return raw;
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch >= '0' && ch <= '9') out.append(FA_DIGITS[ch - '0']);
            else if (ch == '.') out.append('٫');
            else out.append(ch);
        }
        return out.toString();
    }

    static String num(long value, boolean persian) {
        return num(String.valueOf(value), persian);
    }

    /** 0 → "۰ B", 1024 → "۱ KB", and always one decimal above 100 units. */
    static String bytes(long value, boolean persian) {
        if (value < 0) value = 0;
        if (value < 1024) return num(String.valueOf(value), persian) + " B";
        double kb = value / 1024.0;
        if (kb < 1024) return fmt(kb, persian) + " KB";
        double mb = kb / 1024.0;
        if (mb < 1024) return fmt(mb, persian) + " MB";
        return fmt(mb / 1024.0, persian) + " GB";
    }

    static String speed(long bytesPerSecond, boolean persian) {
        return bytes(bytesPerSecond, persian) + "/s";
    }

    private static String fmt(double value, boolean persian) {
        String raw = value >= 100 ? String.format(java.util.Locale.US, "%.0f", value)
                : String.format(java.util.Locale.US, "%.1f", value);
        return num(raw, persian);
    }

    static String duration(long millis, boolean persian) {
        long total = Math.max(0, millis) / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        String raw = h > 0
                ? String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
                : String.format(java.util.Locale.US, "%02d:%02d", m, s);
        return num(raw, persian);
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
        bar.setTextColor(color(context, R.attr.rayTextDim));
        bar.setTextSize(12.5f);
        bar.setTypeface(medium(context));
        bar.setIncludeFontPadding(false);
        bar.setBackgroundResource(R.drawable.bg_snack);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dpi(context, 18);
        int padV = dpi(context, 13);
        bar.setPadding(padH, padV, padH, padV);
        bar.setElevation(dp(context, 10));
        bar.setMaxLines(3);
        bar.setAlpha(0f);
        bar.setTranslationY(dp(context, 22));

        if (root instanceof FrameLayout) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            int margin = dpi(context, 22);
            lp.setMargins(margin, margin, margin, margin + dpi(context, 70));
            ((FrameLayout) root).addView(bar, lp);
        } else if (root instanceof ViewGroup) {
            ((ViewGroup) root).addView(bar);
        } else {
            return;
        }
        currentSnack = bar;
        bar.animate().alpha(1f).translationY(0f)
                .setDuration(240).setInterpolator(EASE).start();

        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                bar.animate().alpha(0f).translationY(dp(bar.getContext(), 16))
                        .setDuration(220).setInterpolator(SOFT)
                        .withEndAction(new Runnable() {
                            @Override public void run() {
                                ViewGroup parent = (ViewGroup) bar.getParent();
                                if (parent != null) parent.removeView(bar);
                                if (currentSnack == bar) currentSnack = null;
                            }
                        }).start();
            }
        }, 2600);
    }
}
