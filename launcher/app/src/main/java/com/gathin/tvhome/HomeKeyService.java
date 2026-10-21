package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.Collections;
import java.util.Map;

/**
 * Sends the remote's Home button to this launcher on remotes that emit KEYCODE_ALL_APPS for Home
 * (Xiaomi sticks and boxes). Android TV 9 routes that key only to the system-image launcher, so once
 * the stock launcher is disabled the button does nothing. Every other key passes through untouched.
 *
 * With take_over_home (config.xml) it also stands in for Home on Google TV builds that have to keep
 * their stock launcher, because casting needs setupwraith (docs/TROUBLESHOOTING.md). Android then
 * dispatches Home to the stock launcher whatever the HOME role says, so the Home key opens this
 * launcher instead, and so does the stock home screen whenever Android shows it (start-up, wake),
 * unless another screen opens first. Only while this app holds the HOME role: tools/restore.sh hands
 * the role back, which ends the takeover. It reads which app and class each window change comes
 * from, never window content.
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {

    /** take_over_home: covering waits this long after the stock home screen's last window change. */
    private static final long COVER_DELAY_MS = 250;
    /** At most this many covers in this long, so this launcher never fights a stock one that keeps coming back. */
    private static final int MAX_COVERS = 5;
    private static final long MAX_COVERS_WINDOW_MS = 5000;
    /** When the service connects this soon after boot, the stock home screen is what start-up showed. */
    private static final long BOOT_COVER_MS = 3 * 60 * 1000;
    private static final String SETUP_WIZARD = "com.google.android.tungsten.setupwraith";

    /** Remote buttons that should open Settings (config.xml, settings_key_scancodes and settings_key_keycodes). */
    private int[] settingsScancodes = new int[0];
    private int[] settingsKeycodes = new int[0];
    private boolean settingsPanel;
    /** app_keys, the same map InputKeyService reads, for setups that run only this service. */
    private Map<Integer, String> appKeys = Collections.emptyMap();
    private Map<Integer, String> appScancodes = Collections.emptyMap();
    /** take_over_home: the stock launcher's home activity that Android dispatches Home to; null while off. */
    private ComponentName stockHome;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable coverStockHome = this::coverStockHome;
    private boolean coverPending;
    private long coverWindowStart;
    private int coverCount;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        // Keycodes and the panel choice can also come from a theme; theme.sh restarts this service.
        Config config = Config.load(this);
        settingsKeycodes = config.settingsKeycodes;
        settingsPanel = config.settingsKeyPanel;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
        appScancodes = RemoteKeys.parseAppScancodes(config.appKeys);
        if (getResources().getBoolean(R.bool.take_over_home)) watchStockHome();
    }

    private void watchStockHome() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return; // no HOME role to check
        ResolveInfo home = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        if (home == null || home.activityInfo == null) return;
        String pkg = home.activityInfo.packageName;
        // Already Home, the chooser, or setupwraith's RecoveryActivity: covering that one is the Home
        // transition that crashes system_server, and build-and-install.sh enables the stock launcher first.
        if (pkg.equals(getPackageName()) || pkg.equals("android") || pkg.equals(SETUP_WIZARD)) return;
        stockHome = new ComponentName(pkg, home.activityInfo.name);
        AccessibilityServiceInfo info = getServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        setServiceInfo(info);
        // Later (a launcher update or crash) the user may be in the middle of an app, so only at start-up.
        if (SystemClock.elapsedRealtime() < BOOT_COVER_MS && holdsHomeRole()) showLauncherOverStock();
    }

    /** set-home-activity gives this app the HOME role on Android 10+, and tools/restore.sh takes it back. */
    private boolean holdsHomeRole() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        RoleManager roles = getSystemService(RoleManager.class);
        return roles != null && roles.isRoleHeld(RoleManager.ROLE_HOME);
    }

    private void showLauncher() {
        startActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    /**
     * take_over_home: open this launcher without ACTION_MAIN. With it, Android files the activity in its
     * home task and makes this the home process, and the stock launcher, no longer exempt from Android's
     * background activity start limits, can't open apps for the remote's buttons any more (YouTube,
     * Netflix on a Chromecast). The category still tells MainActivity to go back to the first tile.
     */
    private void showLauncherOverStock() {
        startActivity(new Intent(this, MainActivity.class)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    private void coverStockHome() {
        coverPending = false;
        if (!holdsHomeRole()) return;
        long now = SystemClock.elapsedRealtime();
        if (now - coverWindowStart > MAX_COVERS_WINDOW_MS) { coverWindowStart = now; coverCount = 0; }
        if (++coverCount <= MAX_COVERS) showLauncherOverStock();
    }

    /** Window changes also come from views and dialogs; only those that open a screen cancel a cover. */
    private boolean opensScreen(String pkg, String cls) {
        if (!pkg.equals(stockHome.getPackageName())) return !cls.startsWith("android."); // framework views, dialogs
        try {
            getPackageManager().getActivityInfo(new ComponentName(pkg, cls), 0);
            return true; // another stock-launcher screen, e.g. an account check Settings opened
        } catch (PackageManager.NameNotFoundException e) {
            return false; // one of the stock launcher's own views
        }
    }

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        for (int code : settingsKeycodes) if (event.getKeyCode() == code) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // A scancode entry is more specific than any keycode, so it goes first.
        String scanApp = appScancodes.get(event.getScanCode());
        if (scanApp != null) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) RemoteKeys.launch(this, scanApp);
            return true;
        }
        // The stock launcher handled the remote's Settings button; with it disabled the button does nothing.
        if (isSettingsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                Intent open = settingsPanel ? new Intent(this, QuickSettingsActivity.class)
                        : new Intent(Settings.ACTION_SETTINGS);
                startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return true;
        }
        String app = appKeys.get(event.getKeyCode());
        if (app != null) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) RemoteKeys.launch(this, app);
            return true;
        }
        if (stockHome != null && event.getKeyCode() == KeyEvent.KEYCODE_HOME && holdsHomeRole()) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) showLauncherOverStock();
            return true;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_ALL_APPS) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) showLauncher();
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (stockHome == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName(), cls = event.getClassName();
        if (pkg == null || cls == null) return;
        if (stockHome.getPackageName().contentEquals(pkg) && stockHome.getClassName().contentEquals(cls)) {
            // The stock launcher brings itself to the front again just after a Home intent: wait for it.
            handler.removeCallbacks(coverStockHome);
            handler.postDelayed(coverStockHome, COVER_DELAY_MS);
            coverPending = true;
        } else if (coverPending && opensScreen(pkg.toString(), cls.toString())) {
            handler.removeCallbacks(coverStockHome); // something else opened meanwhile: leave it in front
            coverPending = false;
        }
    }

    @Override
    public void onInterrupt() {
    }
}
