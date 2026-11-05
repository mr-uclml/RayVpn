package app.rayvpn.client;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.widget.TextView;

/**
 * TextView that picks one of the four bundled Vazirmatn weights through the
 * {@code app:rayWeight} attribute, so the layout can describe the typography
 * instead of every screen doing it by hand.
 */
public class RayText extends TextView {

    public RayText(Context context) {
        this(context, null);
    }

    public RayText(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RayText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        int weight = 0;
        boolean bold = false;
        if (attrs != null) {
            TypedArray ta = context.obtainStyledAttributes(attrs, R.styleable.RayText, defStyleAttr, 0);
            weight = ta.getInt(R.styleable.RayText_rayWeight, 0);
            ta.recycle();
            bold = getTypeface() != null && getTypeface().isBold();
        }
        setTypeface(Ui.weight(context, bold && weight == 0 ? 3 : weight));
        setIncludeFontPadding(false);
    }

    /** Swaps the weight at runtime (used for the tab bar and the connect label). */
    void weight(int value) {
        setTypeface(Ui.weight(getContext(), value));
    }
}
