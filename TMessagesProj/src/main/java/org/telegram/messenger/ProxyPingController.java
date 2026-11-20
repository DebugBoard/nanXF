package org.telegram.messenger;

import android.os.SystemClock;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestTimeDelegate;

/**
 * Pings the current proxy periodically, but only while at least one watcher
 * (a visible ProxyPill in a foregrounded app) is registered. All methods must be
 * called on the UI thread.
 */
public class ProxyPingController {

    private static final ProxyPingController INSTANCE = new ProxyPingController();

    private final Runnable pingRunnable = this::doPing;
    private int watchers;
    private boolean pingInFlight;

    private void doPing() {
        if (watchers <= 0 || pingInFlight) {
            return;
        }
        SharedConfig.ProxyInfo proxyInfo;
        if (SharedConfig.isProxyEnabled() && (proxyInfo = SharedConfig.currentProxy) != null) {
            pingInFlight = true;
            final SharedConfig.ProxyInfo info = proxyInfo;
            ConnectionsManager.getInstance(UserConfig.selectedAccount).checkProxy(
                    info.settings,
                    new RequestTimeDelegate() {
                        @Override
                        public void run(long time) {
                            AndroidUtilities.runOnUIThread(() -> onPingResult(info, time));
                        }
                    }
            );
        } else {
            scheduleNextPing();
        }
    }

    private void onPingResult(SharedConfig.ProxyInfo proxyInfo, long time) {
        pingInFlight = false;
        if (time != -1) {
            proxyInfo.ping = time;
            proxyInfo.availableCheckTime = SystemClock.elapsedRealtime();
            proxyInfo.available = true;
        } else {
            proxyInfo.available = false;
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyPingUpdated, time);
        scheduleNextPing();
    }

    private void scheduleNextPing() {
        AndroidUtilities.cancelRunOnUIThread(pingRunnable);
        if (watchers > 0) {
            AndroidUtilities.runOnUIThread(pingRunnable, 10000L);
        }
    }

    public static void addWatcher() {
        if (INSTANCE.watchers++ == 0) {
            AndroidUtilities.cancelRunOnUIThread(INSTANCE.pingRunnable);
            INSTANCE.doPing();
        }
    }

    public static void removeWatcher() {
        if (INSTANCE.watchers > 0 && --INSTANCE.watchers == 0) {
            AndroidUtilities.cancelRunOnUIThread(INSTANCE.pingRunnable);
        }
    }
}
