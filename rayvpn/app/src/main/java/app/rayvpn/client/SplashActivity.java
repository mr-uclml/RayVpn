package app.rayvpn.client;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

/**
 * A short brand beat before the dashboard: the mark fades in, the name follows
 * and a hairline starts to grow. It never blocks longer than ~700 ms.
 */
public class SplashActivity extends Activity {

    private static final long HOLD = 620L;

    private final Handler handler = new Handler();

    private final Runnable open = new Runnable() {
        @Override public void run() {
            startActivity(new Intent(SplashActivity.this, MainActivity.class));
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_RayVPN_Splash);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        View logo = findViewById(R.id.splashLogo);
        View name = findViewById(R.id.splashName);
        View tag = findViewById(R.id.splashTag);
        View line = findViewById(R.id.splashLine);

        logo.setScaleX(0.82f);
        logo.setScaleY(0.82f);
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(520).setInterpolator(Ui.EASE).start();

        name.setTranslationY(Ui.dp(this, 10));
        name.animate().alpha(1f).translationY(0f)
                .setStartDelay(160).setDuration(460).setInterpolator(Ui.EASE).start();

        tag.setTranslationY(Ui.dp(this, 8));
        tag.animate().alpha(1f).translationY(0f)
                .setStartDelay(280).setDuration(420).setInterpolator(Ui.EASE).start();

        line.animate().alpha(1f)
                .setStartDelay(220)
                .setDuration(HOLD)
                .setInterpolator(new AccelerateDecelerateInterpolator()).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(open);
        handler.postDelayed(open, HOLD);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(open);
        super.onPause();
    }
}
