package app.splashskip.android;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.app.KeyguardManager;
import android.service.quicksettings.TileService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SkipService extends AccessibilityService {
    private static final long OPEN_WINDOW_MS = 8000;
    private static final long SCAN_DELAY_MS = 65;
    private static final long WINDOW_PROBE_MS = 105;
    private static final long WINDOW_SETTLE_MS = 1150;
    private static final long VERIFY_CLICK_DELAY_MS = 55;
    private static final long RETRY_DELAY_MS = 550;
    private static final long OUTCOME_WINDOW_MS = 1600;
    private static volatile SkipService live;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable scan = this::scanCurrentWindow;
    private final Runnable deadline = this::openingExpired;
    private final Runnable checkOutcome = this::verifyOutcome;
    private final Runnable probeWindow = this::probeActiveWindow;
    private final Runnable expireTrial = () -> completeTrial("");
    private final Set<String> homePackages = new HashSet<>();
    private final SharedPreferences.OnSharedPreferenceChangeListener settingChanges = (prefs, key) -> {
        if (SettingsStore.affectsEngine(key)) {
            if (this.trial != null && (!this.settings.enabled() || !this.settings.canScan(this.trial.packageName))) {
                this.trial.note(6, "设置已变更，已停止试用");
            }
            endSession("设置已变更，已停止本次处理");
            if (this.trial != null && (!this.settings.enabled() || !this.settings.canScan(this.trial.packageName))) {
                completeTrial("设置已变更，已停止试用");
            }
            updateTile();
        }
    };

    private SettingsStore settings;
    private Session session;
    private String foreground = "";
    private int currentWindow = -1;
    private int windowSequence;
    private String activity = "";
    private ActivityHint activityHint;
    private Trial trial;
    private long probeUntil;
    private boolean probeQueued;
    private boolean scanQueued;
    private boolean listening;

    static boolean connected() { return live != null; }

    static boolean beginTrial(String packageName) {
        SkipService service = live;
        if (service == null) return false;
        service.completeTrial("已开始另一次试用");
        service.endSession("已开始另一次试用");
        service.foreground = "";
        service.currentWindow = -1;
        service.activity = "";
        service.activityHint = null;
        service.main.removeCallbacks(service.probeWindow);
        service.probeQueued = false;
        service.probeUntil = 0;
        Trial started = new Trial(packageName);
        service.trial = started;
        service.settings.recordTrial(packageName, SettingsStore.TRIAL_STARTED, started.wallTime);
        service.main.postDelayed(service.expireTrial, SettingsStore.TRIAL_TIMEOUT_MS);
        service.retryWindowDiscovery();
        return true;
    }

    static void reportTrialLaunchFailed(String packageName) {
        SkipService service = live;
        if (service != null && service.isTrial(packageName)) {
            service.completeTrial("系统未能从试用入口打开这个 App");
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        settings = new SettingsStore(this);
        if (!listening) {
            settings.prefs.registerOnSharedPreferenceChangeListener(settingChanges);
            listening = true;
        }
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo item : getPackageManager().queryIntentActivities(home, 0)) {
            homePackages.add(item.activityInfo.packageName);
        }
        live = this;
        updateTile();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (settings == null || !settings.enabled()) return;
        int type = event.getEventType();
        String eventPackage = safe(event.getPackageName());
        boolean enabledAppEvent = isTargetApp(eventPackage) && settings.canScan(eventPackage);
        if (trial != null) {
            trial.anyEvent = true;
            if (isTrial(eventPackage)) trial.appEvent = true;
        }
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (session != null && (eventPackage.isEmpty() || session.packageName.equals(eventPackage))) queueScan();
            if (enabledAppEvent && !foreground.equals(eventPackage)) retryWindowDiscovery();
            return;
        }
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && enabledAppEvent) {
            String name = activityName(eventPackage, safe(event.getClassName()));
            if (!name.isEmpty()) activityHint = new ActivityHint(eventPackage, event.getWindowId(), name);
        }
        try (Frame frame = activeFrame()) {
            if (frame == null) {
                if (session != null && session.readable) endSession("窗口已切换，结果未确认");
                if (trial != null || enabledAppEvent || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) retryWindowDiscovery();
                return;
            }
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && session != null
                    && session.click == null && event.getEventTime() >= session.startedAt
                    && frame.packageName.equals(session.packageName) && frame.id != event.getWindowId()) {
                if (!eventPackage.isEmpty() && !eventPackage.equals(frame.packageName)
                        && !activityName(eventPackage, safe(event.getClassName())).isEmpty()) {
                    endSession("窗口已切换，结果未确认");
                }
            }
            observe(frame);
            if ((enabledAppEvent && !eventPackage.equals(frame.packageName))
                    || (session != null && frame.blocked) || (trial != null && !isTrial(frame.packageName))) {
                retryWindowDiscovery();
            }
            if (type == AccessibilityEvent.TYPE_VIEW_CLICKED && session != null
                    && session.click == null && frame.id == event.getWindowId()) {
                endSession("用户已操作");
            }
            queueScan();
        }
    }

    @Override
    public void onInterrupt() {
        endSession("系统中断，结果未确认");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        disconnect();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        disconnect();
        super.onDestroy();
    }

    private void disconnect() {
        if (trial != null) trial.note(6, "无障碍服务在试用期间断开");
        endSession("无障碍服务在试用期间断开，结果未确认");
        completeTrial("无障碍服务在试用期间断开");
        foreground = "";
        currentWindow = -1;
        activity = "";
        activityHint = null;
        probeUntil = 0;
        probeQueued = false;
        main.removeCallbacksAndMessages(null);
        if (settings != null && listening) {
            settings.prefs.unregisterOnSharedPreferenceChangeListener(settingChanges);
            listening = false;
        }
        if (live == this) live = null;
        updateTile();
    }

    private void updateTile() {
        try {
            TileService.requestListeningState(this, new ComponentName(this, PauseTile.class));
        } catch (IllegalArgumentException | SecurityException ignored) {
            // The optional tile is unavailable for some managed profiles.
        }
    }

    private void observe(Frame frame) {
        if (frame.packageName.isEmpty()) return;
        if (isTrial(frame.packageName)) {
            trial.appEvent = true;
            if (frame.blocked) trial.note(1, "系统窗口暂不可读，或目标界面被系统窗口遮住");
        }
        if (!foreground.equals(frame.packageName)) {
            endSession("点击后已切换到其他应用");
            foreground = frame.packageName;
            currentWindow = -1;
            activity = "";
            if (isTargetApp(foreground) && settings.canScan(foreground)) {
                session = new Session(foreground);
                main.postDelayed(deadline, OPEN_WINDOW_MS);
            }
        }
        if (currentWindow != frame.id) {
            currentWindow = frame.id;
            windowSequence++;
            activity = "";
            if (session != null) session.queuedClick = false;
        }
        if (activityHint != null && frame.id == activityHint.windowId && foreground.equals(activityHint.packageName)
                && SystemClock.uptimeMillis() - activityHint.observedAt <= OPEN_WINDOW_MS) {
            activity = activityHint.name;
        }
        if (frame.blocked && session != null && session.readable) endSession("当前窗口已被打断，结果未确认");
    }

    private void retryWindowDiscovery() {
        if (probeQueued || !settings.enabled()) return;
        if (probeUntil == 0) probeUntil = SystemClock.uptimeMillis() + WINDOW_SETTLE_MS;
        if (SystemClock.uptimeMillis() >= probeUntil) {
            probeUntil = 0;
            return;
        }
        probeQueued = true;
        main.postDelayed(probeWindow, WINDOW_PROBE_MS);
    }

    private void probeActiveWindow() {
        probeQueued = false;
        if (!settings.enabled()) {
            probeUntil = 0;
            return;
        }
        try (Frame frame = activeFrame()) {
            if (frame != null) {
                observe(frame);
                if (!frame.blocked && session != null) {
                    probeUntil = 0;
                    queueScan();
                    return;
                }
            }
        }
        retryWindowDiscovery();
    }

    private String activityName(String packageName, String className) {
        if (className.isEmpty() || className.startsWith("android.widget.") || className.startsWith("android.view.")) return "";
        if (className.equals(activity) && packageName.equals(foreground)) return className;
        try {
            getPackageManager().getActivityInfo(new ComponentName(packageName, className), 0);
            return className;
        } catch (PackageManager.NameNotFoundException e) {
            return className.endsWith("Activity") && className.startsWith(packageName + ".") ? className : "";
        }
    }

    private boolean isTargetApp(String packageName) {
        return !packageName.isEmpty() && !packageName.equals(getPackageName())
                && !packageName.equals("android") && !packageName.equals("com.android.settings")
                && !packageName.equals("com.android.systemui") && !packageName.startsWith("com.android.permissioncontroller")
                && !packageName.startsWith("com.google.android.permissioncontroller")
                && !homePackages.contains(packageName);
    }

    private void queueScan() {
        if (session == null || session.finished || session.click != null || session.queuedClick || scanQueued) return;
        scanQueued = true;
        main.postDelayed(scan, SCAN_DELAY_MS);
    }

    private void scanCurrentWindow() {
        scanQueued = false;
        Session current = session;
        if (!eligible(current) || current.click != null) return;
        try (Frame frame = activeFrame()) {
            if (frame != null && (!frame.packageName.equals(current.packageName) || frame.id != currentWindow)) {
                observe(frame);
                if (session != current) {
                    queueScan();
                    return;
                }
            }
            if (!sameSessionWindow(current, frame)) {
                if (current.readable) endSession("窗口已切换，结果未确认");
                else if (SystemClock.uptimeMillis() - current.startedAt < WINDOW_SETTLE_MS) retryWindowDiscovery();
                return;
            }
            try (UiTree tree = new UiTree(frame.root)) {
                current.readable |= tree.items.size() > 1;
                RuleEngine.Decision hit = decide(current, tree, frame);
                noteScan(current, tree, hit, frame);
                if (hit.target == null || !frame.permits(hit.target.box)) {
                    if (tree.items.size() <= 1 && SystemClock.uptimeMillis() - current.startedAt < WINDOW_SETTLE_MS) {
                        retryWindowDiscovery();
                    }
                    return;
                }
                current.queuedClick = true;
                int sequence = windowSequence;
                main.postDelayed(() -> clickPrepared(current, sequence, hit), VERIFY_CLICK_DELAY_MS);
            }
        }
    }

    private void noteScan(Session current, UiTree tree, RuleEngine.Decision hit, Frame frame) {
        if (RuleEngine.AMBIGUOUS.equals(hit.reason) || RuleEngine.NO_CONTROL.equals(hit.reason)) current.reason = hit.reason;
        if (!isTrial(current.packageName)) return;
        if (!tree.complete) trial.note(2, "已读取窗口，控件树过大，本次跳过扫描");
        else if (tree.items.size() <= 1) trial.note(2, "已读取窗口，系统没有提供可识别的界面控件");
        else if (hit.target != null) {
            trial.note(5, frame.permits(hit.target.box) ? "识别到关闭按钮，点击前窗口或按钮已变化"
                    : "识别到关闭按钮，但按钮被其他窗口遮挡");
        } else if (!hit.reason.isEmpty()) trial.note(4, hit.reason);
        else trial.note(3, "已扫描界面，未找到符合规则的开屏按钮");
    }

    private RuleEngine.Decision decide(Session current, UiTree tree, Frame frame) {
        if (!tree.complete) return new RuleEngine.Decision(null, null, "", "", false, false, false);
        return RuleEngine.decide(current.packageName, activity, tree.items, frame.box,
                settings.mode(current.packageName), settings.generic(current.packageName));
    }

    private boolean eligible(Session current) {
        return current != null && current == session && !current.finished && settings.enabled()
                && settings.canScan(current.packageName) && SystemClock.uptimeMillis() <= current.expires;
    }

    private boolean sameSessionWindow(Session current, Frame frame) {
        return frame != null && !frame.blocked && frame.id == currentWindow
                && current.packageName.equals(frame.packageName);
    }

    private void clickPrepared(Session current, int expectedSequence, RuleEngine.Decision initial) {
        if (!eligible(current) || current.click != null || !current.queuedClick
                || windowSequence != expectedSequence) return;
        current.queuedClick = false;
        try (Frame frame = activeFrame()) {
            if (!sameSessionWindow(current, frame)) {
                endSession("窗口已切换，结果未确认");
                return;
            }
            try (UiTree tree = new UiTree(frame.root)) {
                RuleEngine.Decision hit = decide(current, tree, frame);
                if (!initial.sameTarget(hit) || !frame.permits(hit.target.box)) {
                    noteScan(current, tree, hit, frame);
                    if (hit.target != null) queueScan();
                    return;
                }
                AccessibilityNodeInfo button = tree.handle(hit.target);
                if (!confirmCurrentTarget(current, hit, button)) return;
                current.click = hit;
                current.clickWindow = frame.id;
                current.clickActivity = activity;
                current.clickAt = SystemClock.uptimeMillis();
                current.attempts = 1;
                current.actionAccepted = hit.touchLabel ? tapCenter(hit.target.box)
                        : button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                main.postDelayed(checkOutcome, 260);
            }
        }
    }

    private boolean confirmCurrentTarget(Session current, RuleEngine.Decision hit, AccessibilityNodeInfo button) {
        try (Frame latest = activeFrame()) {
            if (!sameSessionWindow(current, latest) || !eligible(current)) {
                endSession("窗口已切换，结果未确认");
                return false;
            }
            if (!button.refresh() || !button.isVisibleToUser() || !button.isEnabled()
                    || (!hit.touchLabel && !hasClickAction(button))
                    || button.getWindowId() != latest.id || !current.packageName.contentEquals(safe(button.getPackageName()))) {
                return false;
            }
            if (hit.touchLabel && !hasFreshSkipLabel(button)) {
                queueScan();
                return false;
            }
            Rect freshBounds = new Rect();
            button.getBoundsInScreen(freshBounds);
            if (freshBounds.left != hit.target.box.left || freshBounds.top != hit.target.box.top
                    || freshBounds.right != hit.target.box.right || freshBounds.bottom != hit.target.box.bottom) {
                if (current.click == null) queueScan();
                return false;
            }
            return latest.permits(box(freshBounds));
        }
    }

    private void verifyOutcome() {
        Session current = session;
        if (current == null || current.click == null || !settings.enabled() || !settings.canScan(current.packageName)) return;
        long now = SystemClock.uptimeMillis();
        try (Frame frame = activeFrame()) {
            if (frame == null || frame.blocked) {
                endSession("窗口已中断，结果未确认");
                return;
            }
            if (!current.packageName.equals(frame.packageName)) {
                endSession("点击后已切换到其他应用");
                return;
            }
            try (UiTree tree = new UiTree(frame.root)) {
                RuleEngine.Decision currentHit = decide(current, tree, frame);
                boolean wholeLayerStillActive = current.click.wholeWindow && current.clickWindow == frame.id
                        && current.clickActivity.equals(activity);
                boolean stillPresent = wholeLayerStillActive ||
                        RuleEngine.layerPresent(current.click, tree.items, frame.box);
                if (tree.complete && tree.items.size() > 2 && !stillPresent) {
                    if (++current.missingChecks >= 2) {
                        finish(current, "点击后广告层消失");
                        return;
                    }
                } else {
                    current.missingChecks = 0;
                }
                if (eligible(current) && current.click.dedicated && current.attempts == 1
                        && settings.retry(current.packageName) && current.clickWindow == frame.id
                        && now - current.clickAt >= RETRY_DELAY_MS && current.click.sameTarget(currentHit)
                        && frame.permits(currentHit.target.box)
                        && confirmCurrentTarget(current, currentHit, tree.handle(currentHit.target))) {
                    current.attempts++;
                    current.actionAccepted |= tapCenter(currentHit.target.box);
                }
                if (now - current.clickAt >= OUTCOME_WINDOW_MS) {
                    finish(current, stillPresent ? (current.actionAccepted ? "点击后广告仍在" : "系统未接受点击，广告仍在")
                            : "点击已发起，结果未确认");
                    return;
                }
            }
        }
        if (session == current) main.postDelayed(checkOutcome, 240);
    }

    private boolean tapCenter(RuleEngine.Box box) {
        Path path = new Path();
        path.moveTo(box.centerX(), box.centerY());
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 40)).build();
        return dispatchGesture(gesture, null, null);
    }

    private void openingExpired() {
        if (session != null && session.click == null) finish(session, session.reason);
    }

    private void endSession(String outcome) {
        Session old = session;
        if (old != null && !old.finished) {
            finish(old, old.click != null ? outcome : old.reason);
        }
        session = null;
        scanQueued = false;
        main.removeCallbacks(scan);
        main.removeCallbacks(deadline);
        main.removeCallbacks(checkOutcome);
    }

    private void finish(Session current, String outcome) {
        if (current.finished) return;
        current.finished = true;
        if (isTrial(current.packageName) && current.startedAt >= trial.startedAt) {
            completeTrial(outcome);
        } else if (!outcome.isEmpty()) {
            settings.record(current.packageName, current.click == null ? "开屏识别" : current.click.title, outcome);
        }
    }

    private boolean isTrial(String packageName) {
        return trial != null && trial.packageName.equals(packageName);
    }

    private void completeTrial(String detail) {
        if (trial == null) return;
        Trial completed = trial;
        trial = null;
        main.removeCallbacks(expireTrial);
        settings.recordTrial(completed.packageName, detail.isEmpty() ? completed.summary() : detail, completed.wallTime);
    }

    private Frame activeFrame() {
        PowerManager power = getSystemService(PowerManager.class);
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if ((power != null && !power.isInteractive()) || (keyguard != null && keyguard.isKeyguardLocked())) return null;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        List<AccessibilityWindowInfo> windows = getWindows();
        Rect bounds = new Rect();
        List<Region> covers = new ArrayList<>();
        AccessibilityWindowInfo active = null;
        boolean blocked = false;
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (window.getId() == root.getWindowId()) active = window;
                if (window.getType() == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER
                        || window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                        || window.isInPictureInPictureMode()) blocked = true;
            }
            if (active == null || active.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) blocked = true;
            if (active != null) {
                active.getBoundsInScreen(bounds);
                for (AccessibilityWindowInfo window : windows) {
                    if (window.getId() == active.getId() || window.getLayer() <= active.getLayer()) continue;
                    if (window.isFocused() || window.isActive()
                            || window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) blocked = true;
                    Region cover = new Region();
                    if (Build.VERSION.SDK_INT >= 30) {
                        window.getRegionInScreen(cover);
                    } else {
                        Rect coverBounds = new Rect();
                        window.getBoundsInScreen(coverBounds);
                        cover.set(coverBounds);
                    }
                    if (!cover.isEmpty()) covers.add(cover);
                }
            }
            if (bounds.isEmpty()) root.getBoundsInScreen(bounds);
            return new Frame(root, safe(root.getPackageName()), root.getWindowId(), box(bounds),
                    blocked || bounds.isEmpty(), covers);
        } finally {
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
    }

    private static RuleEngine.Box box(Rect rectangle) {
        return new RuleEngine.Box(rectangle.left, rectangle.top, rectangle.right, rectangle.bottom);
    }

    private static String safe(CharSequence chars) { return chars == null ? "" : chars.toString(); }

    private static boolean hasClickAction(AccessibilityNodeInfo node) {
        return node.isClickable() || (node.getActions() & AccessibilityNodeInfo.ACTION_CLICK) != 0;
    }

    private static boolean hasFreshSkipLabel(AccessibilityNodeInfo node) {
        String text = safe(node.getText());
        String description = safe(node.getContentDescription());
        if (RuleEngine.matchesTapLabel(text, description, "")) return true;
        int count = node.getChildCount();
        if (count == 0 || count > 3) return false;
        StringBuilder labels = new StringBuilder();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                if (child.isVisibleToUser() && child.getChildCount() == 0) labels.append(safe(child.getText()));
            } finally {
                child.recycle();
            }
        }
        return RuleEngine.matchesTapLabel(text, description, labels.toString());
    }

    private static final class Session {
        final String packageName;
        final long startedAt = SystemClock.uptimeMillis();
        final long expires = startedAt + OPEN_WINDOW_MS;
        boolean finished;
        boolean readable;
        boolean queuedClick;
        String reason = "";
        RuleEngine.Decision click;
        int clickWindow;
        String clickActivity;
        long clickAt;
        int attempts;
        int missingChecks;
        boolean actionAccepted;

        Session(String packageName) { this.packageName = packageName; }
    }

    private static final class ActivityHint {
        final String packageName;
        final int windowId;
        final String name;
        final long observedAt = SystemClock.uptimeMillis();

        ActivityHint(String packageName, int windowId, String name) {
            this.packageName = packageName;
            this.windowId = windowId;
            this.name = name;
        }
    }

    private static final class Trial {
        final String packageName;
        final long startedAt = SystemClock.uptimeMillis();
        final long wallTime = System.currentTimeMillis();
        boolean anyEvent;
        boolean appEvent;
        int stage;
        String detail = "";

        Trial(String packageName) { this.packageName = packageName; }

        void note(int stage, String detail) {
            if (stage < this.stage) return;
            this.stage = stage;
            this.detail = detail;
        }

        String summary() {
            if (!detail.isEmpty()) return detail;
            if (appEvent) return "收到目标 App 的事件，系统未提供可读取的前台窗口";
            if (anyEvent) return "服务收到系统事件，但未识别到此 App 的前台窗口";
            return "试用期间未收到系统界面事件";
        }
    }

    private static final class Frame implements AutoCloseable {
        final AccessibilityNodeInfo root;
        final String packageName;
        final int id;
        final RuleEngine.Box box;
        final boolean blocked;
        final List<Region> covers;

        Frame(AccessibilityNodeInfo root, String packageName, int id, RuleEngine.Box box,
              boolean blocked, List<Region> covers) {
            this.root = root;
            this.packageName = packageName;
            this.id = id;
            this.box = box;
            this.blocked = blocked;
            this.covers = covers;
        }

        boolean permits(RuleEngine.Box target) {
            int x = target.centerX();
            int y = target.centerY();
            if (blocked || !box.contains(x, y)) return false;
            for (Region cover : covers) {
                if (cover.contains(x, y)) return false;
            }
            return true;
        }

        @Override public void close() { root.recycle(); }
    }

    private static final class UiTree implements AutoCloseable {
        private static final int MAX_NODES = 1200;
        private static final int MAX_DEPTH = 32;
        final List<RuleEngine.Node> items = new ArrayList<>();
        private final List<AccessibilityNodeInfo> handles = new ArrayList<>();
        boolean complete = true;

        UiTree(AccessibilityNodeInfo root) {
            Set<AccessibilityNodeInfo> seen = new HashSet<>();
            ArrayDeque<PendingNode> pending = new ArrayDeque<>();
            pending.push(new PendingNode(root, null, 0, 0));
            Rect bounds = new Rect();
            while (!pending.isEmpty()) {
                PendingNode next = pending.pop();
                AccessibilityNodeInfo node = next.node;
                if (!seen.add(node)) {
                    if (node != root) node.recycle();
                    continue;
                }
                if (items.size() >= MAX_NODES || next.depth > MAX_DEPTH) {
                    complete = false;
                    if (node != root) node.recycle();
                    continue;
                }
                node.getBoundsInScreen(bounds);
                RuleEngine.Node entry = new RuleEngine.Node(items.size(), next.position, next.parent,
                        node.getViewIdResourceName(), safe(node.getClassName()), safe(node.getText()),
                        safe(node.getContentDescription()), box(bounds), node.isVisibleToUser(), node.isEnabled(),
                        hasClickAction(node), node.isPassword(), node.isEditable() && node.isFocused());
                items.add(entry);
                handles.add(node);
                int children = node.getChildCount();
                if (node.isVisibleToUser()) {
                    if (items.size() + pending.size() + children > MAX_NODES) {
                        complete = false;
                        continue;
                    }
                    for (int i = children - 1; i >= 0; i--) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) pending.push(new PendingNode(child, entry, i, next.depth + 1));
                    }
                }
            }
        }

        AccessibilityNodeInfo handle(RuleEngine.Node node) { return handles.get(node.index); }

        @Override public void close() {
            for (int i = 1; i < handles.size(); i++) handles.get(i).recycle();
        }
    }

    private static final class PendingNode {
        final AccessibilityNodeInfo node;
        final RuleEngine.Node parent;
        final int position;
        final int depth;

        PendingNode(AccessibilityNodeInfo node, RuleEngine.Node parent, int position, int depth) {
            this.node = node;
            this.parent = parent;
            this.position = position;
            this.depth = depth;
        }
    }
}
