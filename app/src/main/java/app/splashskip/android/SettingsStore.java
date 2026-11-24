package app.splashskip.android;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class SettingsStore {
    static final String ENABLED = "enabled";
    static final String HISTORY = "history";
    static final String TRIAL_TITLE = "试用诊断";
    static final String TRIAL_STARTED = "试用已启动，正在等待系统界面事件";
    static final long TRIAL_TIMEOUT_MS = 12000;
    private static final String MODE = "mode:";
    private static final String GENERIC = "generic:";
    private static final String RETRY = "retry:";
    private static final int MAX_HISTORY = 60;
    final SharedPreferences prefs;

    SettingsStore(Context context) {
        prefs = context.getSharedPreferences("splash_skip", Context.MODE_PRIVATE);
    }

    boolean enabled() { return prefs.getBoolean(ENABLED, true); }

    void setEnabled(boolean enabled) { prefs.edit().putBoolean(ENABLED, enabled).apply(); }

    RuleEngine.Mode mode(String packageName) {
        String value = prefs.getString(MODE + packageName, RuleEngine.Mode.STANDARD.name());
        try {
            return RuleEngine.Mode.valueOf(value);
        } catch (IllegalArgumentException e) {
            return RuleEngine.Mode.STANDARD;
        }
    }

    boolean generic(String packageName) { return prefs.getBoolean(GENERIC + packageName, false); }

    boolean retry(String packageName) { return prefs.getBoolean(RETRY + packageName, false); }

    void configure(String packageName, RuleEngine.Mode mode, boolean generic, boolean retry) {
        prefs.edit().putString(MODE + packageName, mode.name())
                .putBoolean(GENERIC + packageName, generic)
                .putBoolean(RETRY + packageName, retry).apply();
    }

    void ignore(String packageName) { prefs.edit().putString(MODE + packageName, RuleEngine.Mode.IGNORE.name()).apply(); }

    static boolean affectsEngine(String key) {
        return key == null || key.equals(ENABLED) || key.startsWith(MODE)
                || key.startsWith(GENERIC) || key.startsWith(RETRY);
    }

    boolean canScan(String packageName) {
        RuleEngine.Mode mode = mode(packageName);
        return mode != RuleEngine.Mode.IGNORE
                && (mode == RuleEngine.Mode.ENHANCED || generic(packageName) || RuleEngine.hasDedicated(packageName));
    }

    void record(String packageName, String title, String detail) {
        recordAt(packageName, title, detail, System.currentTimeMillis(), false);
    }

    void recordTrial(String packageName, String detail, long startedAt) {
        recordAt(packageName, TRIAL_TITLE, detail, startedAt, true);
    }

    private void recordAt(String packageName, String title, String detail, long time, boolean replaceTrial) {
        JSONArray previous = readHistory();
        JSONArray entries = new JSONArray();
        try {
            JSONObject item = new JSONObject();
            item.put("time", time);
            item.put("package", packageName);
            item.put("rule", title);
            item.put("detail", detail);
            entries.put(item);
        } catch (JSONException e) {
            return;
        }
        for (int i = 0; i < previous.length() && entries.length() < MAX_HISTORY; i++) {
            JSONObject old = previous.optJSONObject(i);
            if (old == null) continue;
            if (replaceTrial && old.optLong("time") == time && packageName.equals(old.optString("package"))
                    && TRIAL_TITLE.equals(old.optString("rule"))) continue;
            entries.put(old);
        }
        prefs.edit().putString(HISTORY, entries.toString()).apply();
    }

    List<HistoryItem> history() {
        JSONArray stored = readHistory();
        List<HistoryItem> result = new ArrayList<>();
        for (int i = 0; i < stored.length(); i++) {
            JSONObject item = stored.optJSONObject(i);
            if (item != null) {
                long time = item.optLong("time");
                String detail = item.optString("detail");
                if (TRIAL_STARTED.equals(detail) && System.currentTimeMillis() - time > TRIAL_TIMEOUT_MS + 1000) {
                    detail = "试用中断，服务未写入结果";
                }
                result.add(new HistoryItem(time, item.optString("package"), item.optString("rule"), detail));
            }
        }
        return result;
    }

    void clearHistory() { prefs.edit().remove(HISTORY).apply(); }

    private JSONArray readHistory() {
        try {
            return new JSONArray(prefs.getString(HISTORY, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    static final class HistoryItem {
        final long time;
        final String packageName;
        final String rule;
        final String detail;

        HistoryItem(long time, String packageName, String rule, String detail) {
            this.time = time;
            this.packageName = packageName;
            this.rule = rule;
            this.detail = detail;
        }
    }
}
