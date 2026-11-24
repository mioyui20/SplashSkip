package app.splashskip.android;

import java.util.ArrayList;
import java.util.List;

final class RuleEngineTest {
    private static final RuleEngine.Box SCREEN = new RuleEngine.Box(0, 0, 1080, 2100);
    private static final RuleEngine.Box TOP_RIGHT = new RuleEngine.Box(900, 100, 1030, 174);
    private static final RuleEngine.Box BOTTOM_LEFT = new RuleEngine.Box(50, 1830, 180, 1904);
    private static final String TEST_APP = "test.app";
    private static final String MAIN = "test.app.MainActivity";

    public static void main(String[] args) {
        Fixture first = new Fixture("splash_ad");
        RuleEngine.Node firstTarget = first.button("ad_skip_title", "跳 过 ｜ 3 秒", TOP_RIGHT);
        first.button("unlabeled", "×", BOTTOM_LEFT);
        RuleEngine.Decision firstHit = first.enhanced();
        require(firstHit.target == firstTarget, "explicit skip outranks an X");

        Fixture moved = new Fixture("splash_ad");
        RuleEngine.Node movedTarget = moved.button("ad_skip_title", "3s Skip", BOTTOM_LEFT);
        require(moved.enhanced().target == movedTarget && firstHit.sameTarget(moved.enhanced()),
                "moving button keeps its identity");

        Fixture split = new Fixture("splash_ad");
        RuleEngine.Node grouped = split.button("close_box", "", TOP_RIGHT);
        split.add(grouped, "", "跳", TOP_RIGHT, false);
        split.add(grouped, "", "过", TOP_RIGHT, false);
        split.add(grouped, "", "5 秒", TOP_RIGHT, false);
        require(split.enhanced().target == grouped, "split label and countdown");

        Fixture duplicate = new Fixture("splash_ad");
        RuleEngine.Node oneControl = duplicate.button("skip_control", "跳过", TOP_RIGHT);
        duplicate.add(oneControl, "", "×", TOP_RIGHT, false);
        require(duplicate.enhanced().target == oneControl, "parent and child collapse to one target");

        Fixture ambiguous = new Fixture("");
        ambiguous.add(ambiguous.root, "", "摇一摇了解详情", SCREEN, false);
        ambiguous.button("first", "×", TOP_RIGHT);
        ambiguous.button("second", "×", BOTTOM_LEFT);
        require(RuleEngine.AMBIGUOUS.equals(ambiguous.enhanced().reason), "two unverified X buttons are ambiguous");
        Fixture resolved = new Fixture("");
        resolved.add(resolved.root, "", "摇一摇了解详情", SCREEN, false);
        RuleEngine.Node onlyClose = resolved.button("first", "×", BOTTOM_LEFT);
        require(resolved.enhanced().target == onlyClose, "same-layer close with a shake prompt");
        Fixture twoCloseButtons = new Fixture("splash_ad");
        twoCloseButtons.add(twoCloseButtons.root, "", "广告", SCREEN, false);
        twoCloseButtons.button("unknown", "×", TOP_RIGHT);
        RuleEngine.Node adClose = twoCloseButtons.button("ad_close", "×", BOTTOM_LEFT);
        require(twoCloseButtons.enhanced().target == adClose, "explicit ad close ID outranks a bare X");

        Fixture standalone = new Fixture("splash_ad");
        RuleEngine.Node plain = standalone.button("plain", "跳过", BOTTOM_LEFT);
        require(standalone.enhanced().target == plain, "lone skip on a recognized opening layer");
        require(standalone.decide(TEST_APP, MAIN, RuleEngine.Mode.STANDARD, false).target == null,
                "standard mode requires explicit generic opt-in or a dedicated rule");
        require(standalone.decide(TEST_APP, MAIN, RuleEngine.Mode.STANDARD, true).target == plain,
                "standard generic opt-in");
        require(standalone.decide(TEST_APP, MAIN, RuleEngine.Mode.IGNORE, true).target == null, "ignore always wins");

        Fixture feed = new Fixture("");
        RuleEngine.Node listing = feed.add(feed.root, "feed_list", "", SCREEN, false);
        feed.add(listing, "", "广告", SCREEN, false);
        feed.add(listing, "", "×", TOP_RIGHT, true);
        require(feed.enhanced().target == null, "opening a normal feed does not close the ad card");

        Fixture layerOverHome = new Fixture("");
        layerOverHome.add(layerOverHome.root, "bottom_nav", "首页", SCREEN, false);
        RuleEngine.Node overlay = layerOverHome.add(layerOverHome.root, "splash_ad", "", SCREEN, true);
        RuleEngine.Node overlaySkip = layerOverHome.add(overlay, "", "跳过", BOTTOM_LEFT, true);
        require(layerOverHome.enhanced().target == overlaySkip, "opening overlay over an already-loaded homepage");

        Fixture badParent = new Fixture("splash_ad");
        RuleEngine.Node fullCard = badParent.add(badParent.root, "creative", "", SCREEN, true);
        badParent.add(fullCard, "", "跳过", TOP_RIGHT, false);
        require(badParent.enhanced().target == null, "do not click the whole creative");

        Fixture cta = new Fixture("splash_ad");
        RuleEngine.Node deceptive = cta.button("", "跳过", TOP_RIGHT);
        cta.add(deceptive, "", "了解详情", TOP_RIGHT, false);
        require(cta.enhanced().target == null, "mixed action semantics require skipping this candidate");

        Fixture protectedPage = new Fixture("splash_ad");
        protectedPage.button("skip", "跳过", TOP_RIGHT);
        protectedPage.add(protectedPage.root, "payment", "确认支付", BOTTOM_LEFT, false);
        require(RuleEngine.PROTECTED.equals(protectedPage.enhanced().reason), "payment screen has priority");

        Fixture onboarding = new Fixture("");
        onboarding.button("skip", "跳过", TOP_RIGHT);
        require(onboarding.decide(TEST_APP, "test.app.OnboardingActivity", RuleEngine.Mode.ENHANCED, true).target == null,
                "onboarding is not an opening ad");

        Fixture dedicated = new Fixture("");
        RuleEngine.Node countdown = dedicated.button("tv.danmaku.bili:id/count_down", "", TOP_RIGHT);
        require(dedicated.decide("tv.danmaku.bili", "tv.danmaku.bili.MainActivityV2",
                RuleEngine.Mode.STANDARD, false).target == countdown, "published dedicated ID and page");
        require(dedicated.decide("tv.danmaku.bili", "tv.danmaku.bili.SearchActivity",
                RuleEngine.Mode.STANDARD, false).target == null, "dedicated ID has a page scope");
        require(dedicated.decide(TEST_APP, MAIN, RuleEngine.Mode.STANDARD, false).target == null,
                "dedicated ID has a package scope");

        Fixture cainiao = new Fixture("");
        RuleEngine.Node skipContainer = cainiao.button("ms_skipView_container", "", TOP_RIGHT);
        cainiao.add(skipContainer, "ms_skipView", "", TOP_RIGHT, false);
        require(cainiao.decide("com.cainiao.wireless", "com.cainiao.wireless.homepage.HomePageActivity",
                RuleEngine.Mode.ENHANCED, false).target == skipContainer, "Cainiao icon structure survives activity changes");
        require(cainiao.decide(TEST_APP, MAIN, RuleEngine.Mode.ENHANCED, false).target == null,
                "Cainiao icon structure is scoped to Cainiao");
        Fixture unrelated = new Fixture("");
        unrelated.button("ms_skipView", "", TOP_RIGHT);
        require(unrelated.decide("com.cainiao.wireless", "com.cainiao.wireless.homepage.HomePageActivity",
                RuleEngine.Mode.ENHANCED, false).target == null, "unfamiliar activity requires the Cainiao icon pair");

        Fixture insufficient = new Fixture("");
        RuleEngine.Node skipOnly = insufficient.button("skip", "跳过", BOTTOM_LEFT);
        require(insufficient.enhanced().target == skipOnly, "enhanced mode accepts a lone skip button without a named layer");
        require(RuleEngine.UNCONFIRMED_LAYER.equals(
                insufficient.decide(TEST_APP, MAIN, RuleEngine.Mode.STANDARD, true).reason),
                "standard mode keeps the independent opening-layer requirement");

        Fixture qqMusic = new Fixture("");
        qqMusic.add(qqMusic.root, "", "运营推广", SCREEN, false);
        qqMusic.add(qqMusic.root, "promotion", "点击跳转活动页", BOTTOM_LEFT, true);
        RuleEngine.Node qqSkip = qqMusic.add(qqMusic.root, "", "跳过", TOP_RIGHT, false);
        RuleEngine.Decision qqHit = qqMusic.decide("com.tencent.qqmusic", "com.tencent.qqmusic.activity.AppStarterActivity",
                RuleEngine.Mode.ENHANCED, false);
        require(qqHit.target == qqSkip && qqHit.touchLabel, "QQ Music promotion taps the visible skip label");
        require(qqMusic.decide("com.tencent.qqmusic", "com.tencent.qqmusic.business.playernew.view.NewPlayerActivity",
                RuleEngine.Mode.ENHANCED, false).target == null, "QQ Music player is not a standalone opening ad");

        Fixture qqNative = new Fixture("");
        RuleEngine.Node qqNativeSkip = qqNative.button("skip", "跳过", TOP_RIGHT);
        RuleEngine.Decision qqNativeHit = qqNative.decide("com.tencent.qqmusic", "com.tencent.qqmusic.activity.AppStarterActivity",
                RuleEngine.Mode.ENHANCED, false);
        require(qqNativeHit.target == qqNativeSkip && qqNativeHit.touchLabel,
                "QQ Music uses the visible label tap path when its node also advertises a click action");

        Fixture schedule = new Fixture("");
        schedule.add(schedule.root, "", "摇动手机", SCREEN, false);
        schedule.add(schedule.root, "shake_cta", "跳转至详情页或第三方应用", BOTTOM_LEFT, true);
        RuleEngine.Node timedSkip = schedule.add(schedule.root, "", "跳过 1", TOP_RIGHT, false);
        RuleEngine.Decision scheduleHit = schedule.decide("com.xtuone.android.syllabus",
                "com.xtuone.android.friday.MainFragmentActivity", RuleEngine.Mode.ENHANCED, false);
        require(scheduleHit.target == timedSkip && scheduleHit.touchLabel, "Super Schedule taps its countdown label");

        Fixture duplicateLabel = new Fixture("");
        RuleEngine.Node cluster = duplicateLabel.add(duplicateLabel.root, "", "", TOP_RIGHT, false);
        RuleEngine.Node label = duplicateLabel.add(cluster, "", "跳过", TOP_RIGHT, false);
        duplicateLabel.add(cluster, "", "1秒", TOP_RIGHT, false);
        RuleEngine.Decision duplicateHit = duplicateLabel.enhanced();
        require((duplicateHit.target == cluster || duplicateHit.target == label) && duplicateHit.touchLabel,
                "the countdown label and its enclosing control represent one possible tap");
        Fixture ambiguousLabels = new Fixture("");
        ambiguousLabels.add(ambiguousLabels.root, "", "跳过", TOP_RIGHT, false);
        ambiguousLabels.add(ambiguousLabels.root, "", "跳过", BOTTOM_LEFT, false);
        require(RuleEngine.AMBIGUOUS.equals(ambiguousLabels.enhanced().reason),
                "independent skip labels at different positions stay ambiguous");

        Fixture playback = new Fixture("");
        playback.button("skip", "跳过片头", TOP_RIGHT);
        require(playback.enhanced().target == null, "a video intro label never qualifies");
        require(RuleEngine.matchesTapLabel("跳过2", "", "") && RuleEngine.matchesTapLabel("", "", "跳过1秒"),
                "fresh label check accepts the countdown and split text");
        require(!RuleEngine.matchesTapLabel("点击跳转活动页", "", "跳过1"),
                "fresh label check rejects a control whose action changed");

        Fixture markerOnly = new Fixture("splash_ad");
        markerOnly.add(markerOnly.root, "", "广告", SCREEN, false);
        require(RuleEngine.NO_CONTROL.equals(markerOnly.enhanced().reason), "ad marker without a control reports a miss");
        require(RuleEngine.layerPresent(firstHit, moved.nodes, SCREEN), "moving a target does not indicate closure");
        require(!RuleEngine.layerPresent(firstHit, new Fixture("").nodes, SCREEN), "closed layer is absent");
        System.out.println("RuleEngine cases passed");
    }

    private static void require(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }

    private static final class Fixture {
        final List<RuleEngine.Node> nodes = new ArrayList<>();
        final RuleEngine.Node root;

        Fixture(String name) { root = add(null, name, "", SCREEN, false); }

        RuleEngine.Node button(String id, String label, RuleEngine.Box box) {
            return add(root, id, label, box, true);
        }

        RuleEngine.Node add(RuleEngine.Node parent, String id, String label, RuleEngine.Box box, boolean clickable) {
            RuleEngine.Node node = new RuleEngine.Node(nodes.size(), parent == null ? 0 : parent.children.size(),
                    parent, id, "android.view.View", label, "", box, true, true, clickable, false, false);
            nodes.add(node);
            return node;
        }

        RuleEngine.Decision enhanced() { return decide(TEST_APP, MAIN, RuleEngine.Mode.ENHANCED, false); }

        RuleEngine.Decision decide(String pkg, String page, RuleEngine.Mode mode, boolean generic) {
            return RuleEngine.decide(pkg, page, nodes, SCREEN, mode, generic);
        }
    }
}
