package app.splashskip.android;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final String[] PAGES = {"首页", "应用", "记录"};
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<InstalledApp> installed = new ArrayList<>();
    private final Map<String, InstalledApp> installedByPackage = new LinkedHashMap<>();
    private final DateFormat timeFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
    private final SharedPreferences.OnSharedPreferenceChangeListener settingChanges = (prefs, key) -> {
        if (this.page == 0) refreshHome();
        if (this.page == 2) showPage(2);
        if (this.page == 1 && this.appsAdapter != null) filterApps();
    };
    private final Runnable checkHome = new Runnable() {
        @Override public void run() {
            if (page == 0) refreshHome();
            main.postDelayed(this, 750);
        }
    };

    private SettingsStore settings;
    private FrameLayout content;
    private FrameLayout feedback;
    private final Button[] tabButtons = new Button[PAGES.length];
    private LinearLayout homeBody;
    private AppsAdapter appsAdapter;
    private boolean dark;
    private int paper;
    private int card;
    private int ink;
    private int muted;
    private int accent;
    private int onAccent;
    private int soft;
    private int border;
    private int page = 0;
    private int statusStamp = -1;
    private int historySize = -1;
    private SettingsStore.HistoryItem latestEntry;
    private String query = "";
    private RuleEngine.Mode filter;
    private boolean triedSettings;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new SettingsStore(this);
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        paper = color(dark ? "#121915" : "#F2F5F1");
        card = color(dark ? "#1B251F" : "#FFFFFF");
        ink = color(dark ? "#EAF2EB" : "#182720");
        muted = color(dark ? "#A8BAAE" : "#4F6358");
        accent = color(dark ? "#92E3C4" : "#006B55");
        onAccent = color(dark ? "#0C3024" : "#FFFFFF");
        soft = color(dark ? "#243C31" : "#E1F1E8");
        border = color(dark ? "#3A4B41" : "#D9E5DC");
        if (state != null) {
            page = state.getInt("page");
            query = state.getString("query", "");
            triedSettings = state.getBoolean("triedSettings");
            String savedFilter = state.getString("filter");
            if (savedFilter != null) filter = RuleEngine.Mode.valueOf(savedFilter);
        }
        buildShell();
        showPage(page);
    }

    @Override
    protected void onResume() {
        super.onResume();
        settings.prefs.registerOnSharedPreferenceChangeListener(settingChanges);
        loadApps();
        if (page == 1) filterApps();
        if (page == 2) showPage(2);
        checkHome.run();
    }

    @Override
    protected void onPause() {
        settings.prefs.unregisterOnSharedPreferenceChangeListener(settingChanges);
        main.removeCallbacks(checkHome);
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        out.putInt("page", page);
        out.putString("query", query);
        out.putBoolean("triedSettings", triedSettings);
        if (filter != null) out.putString("filter", filter.name());
        super.onSaveInstanceState(out);
    }

    private void buildShell() {
        LinearLayout shell = column();
        shell.setBackgroundColor(paper);
        content = new FrameLayout(this);
        shell.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        feedback = new FrameLayout(this);
        feedback.setVisibility(View.GONE);
        shell.addView(feedback, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout nav = row();
        nav.setBackgroundColor(card);
        nav.setPadding(dp(12), dp(5), dp(12), dp(5));
        for (int i = 0; i < PAGES.length; i++) {
            int tab = i;
            Button item = button(PAGES[i], false);
            item.setTextSize(14);
            item.setOnClickListener(view -> {
                if (page != tab) {
                    hideKeyboard();
                    showPage(tab);
                }
            });
            tabButtons[i] = item;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(49), 1);
            params.setMargins(dp(4), 0, dp(4), 0);
            nav.addView(item, params);
        }
        shell.addView(nav, new LinearLayout.LayoutParams(-1, -2));
        setContentView(shell);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            shell.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets keyboard = windowInsets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
                return windowInsets;
            });
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(dark ? 0 : light, light);
            }
        } else {
            getWindow().setStatusBarColor(paper);
            getWindow().setNavigationBarColor(card);
            getWindow().getDecorView().setSystemUiVisibility(dark ? 0
                    : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    private void showPage(int index) {
        if (index < 0 || index >= PAGES.length) index = 0;
        page = index;
        content.removeAllViews();
        appsAdapter = null;
        homeBody = null;
        for (int i = 0; i < PAGES.length; i++) {
            tabButtons[i].setSelected(i == page);
            tabButtons[i].setTextColor(i == page ? accent : muted);
            tabButtons[i].setBackground(ripple(i == page ? soft : card, 20, false));
        }
        if (page == 0) {
            homeBody = scrollBody();
            statusStamp = -1;
            refreshHome();
        } else if (page == 1) {
            buildAppsPage();
        } else {
            buildHistoryPage();
        }
    }

    private void refreshHome() {
        if (homeBody == null) return;
        boolean authorized = authorized();
        boolean connected = SkipService.connected();
        boolean enabled = settings.enabled();
        int stamp = (authorized ? 1 : 0) | (connected ? 2 : 0) | (enabled ? 4 : 0) | (triedSettings ? 8 : 0);
        List<SettingsStore.HistoryItem> entries = settings.history();
        SettingsStore.HistoryItem newest = entries.isEmpty() ? null : entries.get(0);
        boolean sameEntry = latestEntry == null ? newest == null : newest != null
                && latestEntry.time == newest.time && latestEntry.detail.equals(newest.detail);
        if (stamp == statusStamp && historySize == entries.size() && sameEntry) return;
        statusStamp = stamp;
        historySize = entries.size();
        latestEntry = newest;
        homeBody.removeAllViews();
        heading(homeBody, "开屏跳过");

        LinearLayout status = panel(20);
        LinearLayout intro = row();
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_launcher);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(45), dp(45));
        iconParams.setMarginEnd(dp(14));
        intro.addView(icon, iconParams);
        LinearLayout words = column();
        String title;
        String helper;
        if (!authorized) {
            title = "未授权";
            helper = "允许访问无障碍后，才能识别开屏按钮并代为点击。界面识别和触发记录都留在本机。";
        } else if (!enabled) {
            title = "已暂停";
            helper = "恢复后，在下一次打开应用时继续按规则识别。";
        } else if (connected) {
            title = "运行中";
            helper = "按应用名单处理开屏广告。";
        } else {
            title = "服务未连接";
            helper = "已授权，但系统还未连接服务。可在无障碍设置里重新开启本服务。";
        }
        words.addView(text(title, 23, ink, true));
        addText(words, helper, 14, muted, 5);
        intro.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        status.addView(intro);
        Button action = button(!authorized || !connected ? "打开无障碍设置"
                : !enabled ? "恢复跳过" : "选择一个 App 试用", true);
        action.setOnClickListener(view -> {
            if (!authorized || !connected) openAccessibilitySettings();
            else if (!enabled) settings.setEnabled(true);
            else showPage(1);
        });
        add(status, action, 18);

        if (authorized) {
            Switch toggle = new Switch(this);
            toggle.setText("自动跳过");
            toggle.setTextColor(ink);
            toggle.setTextSize(15);
            toggle.setMinHeight(dp(48));
            toggle.setChecked(enabled);
            toggle.setOnCheckedChangeListener((button, checked) -> settings.setEnabled(checked));
            add(status, toggle, 8);
        } else if (triedSettings && Build.VERSION.SDK_INT >= 33) {
            Button blocked = button("无障碍开关是灰色？", false);
            blocked.setOnClickListener(view -> showRestrictedHelp());
            add(status, blocked, 8);
        }
        add(homeBody, status, 16);

        addText(homeBody, "最近一次", 17, ink, 23);
        LinearLayout recent = panel(16);
        if (entries.isEmpty()) {
            addText(recent, "暂无记录", 15, muted, 0);
        } else {
            SettingsStore.HistoryItem newestEntry = entries.get(0);
            recent.addView(text(appName(newestEntry.packageName), 16, ink, true));
            addText(recent, newestEntry.detail, 14, muted, 5);
            Button records = button("查看记录", false);
            records.setOnClickListener(view -> showPage(2));
            add(recent, records, 10);
        }
        add(homeBody, recent, 9);

        if (connected) {
            Button tile = button("添加快捷暂停开关", false);
            tile.setOnClickListener(view -> requestTile());
            add(homeBody, tile, 14);
        }
    }

    private void buildAppsPage() {
        LinearLayout layout = column();
        layout.setPadding(dp(20), dp(20), dp(20), dp(8));
        layout.setFocusableInTouchMode(true);
        heading(layout, "应用");
        addText(layout, "为每个 App 选择跳过方式。", 14, muted, 5);
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(16);
        search.setTextColor(ink);
        search.setHintTextColor(muted);
        search.setHint("搜索应用");
        search.setContentDescription("搜索应用");
        search.setBackground(shape(card, 14, border));
        search.setPadding(dp(16), dp(10), dp(16), dp(10));
        search.setMinHeight(dp(50));
        search.setText(query);
        add(layout, search, 17);

        HorizontalScrollView filterScroll = new HorizontalScrollView(this);
        filterScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout filters = row();
        addFilter(filters, "全部", null);
        addFilter(filters, "标准", RuleEngine.Mode.STANDARD);
        addFilter(filters, "加强跳过", RuleEngine.Mode.ENHANCED);
        addFilter(filters, "忽略", RuleEngine.Mode.IGNORE);
        filterScroll.addView(filters);
        add(layout, filterScroll, 9);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setClipToPadding(false);
        list.setPadding(0, dp(4), 0, dp(6));
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        TextView empty = text("没有符合条件的应用", 15, muted, false);
        empty.setGravity(Gravity.CENTER);
        FrameLayout listArea = new FrameLayout(this);
        listArea.addView(list, new FrameLayout.LayoutParams(-1, -1));
        listArea.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        layout.addView(listArea, new LinearLayout.LayoutParams(-1, 0, 1));
        appsAdapter = new AppsAdapter();
        list.setEmptyView(empty);
        list.setAdapter(appsAdapter);
        list.setOnItemClickListener((parent, view, position, id) ->
                showAppSettings(appsAdapter.getItem(position)));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString();
                filterApps();
            }
            @Override public void afterTextChanged(Editable editable) {}
        });
        content.addView(layout, new FrameLayout.LayoutParams(-1, -1));
        layout.requestFocus();
        filterApps();
    }

    private void addFilter(LinearLayout layout, String label, RuleEngine.Mode value) {
        Button chip = button(label, filter == value);
        chip.setTextSize(13);
        chip.setOnClickListener(view -> {
            filter = value;
            hideKeyboard();
            showPage(1);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(42));
        params.setMarginEnd(dp(7));
        layout.addView(chip, params);
    }

    private void filterApps() {
        if (appsAdapter == null) return;
        List<InstalledApp> matches = new ArrayList<>();
        String search = query.toLowerCase(Locale.ROOT).trim();
        for (InstalledApp app : installed) {
            if (filter != null && settings.mode(app.packageName) != filter) continue;
            if (app.label.toLowerCase(Locale.ROOT).contains(search) || app.packageName.contains(search)) matches.add(app);
        }
        appsAdapter.replace(matches);
    }

    private void loadApps() {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        PackageManager manager = getPackageManager();
        installed.clear();
        installedByPackage.clear();
        for (ResolveInfo item : manager.queryIntentActivities(launcher, 0)) {
            String pkg = item.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || installedByPackage.containsKey(pkg)) continue;
            InstalledApp app = new InstalledApp(pkg, item.loadLabel(manager).toString(),
                    new ComponentName(pkg, item.activityInfo.name), item.activityInfo.applicationInfo);
            installedByPackage.put(pkg, app);
            installed.add(app);
        }
        Collator collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE);
        installed.sort((first, second) -> collator.compare(first.label, second.label));
    }

    private void showAppSettings(InstalledApp app) {
        hideKeyboard();
        boolean dedicated = RuleEngine.hasDedicated(app.packageName);
        LinearLayout fields = column();
        fields.setPadding(dp(20), dp(2), dp(20), dp(8));
        addText(fields, app.packageName, 13, muted, 0);
        RadioGroup selection = new RadioGroup(this);
        for (RuleEngine.Mode mode : RuleEngine.Mode.values()) {
            RadioButton radio = new RadioButton(this);
            radio.setId(mode.ordinal() + 1);
            radio.setMinHeight(dp(48));
            String label = mode == RuleEngine.Mode.IGNORE ? "忽略（白名单）"
                    : mode == RuleEngine.Mode.ENHANCED ? "加强跳过（黑名单）" : "标准";
            radio.setText(label);
            radio.setTextColor(ink);
            selection.addView(radio);
        }
        selection.check(settings.mode(app.packageName).ordinal() + 1);
        add(fields, selection, 8);
        TextView description = text("", 14, muted, false);
        add(fields, description, 5);
        CheckBox generic = new CheckBox(this);
        generic.setText("在标准模式下开启通用识别");
        generic.setTextColor(ink);
        generic.setChecked(settings.generic(app.packageName));
        add(fields, generic, 4);
        CheckBox retry = new CheckBox(this);
        retry.setText("专用规则允许补点一次");
        retry.setTextColor(ink);
        retry.setChecked(settings.retry(app.packageName));
        add(fields, retry, 3);

        Runnable explain = () -> {
            RuleEngine.Mode mode = selectedMode(selection);
            description.setText(mode == RuleEngine.Mode.IGNORE ? "跳过此 App 的界面扫描和自动操作。"
                    : mode == RuleEngine.Mode.ENHANCED ? "打开后 8 秒内，直接处理明确的「跳过」按钮，也识别运营推广和摇动广告。"
                    : dedicated ? "优先使用这个 App 的开屏专用规则。"
                    : "这个 App 暂无专用规则，可开启通用识别。");
            generic.setVisibility(mode == RuleEngine.Mode.STANDARD ? View.VISIBLE : View.GONE);
            retry.setVisibility(mode != RuleEngine.Mode.IGNORE && dedicated ? View.VISIBLE : View.GONE);
        };
        selection.setOnCheckedChangeListener((group, checked) -> explain.run());
        explain.run();
        new AlertDialog.Builder(this).setTitle(app.label).setView(fields)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) ->
                        settings.configure(app.packageName, selectedMode(selection), generic.isChecked(), retry.isChecked()))
                .setNeutralButton("保存并试用", (dialog, which) -> {
                    settings.configure(app.packageName, selectedMode(selection), generic.isChecked(), retry.isChecked());
                    launchTrial(app);
                }).show();
    }

    private RuleEngine.Mode selectedMode(RadioGroup group) {
        return RuleEngine.Mode.values()[group.getCheckedRadioButtonId() - 1];
    }

    private void launchTrial(InstalledApp app) {
        if (!SkipService.connected() || !settings.enabled()) {
            showPage(0);
            toast("先在首页启用跳过服务");
            return;
        }
        if (!settings.canScan(app.packageName)) {
            toast("这个 App 当前没有启用跳过");
            return;
        }
        if (!SkipService.beginTrial(app.packageName)) {
            showPage(0);
            toast("服务已断开，请重新连接后再试");
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(app.launcher).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            SkipService.reportTrialLaunchFailed(app.packageName);
            toast("请从桌面打开这个 App");
        }
    }

    private void buildHistoryPage() {
        LinearLayout body = scrollBody();
        heading(body, "记录");
        addText(body, "仅保留最近 60 条开屏处理记录。", 14, muted, 5);
        List<SettingsStore.HistoryItem> entries = settings.history();
        if (entries.isEmpty()) {
            LinearLayout empty = panel(18);
            empty.addView(text("暂无记录", 16, ink, true));
            addText(empty, "启用一个 App 并打开它，有可判断的结果时会显示在这里。", 14, muted, 6);
            add(body, empty, 15);
            return;
        }
        for (SettingsStore.HistoryItem item : entries) {
            LinearLayout record = panel(16);
            record.addView(text(appName(item.packageName), 17, ink, true));
            addText(record, item.detail, 15, ink, 5);
            addText(record, timeFormat.format(new Date(item.time)) + " · " + item.rule, 13, muted, 5);
            RuleEngine.Mode mode = settings.mode(item.packageName);
            if (mode != RuleEngine.Mode.IGNORE && installedByPackage.containsKey(item.packageName)) {
                Button ignore = button("停用这个 App 的跳过", false);
                ignore.setOnClickListener(view -> ignoreApp(item.packageName, mode));
                add(record, ignore, 11);
            }
            add(body, record, 11);
        }
        Button clear = button("清空记录", false);
        clear.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle("清空本机记录？")
                .setNegativeButton("取消", null).setPositiveButton("清空", (dialog, which) -> settings.clearHistory())
                .show());
        add(body, clear, 13);
    }

    private void ignoreApp(String packageName, RuleEngine.Mode previous) {
        boolean generic = settings.generic(packageName);
        boolean retry = settings.retry(packageName);
        settings.ignore(packageName);
        feedback.removeAllViews();
        LinearLayout message = row();
        message.setPadding(dp(16), dp(3), dp(8), dp(3));
        message.setBackgroundColor(soft);
        TextView label = text("已停用 " + appName(packageName) + " 的跳过", 14, ink, false);
        message.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        Button undo = button("撤销", false);
        undo.setOnClickListener(view -> {
            settings.configure(packageName, previous, generic, retry);
            feedback.setVisibility(View.GONE);
        });
        message.addView(undo);
        feedback.addView(message);
        feedback.setVisibility(View.VISIBLE);
        main.postDelayed(() -> {
            if (message.getParent() == feedback) feedback.setVisibility(View.GONE);
        }, 5500);
    }

    private String appName(String packageName) {
        InstalledApp installedApp = installedByPackage.get(packageName);
        return installedApp == null ? packageName : installedApp.label;
    }

    private boolean authorized() {
        AccessibilityManager manager = getSystemService(AccessibilityManager.class);
        if (manager == null) return false;
        for (AccessibilityServiceInfo service : manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            ResolveInfo info = service.getResolveInfo();
            if (info != null && info.serviceInfo != null && getPackageName().equals(info.serviceInfo.packageName)
                    && SkipService.class.getName().equals(info.serviceInfo.name)) return true;
        }
        return false;
    }

    private void openAccessibilitySettings() {
        triedSettings = true;
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (ActivityNotFoundException e) {
            toast("请在系统设置中搜索「无障碍」");
        }
    }

    private void showRestrictedHelp() {
        new AlertDialog.Builder(this).setTitle("系统限制了无障碍设置")
                .setMessage("如果系统提示「受限制的设置」，可以在本应用的系统信息页查看右上角菜单。仅在确认信任这个安装包时，选择「允许受限制的设置」，再回到无障碍页面开启。")
                .setNegativeButton("关闭", null)
                .setPositiveButton("打开应用信息", (dialog, which) -> startActivity(new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null))))
                .show();
    }

    private void requestTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            StatusBarManager manager = getSystemService(StatusBarManager.class);
            if (manager != null) {
                try {
                    manager.requestAddTileService(new ComponentName(this, PauseTile.class), getString(R.string.tile_name),
                            Icon.createWithResource(this, R.drawable.ic_tile), getMainExecutor(),
                            result -> {
                                if (result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                                        && result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) showTileHelp();
                            });
                    return;
                } catch (IllegalArgumentException | SecurityException ignored) {
                    // Some system skins only expose the manual tile editor.
                }
            }
        }
        showTileHelp();
    }

    private void showTileHelp() {
        new AlertDialog.Builder(this).setTitle("添加快捷开关")
                .setMessage("下拉 Android 快捷设置，点编辑，找到「开屏跳过」并拖到常用区。点磁贴即可暂停或恢复。")
                .setPositiveButton("知道了", null).show();
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (focus != null && keyboard != null) keyboard.hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }

    private LinearLayout scrollBody() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = column();
        body.setPadding(dp(20), dp(20), dp(20), dp(22));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        content.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        return body;
    }

    private void heading(LinearLayout parent, String title) {
        parent.addView(text(title, 30, ink, true));
    }

    private LinearLayout row() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.HORIZONTAL);
        result.setGravity(Gravity.CENTER_VERTICAL);
        return result;
    }

    private LinearLayout column() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }

    private LinearLayout panel(int padding) {
        LinearLayout panel = column();
        panel.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        panel.setBackground(shape(card, 21, border));
        return panel;
    }

    private void add(LinearLayout parent, View child, int marginTop) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(marginTop);
        parent.addView(child, params);
    }

    private void addText(LinearLayout parent, String label, int size, int color, int marginTop) {
        add(parent, text(label, size, color, false), marginTop);
    }

    private TextView text(String label, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextColor(color);
        text.setTextSize(size);
        text.setLineSpacing(dp(2), 1);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private Button button(String label, boolean solid) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(solid ? onAccent : accent);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(13), dp(8), dp(13), dp(8));
        button.setStateListAnimator(null);
        button.setBackground(ripple(solid ? accent : soft, 14, false));
        return button;
    }

    private Drawable ripple(int fill, int radius, boolean outlined) {
        int wave = dark ? 0x30FFFFFF : 0x2000624D;
        return new RippleDrawable(ColorStateList.valueOf(wave), shape(fill, radius, outlined ? border : 0), null);
    }

    private GradientDrawable shape(int fill, int radius, int outline) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(fill);
        result.setCornerRadius(dp(radius));
        if (outline != 0) result.setStroke(dp(1), outline);
        return result;
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    private int dp(float amount) { return Math.round(amount * getResources().getDisplayMetrics().density); }

    private static int color(String hex) { return Color.parseColor(hex); }

    private static final class InstalledApp {
        final String packageName;
        final String label;
        final ComponentName launcher;
        final ApplicationInfo info;
        Drawable icon;

        InstalledApp(String packageName, String label, ComponentName launcher, ApplicationInfo info) {
            this.packageName = packageName;
            this.label = label;
            this.launcher = launcher;
            this.info = info;
        }
    }

    private final class AppsAdapter extends BaseAdapter {
        private List<InstalledApp> apps = new ArrayList<>();

        void replace(List<InstalledApp> apps) {
            this.apps = apps;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return apps.size(); }
        @Override public InstalledApp getItem(int position) { return apps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            AppRow holder;
            if (recycled == null) {
                holder = new AppRow();
                recycled = holder.container;
                recycled.setTag(holder);
            } else {
                holder = (AppRow) recycled.getTag();
            }
            InstalledApp app = getItem(position);
            RuleEngine.Mode mode = settings.mode(app.packageName);
            holder.title.setText(app.label);
            holder.subtitle.setText(mode == RuleEngine.Mode.IGNORE ? "这个 App 已停用"
                    : RuleEngine.hasDedicated(app.packageName) ? "有专用规则"
                    : settings.canScan(app.packageName) ? "通用识别已启用" : "暂无启用的规则");
            holder.mode.setText(mode.title);
            if (app.icon == null) app.icon = app.info.loadIcon(getPackageManager());
            holder.icon.setImageDrawable(app.icon);
            return recycled;
        }
    }

    private final class AppRow {
        final FrameLayout container = new FrameLayout(MainActivity.this);
        final TextView title;
        final TextView subtitle;
        final TextView mode;
        final ImageView icon = new ImageView(MainActivity.this);

        AppRow() {
            LinearLayout row = row();
            row.setBackground(ripple(card, 16, true));
            row.setMinimumHeight(dp(66));
            row.setPadding(dp(11), dp(10), dp(11), dp(10));
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(40), dp(40));
            iconParams.setMarginEnd(dp(11));
            row.addView(icon, iconParams);
            LinearLayout words = column();
            title = text("", 16, ink, true);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            subtitle = text("", 13, muted, false);
            words.addView(title);
            words.addView(subtitle);
            row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            mode = text("", 13, accent, true);
            mode.setPadding(dp(9), dp(5), dp(9), dp(5));
            mode.setBackground(shape(soft, 13, 0));
            row.addView(mode);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(7);
            container.addView(row, params);
        }
    }
}
