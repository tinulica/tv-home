package ro.tinulica.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;

/**
 * tvOS's press, everywhere: what has focus gives a little under the click and springs back
 * when it is let go, overshooting its size a touch before it settles. This remote's "released"
 * can arrive half a second late, so the spring lets go on its own after a moment.
 *
 * The press scales from the size the view had when it was pressed; once the view loses focus
 * (the click opened something) the press lets go of it, so its own focus animation decides.
 */
final class Press {

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable release = Press::up;
    private static View last;
    private static Spring spring;
    private static boolean held;

    private Press() {
    }

    /** Call first thing in dispatchKeyEvent; it never consumes the key. */
    static void key(KeyEvent event, View focused) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            return;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && focused != null) {
            down(focused);
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            up();
        }
    }

    private static void down(final View view) {
        if (view != last || spring == null || !spring.isRunning()) {
            // A new press (a press again while it springs back keeps the size it started from).
            last = view;
            final float sx = view.getScaleX();
            final float sy = view.getScaleY();
            final Spring[] self = new Spring[1];
            self[0] = new Spring(1f, 0.001f, k -> {
                if (!view.isFocused()) {
                    self[0].stop();
                    if (spring == self[0]) {
                        spring = null;
                    }
                    return;
                }
                view.setScaleX(sx * k);
                view.setScaleY(sy * k);
            });
            spring = self[0];
        }
        held = true;
        spring.to(0.93f, 0.12f, 1f);
        handler.removeCallbacks(release);
        handler.postDelayed(release, 170);
    }

    private static void up() {
        handler.removeCallbacks(release);
        if (!held || spring == null) {
            return;
        }
        held = false;
        spring.to(1f, 0.42f, 0.5f);
    }
}
