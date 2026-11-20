package org.telegram.ui.web;

import android.webkit.WebView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.ui.Components.ForegroundDetector;
import org.telegram.utils.pip.PipCoordinator;
import org.telegram.utils.proxy.WebProxyTransport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Pauses JavaScript timers of all WebViews while the whole app is in background, and resumes
 * them when any activity of the app is started again.
 * <p>
 * {@link WebView#pauseTimers()} is process-global, so it is only used when no activity is
 * started (nothing of ours is on screen). Before pausing, every open mini app / in-app browser
 * page is asked whether it is playing media; if one is, or a floating PiP window is shown, or
 * the WEB proxy transport carries traffic through a WebView, the timers keep running.
 * <p>
 * All methods must be called on the UI thread.
 */
public final class WebViewTimers {

    private static final long PROBE_TIMEOUT_MS = 1500;

    // Returns true when the top document plays audible media or declares an active media session.
    private static final String MEDIA_PROBE_SCRIPT =
        "(function(){try{" +
            "var m=document.querySelectorAll('audio,video');" +
            "for(var i=0;i<m.length;i++){var e=m[i];" +
                "if(!e.paused&&!e.ended&&!e.muted&&e.volume>0)return true;}" +
            "if(navigator.mediaSession&&navigator.mediaSession.playbackState==='playing')return true;" +
        "}catch(e){}return false;})()";

    private static final Set<BotWebViewContainer.MyWebView> webViews =
        Collections.newSetFromMap(new WeakHashMap<>());

    private static boolean listenerAdded;
    private static boolean paused;
    // Strong reference to the instance used to pause, so it can always resume the global timers.
    private static BotWebViewContainer.MyWebView pausedVia;
    private static int probeGeneration;

    private WebViewTimers() {}

    static void register(BotWebViewContainer.MyWebView webView) {
        webViews.add(webView);
        if (!listenerAdded) {
            final ForegroundDetector detector = ForegroundDetector.getInstance();
            if (detector != null) {
                listenerAdded = true;
                detector.addListener(new ForegroundDetector.Listener() {
                    @Override
                    public void onBecameForeground() {
                        resume();
                    }

                    @Override
                    public void onBecameBackground() {
                        requestPause();
                    }
                });
            }
        }
        // A WebView created while timers are paused (for example by a notification action)
        // must not start frozen.
        if (paused) {
            resume();
        }
    }

    /** Called right before the WebView is destroyed. */
    static void unregister(BotWebViewContainer.MyWebView webView) {
        webViews.remove(webView);
        if (paused && webView == pausedVia) {
            // Timers are global to the process: resume them while this instance can still do it,
            // then pause again through another live instance if we are still in background.
            resume();
            pauseNow();
        }
    }

    private static boolean isBackground() {
        final ForegroundDetector detector = ForegroundDetector.getInstance();
        return detector != null && detector.isBackground();
    }

    private static boolean mustKeepRunning() {
        if (PipCoordinator.getInstance().findFloating() != null) {
            return true; // floating video (YouTube embeds and similar) may be a WebView
        }
        return WebProxyTransport.isRunning();
    }

    private static void requestPause() {
        if (paused || webViews.isEmpty() || mustKeepRunning()) {
            return;
        }
        final int generation = ++probeGeneration;
        final ArrayList<BotWebViewContainer.MyWebView> targets = new ArrayList<>(webViews);
        final int[] pending = new int[] { targets.size() };
        final boolean[] playing = new boolean[1];
        final Runnable[] finish = new Runnable[1];
        finish[0] = () -> {
            if (generation != probeGeneration) {
                return;
            }
            probeGeneration++;
            if (!playing[0]) {
                pauseNow();
            }
        };
        for (BotWebViewContainer.MyWebView webView : targets) {
            try {
                webView.evaluateJavascript(MEDIA_PROBE_SCRIPT, value -> {
                    if ("true".equals(value)) {
                        playing[0] = true;
                    }
                    if (--pending[0] == 0) {
                        AndroidUtilities.cancelRunOnUIThread(finish[0]);
                        finish[0].run();
                    }
                });
            } catch (Exception e) {
                FileLog.e(e);
                if (--pending[0] == 0) {
                    finish[0].run();
                    return;
                }
            }
        }
        // A hung renderer never answers; it is not playing anything useful either.
        AndroidUtilities.runOnUIThread(finish[0], PROBE_TIMEOUT_MS);
    }

    private static void pauseNow() {
        if (paused || !isBackground() || mustKeepRunning()) {
            return;
        }
        for (BotWebViewContainer.MyWebView webView : webViews) {
            try {
                webView.pauseTimers();
                paused = true;
                pausedVia = webView;
                return;
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    private static void resume() {
        probeGeneration++;
        if (!paused) {
            return;
        }
        final BotWebViewContainer.MyWebView webView = pausedVia;
        paused = false;
        pausedVia = null;
        try {
            webView.resumeTimers();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
