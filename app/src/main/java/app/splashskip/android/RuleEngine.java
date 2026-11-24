package app.splashskip.android;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class RuleEngine {
    enum Mode {
        STANDARD("标准"), IGNORE("忽略"), ENHANCED("加强跳过");

        final String title;

        Mode(String title) {
            this.title = title;
        }
    }

    static final String AMBIGUOUS = "多个关闭按钮无法区分";
    static final String NO_CONTROL = "识别到广告，未找到可操作的关闭按钮";
    static final String SKIP_UNAVAILABLE = "读到「跳过」字样，但未确认可点击的按钮";
    static final String UNCONFIRMED_LAYER = "看到广告或跳过线索，无法确认是开屏广告";
    static final String PROTECTED = "当前界面已放行";
    static final String EMPTY = "";
    private static final int DEDICATED_PRIORITY = 4;

    private static final Pattern SPACING = Pattern.compile("[\\s\\p{Z}·•｜|：:（）()【】\\[\\]]+");
    private static final Pattern SKIP = Pattern.compile(
            "^(?:\\d{1,2}(?:秒|s)?)?(?:跳过广告|跳過廣告|关闭广告|關閉廣告|跳过|跳過|略过|skipad|skip|closead)(?:\\d{1,2}(?:秒|s)?)?$");
    private static final Pattern AD_CONTAINER = Pattern.compile(
            "^(?:splash(?:_?ad)?(?:_?(?:container|view|layout|root|frame|overlay))?|"
                    + "(?:open|launch|startup)_?ad(?:_?(?:container|view|layout|root|frame|overlay))?|"
                    + "(?:tt|gdt|ks|bd|ms|tanx)_?splash(?:_?[a-z0-9]+){0,3})$");
    private static final Pattern SEARCH_OR_FEED = Pattern.compile(
            "(?:recycler|listview|scrollview|viewpager|webview|search_bar|search_edit|search_result|"
                    + "feed_list|bottom_nav|tab_layout|player_controls|playback_controls|video_player)");
    private static final Pattern NON_OPENING_PAGE = Pattern.compile(
            "(?:player|playback|search|onboard|tutorial|guide|intro)");
    private static final List<String> PROTECTED_TEXT = Arrays.asList(
            "确认支付", "支付密码", "请输入密码", "请输入验证码", "短信验证码",
            "授权登录", "允许安装应用", "允许访问", "授予权限", "隐私政策与用户协议",
            "隐私政策和用户协议", "立即更新", "安装此应用", "应用权限");

    // Reference IDs come from public layout snapshots. Keep host and page checks attached to them.
    private static final List<BuiltIn> BUILT_INS = Arrays.asList(
            new BuiltIn("com.jd.app.reader", "京东读书 · 开屏控件", "mjumpbtn", "JdLogoActivity"),
            new BuiltIn("com.smzdm.client.android", "什么值得买 · 开屏控件", "tv_skip", "WelComeActivity"),
            new BuiltIn("net.csdn.csdnplus", "CSDN · 开屏控件", "tt_splash_skip_btn", "SplashActivity"),
            new BuiltIn("net.csdn.csdnplus", "CSDN · 开屏关闭", "vlion_ad_closed", "SplashActivity"),
            new BuiltIn("com.zhihu.android", "知乎 · 开屏控件", "btn_skip", "SplashActivity", "LaunchActivity"),
            new BuiltIn("tv.danmaku.bili", "哔哩哔哩 · 开屏倒计时", "count_down", "MainActivityV2", "HotSplashActivity"),
            new BuiltIn("tv.danmaku.bili", "哔哩哔哩 · 开屏控件", "skip", "HotSplashActivity", "SplashShellActivity"),
            new BuiltIn("com.cainiao.wireless", "菜鸟 · 开屏控件", "ms_skipview", "SplashActivity", "LaunchActivity"),
            new BuiltIn("com.cainiao.wireless", "菜鸟 · 开屏控件", "ms_skipview_container", "SplashActivity", "LaunchActivity")
    );

    static final class Box {
        final int left;
        final int top;
        final int right;
        final int bottom;

        Box(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        int width() { return Math.max(0, right - left); }
        int height() { return Math.max(0, bottom - top); }
        long area() { return (long) width() * height(); }
        int centerX() { return left + width() / 2; }
        int centerY() { return top + height() / 2; }

        boolean contains(int x, int y) {
            return x >= left && x < right && y >= top && y < bottom;
        }
    }

    static final class Node {
        final int index;
        final int position;
        final Node parent;
        final String id;
        final String type;
        final String text;
        final String description;
        final Box box;
        final boolean visible;
        final boolean enabled;
        final boolean clickable;
        final boolean password;
        final boolean editing;
        final List<Node> children = new ArrayList<>();

        Node(int index, int position, Node parent, String id, String type, String text,
             String description, Box box, boolean visible, boolean enabled, boolean clickable,
             boolean password, boolean editing) {
            this.index = index;
            this.position = position;
            this.parent = parent;
            this.id = localId(id);
            this.type = type == null ? "" : type.toLowerCase(Locale.ROOT);
            this.text = normalize(text);
            this.description = normalize(description);
            this.box = box;
            this.visible = visible;
            this.enabled = enabled;
            this.clickable = clickable;
            this.password = password;
            this.editing = editing;
            if (parent != null) parent.children.add(this);
        }

        String identity() {
            if (!id.isEmpty()) return "id:" + id;
            if (parent == null) return "root";
            return parent.identity() + "/" + position + ":" + type;
        }
    }

    static final class Decision {
        final Node target;
        final Node layer;
        final String title;
        final String reason;
        final boolean dedicated;
        final boolean wholeWindow;
        final boolean touchLabel;

        Decision(Node target, Node layer, String title, String reason, boolean dedicated, boolean wholeWindow,
                 boolean touchLabel) {
            this.target = target;
            this.layer = layer;
            this.title = title;
            this.reason = reason;
            this.dedicated = dedicated;
            this.wholeWindow = wholeWindow;
            this.touchLabel = touchLabel;
        }

        boolean sameTarget(Decision other) {
            return target != null && other.target != null
                    && title.equals(other.title)
                    && touchLabel == other.touchLabel
                    && layer.identity().equals(other.layer.identity())
                    && target.identity().equals(other.target.identity());
        }
    }

    private static final class BuiltIn {
        final String packageName;
        final String title;
        final String viewId;
        final String[] pageNames;

        BuiltIn(String packageName, String title, String viewId, String... pageNames) {
            this.packageName = packageName;
            this.title = title;
            this.viewId = viewId;
            this.pageNames = pageNames;
        }

        boolean pageMatches(String activity) {
            for (String name : pageNames) {
                if (activity.endsWith("." + name) || activity.equals(name)) return true;
            }
            return false;
        }
    }

    private static final class Candidate {
        final Node target;
        final Node layer;
        final int priority;
        final String title;
        final boolean wholeWindow;
        final boolean touchLabel;

        Candidate(Node target, Node layer, int priority, String title, boolean wholeWindow, boolean touchLabel) {
            this.target = target;
            this.layer = layer;
            this.priority = priority;
            this.title = title;
            this.wholeWindow = wholeWindow;
            this.touchLabel = touchLabel;
        }
    }

    static boolean hasDedicated(String packageName) {
        for (BuiltIn rule : BUILT_INS) {
            if (rule.packageName.equals(packageName)) return true;
        }
        return false;
    }

    static Decision decide(String packageName, String activity, List<Node> nodes, Box window,
                           Mode mode, boolean generic) {
        if (nodes.isEmpty() || mode == Mode.IGNORE) return idle(EMPTY);
        if (protectedPage(activity, nodes)) return idle(PROTECTED);

        boolean enhanced = mode == Mode.ENHANCED;
        boolean scanGeneric = enhanced || generic;
        boolean openingPage = openingActivity(activity);
        boolean ordinaryPage = !openingPage && nonOpeningPage(activity);
        boolean allowStandaloneSkip = enhanced && !ordinaryPage;
        Node root = nodes.get(0);
        boolean openingAd = openingPage && isAdLayer(root, window, .45) && hasMarker(root);
        boolean adEvidence = openingAd;
        boolean observedContent = false;
        boolean observedButton = false;
        Map<Integer, Candidate> candidates = new LinkedHashMap<>();
        for (Node node : nodes) {
            if (!node.visible) continue;
            boolean marker = isMarker(node.text) || isMarker(node.description);
            boolean inOrdinaryContent = withinFeed(node);
            if (marker && !inOrdinaryContent) observedContent = true;
            if (AD_CONTAINER.matcher(node.id).matches() && isAdLayer(node, window, .30)) adEvidence = true;
            if (!node.enabled || !small(node.box, window)
                    || blockedLabel(node.text) || blockedLabel(node.description)) continue;

            Node layer = namedLayer(node, window);
            if (inOrdinaryContent && !(enhanced && layer != null)) continue;
            for (BuiltIn rule : BUILT_INS) {
                if (!rule.packageName.equals(packageName) || !rule.viewId.equals(node.id)
                        || (!rule.pageMatches(activity) && layer == null && !cainiaoSkipPair(packageName, node))) continue;
                Node ruleLayer = layer != null ? layer : root;
                Node target = clickTarget(node, ruleLayer, window);
                adEvidence = true;
                if (target != null) add(candidates, new Candidate(target, ruleLayer, DEDICATED_PRIORITY, rule.title,
                        ruleLayer == root && openingPage, false));
            }
            if (!scanGeneric) continue;
            String shortLabel = buttonLabel(node);
            boolean skip = SKIP.matcher(shortLabel).matches();
            boolean close = closeLabel(shortLabel) || closeId(node.id);
            if (!skip && !close) continue;
            observedButton |= skip;
            if (layer == null && openingAd) layer = root;
            if (layer == null && enhanced) layer = inferredLayer(node, window);
            boolean supportedLayer = layer != null;
            if (layer == null && allowStandaloneSkip && skip) layer = root;
            if (layer == null || (enhanced && ordinaryPage && layer == root)) continue;
            adEvidence |= supportedLayer;
            if (close && !skip && !hasMarker(layer)) continue;

            Node target = clickTarget(node, layer, window);
            // QQ Music's public splash rules target the visible label with a tap.
            boolean preferLabel = packageName.equals("com.tencent.qqmusic");
            boolean touchLabel = enhanced && skip && (target == null || preferLabel)
                    && canTapSkipLabel(node, layer, window, supportedLayer);
            if (touchLabel) target = node;
            if (target != null) {
                int priority = skip ? 3 : describedClose(node) ? 2 : 1;
                add(candidates, new Candidate(target, layer, priority,
                        enhanced ? "加强跳过" : "通用开屏规则", layer == root && openingPage, touchLabel));
            }
        }

        Candidate best = null;
        boolean tied = false;
        for (Candidate candidate : candidates.values()) {
            if (best == null || candidate.priority > best.priority) {
                best = candidate;
                tied = false;
            } else if (candidate.priority == best.priority) {
                tied = true;
            }
        }
        if (best == null) {
            if (adEvidence && (observedContent || scanGeneric || hasDedicated(packageName))) return idle(NO_CONTROL);
            if (observedButton && allowStandaloneSkip) return idle(SKIP_UNAVAILABLE);
            return idle(scanGeneric && (observedContent || observedButton) ? UNCONFIRMED_LAYER : EMPTY);
        }
        if (tied) return idle(AMBIGUOUS);
        return new Decision(best.target, best.layer, best.title, EMPTY,
                best.priority == DEDICATED_PRIORITY, best.wholeWindow, best.touchLabel);
    }

    private static boolean cainiaoSkipPair(String packageName, Node node) {
        if (!packageName.equals("com.cainiao.wireless")) return false;
        if (node.id.equals("ms_skipview")) {
            return node.parent != null && node.parent.id.equals("ms_skipview_container");
        }
        if (!node.id.equals("ms_skipview_container")) return false;
        for (Node child : node.children) {
            if (child.visible && child.id.equals("ms_skipview")) return true;
        }
        return false;
    }

    static boolean layerPresent(Decision clicked, List<Node> nodes, Box window) {
        if (clicked.layer == null) return false;
        for (Node node : nodes) {
            if (!node.visible) continue;
            if (node.identity().equals(clicked.target.identity()) && small(node.box, window)
                    && (!node.id.isEmpty() || buttonLabel(node).equals(buttonLabel(clicked.target)))) return true;
            if (clicked.layer.parent != null && node.identity().equals(clicked.layer.identity())
                    && isAdLayer(node, window, .30) && hasMarker(node)) return true;
            if (AD_CONTAINER.matcher(node.id).matches() && isAdLayer(node, window, .30)) return true;
        }
        return false;
    }

    private static Decision idle(String reason) {
        return new Decision(null, null, EMPTY, reason, false, false, false);
    }

    private static void add(Map<Integer, Candidate> candidates, Candidate candidate) {
        Candidate former = candidates.get(candidate.target.index);
        if (former != null) {
            if (candidate.priority > former.priority) candidates.put(candidate.target.index, candidate);
            return;
        }
        Candidate duplicate = null;
        for (Candidate other : candidates.values()) {
            if (!candidate.touchLabel || !other.touchLabel || candidate.layer != other.layer
                    || candidate.priority != other.priority || !sameLabelBranch(candidate.target, other.target)) continue;
            duplicate = other;
            break;
        }
        if (duplicate != null) {
            if (candidate.target.box.area() >= duplicate.target.box.area()) return;
            candidates.remove(duplicate.target.index);
        }
        candidates.put(candidate.target.index, candidate);
    }

    private static boolean sameLabelBranch(Node first, Node second) {
        if (!first.box.contains(second.box.centerX(), second.box.centerY())
                || !second.box.contains(first.box.centerX(), first.box.centerY())) return false;
        for (Node parent = first.parent; parent != null; parent = parent.parent) {
            if (parent == second) return true;
        }
        for (Node parent = second.parent; parent != null; parent = parent.parent) {
            if (parent == first) return true;
        }
        return false;
    }

    private static boolean protectedPage(String activity, List<Node> nodes) {
        String page = activity.toLowerCase(Locale.ROOT);
        if (page.matches(".*\\.(?:[^.]*)(?:login|signin|checkout|payment|pay|permission|install|consent|update|onboarding|tutorial)(?:activity|dialog|screen|page).*")) {
            return true;
        }
        for (Node node : nodes) {
            if (node.visible && (node.password || node.editing || PROTECTED_TEXT.contains(node.text)
                    || PROTECTED_TEXT.contains(node.description))) return true;
        }
        return false;
    }

    private static boolean openingActivity(String activity) {
        String name = activity.substring(activity.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return name.contains("splash") || name.contains("launchad")
                || name.contains("logoactivity");
    }

    private static boolean nonOpeningPage(String activity) {
        String name = activity.substring(activity.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return NON_OPENING_PAGE.matcher(name).find();
    }

    private static Node namedLayer(Node node, Box window) {
        for (Node parent = node.parent; parent != null; parent = parent.parent) {
            if (AD_CONTAINER.matcher(parent.id).matches() && isAdLayer(parent, window, .30)) return parent;
        }
        return null;
    }

    private static Node inferredLayer(Node node, Box window) {
        for (Node parent = node.parent; parent != null; parent = parent.parent) {
            if (!isAdLayer(parent, window, .65) || !nearLayerEdge(node.box, parent.box)
                    || !hasMarker(parent) || hasOrdinaryContent(parent)) continue;
            return parent;
        }
        return null;
    }

    private static boolean isAdLayer(Node node, Box window, double coverage) {
        return node.visible && window.area() > 0 && node.box.area() >= window.area() * coverage;
    }

    private static boolean hasMarker(Node node) {
        if (!node.visible) return false;
        if ((isMarker(node.text) || isMarker(node.description)) && !withinFeed(node)) return true;
        for (Node child : node.children) {
            if (hasMarker(child)) return true;
        }
        return false;
    }

    private static boolean hasOrdinaryContent(Node node) {
        if (!node.visible) return false;
        if (SEARCH_OR_FEED.matcher(node.id + " " + node.type).find()
                || node.text.equals("搜索历史") || node.text.equals("搜索记录")
                || node.text.equals("首页") || node.text.equals("消息")
                || node.text.equals("视频片头")) return true;
        for (Node child : node.children) {
            if (hasOrdinaryContent(child)) return true;
        }
        return false;
    }

    private static boolean withinFeed(Node node) {
        for (Node current = node; current != null; current = current.parent) {
            if (SEARCH_OR_FEED.matcher(current.id + " " + current.type).find()) return true;
            if (current != node && AD_CONTAINER.matcher(current.id).matches()) return false;
        }
        return false;
    }

    private static boolean nearLayerEdge(Box button, Box layer) {
        double x = (button.centerX() - layer.left) / (double) layer.width();
        double y = (button.centerY() - layer.top) / (double) layer.height();
        return x >= 0 && x <= 1 && y >= 0 && y <= 1 && (y < .27 || y > .73 || x < .18 || x > .82);
    }

    private static Node clickTarget(Node node, Node layer, Box window) {
        Node current = node;
        for (int up = 0; current != null && current != layer && up <= 2; up++, current = current.parent) {
            if (!current.visible || !current.enabled || blockedLabel(current.text)
                    || blockedLabel(current.description) || !small(current.box, window)) return null;
            if (!current.clickable) continue;
            if (hasCallToAction(current)) return null;
            if (up > 0 && (current.box.width() > Math.max(node.box.width() * 3, window.width() * .23)
                    || current.box.height() > Math.max(node.box.height() * 3, window.height() * .11))) return null;
            return current;
        }
        return null;
    }

    private static boolean canTapSkipLabel(Node node, Node layer, Box window, boolean supportedLayer) {
        if (node == layer || hasCallToAction(node) || node.box.width() > window.width() * .35
                || node.box.height() > window.height() * .12 || (!supportedLayer && !nearLayerEdge(node.box, layer.box))) {
            return false;
        }
        for (Node parent = node.parent; parent != null && parent != layer; parent = parent.parent) {
            if (blockedLabel(parent.text) || blockedLabel(parent.description)) return false;
            if (parent.clickable && !small(parent.box, window)) return false;
        }
        return true;
    }

    static boolean matchesTapLabel(String text, String description, String children) {
        String label = normalize(text);
        String desc = normalize(description);
        return !blockedLabel(label) && !blockedLabel(desc)
                && (SKIP.matcher(label).matches() || SKIP.matcher(desc).matches()
                || SKIP.matcher(normalize(children)).matches());
    }

    private static boolean hasCallToAction(Node node) {
        for (Node child : node.children) {
            if (child.visible && (blockedLabel(child.text) || blockedLabel(child.description)
                    || hasCallToAction(child))) return true;
        }
        return false;
    }

    private static boolean small(Box box, Box window) {
        return box.area() > 0 && window.contains(box.centerX(), box.centerY())
                && box.width() <= window.width() * .50 && box.height() <= window.height() * .18
                && box.area() <= window.area() * .065;
    }

    private static String buttonLabel(Node node) {
        if (SKIP.matcher(node.text).matches() || closeLabel(node.text)) return node.text;
        if (SKIP.matcher(node.description).matches() || closeLabel(node.description)) return node.description;
        if (node.children.size() > 3) return "";
        StringBuilder joined = new StringBuilder();
        for (Node child : node.children) {
            if (child.visible && child.children.isEmpty()) joined.append(child.text);
        }
        return joined.toString();
    }

    private static boolean closeId(String id) {
        return id.equals("close") || id.equals("btn_close") || id.equals("iv_close")
                || id.equals("close_button") || id.equals("close_ad") || id.equals("ad_close");
    }

    private static boolean closeLabel(String label) {
        return label.equals("×") || label.equals("x") || label.equals("✕")
                || label.equals("关闭") || label.equals("關閉") || label.equals("close");
    }

    private static boolean describedClose(Node node) {
        return node.id.equals("close_ad") || node.id.equals("ad_close") || node.description.equals("关闭")
                || node.description.equals("關閉") || node.description.equals("close");
    }

    private static boolean isMarker(String text) {
        return text.equals("广告") || text.equals("廣告") || text.equals("互动广告")
                || text.equals("运营推广") || text.equals("運營推廣") || text.equals("營運推廣")
                || (text.length() <= 32 && shakeLabel(text));
    }

    private static boolean shakeLabel(String text) {
        return text.contains("摇一摇") || text.contains("搖一搖")
                || text.contains("摇动手机") || text.contains("搖動手機") || text.contains("摇动点击");
    }

    private static boolean blockedLabel(String label) {
        return shakeLabel(label) || label.contains("片头")
                || label.contains("片尾") || label.contains("教程") || label.contains("引导")
                || label.contains("了解详情") || label.contains("查看详情") || label.contains("跳转") || label.contains("跳轉")
                || label.contains("立即") || label.contains("订阅") || label.contains("下载")
                || label.contains("更新") || label.contains("登录") || label.contains("购买")
                || label.contains("支付") || label.contains("安装") || label.equals("打开")
                || label.equals("open") || label.equals("learnmore") || label.equals("shopnow")
                || PROTECTED_TEXT.contains(label);
    }

    private static String localId(String id) {
        if (id == null) return "";
        return id.substring(id.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalize(String label) {
        if (label == null || label.length() > 120) return "";
        return SPACING.matcher(label.trim().toLowerCase(Locale.ROOT)).replaceAll("");
    }
}
