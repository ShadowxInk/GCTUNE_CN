package com.poc.gctune;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GC Tune - minimal terminal on top of the Game Cube uid1000 command channel.
 *
 * The command is smuggled into the SurfaceFlinger layer name; FpsManager runs
 *   sh -c  dumpsys SurfaceFlinger --latency "<layer>"
 * so the $(...) block executes as uid 1000. Its stdout+stderr are base64
 * encoded and parked in Settings.System (gc_tune_ack = "N|base64"), which the
 * poller decodes back into the console.
 */
public class Main extends Activity {

    private static final String PKG = "com.poc.gctune";
    private static final String ACK_KEY = "gc_tune_ack";

    /**
     * Bootstrap of the uid1000 watcher, prepended to every payload while the
     * daemon toggle is on. No-op when a watcher is already alive (orphaned
     * watcher = ppid 1, "gctw" in cmdline). The watcher blocks on
     * `logcat -T 1 -s GCTune:I` - idle cost is exactly zero - and on a WAKE
     * line runs `dumpsys SurfaceFlinger --latency "<our layer>"`, which
     * re-parses the layer name and executes the payload as uid1000, byte
     * identical to the Game Cube panel path. Lives until reboot; the next
     * panel pull after a reboot respawns it.
     *
     * Detection: /proc/[pid]/cmdline is scanned directly instead of ps ARGS -
     * no column-width truncation, no dependency on the toybox ps version, the
     * whole argv is read. A live watcher is any process whose cmdline carries
     * the W marker but not the exec-chain marker X. X is spelled with an
     * interrupted double quote (`--""latency com.poc`) so the literal string
     * `--latency com.poc` never appears in our own cmdline: the daemon
     * recognises itself by W, correctly excludes the sh/dumpsys exec chain
     * (whose argv really does contain `--latency com.poc.gctune/x...`), and
     * the detection never self-collides.
     *
     * Spawn race: each candidate waits 0.3s (so a racing twin's exec is
     * already visible in /proc), then only the lowest gctw pid survives -
     * exit 0 if someone older exists. Pids are handed out in spawn order, so
     * the older candidate is always the smaller number and exactly one
     * watcher reaches the pipeline.
     *
     * Read loop: `logcat -T 1` starts at the tail of the buffer, so stale
     * WAKE lines are never replayed on (re)start. The dumpsys re-exec is put
     * into the background, so a hung dumpsys cannot stall the read loop or
     * block the pipe (SIGPIPE/backpressure). If logcat itself dies (chatty
     * trim, logd restart) the loop sleeps 5s before re-attaching - no busy
     * spin burning CPU.
     *
     * Note: this string sits inside the layer name, i.e. inside the $( ... )
     * block that FpsManager expands within double quotes. The shell parses
     * $( ... ) as an independent context, so quoting here is safe; the user
     * command itself still goes through executeCurrentCommand's " -> ' pass.
     */
    private static final String DAEMON_BOOT =
            "W=gctw;P=/data/bbkcore/theme/.gctw.pid;ex=0;"
            // Single-instance check via lock file ONLY - no /proc cmdline scan.
            // Scanning /proc was fatal: the launching shell's own cmdline
            // contains "W=gctw" (the payload text itself), so ex was always 1
            // and setsid never ran. Toybox sh has no $PPID either, so any
            // self-PID trickery fails. Lock file is the only reliable way:
            // if the recorded PID is still alive, the daemon exists.
            + "[ -f $P ]&&{ o=$(cat $P 2>/dev/null);"
            + "[ -n \"$o\" ]&&[ -d /proc/$o ]&&ex=1; };"
            + "if [ $ex = 1 ];then :;else setsid sh -c 'W=gctw;P=/data/bbkcore/theme/.gctw.pid;"
            // Daemon owns the lock: write our own real PID ($$ is correct here -
            // this shell IS the daemon main loop) before entering the loop.
            + "echo $$ >$P 2>/dev/null;"
            // Whitelist GC Tune in Doze battery whitelist on daemon startup
            + "cmd deviceidle whitelist +com.poc.gctune >/dev/null 2>&1;"
            + "cmd appops set com.poc.gctune RUN_IN_BACKGROUND allow >/dev/null 2>&1;"
            + "cmd appops set com.poc.gctune START_FOREGROUND allow >/dev/null 2>&1;"
            + "while :;do logcat -T 1 -s GCTune:I 2>/dev/null"
            + "|while IFS= read -r x;do case $x in *WAKE*)"
            + "n=$(settings get secure gc_tune_cmd 2>/dev/null);"
            + "[ -n \"$n\" ]&&[ \"$n\" != null ]&&sh -c \"$n\" </dev/null &;;"
            + "esac;done;sleep 5;done' >/dev/null 2>&1 </dev/null & fi;";
    private static final String BENIGN_TITLE = "GC Tune";
    private static final int REQ_IMPORT_FILE = 2001;
    private static final int REQ_PICK_ITZ = 2002;
    private static final int REQ_PICK_FONT = 2003;
    private static final int RC_UNKNOWN = -999;

    /** Staging location of the picked .itz (public Download/gctune/theme.itz first). */
    private static final String STAGE_NAME = "theme.itz";
    private static final String STAGE_DIR = "gctune";
    /** iTheme-managed package exported as a rebuild template. */
    private static final String ORIG_NAME = "orig-theme.itz";
    /** Rebuilt package: template structure + the user's own resources. */
    private static final String COMPAT_NAME = "theme-compat.itz";
    private static final String FONT_STAGE_NAME = "font.ttf";
    private static final String FONT_STAGE_DIR = "gctune";
    /** Real nav bar height: divider 1 + row (7 + item 51 + 9) = 68dp. */
    private static final float NAV_H_DP = 68f;

    /** Appended by the payload after the user command; travels inside the blob. */
    private static final String RC_MARK = "__RC__";
    private static final Pattern RC_PAT = Pattern.compile(RC_MARK + "(-?\\d+)\\s*$");
    private static final String NO_OUTPUT = "（命令已执行，无文本输出）";
    private static final int MAX_ENTRIES = 60;

    private Handler h;
    private ContentResolver cr;
    private SharedPreferences prefs;

    private ImageView statusDot;
    private TextView statusText;
    private ObjectAnimator dotPulse;
    private EditText cmdInput;
    private ImageView runBtn;
    private TextView logView;
    private ScrollView logScroll;
    private ImageView jumpLogBtn;

    private M3Icons.Icon iconPlay, iconStop, iconDotOn, iconDotOff;
    private ChannelService.Cb myCb;

    // The channel is singular: shared by Main and every section activity.
    private static final List<Entry> entries = new ArrayList<>();
    private static int ackCounter = 1000000;
    private int curAck = 0;
    private long deadline = 0;
    private static boolean busy = false;
    private int timeoutSec = 30;

    // ------------------------------------------------------- navigation pages
    static final int PAGE_SHELL = 0;
    static final int PAGE_THEME = 1;
    static final int PAGE_ICONS = 2;
    static final int PAGE_SETTINGS = 3;
    static final int PAGE_MISC = 4;
    static final int PAGE_CUSTOM = 5;
    static final int PAGE_FONT = 6;
    static final int PAGE_SETTINGS_THEME = 7;

    private View shellPage, themePage, iconsPage, settingsPage, miscPage;
    private View customPage, fontPage, settingsThemePage;
    private View pasteBtn;
    private final View[] navItems = new View[8];
    private final ImageView[] navIcons = new ImageView[7];
    private final TextView[] navLabels = new TextView[7];
    private View navIndicator;
    private boolean indicatorPlaced = false;
    private int page = 0;

    /**
     * Section activities (主题/图标/其他/字体) subclass Main and return
     * their page index here; the full app (nav bar, console, hub) returns -1.
     */
    protected int sectionPage() {
        return -1;
    }

    // ----------------------------------------------------------- misc page
    private TextView miscKeepStatus, miscBridgeStatus, miscHardenStatus, miscHardenTarget;
    private MaterialSwitch keepSwitch, bridgeSwitch, targetSw;
    private boolean miscRun = false;
    /** What the pending misc command does: harden | unharden | freeze | unfreeze. */
    private String miscAction = "harden";

    // ----------------------------------------------------------- adb bridge
    private volatile long bridgeAck = 0;
    private volatile CountDownLatch bridgeLatch;
    private volatile Bundle bridgeResult;
    private volatile String bridgeLastCmd = "";

    // ---------------------------------------------------------------- theme
    private TextView themeFileText, themeStatusText;
    private View themeHint, installBtn;
    private ObjectAnimator hintPulse;
    private String stagedPath;
    private String stagedMd5;
    private long stagedBytes;
    private boolean staging = false;
    private boolean themeRun = false;
    private boolean themeDiagRun = false;
    private boolean themeRestoreRun = false;
    private boolean themeExportRun = false;
    private EditText settingsTimeout;

    // ------------------------------------------------------------ 诊断与反馈
    /**
     * 通道自检命令：只读，一次带回定位通道问题所需的信息
     * （执行身份、ROM 版本、/data/bbkcore 与 /data/vfonts 是否存在、iTheme 包名、
     * 当前游戏列表状态、已发布命令的长度）。可安全重复执行。
     */
    private static final String DIAG_SELF_CHECK =
            "id;"
            + "echo VIVO_OS=$(getprop ro.vivo.os.version);"
            + "echo ROM_VER=$(getprop ro.vivo.os.rom.version);"
            + "echo BUILD=$(getprop ro.build.display.id);"
            + "echo ANDROID=$(getprop ro.build.version.release);"
            + "if [ -d /data/bbkcore ];then echo BBKCORE=yes;else echo BBKCORE=no;fi;"
            + "if [ -d /data/vfonts ];then echo VFONTS=yes;else echo VFONTS=no;fi;"
            + "ls /data/bbkcore 2>&1|head -8;"
            + "ls /data/bbkcore/theme 2>&1|head -8;"
            + "pm list packages 2>/dev/null|grep -i theme|head -8;"
            + "echo GAME=$(settings get system current_game_package);"
            + "echo CMD_LEN=$(settings get secure gc_tune_cmd|wc -c)";

    /** 最近一次命令是否为「通道自检」：结果要单独弹窗解释。 */
    private boolean diagSelfCheck = false;
    /** 设置页底部那行「最近日志」说明。 */
    private TextView diagNote;
    /** 失败弹窗节流：同一分钟内只弹一次，避免连续失败刷屏。 */
    private static long diagDialogAt = 0L;

    // ----------------------------------------------------------------- font
    private TextView fontThemeFileText, fontThemeStatusText;
    private View fontThemeHint, fontInstallBtn;
    private ObjectAnimator fontHintPulse;
    private String fontStagedPath;
    private String fontStagedMd5;
    private long fontStagedBytes;
    private boolean fontStaging = false;
    private boolean fontThemeRun = false;

    // ---------------------------------------------------------------- icons
    private TextView iconsStatusText;
    private LinearLayout iconsPreviewRow;
    private EditText iconsColorInput;
    private int iconsColor = 0xFF3D7D56;
    // fine tuning of the recolor (percent, see IconsTinter.compose)
    private int iconsDesat = 100;
    private int iconsLighten = 0;
    private int iconsStrength = 100;
    // icon pack source ("" = system icons)
    private IconPackLoader packLoader;
    private String iconsPackPkg = "";
    private String iconsPackLabel = "";
    private TextView iconsSourceValue;
    // IconPack Studio style: background layer + foreground icon
    private boolean iconsBgEnabled = false;
    private int iconsBgColor = 0xFFFFFFFF;
    private boolean iconsMonetEnabled = false;
    private int iconsScale = 100;
    private int iconsFgScale = 76;
    private int iconsRadius = 25;
    private int iconsShape = IconsTinter.SHAPE_ROUNDED;
    private EditText iconsBgInput;
    private Slider sbDesat, sbLighten, sbStrength, sbIconScale, sbFgScale, sbRadius;
    private LinearLayout manualColorBox;
    // icon-style flip state (see toggleIconsStyle)
    private String pendingOldStyle = null;
    private boolean styleToggleOk = true;
    private boolean iconsRun = false;
    private List<String> iconsPackages = new ArrayList<>();
    private List<Drawable> iconsOriginals = new ArrayList<>();
    private List<Drawable> iconsTinted = new ArrayList<>();

    // -------------------------------------------------------- settings themer
    private TextView stStatusText;
    private View stHint;
    private ObjectAnimator stHintPulse;
    private int stColor = 0xFF3D7D56;
    private boolean stMonet = true;
    private boolean stRun = false;
    private EditText stColorInput;
    private LinearLayout stManualBox;
    private ImageView stPreviewSwatch;
    private TextView stPreviewAccentText;
    private MaterialSwitch stPreviewSwitch;

    /**
     * Extracts dynamic Monet accent color from the wallpaper or system theme.
     * Falls back to a stylish Vivo emerald if wallpaper colors are unavailable.
     */
    private int getMonetPrimaryColor() {
        if (Build.VERSION.SDK_INT >= 27) {
            try {
                android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(this);
                if (wm != null) {
                    android.app.WallpaperColors wc = wm.getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM);
                    if (wc != null && wc.getPrimaryColor() != null) {
                        return wc.getPrimaryColor().toArgb();
                    }
                }
            } catch (Throwable t) {
                Log.w("GCTune", "WallpaperColors probe failed: " + t);
            }
        }
        // Fallback to framework system accent or Vivo emerald
        try {
            android.util.TypedValue tv = new android.util.TypedValue();
            if (getTheme().resolveAttribute(android.R.attr.colorAccent, tv, true)) {
                return tv.data;
            }
        } catch (Throwable ignored) {}
        return 0xFF3D7D56;
    }

    /** One console line: either a plain status note or a command with its result. */
    private static final class Entry {
        boolean status;
        String cmd;
        String out;
        int rc = RC_UNKNOWN;
        boolean pending;
        boolean failed;
        long ts = System.currentTimeMillis();
    }

    private static final java.text.SimpleDateFormat LOG_TS =
            new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);

    private static String stamp(long ts) {
        synchronized (LOG_TS) { return LOG_TS.format(new java.util.Date(ts)); }
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            h.postDelayed(this, 1500L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        h = new Handler(Looper.getMainLooper());
        cr = getContentResolver();
        prefs = getSharedPreferences("gctune_m3", MODE_PRIVATE);
        Diag.init(this);
        Diag.entryPoint("主界面");

        iconPlay = M3Icons.play(Ui.ACCENT, Ui.dp(this, 20));
        iconStop = M3Icons.stop(Ui.TEXT, Ui.dp(this, 18));
        iconDotOn = M3Icons.dot(Ui.ACCENT, Ui.dp(this, 9));
        iconDotOff = M3Icons.dotHollow(Ui.MUTED, Ui.dp(this, 9));

        // ACK delivery + retries live in ChannelService (survives backgrounding).
        // The holder is re-attached in onResume: whichever activity is on top
        // processes results (shared entries make finishRun safe in any instance).
        myCb = new ChannelService.Cb() {
            @Override
            public void onDone(final boolean ok, final String out) {
                h.post(new Runnable() {
                    @Override public void run() { finishRun(ok, out); }
                });
            }
        };
        ChannelService.cb = myCb;

        buildUi();
        BridgeProvider.bridgeImpl = bridgeImpl;
        // resume the keep-alive chain after a relaunch
        if (prefs.getBoolean("keep_alive", false)) {
            KeepAliveReceiver.schedule(this,
                    System.currentTimeMillis() + KeepAliveReceiver.INTERVAL);
            KeepAliveService.start(this);
        }
        setWinTitle(BENIGN_TITLE);
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // the top activity owns the channel callbacks and the bridge entry
        ChannelService.cb = myCb;
        BridgeProvider.bridgeImpl = bridgeImpl;
        renderLog();
        h.postDelayed(ticker, 400L);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (ChannelService.cb == myCb) ChannelService.cb = null;
        if (BridgeProvider.bridgeImpl == bridgeImpl) BridgeProvider.bridgeImpl = null;
        if (hintPulse != null) hintPulse.cancel();
        if (fontHintPulse != null) fontHintPulse.cancel();
        if (dotPulse != null) dotPulse.cancel();
    }

    // ------------------------------------------------------------------ UI
    private void buildUi() {
        int section = sectionPage();
        if (section != -1) {
            // Standalone section activity: only its own page, no nav/console.
            switch (section) {
                case PAGE_THEME:
                    themePage = buildThemePage();
                    setContentView(themePage);
                    break;
                case PAGE_ICONS:
                    iconsPage = buildIconsPage();
                    setContentView(iconsPage);
                    break;
                case PAGE_MISC:
                    miscPage = buildMiscPage();
                    setContentView(miscPage);
                    break;
                case PAGE_FONT:
                    fontPage = buildFontPage();
                    setContentView(fontPage);
                    break;
                case PAGE_SETTINGS_THEME:
                    settingsThemePage = buildSettingsThemePage();
                    setContentView(settingsThemePage);
                    break;
                default:
                    setContentView(new View(this));
            }
            return;
        }

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Ui.BG);

        shellPage = buildShellPage();
        customPage = buildCustomPage();
        settingsPage = buildSettingsPage();

        FrameLayout pages = new FrameLayout(this);
        pages.addView(shellPage, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(customPage, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(settingsPage, new FrameLayout.LayoutParams(-1, -1));
        customPage.setVisibility(View.GONE);
        settingsPage.setVisibility(View.GONE);
        FrameLayout.LayoutParams pagesLp = new FrameLayout.LayoutParams(-1, -1);
        pagesLp.bottomMargin = Ui.dp(this, NAV_H_DP);
        frame.addView(pages, pagesLp);

        pasteBtn = pasteButton();
        FrameLayout.LayoutParams pasteLp =
                new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        pasteLp.rightMargin = Ui.dp(this, 20);
        pasteLp.bottomMargin = Ui.dp(this, NAV_H_DP + 20);
        frame.addView(pasteBtn, pasteLp);

        LinearLayout nav = navBar();
        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        navLp.leftMargin = Ui.dp(this, 16);
        navLp.rightMargin = Ui.dp(this, 16);
        navLp.bottomMargin = Ui.dp(this, 14);
        frame.addView(nav, navLp);

        setContentView(frame);
        switchPage(PAGE_SHELL);
    }

    /** Console tab: top bar + status + input + log. */
    private View buildShellPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);

        LinearLayout root = Ui.col(this);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 30));
        scroll.addView(root, new FrameLayout.LayoutParams(-1, -2));

        root.addView(topBar());
        root.addView(titleRow());
        root.addView(inputRow());
        root.addView(consoleCard());
        return scroll;
    }

    private View pasteButton() {
        TextView paste = Ui.label(this, "粘贴", 13.5f, Ui.ACCENT);
        paste.setGravity(Gravity.CENTER);
        paste.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 18), Ui.dp(this, 12));
        paste.setBackground(Ui.pillRipple(this, Ui.PRIMARY, 0x40FFFFFF));
        paste.setCompoundDrawablesWithIntrinsicBounds(
                M3Icons.clipboard(Ui.ACCENT, Ui.dp(this, 18)), null, null, null);
        paste.setCompoundDrawablePadding(Ui.dp(this, 8));
        paste.setElevation(Ui.dp(this, 4));
        paste.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.tap(v);
                pasteFromClipboard();
            }
        });
        return paste;
    }

    // ------------------------------------------------------------- navigation
    private LinearLayout navBar() {
        LinearLayout wrap = Ui.col(this);
        // M3 Expressive Floating Capsule Container: 30dp radius, subtle accent glow stroke
        wrap.setBackground(Ui.shape(this, Ui.SURFACE, 30f, Ui.BORDER_SOFT, 1.2f));
        wrap.setElevation(Ui.dp(this, 6));

        FrameLayout holder = new FrameLayout(this);

        // M3 Expressive active-indicator pill, slides between items with capsule shape
        navIndicator = new View(this);
        navIndicator.setBackground(Ui.pillShape(this, Ui.PRIMARY, 0, 0));
        navIndicator.setAlpha(0f);
        holder.addView(navIndicator, new FrameLayout.LayoutParams(0, 0));

        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));

        navItems[PAGE_SHELL] = navItem(PAGE_SHELL,
                M3Icons.terminal(Ui.MUTED, Ui.dp(this, 22)), "Shell");
        navItems[PAGE_CUSTOM] = navItem(PAGE_CUSTOM,
                M3Icons.grid(Ui.MUTED, Ui.dp(this, 22)), "个性化");
        navItems[PAGE_SETTINGS] = navItem(PAGE_SETTINGS,
                M3Icons.tune(Ui.MUTED, Ui.dp(this, 22)), "设置");
        for (View v : navItems) {
            if (v == null) continue;
            row.addView(v, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        holder.addView(row, new FrameLayout.LayoutParams(-1, -2));

        holder.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                if (!indicatorPlaced) {
                    placeIndicator(false);
                }
            }
        });

        wrap.addView(holder, new LinearLayout.LayoutParams(-1, -2));
        return wrap;
    }

    /** Sizes the pill to the selected item and (optionally) slides it there. */
    private void placeIndicator(boolean animate) {
        if (navIndicator == null) return;
        View item = navItems[page];
        if (item == null || item.getWidth() == 0) return;

        FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) navIndicator.getLayoutParams();
        boolean sizeChanged = lp.width != item.getWidth()
                || lp.height != item.getHeight()
                || lp.topMargin != item.getTop();
        lp.width = item.getWidth();
        lp.height = item.getHeight();
        lp.topMargin = item.getTop();
        if (sizeChanged) navIndicator.setLayoutParams(lp);

        float target = item.getLeft();
        if (animate) {
            ObjectAnimator slide = ObjectAnimator.ofFloat(navIndicator, "translationX",
                    navIndicator.getTranslationX(), target);
            slide.setDuration(260L);
            slide.setInterpolator(new android.view.animation.DecelerateInterpolator());
            slide.start();
        } else {
            navIndicator.setTranslationX(target);
        }
        navIndicator.setAlpha(1f);
        indicatorPlaced = true;
    }

    private View navItem(final int idx, M3Icons.Icon icon, String label) {
        LinearLayout item = Ui.col(this);
        item.setGravity(Gravity.CENTER);
        item.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
        // Expressive tap response via rounded capsule mask
        item.setBackground(Ui.pillShape(this, 0x00000000, 0, 0));

        ImageView ic = new ImageView(this);
        ic.setImageDrawable(icon);
        ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        item.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));

        TextView lb = Ui.label(this, label, 11f, Ui.MUTED);
        lb.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lbLp = new LinearLayout.LayoutParams(-2, -2);
        lbLp.topMargin = Ui.dp(this, 4);
        item.addView(lb, lbLp);

        navIcons[idx] = ic;
        navLabels[idx] = lb;
        item.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.tap(v);
                switchPage(idx);
            }
        });
        return item;
    }

    private void switchPage(int idx) {
        if (sectionPage() != -1) return;
        page = idx;
        View[] pages = new View[7];
        pages[PAGE_SHELL] = shellPage;
        pages[PAGE_SETTINGS] = settingsPage;
        pages[PAGE_CUSTOM] = customPage;
        for (int i = 0; i < pages.length; i++) {
            if (pages[i] == null) continue;
            if (i == idx) {
                fadeIn(pages[i]);
            } else {
                pages[i].animate().cancel();
                pages[i].setAlpha(1f);
                pages[i].setTranslationY(0f);
                pages[i].setVisibility(View.GONE);
            }
        }
        if (pasteBtn != null) {
            pasteBtn.setVisibility(idx == PAGE_SHELL ? View.VISIBLE : View.GONE);
        }
        for (int i = 0; i < navIcons.length; i++) {
            if (navIcons[i] == null || navLabels[i] == null) continue;
            boolean sel = i == idx;
            Drawable d = navIcons[i].getDrawable();
            if (d != null) d.setTint(sel ? Ui.ACCENT : Ui.MUTED);
            navLabels[i].setTextColor(sel ? Ui.ACCENT : Ui.MUTED);
        }
        placeIndicator(true);
    }

    /** Soft fade + rise used whenever a page becomes visible. */
    private void fadeIn(View v) {
        v.animate().cancel();
        v.setAlpha(0f);
        v.setTranslationY(Ui.dp(this, 10));
        v.setVisibility(View.VISIBLE);
        v.animate().alpha(1f).translationY(0f).setDuration(200L)
                .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                .start();
    }

    private View topBar() {
        LinearLayout row = Ui.row(this);

        ImageView importBtn = iconButton(
                M3Icons.folder(Ui.ACCENT, Ui.dp(this, 22)),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { pickScriptFile(); }
                });
        row.addView(importBtn, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));

        ImageView clearBtn = iconButton(
                M3Icons.trash(Ui.ACCENT, Ui.dp(this, 22)),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        entries.clear();
                        renderLog();
                    }
                });
        LinearLayout.LayoutParams clearLp =
                new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46));
        clearLp.leftMargin = Ui.dp(this, 12);
        row.addView(clearBtn, clearLp);

        row.addView(new View(this), Ui.grow());

        ImageView settingsBtn = iconButton(
                M3Icons.tune(Ui.ACCENT, Ui.dp(this, 22)),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { switchPage(PAGE_SETTINGS); }
                });
        row.addView(settingsBtn, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(this, 20);
        row.setLayoutParams(lp);
        return row;
    }

    private ImageView iconButton(M3Icons.Icon icon, View.OnClickListener l) {
        ImageView b = new ImageView(this);
        b.setImageDrawable(icon);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setBackground(Ui.pillRipple(this, Ui.SURFACE_HIGH, 0x40FFFFFF));
        int p = Ui.dp(this, 12);
        b.setPadding(p, p, p, p);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.tap(v);
                l.onClick(v);
            }
        });
        return b;
    }

    private View titleRow() {
        LinearLayout row = Ui.row(this);

        TextView title = Ui.label(this, "GC Tune", 28f, Ui.TEXT);
        title.setLetterSpacing(-0.01f);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        // M3 Expressive status capsule: pill container with glowing outline
        LinearLayout pill = Ui.row(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(Ui.pillShape(this, Ui.SURFACE_HIGH, Ui.BORDER_SOFT, 1.2f));
        pill.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));

        statusDot = new ImageView(this);
        statusDot.setImageDrawable(iconDotOff);
        statusDot.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(-2, -2);
        dotLp.rightMargin = Ui.dp(this, 7);
        pill.addView(statusDot, dotLp);

        statusText = Ui.text(this, "", 11.5f, Ui.MUTED);
        pill.addView(statusText);

        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(-2, -2);
        pillLp.bottomMargin = Ui.dp(this, 4);
        row.addView(pill, pillLp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(this, 20);
        row.setLayoutParams(lp);
        return row;
    }

    /** Breathing scale on the status dot while a command is in flight. */
    private void setDotPulse(boolean on) {
        if (statusDot == null) return;
        if (on) {
            if (dotPulse == null) {
                android.animation.PropertyValuesHolder sx =
                        android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.4f);
                android.animation.PropertyValuesHolder sy =
                        android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.4f);
                dotPulse = ObjectAnimator.ofPropertyValuesHolder(statusDot, sx, sy);
                dotPulse.setDuration(500L);
                dotPulse.setRepeatCount(ValueAnimator.INFINITE);
                dotPulse.setRepeatMode(ValueAnimator.REVERSE);
            }
            if (!dotPulse.isRunning()) dotPulse.start();
        } else if (dotPulse != null) {
            dotPulse.cancel();
            statusDot.setScaleX(1f);
            statusDot.setScaleY(1f);
        }
    }

    private View inputRow() {
        LinearLayout row = Ui.row(this);

        LinearLayout box = Ui.col(this);
        // Expressive input shape: generous 22dp corner radius with deep background
        final android.graphics.drawable.GradientDrawable boxBg =
                Ui.shape(this, Ui.INPUT_BG, 22f, Ui.BORDER, 1.2f);
        box.setBackground(boxBg);
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));

        cmdInput = new EditText(this);
        cmdInput.setBackground(null);
        cmdInput.setTextColor(Ui.TEXT);
        cmdInput.setHintTextColor(Ui.MUTED);
        cmdInput.setHint("命令或脚本");
        cmdInput.setTypeface(Typeface.MONOSPACE);
        cmdInput.setTextSize(14f);
        cmdInput.setGravity(Gravity.TOP | Gravity.START);
        cmdInput.setMinLines(2);
        cmdInput.setMaxLines(8);
        cmdInput.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        cmdInput.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                boxBg.setStroke(Ui.dp(Main.this, hasFocus ? 2f : 1.2f),
                        hasFocus ? Ui.ACCENT : Ui.BORDER);
            }
        });
        box.addView(cmdInput, new LinearLayout.LayoutParams(-1, -2));

        row.addView(box, new LinearLayout.LayoutParams(0, -2, 1f));

        runBtn = new ImageView(this);
        runBtn.setImageDrawable(iconPlay);
        runBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        runBtn.setBackground(Ui.pillRipple(this, Ui.PRIMARY, 0x40FFFFFF));
        int p = Ui.dp(this, 14);
        runBtn.setPadding(p, p, p, p);
        runBtn.setElevation(Ui.dp(this, 3));
        runBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.tap(v);
                if (busy) cancelRun(); else executeCurrentCommand();
            }
        });
        LinearLayout.LayoutParams runLp = new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52));
        runLp.leftMargin = Ui.dp(this, 12);
        row.addView(runBtn, runLp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 12);
        lp.bottomMargin = Ui.dp(this, 18);
        row.setLayoutParams(lp);
        return row;
    }

    private View consoleCard() {
        LinearLayout card = Ui.col(this);
        // Expressive card container: 24dp radius, subtle micro-contrast border
        card.setBackground(Ui.shape(this, Ui.SURFACE, 24f, Ui.BORDER_SOFT, 1.2f));

        // ---- header: caption + copy-to-clipboard
        LinearLayout head = Ui.row(this);
        head.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 10));
        head.addView(Ui.section(this, "控制台"),
                new LinearLayout.LayoutParams(0, -2, 1f));

        ImageView copyBtn = new ImageView(this);
        copyBtn.setImageDrawable(M3Icons.clipboard(Ui.MUTED, Ui.dp(this, 17)));
        copyBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        copyBtn.setBackground(Ui.pillRipple(this, 0x00000000, 0x306CE5A8));
        int cp = Ui.dp(this, 8);
        copyBtn.setPadding(cp, cp, cp, cp);
        copyBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Ui.tap(v); copyLog(); }
        });
        head.addView(copyBtn, new LinearLayout.LayoutParams(-2, -2));
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));

        View divider = new View(this);
        divider.setBackgroundColor(Ui.BORDER_SOFT);
        card.addView(divider, new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));

        // ---- body: scrollable log + "jump to bottom" bubble
        FrameLayout body = new FrameLayout(this);

        logScroll = new ScrollView(this);
        logScroll.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        logScroll.setVerticalScrollBarEnabled(false);

        logView = new TextView(this);
        logView.setTextColor(Ui.TEXT);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(12.5f);
        logView.setLetterSpacing(0.02f);
        logView.setTextIsSelectable(true);
        logScroll.addView(logView);
        body.addView(logScroll, new FrameLayout.LayoutParams(-1, -1));

        jumpLogBtn = new ImageView(this);
        jumpLogBtn.setImageDrawable(M3Icons.chevron(Ui.ACCENT, Ui.dp(this, 18)));
        jumpLogBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        jumpLogBtn.setRotation(90f);
        jumpLogBtn.setBackground(Ui.pillRipple(this, Ui.PRIMARY, 0x40FFFFFF));
        int jp = Ui.dp(this, 10);
        jumpLogBtn.setPadding(jp, jp, jp, jp);
        jumpLogBtn.setElevation(Ui.dp(this, 3));
        jumpLogBtn.setVisibility(View.GONE);
        FrameLayout.LayoutParams jumpLp =
                new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        jumpLp.rightMargin = Ui.dp(this, 12);
        jumpLp.bottomMargin = Ui.dp(this, 12);
        body.addView(jumpLogBtn, jumpLp);
        jumpLogBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.tap(v);
                logScroll.fullScroll(View.FOCUS_DOWN);
            }
        });

        logScroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int sx, int sy, int ox, int oy) {
                jumpLogBtn.setVisibility(v.canScrollVertically(1) ? View.VISIBLE : View.GONE);
            }
        });

        card.addView(body, new LinearLayout.LayoutParams(-1, Ui.dp(this, 260)));
        return card;
    }

    /** Plain-text dump of the console for the copy button. */
    private void copyLog() {
        if (entries.isEmpty()) {
            toast("控制台为空");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            if (e.status) {
                sb.append(e.out).append('\n');
            } else {
                sb.append("uid1000$ ").append(e.cmd).append('\n');
                if (e.pending) sb.append("…正在执行\n");
                else if (e.failed) sb.append("uid 1000 无响应\n");
                else {
                    sb.append(e.out).append('\n');
                    if (e.rc != RC_UNKNOWN) sb.append("rc ").append(e.rc).append('\n');
                }
            }
            sb.append('\n');
        }
        try {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("GC Tune", sb.toString()));
            toast("输出已复制");
        } catch (Throwable t) {
            toast("复制失败");
        }
    }

    // ----------------------------------------------------------------- theme
    private View buildThemePage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);

        LinearLayout root = Ui.col(this);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 30));
        scroll.addView(root, new FrameLayout.LayoutParams(-1, -2));

        TextView title = Ui.label(this, "主题", 26f, Ui.TEXT);
        root.addView(pageTitle(title, "安装并应用 .itz"));

        themeHint = hintCard();
        themeHint.setVisibility(View.GONE);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.bottomMargin = Ui.dp(this, 14);
        root.addView(themeHint, hintLp);

        // ---- file card
        LinearLayout fileCard = Ui.col(this);
        fileCard.setBackground(Ui.shape(this, Ui.SURFACE, 12f, Ui.BORDER_SOFT, 1f));
        fileCard.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        fileCard.addView(Ui.section(this, "主题文件（.itz）"));

        themeFileText = Ui.text(this, "未选择文件", 14f, Ui.TEXT);
        LinearLayout.LayoutParams fileLp = new LinearLayout.LayoutParams(-2, -2);
        fileLp.topMargin = Ui.dp(this, 8);
        fileCard.addView(themeFileText, fileLp);

        View choose = pillButton(M3Icons.folder(Ui.ACCENT, Ui.dp(this, 18)), "选择 .itz",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { pickThemeFile(); }
                });
        LinearLayout.LayoutParams chooseLp = new LinearLayout.LayoutParams(-1, -2);
        chooseLp.topMargin = Ui.dp(this, 12);
        fileCard.addView(choose, chooseLp);

        LinearLayout.LayoutParams fileCardLp = new LinearLayout.LayoutParams(-1, -2);
        fileCardLp.bottomMargin = Ui.dp(this, 14);
        root.addView(fileCard, fileCardLp);

        // ---- install button
        View install = pillButton(M3Icons.play(Ui.ACCENT, Ui.dp(this, 18)),
                "安装并打开 iTheme",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { runThemeInstall(); }
                });
        installBtn = install;
        LinearLayout.LayoutParams installLp = new LinearLayout.LayoutParams(-1, -2);
        installLp.bottomMargin = Ui.dp(this, 10);
        root.addView(install, installLp);

        // ---- tools: read-only diagnostics, restore of the pre-install backup, and a
        // rebuild that keeps the structure iTheme already accepts
        LinearLayout tools = Ui.row(this);
        View diagBtn = textButton("主题诊断", new View.OnClickListener() {
            @Override public void onClick(View v) { runThemeDiagnose(); }
        });
        View restoreBtn = textButton("还原原主题", new View.OnClickListener() {
            @Override public void onClick(View v) { runThemeRestore(); }
        });
        View compatBtn = textButton("生成兼容包", new View.OnClickListener() {
            @Override public void onClick(View v) { runThemeExport(); }
        });
        LinearLayout.LayoutParams thirdLp = new LinearLayout.LayoutParams(0, -2, 1f);
        thirdLp.leftMargin = Ui.dp(this, 10);
        tools.addView(diagBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        tools.addView(restoreBtn, thirdLp);
        LinearLayout.LayoutParams compatLp = new LinearLayout.LayoutParams(0, -2, 1f);
        compatLp.leftMargin = Ui.dp(this, 10);
        tools.addView(compatBtn, compatLp);
        LinearLayout.LayoutParams toolsLp = new LinearLayout.LayoutParams(-1, -2);
        toolsLp.bottomMargin = Ui.dp(this, 8);
        root.addView(tools, toolsLp);

        TextView compatHint = Ui.text(this,
                "「生成兼容包」：先用 iTheme 里已下载的主题包作模板，再把你的 .itz 内容套进去。"
                        + "iTheme 只认它自己生成的 description.xml 与包结构，直接覆盖外部 .itz 常被判为非法包并删除。",
                11.5f, Ui.MUTED);
        LinearLayout.LayoutParams compatHintLp = new LinearLayout.LayoutParams(-1, -2);
        compatHintLp.bottomMargin = Ui.dp(this, 14);
        root.addView(compatHint, compatHintLp);

        // ---- status card
        LinearLayout statusCard = Ui.col(this);
        statusCard.setBackground(Ui.shape(this, Ui.INPUT_BG, 12f, Ui.BORDER, 1.2f));
        statusCard.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        themeStatusText = Ui.text(this, "请选择 .itz 并点击「安装」", 13f, Ui.MUTED);
        statusCard.addView(themeStatusText);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.bottomMargin = Ui.dp(this, 14);
        root.addView(statusCard, statusLp);

        TextView note = Ui.text(this,
                "文件副本位于 Download/gctune。应用需在打开游戏模式时进行。",
                11.5f, Ui.MUTED);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
        noteLp.bottomMargin = Ui.dp(this, 24);
        root.addView(note, noteLp);

        return scroll;
    }

    /** Big Expressive page title with generous typography and a muted subtitle underneath. */
    private View pageTitle(TextView title, String subtitle) {
        LinearLayout col = Ui.col(this);
        title.setTextSize(28f);
        title.setLetterSpacing(-0.01f);
        col.addView(title);
        TextView sub = Ui.text(this, subtitle, 12.5f, Ui.MUTED);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-2, -2);
        subLp.topMargin = Ui.dp(this, 4);
        col.addView(sub, subLp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.bottomMargin = Ui.dp(this, 20);
        col.setLayoutParams(lp);
        return col;
    }

    private View pillButton(M3Icons.Icon icon, String label, View.OnClickListener onClick) {
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER);
        // Expressive full capsule pill button (radius = height / 2) with micro-elevation
        row.setBackground(Ui.pillRipple(this, Ui.PRIMARY, 0x40FFFFFF));
        row.setPadding(Ui.dp(this, 18), Ui.dp(this, 13), Ui.dp(this, 18), Ui.dp(this, 13));
        row.setElevation(Ui.dp(this, 2));

        ImageView ic = new ImageView(this);
        ic.setImageDrawable(icon);
        ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));

        TextView lb = Ui.label(this, label, 14f, Ui.TEXT);
        LinearLayout.LayoutParams lbLp = new LinearLayout.LayoutParams(-2, -2);
        lbLp.leftMargin = Ui.dp(this, 10);
        row.addView(lb, lbLp);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.tap(v);
                onClick.onClick(v);
            }
        });
        return row;
    }

    /** Pill button without an icon — for narrow half-width pairs. */
    private View textButton(String label, View.OnClickListener onClick) {
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER);
        row.setBackground(Ui.pillRipple(this, Ui.PRIMARY, 0x40FFFFFF));
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 13), Ui.dp(this, 14), Ui.dp(this, 13));
        row.setElevation(Ui.dp(this, 2));
        TextView lb = Ui.label(this, label, 14f, Ui.TEXT);
        lb.setGravity(Gravity.CENTER);
        row.addView(lb);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.tap(v);
                onClick.onClick(v);
            }
        });
        return row;
    }

    /** "Open the game mode" hint with an arrow, pulses while a command is pending. */
    private View hintCard() {
        LinearLayout card = Ui.row(this);
        // M3 Expressive Container: 22dp corner radius with glowing outline
        card.setBackground(Ui.shape(this, 0x1A6CE5A8, 22f, Ui.ACCENT, 1.2f));
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));

        ImageView arrow = new ImageView(this);
        arrow.setImageDrawable(M3Icons.arrowUpRight(Ui.ACCENT, Ui.dp(this, 28)));
        arrow.setScaleType(ImageView.ScaleType.FIT_CENTER);
        card.addView(arrow, new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32)));

        LinearLayout col = Ui.col(this);
        col.addView(Ui.label(this, "请打开游戏模式", 15.5f, Ui.ACCENT));
        TextView sub = Ui.text(this,
                "打开 Game Cube 面板后，命令将自动执行—— "
                        + "FpsManager 会自动启动它。", 12f, Ui.TEXT);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = Ui.dp(this, 4);
        col.addView(sub, subLp);

        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, -2, 1f);
        colLp.leftMargin = Ui.dp(this, 14);
        card.addView(col, colLp);
        return card;
    }

    // -------------------------------------------------------- theme: picking
    private void pickThemeFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/octet-stream", "application/zip", "application/x-zip-compressed", "*/*"});
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "主题文件（.itz）"), REQ_PICK_ITZ);
        } catch (Throwable t) {
            toast("文件管理器不可用");
        }
    }

    /**
     * Prepares the picked .itz for the uid 1000 channel: prefers the ORIGINAL file
     * path (resolved from the SAF uri) so the command works on the user's own file,
     * falls back to copying into Download/gctune via MediaStore (no permission needed).
     * stagedPath is nulled while staging so an install can never use a stale file.
     */
    private void stageThemeFile(final Uri src) {
        if (staging) {
            toast("文件仍在准备中");
            return;
        }
        staging = true;
        stagedPath = null;
        stagedMd5 = null;
        themeFileText.setText("准备中…");
        themeStatus("正在读取文件…", Ui.MUTED);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final String picked = queryDisplayName(src);
                    String path = null;
                    String md5 = null;

                    // 1) the picked file itself, if it already lives on /storage
                    String real = resolveRealPath(src);
                    if (real != null && real.startsWith("/")
                            && (real.startsWith("/storage/") || real.startsWith("/sdcard/"))
                            && !real.contains("/Android/") && new File(real).isFile()) {
                        try {
                            md5 = md5File(new File(real));
                            path = real;
                        } catch (Throwable t) {
                            Log.e("GCTune", "direct stage failed: " + t);
                            path = null;
                        }
                    }

                    // 2) copy into a location the channel can read
                    if (path == null) {
                        String staged = null;
                        Throwable firstErr = null;
                        if (Build.VERSION.SDK_INT >= 29) {
                            try {
                                staged = copyToDownloads(src, STAGE_NAME);
                            } catch (Throwable t) {
                                Log.e("GCTune", "MediaStore stage failed: " + t);
                                firstErr = t;
                            }
                        }
                        if (staged == null) {
                            try {
                                staged = copyToAppFiles(src, STAGE_NAME);
                            } catch (Throwable t) {
                                if (firstErr == null) firstErr = t;
                            }
                        }
                        if (staged == null) {
                            throw firstErr != null ? firstErr : new Exception("复制失败");
                        }
                        // reading it back proves the reported path really holds the copy
                        md5 = md5File(new File(staged));
                        path = staged;
                    }

                    // 2.5) id-substitution: custom .itz files keep the ids of paid
                    // store themes as placeholders, so iTheme shows a price on them.
                    // Stage a copy with neutral ids instead of the picked file.
                    String idNote = null;
                    if (path != null && path.toLowerCase(java.util.Locale.ROOT).endsWith(".itz")) {
                        File dl = Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS);
                        File dst = new File(dl, STAGE_DIR + "/" + STAGE_NAME);
                        File tmp = new File(dl, STAGE_DIR + "/" + STAGE_NAME + ".tmp");
                        try {
                            int n = ThemePatcher.patch(new File(path), tmp);
                            if (n > 0) {
                                // rename replaces the destination atomically (same dir)
                                if (!tmp.renameTo(dst)) {
                                    throw new Exception("写入失败：" + dst.getName());
                                }
                                path = dst.getAbsolutePath();
                                md5 = md5File(dst);
                                idNote = "id 已替换：" + n + " → "
                                        + ThemePatcher.FREE_ID + "/" + ThemePatcher.FREE_UID;
                            } else {
                                tmp.delete();
                                idNote = "description.xml 中没有 id——文件未改动";
                            }
                        } catch (Throwable t) {
                            Log.e("GCTune", "id patch failed: " + t);
                            tmp.delete();
                            idNote = "id 未替换：" + t.getMessage();
                        }
                    }

                    final String fp = path;
                    final String idNoteF = idNote;
                    final String fm = md5;
                    final long size = new File(path).length();
                    final String sizeTxt = humanSize(size);
                    h.post(new Runnable() {
                        @Override public void run() {
                            staging = false;
                            stagedPath = fp;
                            stagedMd5 = fm;
                            themeFileText.setText(picked + " · " + sizeTxt + "\n" + fp);
                            String pack = "";
                            try {
                                pack = ThemePatcher.inspect(new File(fp));
                            } catch (Throwable ignore) {
                            }
                            boolean bad = pack.contains("不能") || pack.contains("缺失");
                            themeStatus("文件已就绪，md5 " + fm + "——可以安装"
                                    + (idNoteF != null ? "\n" + idNoteF : "")
                                    + (pack.isEmpty() ? "" : "\n" + pack), bad ? Ui.ERROR : Ui.TEXT);
                        }
                    });
                } catch (final Throwable t) {
                    h.post(new Runnable() {
                        @Override public void run() {
                            staging = false;
                            stagedPath = null;
                            stagedMd5 = null;
                            themeFileText.setText("未选择文件");
                            themeStatus("准备出错：" + t.getMessage(), Ui.ERROR);
                        }
                    });
                }
            }
        }).start();
    }

    /** Best-effort filesystem path behind a SAF/content uri (null if none). */
    private String resolveRealPath(Uri uri) {
        if (uri == null) return null;
        if ("file".equals(uri.getScheme())) return uri.getPath();
        Cursor c = null;
        try {
            c = getContentResolver().query(uri,
                    new String[]{MediaStore.MediaColumns.DATA}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String p = c.getString(0);
                if (p != null && !p.isEmpty()) return p;
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    /** Streaming MD5 of a local file (also used to verify what the channel reads). */
    private static String md5File(File f) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        InputStream in = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
        byte[] d = md.digest();
        StringBuilder sb = new StringBuilder(32);
        for (byte b : d) sb.append(String.format(java.util.Locale.US, "%02x", b));
        return sb.toString();
    }

    private String queryDisplayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return STAGE_NAME;
    }

    /** API 29+: public Download/gctune/theme.itz via MediaStore (no storage permission). */
    private String copyToDownloads(Uri src, String name) throws Exception {
        ContentResolver r = getContentResolver();
        String rel = Environment.DIRECTORY_DOWNLOADS + "/" + STAGE_DIR;

        Cursor old = r.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.RELATIVE_PATH},
                MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                        + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                new String[]{name, rel + "/"}, null);
        if (old != null) {
            while (old.moveToNext()) {
                try {
                    r.delete(ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, old.getLong(0)), null, null);
                } catch (Throwable ignored) {}
            }
            old.close();
        }

        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
        Uri out = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (out == null) throw new Exception("MediaStore insert failed");

        stagedBytes = 0;
        InputStream in = r.openInputStream(src);
        if (in == null) throw new Exception("来源不可用");
        OutputStream os = r.openOutputStream(out);
        if (os == null) {
            in.close();
            throw new Exception("存储不可用");
        }
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                stagedBytes += n;
            }
            os.flush();
        } finally {
            try { os.close(); } catch (Throwable ignored) {}
            try { in.close(); } catch (Throwable ignored) {}
        }

        String path = null;
        Cursor q = r.query(out, new String[]{MediaStore.MediaColumns.DATA}, null, null, null);
        if (q != null) {
            if (q.moveToFirst()) path = q.getString(0);
            q.close();
        }
        if (path == null || path.isEmpty()) {
            path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    + "/" + STAGE_DIR + "/" + name;
        }
        return path;
    }

    /** Fallback: app-specific external dir (needs no permission on any API level). */
    private String copyToAppFiles(Uri src, String name) throws Exception {
        File dir = getExternalFilesDir(null);
        if (dir == null) throw new Exception("无外部存储");
        File f = new File(dir, name);

        InputStream in = getContentResolver().openInputStream(src);
        if (in == null) throw new Exception("来源不可用");
        OutputStream os = new FileOutputStream(f);
        stagedBytes = 0;
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                stagedBytes += n;
            }
            os.flush();
        } finally {
            try { os.close(); } catch (Throwable ignored) {}
            try { in.close(); } catch (Throwable ignored) {}
        }
        return f.getAbsolutePath();
    }

    private static String humanSize(long bytes) {
        if (bytes >= 1048576L) {
            return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
        }
        if (bytes >= 1024L) return (bytes / 1024L) + " KB";
        return bytes + " B";
    }

    // ---------------------------------------------------------- theme: install
    private void runThemeInstall() {
        if (busy) {
            toast("请等待当前命令执行完成");
            return;
        }
        if (staging) {
            toast("文件仍在准备中，请稍候");
            return;
        }
        if (stagedPath == null || stagedPath.isEmpty()) {
            toast("请先选择 .itz");
            return;
        }
        themeRun = true;
        themeStatus("命令已发送——请打开游戏模式…", Ui.ACCENT);
        showHint();
        if (!apply(themeCommand())) {
            themeRun = false;
            hideHint();
            themeStatus("GC Tune 不在游戏列表中——请添加应用后重试", Ui.ERROR);
        }
    }

    /**
     * Install command: probes the staged source (and verifies its md5 against the one
     * computed at staging time, so a stale file is reported instead of silently used),
     * backs up every .itz inside the theme package's .../T directory to
     * "&lt;file&gt;.gctune-bak" keeping its modification time (an existing backup is never
     * overwritten, so the oldest copy stays pristine), stops iTheme, drops the saved
     * integrity fingerprint (shared_prefs/itz_integrity.xml), replaces each package and
     * restores its original mtime, verifies every copy, opens iTheme, then waits and
     * checks whether iTheme dropped any package it deemed invalid - if one disappeared
     * it is restored from the backup and reported as RESTORED:.
     * Why mtime matters: iTheme matches its cached downloadTime against
     * File.lastModified(), so a rewritten file with a fresh mtime drops out of the local
     * theme list even when its size is unchanged. Why the fingerprint matters:
     * ThemeIntegrityInterceptor compares md5(zip)/md5(description.xml) against the saved
     * values and returns Response(-1) instead of applying when they differ.
     * Package name is derived from the .dwd path letters - no hard-coded device, theme or
     * file names.
     */
    private String themeCommand() {
        String src = (stagedPath == null ? "" : stagedPath).replace("'", "");
        String expect = stagedMd5 == null ? "" : stagedMd5;
        return "EXPECT=" + expect + ";S=;for p in '" + src + "'"
                + " /storage/emulated/0/Download/" + STAGE_DIR + "/" + STAGE_NAME
                + " /sdcard/Download/" + STAGE_DIR + "/" + STAGE_NAME
                + " /storage/emulated/0/Android/data/" + PKG + "/files/" + STAGE_NAME
                + ";do [ -r \"$p\" ]&&S=\"$p\"&&break;done;"
                + "if [ -z \"$S\" ];then echo SRC_UNREADABLE;else A=$(md5sum \"$S\"|cut -d' ' -f1);"
                + "if [ -n \"$EXPECT\" ]&&[ \"$A\" != \"$EXPECT\" ];then echo SRC_STALE:$A;else echo SRC_OK;"
                + "LIST=$(find /data/bbkcore -name '*.itz' -path '*/T/*' 2>/dev/null);"
                + "if [ -z \"$LIST\" ];then echo NO_THEME_FOUND;else "
                // 1) back up every target first (an existing backup is never overwritten)
                // iTheme matches its cached downloadTime against file.lastModified(), so the
                // backup and the replaced file must keep the original modification time.
                + "echo \"$LIST\"|while read T;do [ -f \"$T.gctune-bak\" ]||{ cp -p \"$T\" \"$T.gctune-bak\" 2>/dev/null||cp \"$T\" \"$T.gctune-bak\" 2>/dev/null; };done;"
                + "echo BAK=$(echo \"$LIST\"|while read T;do [ -f \"$T.gctune-bak\" ]&&echo x;done|wc -l);"
                // 2) derive the iTheme package from the .dwd path letters
                + "T1=$(echo \"$LIST\"|head -n1);P=${T1%/*};P=${P%/*};P=${P##*/.dwd/};LE=$(echo \"$P\"|tr -d /);"
                + "PKG=$(pm list packages 2>/dev/null|sed 's/^package://'|while read q;do [ \"$(echo \"$q\"|tr -d .)\" = \"$LE\" ]&&echo \"$q\";done|head -n1);"
                // iTheme stores md5(zip) and md5(description.xml) per package in
                // shared_prefs/itz_integrity.xml and rejects a package whose hashes changed
                // (ThemeIntegrityInterceptor -> Response(-1)). Dropping the record makes it
                // recompute and save the new file's hashes on the next apply.
                + "case \"$PKG\" in *.*.*)am force-stop \"$PKG\";sleep 1;"
                + "D=/data/data/$PKG/shared_prefs;F=0;"
                + "for SP in $D/itz_integrity.xml $D/*itz_integrity*;do [ -e \"$SP\" ]||continue;F=1;"
                + "if [ -r \"$SP\" ];then cp -f \"$SP\" \"$SP.gctune-bak\" 2>/dev/null;"
                + "rm -f \"$SP\" 2>/dev/null&&echo INTEGRITY_CLEARED||echo INTEGRITY_NOPERM;"
                + "else echo INTEGRITY_NOREAD;fi;done;[ $F = 0 ]&&echo INTEGRITY_ABSENT;;esac;"
                // 3) replace and verify every package
                + "echo \"$LIST\"|while read T;do cat \"$S\">\"$T\" 2>/dev/null;"
                + "[ -f \"$T.gctune-bak\" ]&&touch -r \"$T.gctune-bak\" \"$T\" 2>/dev/null;"
                + "B=$(md5sum \"$T\"|cut -d' ' -f1);[ \"$A\" = \"$B\" ]&&echo OK:$T||echo FAIL:$T;done;"
                + "echo FOUND=$(echo \"$LIST\"|wc -l);"
                // 4) open iTheme
                + "case \"$PKG\" in *.*.*)"
                + " ACT=$(cmd package resolve-activity --brief -c android.intent.category.LAUNCHER \"$PKG\" 2>/dev/null|tail -n1);echo ACT=$ACT;"
                + " O=$(am start -n \"$ACT\" 2>&1|tail -n3);echo \"$O\";"
                + " echo \"$O\"|grep -q Starting&&echo APP_OPENED||{ M=$(monkey -p \"$PKG\" -c android.intent.category.LAUNCHER 1 2>&1|tail -n2);echo \"$M\";echo \"$M\"|grep -q 'Events injected'&&echo APP_OPENED||echo APP_FAILED; };;"
                + "*)echo NO_PKG;;esac;"
                // 5) iTheme may drop a package it considers invalid: detect and restore it
                + "sleep 6;L=0;for T in $LIST;do if [ ! -f \"$T\" ];then if [ -f \"$T.gctune-bak\" ]&&cp -p \"$T.gctune-bak\" \"$T\" 2>/dev/null;then echo RESTORED:$T;fi;L=$((L+1));fi;done;echo LOST=$L;"
                + "fi;fi;fi";
    }

    /**
     * Read-only dump of the theme directories plus the iTheme log tail. Runs through
     * the same uid 1000 channel (there is no other way to see /data/bbkcore), so it
     * still needs the game mode - but it never writes anything.
     */
    private String themeDiagCommand() {
        return "echo ITZ_COUNT=$(find /data/bbkcore -name '*.itz' -path '*/T/*' 2>/dev/null|wc -l);"
                + "find /data/bbkcore -name '*.itz' -path '*/T/*' 2>/dev/null|while read T;do"
                + " echo \"ITZ:$T\";"
                + " echo \"  size=$(stat -c %s \"$T\" 2>/dev/null) mtime=$(stat -c %y \"$T\" 2>/dev/null|cut -c1-19) head=$(head -c 4 \"$T\" 2>/dev/null)\";"
                + "done;"
                + "echo BAK=$(find /data/bbkcore -name '*.gctune-bak' 2>/dev/null|wc -l);"
                + "find /data/bbkcore -name '*.gctune-bak' 2>/dev/null|head -n 10|while read B;do echo \"BAK:$B\";done;"
                + "echo ---DIRS---;"
                + "find /data/bbkcore -maxdepth 5 -type d 2>/dev/null|head -n 20;"
                + "echo ---LOG---;"
                + "logcat -d -t 4000 2>/dev/null|grep -iE 'itheme|bbk\\.theme|theme'|tail -n 20";
    }

    /** Copies every *.gctune-bak back over its original file. */
    private String themeRestoreCommand() {
        return "find /data/bbkcore -name '*.gctune-bak' 2>/dev/null|while read B;do"
                + " T=$(echo \"$B\"|sed 's/\\.gctune-bak$//');"
                + " if cp -p \"$B\" \"$T\" 2>/dev/null||cp \"$B\" \"$T\" 2>/dev/null; then echo RESTORED:$T; fi; done;"
                + "echo BAK_LEFT=$(find /data/bbkcore -name '*.gctune-bak' 2>/dev/null|wc -l)";
    }

    private void themeStatus(String s, int color) {
        if (themeStatusText == null) return;
        themeStatusText.setText(s);
        themeStatusText.setTextColor(color);
    }

    private void showHint() {
        if (themeHint == null) return;
        themeHint.setVisibility(View.VISIBLE);
        if (hintPulse == null) {
            hintPulse = ObjectAnimator.ofFloat(themeHint, View.ALPHA, 1f, 0.35f);
            hintPulse.setDuration(700);
            hintPulse.setRepeatCount(ValueAnimator.INFINITE);
            hintPulse.setRepeatMode(ValueAnimator.REVERSE);
        }
        hintPulse.start();
    }

    private void hideHint() {
        if (themeHint == null) return;
        if (hintPulse != null) hintPulse.cancel();
        themeHint.setAlpha(1f);
        themeHint.setVisibility(View.GONE);
    }

    /** Turns the uid 1000 output of the theme command into a human status line. */
    private void themeResult(boolean ok, Entry e) {
        themeRun = false;
        hideHint();
        String out = (ok && e != null && e.out != null) ? e.out : "";
        if (!ok) {
            themeStatus("uid 1000 无响应——请打开游戏模式后重试", Ui.ERROR);
        } else if (out.contains("SRC_UNREADABLE")) {
            themeStatus("uid 1000 无法读取来源文件", Ui.ERROR);
        } else if (out.contains("SRC_STALE")) {
            themeStatus("来源与所选文件不一致——请重新选择", Ui.ERROR);
        } else if (out.contains("NO_THEME_FOUND")) {
            themeStatus("目录 .../theme/.dwd/.../T 中没有 .itz 文件", Ui.ERROR);
        } else if (out.contains("NO_PKG")) {
            themeStatus("无法确定 iTheme 包——详情见控制台", Ui.ERROR);
        } else if (!out.contains("OK:")) {
            themeStatus("写入失败——输出见控制台（Shell 标签页）", Ui.ERROR);
        } else {
            int n = countOccurrences(out, "OK:");
            int fails = countOccurrences(out, "FAIL:");
            int restored = countOccurrences(out, "RESTORED:");
            int lost = numAfter(out, "LOST=");
            boolean integCleared = out.contains("INTEGRITY_CLEARED");
            boolean integBlocked = out.contains("INTEGRITY_NOPERM") || out.contains("INTEGRITY_NOREAD");
            String base = "已替换文件：" + n + (fails > 0 ? "，错误：" + fails : "");
            if (restored > 0) {
                // iTheme accepted nothing and wiped the package: the backup was put back
                themeStatus("iTheme 仍不认这个主题包：已还原原来的 " + restored
                        + " 个本地主题文件（下载的主题没有丢）——请换一个 .itz，"
                        + "或点「生成兼容包」用原主题包做模板", Ui.ERROR);
            } else if (out.contains("APP_FAILED")) {
                themeStatus(base + "——iTheme 未打开，输出见控制台", Ui.ERROR);
            } else if (lost > 0) {
                themeStatus(base + "，但 iTheme 移除了 " + lost
                        + " 个文件（备份仍在，可点「还原原主题」）", Ui.ERROR);
            } else if (out.contains("APP_OPENED")) {
                themeStatus(base + "，已保留原修改时间（不会从本地主题列表消失），iTheme 已打开——请应用主题"
                        + (integCleared ? "（旧校验记录已清除）"
                        : integBlocked ? "（无权限清除校验记录：应用时若提示主题无效，属正常限制）" : ""), Ui.ACCENT);
            } else {
                themeStatus(base + "——详情见控制台", Ui.TEXT);
            }
        }
    }

    // ------------------------------------------------- theme: diagnose / restore
    private void runThemeDiagnose() {
        if (busy) {
            toast("请等待当前命令执行完成");
            return;
        }
        themeDiagRun = true;
        themeStatus("正在收集主题目录信息（只读）…", Ui.ACCENT);
        showHint();
        if (!apply(themeDiagCommand())) {
            themeDiagRun = false;
            hideHint();
            themeStatus("GC Tune 不在游戏列表中——请添加应用后重试", Ui.ERROR);
        }
    }

    private void themeDiagResult(boolean ok, Entry e) {
        themeDiagRun = false;
        hideHint();
        if (!ok) {
            themeStatus("uid 1000 无响应——请打开游戏模式后重试", Ui.ERROR);
            return;
        }
        String out = e != null && e.out != null ? e.out.trim() : "";
        int itz = numAfter(out, "ITZ_COUNT=");
        int bak = numAfter(out, "BAK=");
        themeStatus("诊断完成：.itz " + (itz < 0 ? "?" : String.valueOf(itz))
                + " 个，备份 " + (bak < 0 ? "?" : String.valueOf(bak))
                + " 个——详情见弹窗", Ui.ACCENT);
        String raw = out.isEmpty() ? "（没有输出）" : out;
        final String body = raw.length() > 4000 ? raw.substring(0, 4000) + "…" : raw;
        new AlertDialog.Builder(this)
                .setTitle("主题诊断（只读）")
                .setMessage(body)
                .setPositiveButton("复制文本", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { copyToClipboard(body); }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void runThemeRestore() {
        if (busy) {
            toast("请等待当前命令执行完成");
            return;
        }
        themeRestoreRun = true;
        themeStatus("正在用备份还原原主题…", Ui.ACCENT);
        showHint();
        if (!apply(themeRestoreCommand())) {
            themeRestoreRun = false;
            hideHint();
            themeStatus("GC Tune 不在游戏列表中——请添加应用后重试", Ui.ERROR);
        }
    }

    private void themeRestoreResult(boolean ok, Entry e) {
        themeRestoreRun = false;
        hideHint();
        if (!ok) {
            themeStatus("uid 1000 无响应——请打开游戏模式后重试", Ui.ERROR);
            return;
        }
        String out = e != null && e.out != null ? e.out.trim() : "";
        int restored = countOccurrences(out, "RESTORED:");
        int left = numAfter(out, "BAK_LEFT=");
        if (restored > 0) {
            themeStatus("已还原 " + restored + " 个原主题文件——重启 iTheme 后查看本地主题", Ui.ACCENT);
        } else if (left == 0) {
            themeStatus("没有可还原的备份（此前未执行过带备份的安装）", Ui.MUTED);
        } else {
            themeStatus("还原失败——剩余备份 " + left + " 个，输出见控制台", Ui.ERROR);
        }
    }

    // ------------------------------------------------- theme: compatible rebuild

    /** App-private copy of the iTheme package used as the rebuild template. */
    private File origThemeFile() {
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        return new File(dir, ORIG_NAME);
    }

    /** Rebuilt package (template structure + the user's resources). */
    private File compatThemeFile() {
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        return new File(dir, COMPAT_NAME);
    }

    /**
     * Exports the package iTheme already manages - its pre-install backup when present,
     * the current package otherwise - into the app's own files directory, so the "compat"
     * package can be rebuilt from a structure iTheme accepts. Needs uid 1000, because
     * /data/bbkcore is not readable for a normal app.
     */
    private void runThemeExport() {
        if (busy) {
            toast("请等待当前命令执行完成");
            return;
        }
        if (staging) {
            toast("文件仍在准备中，请稍候");
            return;
        }
        if (stagedPath == null || stagedPath.isEmpty()) {
            toast("请先选择 .itz");
            return;
        }
        themeExportRun = true;
        themeStatus("正在从 iTheme 取原主题包作模板…", Ui.ACCENT);
        showHint();
        if (!apply(themeExportCommand())) {
            themeExportRun = false;
            hideHint();
            themeStatus("GC Tune 不在游戏列表中——请添加应用后重试", Ui.ERROR);
        }
    }

    /** Copies the managed .itz (backup first) into the app's files directory. */
    private String themeExportCommand() {
        String dst = origThemeFile().getAbsolutePath();
        return "D='" + dst + "';"
                + "T=$(find /data/bbkcore -name '*.itz' -path '*/T/*' 2>/dev/null|head -n1);"
                + "if [ -z \"$T\" ];then echo NO_THEME_FOUND;else "
                + "if [ -f \"$T.gctune-bak\" ];then S=\"$T.gctune-bak\";else S=\"$T\";fi;"
                + "if cp -p \"$S\" \"$D\" 2>/dev/null||cp \"$S\" \"$D\" 2>/dev/null;then "
                + "echo EXPORT_OK:$S;echo SIZE=$(stat -c %s \"$D\" 2>/dev/null);"
                + "else echo EXPORT_FAIL;fi;fi";
    }

    /** Rebuilds the picked .itz on top of the exported template, then arms the installer. */
    private void themeExportResult(boolean ok, Entry e) {
        themeExportRun = false;
        hideHint();
        if (!ok) {
            themeStatus("uid 1000 无响应——请打开游戏模式后重试", Ui.ERROR);
            return;
        }
        String out = e != null && e.out != null ? e.out : "";
        if (out.contains("NO_THEME_FOUND")) {
            themeStatus("iTheme 里还没有已下载的主题包——先在主题 App 里下载一个免费主题再试", Ui.ERROR);
            return;
        }
        if (!out.contains("EXPORT_OK")) {
            themeStatus("导出原主题包失败——输出见控制台（Shell 标签页）", Ui.ERROR);
            return;
        }
        themeStatus("模板已导出，正在生成兼容包…", Ui.ACCENT);
        final File tpl = origThemeFile();
        final File user = new File(stagedPath);
        final File dst = compatThemeFile();
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                int[] cnt = null;
                try {
                    cnt = ThemePatcher.merge(tpl, user, dst);
                } catch (Throwable t) {
                    err = String.valueOf(t.getMessage());
                }
                final String errF = err;
                final int[] cntF = cnt;
                h.post(new Runnable() {
                    @Override public void run() {
                        if (errF != null) {
                            themeStatus("生成兼容包失败：" + errF, Ui.ERROR);
                            return;
                        }
                        try {
                            stagedPath = dst.getAbsolutePath();
                            stagedMd5 = md5File(dst);
                            themeFileText.setText("兼容包 " + humanSize(dst.length())
                                    + "\n" + stagedPath);
                            themeStatus("兼容包已生成（替换模板里的 " + cntF[0] + " 个文件，保留模板结构 "
                                    + cntF[1] + " 项）——点「安装并打开 iTheme」", Ui.ACCENT);
                        } catch (Throwable t) {
                            themeStatus("兼容包已生成，但读取失败：" + t.getMessage(), Ui.ERROR);
                        }
                    }
                });
            }
        }).start();
    }

    /** Copies text to the system clipboard. */
    private void copyToClipboard(String text) {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(android.content.ClipData.newPlainText("gctune", text));
        toast("已复制");
    }

    /** Reads the integer that follows key in the uid 1000 output, or -1. */
    private static int numAfter(String s, String key) {
        if (s == null) return -1;
        int i = s.indexOf(key);
        if (i < 0) return -1;
        int j = i + key.length(), k = j;
        while (k < s.length() && Character.isDigit(s.charAt(k))) k++;
        if (k == j) return -1;
        try { return Integer.parseInt(s.substring(j, k)); } catch (NumberFormatException ex) { return -1; }
    }

    private static int countOccurrences(String haystack, String needle) {
        int i = 0, n = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    // ---------------------------------------------------------------- icons
    private View buildIconsPage() {
        // saved tuning survives restarts
        iconsColor = prefs.getInt("icons_color", iconsColor);
        iconsDesat = prefs.getInt("icons_desat", iconsDesat);
        iconsLighten = prefs.getInt("icons_lighten", iconsLighten);
        iconsStrength = prefs.getInt("icons_strength", iconsStrength);
        iconsPackPkg = prefs.getString("icons_pack", iconsPackPkg);
        iconsPackLabel = prefs.getString("icons_pack_label", iconsPackLabel);
        iconsBgEnabled = prefs.getBoolean("icons_bg", iconsBgEnabled);
        iconsBgColor = prefs.getInt("icons_bg_color", iconsBgColor);
        iconsMonetEnabled = prefs.getBoolean("icons_monet", iconsMonetEnabled);
        if (iconsMonetEnabled) {
            iconsColor = getMonetPrimaryColor();
        }
        iconsScale = prefs.getInt("icons_scale", iconsScale);
        iconsFgScale = prefs.getInt("icons_fg_scale", iconsFgScale);
        iconsRadius = prefs.getInt("icons_radius", iconsRadius);
        iconsShape = prefs.getInt("icons_shape", iconsShape);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);

        LinearLayout root = Ui.col(this);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 30));
        scroll.addView(root, new FrameLayout.LayoutParams(-1, -2));

        TextView iconsTitle = Ui.label(this, "图标", 26f, Ui.TEXT);
        root.addView(pageTitle(iconsTitle, "图标着色与样式"));

        // ---- preview card
        LinearLayout previewCard = Ui.col(this);
        previewCard.setBackground(Ui.shape(this, Ui.SURFACE, 12f, Ui.BORDER_SOFT, 1f));
        previewCard.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        previewCard.addView(Ui.section(this, "预览"));
        iconsPreviewRow = new LinearLayout(this);
        iconsPreviewRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(-1, -2);
        prevLp.topMargin = Ui.dp(this, 10);
        previewCard.addView(iconsPreviewRow, prevLp);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.bottomMargin = Ui.dp(this, 14);
        root.addView(previewCard, cardLp);

        // ---- icon source: system icons or an imported icon pack
        LinearLayout srcCard = Ui.col(this);
        srcCard.setBackground(Ui.shape(this, Ui.SURFACE, 12f, Ui.BORDER_SOFT, 1f));
        srcCard.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        srcCard.addView(Ui.section(this, "图标来源"));

        LinearLayout srcRow = new LinearLayout(this);
        srcRow.setOrientation(LinearLayout.HORIZONTAL);
        srcRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams srcRowLp = new LinearLayout.LayoutParams(-1, -2);
        srcRowLp.topMargin = Ui.dp(this, 10);
        iconsSourceValue = Ui.text(this, iconsPackPkg.isEmpty() ? "系统图标" : iconsPackLabel,
                14f, Ui.TEXT);
        srcRow.addView(iconsSourceValue, new LinearLayout.LayoutParams(0, -2, 1f));
        View pickBtn = pillButton(M3Icons.folder(Ui.ACCENT, Ui.dp(this, 18)), "图标包",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { chooseIconPack(); }
                });
        LinearLayout.LayoutParams pickLp = new LinearLayout.LayoutParams(-2, -2);
        pickLp.leftMargin = Ui.dp(this, 10);
        srcRow.addView(pickBtn, pickLp);
        srcCard.addView(srcRow);

        LinearLayout.LayoutParams srcCardLp = new LinearLayout.LayoutParams(-1, -2);
        srcCardLp.topMargin = Ui.dp(this, 16);
        srcCardLp.bottomMargin = Ui.dp(this, 14);
        root.addView(srcCard, srcCardLp);

        // ---- color picker & Monet mode
        LinearLayout colorCard = Ui.col(this);
        colorCard.setBackground(Ui.shape(this, Ui.SURFACE, 12f, Ui.BORDER_SOFT, 1f));
        colorCard.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        colorCard.addView(Ui.section(this, "着色颜色"));

        // Switch: Monet (Auto-extract colors from wallpaper) vs Manual
        LinearLayout monetRow = new LinearLayout(this);
        monetRow.setOrientation(LinearLayout.HORIZONTAL);
        monetRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams monetRowLp = new LinearLayout.LayoutParams(-1, -2);
        monetRowLp.topMargin = Ui.dp(this, 8);
        LinearLayout monetTextCol = Ui.col(this);
        monetTextCol.addView(Ui.text(this, "Monet（壁纸取色）", 14f, Ui.TEXT));
        monetTextCol.addView(Ui.text(this, "自动从壁纸获取主题颜色", 11.5f, Ui.MUTED));
        monetRow.addView(monetTextCol, new LinearLayout.LayoutParams(0, -2, 1f));

        final MaterialSwitch monetSw = new MaterialSwitch(this);
        monetSw.setChecked(iconsMonetEnabled);
        tintSwitch(monetSw);
        monetRow.addView(monetSw);
        colorCard.addView(monetRow, monetRowLp);

        // Container for manual color selection (presets + HEX)
        manualColorBox = Ui.col(this);
        manualColorBox.setVisibility(iconsMonetEnabled ? View.GONE : View.VISIBLE);

        // preset swatches
        String[] presets = {"#3D7D56", "#E53935", "#1E88E5", "#FDD835", "#8E24AA",
                "#FB8C00", "#00ACC1", "#F06292", "#5D4037", "#455A64"};
        LinearLayout swatchRow = new LinearLayout(this);
        swatchRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(-1, -2);
        swLp.topMargin = Ui.dp(this, 12);
        manualColorBox.addView(swatchRow, swLp);
        for (String hex : presets) {
            final int color = parseColor(hex);
            View sw = swatch(color);
            sw.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { Ui.tap(v); setIconsColor(color); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34));
            lp.rightMargin = Ui.dp(this, 8);
            swatchRow.addView(sw, lp);
        }

        // hex input
        LinearLayout hexRow = new LinearLayout(this);
        hexRow.setOrientation(LinearLayout.HORIZONTAL);
        hexRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hexLp = new LinearLayout.LayoutParams(-1, -2);
        hexLp.topMargin = Ui.dp(this, 12);
        manualColorBox.addView(hexRow, hexLp);
        hexRow.addView(Ui.label(this, "HEX:", 13f, Ui.MUTED));
        iconsColorInput = new EditText(this);
        iconsColorInput.setText(String.format("#%06X", iconsColor & 0xFFFFFF));
        iconsColorInput.setInputType(InputType.TYPE_CLASS_TEXT);
        iconsColorInput.setTextSize(13f);
        iconsColorInput.setTextColor(Ui.TEXT);
        iconsColorInput.setGravity(Gravity.CENTER);
        iconsColorInput.setBackground(Ui.shape(this, Ui.INPUT_BG, 8f, Ui.BORDER, 1f));
        iconsColorInput.setPadding(Ui.dp(this, 8), Ui.dp(this, 5), Ui.dp(this, 8), Ui.dp(this, 5));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, -2, 1f);
        inputLp.leftMargin = Ui.dp(this, 10);
        hexRow.addView(iconsColorInput, inputLp);
        iconsColorInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                try {
                    setIconsColor(parseColor(s.toString().trim()));
                } catch (Throwable ignored) {}
            }
        });

        colorCard.addView(manualColorBox);

        monetSw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Ui.tap(b);
                iconsMonetEnabled = checked;
                manualColorBox.setVisibility(checked ? View.GONE : View.VISIBLE);
                if (checked) {
                    int monet = getMonetPrimaryColor();
                    setIconsColor(monet);
                    toast("颜色已从壁纸获取（Monet）");
                }
                saveIconsPrefs();
                refreshIconsPreview();
            }
        });

        LinearLayout.LayoutParams colorCardLp = new LinearLayout.LayoutParams(-1, -2);
        colorCardLp.bottomMargin = Ui.dp(this, 14);
        root.addView(colorCard, colorCardLp);

        // ---- fine tuning sliders
        LinearLayout tuneCard = Ui.col(this);
        tuneCard.setBackground(Ui.shape(this, Ui.SURFACE, 12f, Ui.BORDER_SOFT, 1f));
        tuneCard.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        tuneCard.addView(Ui.section(this, "精细调节"));
        sbDesat = addSlider(tuneCard, "降低饱和度", iconsDesat, new IntSetter() {
            @Override public void set(int v) { iconsDesat = v; }
        });
        sbLighten = addSlider(tuneCard, "提亮", iconsLighten, new IntSetter() {
            @Override public void set(int v) { iconsLighten = v; }
        });
        sbStrength = addSlider(tuneCard, "着色强度", iconsStrength, new IntSetter() {
            @Override public void set(int v) { iconsStrength = v; }
        });
        View resetBtn = pillButton(M3Icons.tune(Ui.TEXT, Ui.dp(this, 18)), "重置设置",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { resetIconsTuning(); }
                });
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(-1, -2);
        resetLp.topMargin = Ui.dp(this, 12);
        tuneCard.addView(resetBtn, resetLp);
        LinearLayout.LayoutParams tuneCardLp = new LinearLayout.LayoutParams(-1, -2);
        tuneCardLp.bottomMargin = Ui.dp(this, 14);
        root.addView(tuneCard, tuneCardLp);

        // ---- IconPack Studio style: background layer + foreground icon
        LinearLayout styleCard = Ui.col(this);
        styleCard.setBackground(Ui.shape(this, Ui.SURFACE, 12f, Ui.BORDER_SOFT, 1f));
        styleCard.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        styleCard.addView(Ui.section(this, "样式：背景 + 图标"));

        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(-1, -2);
        switchLp.topMargin = Ui.dp(this, 8);
        TextView switchLabel = Ui.text(this, "分层（同 IconPack Studio）", 14f, Ui.TEXT);
        switchRow.addView(switchLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        final MaterialSwitch sw = new MaterialSwitch(this);
        sw.setChecked(iconsBgEnabled);
        tintSwitch(sw);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                iconsBgEnabled = checked;
                saveIconsPrefs();
                refreshIconsPreview();
            }
        });
        switchRow.addView(sw);
        styleCard.addView(switchRow, switchLp);

        // background color: presets + hex
        LinearLayout bgRow = new LinearLayout(this);
        bgRow.setOrientation(LinearLayout.HORIZONTAL);
        bgRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bgRowLp = new LinearLayout.LayoutParams(-1, -2);
        bgRowLp.topMargin = Ui.dp(this, 10);
        bgRow.addView(Ui.text(this, "背景颜色：", 13f, Ui.MUTED));
        String[] bgPresets = {"#FFFFFF", "#F5F5F5", "#212121", "#000000", "#3D7D56", "#1E88E5"};
        for (String hex : bgPresets) {
            final int color = parseColor(hex);
            View s = swatch(color);
            s.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { Ui.tap(v); setIconsBgColor(color); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26));
            lp.leftMargin = Ui.dp(this, 6);
            bgRow.addView(s, lp);
        }
        iconsBgInput = new EditText(this);
        iconsBgInput.setText(String.format("#%06X", iconsBgColor & 0xFFFFFF));
        iconsBgInput.setInputType(InputType.TYPE_CLASS_TEXT);
        iconsBgInput.setTextSize(12f);
        iconsBgInput.setTextColor(Ui.TEXT);
        iconsBgInput.setGravity(Gravity.CENTER);
        iconsBgInput.setBackground(Ui.shape(this, Ui.INPUT_BG, 8f, Ui.BORDER, 1f));
        iconsBgInput.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4));
        LinearLayout.LayoutParams bgInputLp = new LinearLayout.LayoutParams(0, -2, 1f);
        bgInputLp.leftMargin = Ui.dp(this, 8);
        bgRow.addView(iconsBgInput, bgInputLp);
        iconsBgInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                try {
                    iconsBgColor = parseColor(s.toString().trim());
                    saveIconsPrefs();
                    refreshIconsPreview();
                } catch (Throwable ignored) {}
            }
        });
        styleCard.addView(bgRow, bgRowLp);

        sbIconScale = addSlider(styleCard, "图标大小", 40, 160, iconsScale, new IntSetter() {
            @Override public void set(int v) { iconsScale = v; }
        });

        // ---- Shape selector chips (Full control over icon geometry)
        LinearLayout shapeHead = new LinearLayout(this);
        shapeHead.setOrientation(LinearLayout.HORIZONTAL);
        shapeHead.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams shLp = new LinearLayout.LayoutParams(-1, -2);
        shLp.topMargin = Ui.dp(this, 14);
        shapeHead.addView(Ui.section(this, "图标形状"), new LinearLayout.LayoutParams(0, -2, 1f));
        styleCard.addView(shapeHead, shLp);

        final String[] shapeNames = {
                "圆形（Circle）", "方形（Square）", "斜角方形（Slanted Square）", "拱形（Arch）",
                "扇形（Fan）", "箭头（Arrow）", "半圆（Semi-Circle）", "椭圆（Oval）",
                "胶囊（Pill）", "三角形（Triangle）", "菱形（Diamond）", "贝壳（Clam Shell）",
                "五边形（Pentagon）", "宝石（Gem）", "太阳（Sunny）", "烈日（Very Sunny）",
                "饼干 4（Cookie 4）", "饼干 6（Cookie 6）", "饼干 7（Cookie 7）", "饼干 9（Cookie 9）",
                "饼干 12（Cookie 12）", "幽灵（Ghostish）", "四叶草 4（Clover 4）", "四叶草 8（Clover 8）",
                "闪光（Burst）", "柔和闪光（Soft Burst）", "爆炸（Boom）", "柔和爆炸（Soft Boom）",
                "花朵（Flower）", "云朵（Puffy）", "蓬松菱形（Puffy Diamond）", "像素圆（Pixel Circle）",
                "像素三角形（Pixel Triangle）", "面包（Bun）", "心形（Heart）"
        };
        final int[] shapeIds = {
                IconsTinter.SHAPE_M3_CIRCLE, IconsTinter.SHAPE_M3_SQUARE, IconsTinter.SHAPE_M3_SLANTED_SQUARE, IconsTinter.SHAPE_M3_ARCH,
                IconsTinter.SHAPE_M3_FAN, IconsTinter.SHAPE_M3_ARROW, IconsTinter.SHAPE_M3_SEMI_CIRCLE, IconsTinter.SHAPE_M3_OVAL,
                IconsTinter.SHAPE_M3_PILL, IconsTinter.SHAPE_M3_TRIANGLE, IconsTinter.SHAPE_M3_DIAMOND, IconsTinter.SHAPE_M3_CLAM_SHELL,
                IconsTinter.SHAPE_M3_PENTAGON, IconsTinter.SHAPE_M3_GEM, IconsTinter.SHAPE_M3_SUNNY, IconsTinter.SHAPE_M3_VERY_SUNNY,
                IconsTinter.SHAPE_M3_COOKIE_4, IconsTinter.SHAPE_M3_COOKIE_6, IconsTinter.SHAPE_M3_COOKIE_7, IconsTinter.SHAPE_M3_COOKIE_9,
                IconsTinter.SHAPE_M3_COOKIE_12, IconsTinter.SHAPE_M3_GHOSTISH, IconsTinter.SHAPE_M3_CLOVER_4, IconsTinter.SHAPE_M3_CLOVER_8,
                IconsTinter.SHAPE_M3_BURST, IconsTinter.SHAPE_M3_SOFT_BURST, IconsTinter.SHAPE_M3_BOOM, IconsTinter.SHAPE_M3_SOFT_BOOM,
                IconsTinter.SHAPE_M3_FLOWER, IconsTinter.SHAPE_M3_PUFFY, IconsTinter.SHAPE_M3_PUFFY_DIAMOND, IconsTinter.SHAPE_M3_PIXEL_CIRCLE,
                IconsTinter.SHAPE_M3_PIXEL_TRIANGLE, IconsTinter.SHAPE_M3_BUN, IconsTinter.SHAPE_M3_HEART
        };

        // Current shape preview row (card selector with thumbnail, title and arrow)
        final LinearLayout shapePickerBtn = new LinearLayout(this);
        shapePickerBtn.setOrientation(LinearLayout.HORIZONTAL);
        shapePickerBtn.setGravity(Gravity.CENTER_VERTICAL);
        shapePickerBtn.setBackground(Ui.shape(this, Ui.SURFACE_HIGH, 10f, Ui.BORDER_SOFT, 1f));
        shapePickerBtn.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));

        final ImageView currentShapeThumb = new ImageView(this);
        int thumbSize = Ui.dp(this, 36);
        currentShapeThumb.setImageBitmap(IconsTinter.generateShapeThumbnail(thumbSize, iconsShape, iconsRadius, Ui.ACCENT, 0xFFFFFFFF));
        shapePickerBtn.addView(currentShapeThumb, new LinearLayout.LayoutParams(thumbSize, thumbSize));

        final LinearLayout shapeInfo = Ui.col(this);
        shapeInfo.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
        String initialShapeName = "圆角";
        for (int i = 0; i < shapeIds.length; i++) {
            if (shapeIds[i] == iconsShape) {
                initialShapeName = shapeNames[i];
                break;
            }
        }
        final TextView currentShapeTitle = Ui.text(this, initialShapeName, 14f, Ui.TEXT);
        currentShapeTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        final TextView shapeSub = Ui.text(this, "35 种官方 Material 3 形状 · 点击选择", 11.5f, Ui.MUTED);
        shapeInfo.addView(currentShapeTitle);
        shapeInfo.addView(shapeSub);
        shapePickerBtn.addView(shapeInfo, new LinearLayout.LayoutParams(0, -2, 1f));

        ImageView chevron = new ImageView(this);
        chevron.setImageDrawable(M3Icons.chevron(Ui.MUTED, Ui.dp(this, 20)));
        shapePickerBtn.addView(chevron, new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));

        shapePickerBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.tap(v);
                ScrollView dlgScroll = new ScrollView(Main.this);
                dlgScroll.setBackgroundColor(Ui.SURFACE);
                LinearLayout grid = Ui.col(Main.this);
                grid.setPadding(Ui.dp(Main.this, 16), Ui.dp(Main.this, 16), Ui.dp(Main.this, 16), Ui.dp(Main.this, 16));
                dlgScroll.addView(grid, new FrameLayout.LayoutParams(-1, -2));

                final AlertDialog dialog = new AlertDialog.Builder(Main.this)
                        .setTitle("选择形状（Material 3 Expressive）")
                        .setView(dlgScroll)
                        .setNegativeButton("关闭", null)
                        .create();

                int itemThumbSize = Ui.dp(Main.this, 38);
                for (int i = 0; i < shapeIds.length; i++) {
                    final int sId = shapeIds[i];
                    final String sName = shapeNames[i];
                    final boolean isSel = (iconsShape == sId);

                    LinearLayout itemRow = new LinearLayout(Main.this);
                    itemRow.setOrientation(LinearLayout.HORIZONTAL);
                    itemRow.setGravity(Gravity.CENTER_VERTICAL);
                    itemRow.setPadding(Ui.dp(Main.this, 12), Ui.dp(Main.this, 10), Ui.dp(Main.this, 12), Ui.dp(Main.this, 10));
                    itemRow.setBackground(Ui.shape(Main.this, isSel ? 0x223D7D56 : 0x00000000, 8f, isSel ? Ui.ACCENT : 0x00000000, isSel ? 1.5f : 0f));

                    ImageView iv = new ImageView(Main.this);
                    iv.setImageBitmap(IconsTinter.generateShapeThumbnail(itemThumbSize, sId, iconsRadius,
                            isSel ? Ui.ACCENT : Ui.SURFACE_HIGH, isSel ? 0xFFFFFFFF : Ui.MUTED));
                    itemRow.addView(iv, new LinearLayout.LayoutParams(itemThumbSize, itemThumbSize));

                    TextView tv = Ui.text(Main.this, sName, 14f, isSel ? Ui.ACCENT : Ui.TEXT);
                    tv.setTypeface(Typeface.create("sans-serif-medium", isSel ? Typeface.BOLD : Typeface.NORMAL));
                    tv.setPadding(Ui.dp(Main.this, 14), 0, 0, 0);
                    itemRow.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));

                    if (isSel) {
                        ImageView check = new ImageView(Main.this);
                        check.setImageDrawable(M3Icons.check(Ui.ACCENT, Ui.dp(Main.this, 20)));
                        itemRow.addView(check, new LinearLayout.LayoutParams(Ui.dp(Main.this, 20), Ui.dp(Main.this, 20)));
                    }

                    itemRow.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View itemV) {
                            Ui.tap(itemV);
                            iconsShape = sId;
                            currentShapeTitle.setText(sName);
                            int curSize = Ui.dp(Main.this, 36);
                            currentShapeThumb.setImageBitmap(IconsTinter.generateShapeThumbnail(curSize, iconsShape, iconsRadius, Ui.ACCENT, 0xFFFFFFFF));
                            saveIconsPrefs();
                            refreshIconsPreview();
                            dialog.dismiss();
                        }
                    });

                    LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(-1, -2);
                    itemLp.bottomMargin = Ui.dp(Main.this, 6);
                    grid.addView(itemRow, itemLp);
                }

                dialog.show();
            }
        });

        LinearLayout.LayoutParams pickerLp = new LinearLayout.LayoutParams(-1, -2);
        pickerLp.topMargin = Ui.dp(this, 10);
        styleCard.addView(shapePickerBtn, pickerLp);

        LinearLayout.LayoutParams styleCardLp = new LinearLayout.LayoutParams(-1, -2);
        styleCardLp.bottomMargin = Ui.dp(this, 14);
        root.addView(styleCard, styleCardLp);

        // ---- apply button
        View apply = pillButton(M3Icons.play(Ui.ACCENT, Ui.dp(this, 18)),
                "重新着色并应用",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { runIconsApply(); }
                });
        LinearLayout.LayoutParams applyLp = new LinearLayout.LayoutParams(-1, -2);
        applyLp.bottomMargin = Ui.dp(this, 14);
        root.addView(apply, applyLp);

        // ---- status card
        LinearLayout statusCard = Ui.col(this);
        statusCard.setBackground(Ui.shape(this, Ui.INPUT_BG, 12f, Ui.BORDER, 1.2f));
        statusCard.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        iconsStatusText = Ui.text(this, "请选择颜色并点击「重新着色」", 13f, Ui.MUTED);
        statusCard.addView(iconsStatusText);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.bottomMargin = Ui.dp(this, 14);
        root.addView(statusCard, statusLp);

        TextView note = Ui.text(this,
                "支持导入 ADW/Nova 图标包。应用需在打开游戏模式时进行。",
                11.5f, Ui.MUTED);
        root.addView(note);

        // restore a previously imported icon pack in the background
        if (!iconsPackPkg.isEmpty()) {
            final String packPkg = iconsPackPkg;
            new Thread(new Runnable() {
                @Override public void run() {
                    final int n = packLoader().load(packPkg);
                    h.post(new Runnable() {
                        @Override public void run() {
                            if (n > 0) refreshIconsPreview();
                        }
                    });
                }
            }).start();
        }

        return scroll;
    }

    private View swatch(int color) {
        View v = new View(this);
        v.setBackground(Ui.ripple(this, color, 17f, 0x66FFFFFF));
        return v;
    }

    private interface IntSetter { void set(int v); }

    /** Slider 0..100 with a percent value label; refreshes preview on release. */
    private Slider addSlider(LinearLayout parent, String name, int initial, IntSetter setter) {
        return addSlider(parent, name, 0, 100, initial, setter);
    }

    private Slider addSlider(LinearLayout parent, String name, final int min, final int max,
                             int initial, final IntSetter setter) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = Ui.text(this, name, 13f, Ui.TEXT);
        final TextView val = Ui.text(this, initial + "%", 13f, Ui.ACCENT);
        head.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(val, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout row = Ui.col(this);
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(-1, -2);
        headLp.topMargin = Ui.dp(this, 12);
        row.addView(head, headLp);

        Slider slider = new Slider(this);
        slider.setValueFrom(min);
        slider.setValueTo(max);
        slider.setStepSize(1f);
        slider.setValue(Math.max(min, Math.min(max, initial)));
        slider.setTrackActiveTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        slider.setTrackInactiveTintList(android.content.res.ColorStateList.valueOf(Ui.SURFACE_HIGH));
        slider.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        slider.setHaloTintList(android.content.res.ColorStateList.valueOf(0x333D7D56));

        slider.addOnChangeListener(new Slider.OnChangeListener() {
            @Override
            public void onValueChange(Slider s, float value, boolean fromUser) {
                int v = Math.round(value);
                val.setText(v + "%");
                setter.set(v);
            }
        });
        slider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override public void onStartTrackingTouch(Slider s) {}
            @Override public void onStopTrackingTouch(Slider s) {
                saveIconsPrefs();
                refreshIconsPreview();
            }
        });

        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(-1, -2);
        sliderLp.leftMargin = Ui.dp(this, -8);
        sliderLp.rightMargin = Ui.dp(this, -8);
        row.addView(slider, sliderLp);
        parent.addView(row);
        return slider;
    }

    // ------------------------------------------------------------------ icon pack

    private IconPackLoader packLoader() {
        if (packLoader == null) packLoader = new IconPackLoader(this);
        return packLoader;
    }

    private void saveIconsPrefs() {
        prefs.edit()
                .putInt("icons_color", iconsColor)
                .putInt("icons_desat", iconsDesat)
                .putInt("icons_lighten", iconsLighten)
                .putInt("icons_strength", iconsStrength)
                .putString("icons_pack", iconsPackPkg)
                .putString("icons_pack_label", iconsPackLabel)
                .putBoolean("icons_bg", iconsBgEnabled)
                .putInt("icons_bg_color", iconsBgColor)
                .putBoolean("icons_monet", iconsMonetEnabled)
                .putInt("icons_scale", iconsScale)
                .putInt("icons_fg_scale", iconsFgScale)
                .putInt("icons_radius", iconsRadius)
                .putInt("icons_shape", iconsShape)
                .apply();
    }

    /** Resets every slider to the default Origin Icons / studio look. */
    private void resetIconsTuning() {
        if (sbDesat != null) sbDesat.setValue(100f);
        if (sbLighten != null) sbLighten.setValue(0f);
        if (sbStrength != null) sbStrength.setValue(100f);
        if (sbIconScale != null) sbIconScale.setValue(100f);
        iconsScale = 100;
        iconsFgScale = 76;
        iconsRadius = 25;
        iconsShape = IconsTinter.SHAPE_ROUNDED;
        toast("设置已重置");
        saveIconsPrefs();
        refreshIconsPreview();
    }

    private void setIconsBgColor(int color) {
        iconsBgColor = color;
        if (iconsBgInput != null) {
            String hex = String.format("#%06X", color & 0xFFFFFF);
            if (!iconsBgInput.getText().toString().equals(hex)) {
                iconsBgInput.setText(hex);
                iconsBgInput.setSelection(hex.length());
            }
        }
        saveIconsPrefs();
        refreshIconsPreview();
    }

    /** Discovers installed icon packs (ADW/Nova intents) and shows a chooser. */
    private void chooseIconPack() {
        toast("正在查找图标包…");
        new Thread(new Runnable() {
            @Override public void run() {
                final List<IconPackLoader.Pack> packs = packLoader().findPacks();
                h.post(new Runnable() {
                    @Override public void run() {
                        if (packs.isEmpty()) {
                            toast("未找到图标包——请从 Play 商店安装任意图标包");
                            return;
                        }
                        CharSequence[] items = new CharSequence[packs.size() + 1];
                        items[0] = "系统图标（无图标包）";
                        for (int i = 0; i < packs.size(); i++) items[i + 1] = packs.get(i).label;
                        new AlertDialog.Builder(Main.this)
                                .setTitle("图标包")
                                .setItems(items, new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) {
                                        if (w == 0) {
                                            setIconPack("", "");
                                        } else {
                                            IconPackLoader.Pack p = packs.get(w - 1);
                                            setIconPack(p.pkg, p.label);
                                        }
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
            }
        }).start();
    }

    private void setIconPack(final String pkg, final String label) {
        iconsPackPkg = pkg;
        iconsPackLabel = label;
        saveIconsPrefs();
        if (iconsSourceValue != null) {
            iconsSourceValue.setText(pkg.isEmpty() ? "系统图标" : label);
        }
        if (pkg.isEmpty()) {
            iconsStatus("来源：系统图标", Ui.ACCENT);
            refreshIconsPreview();
            return;
        }
        iconsStatus("正在加载「" + label + "」…", Ui.ACCENT);
        new Thread(new Runnable() {
            @Override public void run() {
                final int n = packLoader().load(pkg);
                h.post(new Runnable() {
                    @Override public void run() {
                        if (n > 0) {
                            iconsStatus("图标包已加载：" + n + " 个图标已映射", Ui.ACCENT);
                        } else {
                            iconsStatus("不支持该图标包（缺少 appfilter.xml）", Ui.ERROR);
                        }
                        refreshIconsPreview();
                    }
                });
            }
        }).start();
    }

    /** Pack icon for a package when a pack is loaded, else null (use app icon). */
    private Drawable packIconOrNull(String pkg) {
        if (iconsPackPkg.isEmpty()) return null;
        try {
            return packLoader().iconFor(pkg);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int parseColor(String s) {
        if (s == null) throw new IllegalArgumentException("null");
        String t = s.trim();
        if (!t.startsWith("#")) t = "#" + t;
        return Color.parseColor(t);
    }

    private void setIconsColor(int color) {
        iconsColor = color;
        if (iconsColorInput != null) {
            String hex = String.format("#%06X", color & 0xFFFFFF);
            if (!iconsColorInput.getText().toString().equals(hex)) {
                iconsColorInput.setText(hex);
                iconsColorInput.setSelection(hex.length());
            }
        }
        refreshIconsPreview();
    }

    /** Loads launcher apps once and renders the preview row with the current color. */
    private void refreshIconsPreview() {
        if (iconsPreviewRow == null) return;
        if (iconsPackages.isEmpty()) {
            loadLauncherApps();
        }
        iconsPreviewRow.removeAllViews();
        iconsTinted.clear();
        int n = Math.min(iconsPackages.size(), 8);
        int previewH = Math.max(Ui.dp(this, 28), Math.min(Ui.dp(this, 72), Math.round(Ui.dp(this, 44) * (iconsScale / 100f))));
        for (int i = 0; i < n; i++) {
            Drawable orig = iconsOriginals.get(i);
            Drawable src = packIconOrNull(iconsPackages.get(i));
            if (src == null) src = orig;
            Bitmap pb = IconsTinter.compose(src, iconsColor, iconsDesat, iconsLighten, iconsStrength,
                    iconsBgEnabled, iconsBgColor, iconsScale, iconsFgScale, iconsRadius, iconsShape);
            Drawable tinted = (pb != null) ? new BitmapDrawable(getResources(), pb) : orig;
            iconsTinted.add(tinted);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(tinted);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, previewH, 1f);
            iconsPreviewRow.addView(iv, lp);
        }
    }

    private void loadLauncherApps() {
        iconsPackages.clear();
        iconsOriginals.clear();
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<android.content.pm.ResolveInfo> list =
                    getPackageManager().queryIntentActivities(intent, 0);
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            for (android.content.pm.ResolveInfo ri : list) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(PKG)) continue;
                if (!seen.add(pkg)) continue; // one icon per package
                iconsPackages.add(pkg);
                try {
                    iconsOriginals.add(ri.activityInfo.loadIcon(getPackageManager()));
                } catch (Throwable t) {
                    iconsOriginals.add(null);
                }
            }
        } catch (Throwable t) {
            Log.e("GCTune", "loadLauncherApps failed: " + t);
        }
    }

    private void runIconsApply() {
        if (busy) {
            toast("请等待当前命令执行完成");
            return;
        }
        if (iconsPackages.isEmpty()) {
            loadLauncherApps();
        }
        if (iconsPackages.isEmpty()) {
            iconsStatus("获取应用列表失败", Ui.ERROR);
            return;
        }
        iconsRun = true;
        busy = true;
        // persist the whole recipe
        saveIconsPrefs();
        iconsStatus("正在为图标着色…", Ui.ACCENT);
        showHint();
        final int total = iconsPackages.size();
        new Thread(new Runnable() {
            @Override public void run() {
                final File dir = new File(getExternalFilesDir(null), "icons");
                dir.mkdirs();
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) f.delete();
                int done = 0;
                int written = 0;
                int failed = 0;
                for (int i = 0; i < iconsPackages.size(); i++) {
                    final String pkg = iconsPackages.get(i);
                    Bitmap bmp = null;
                    try {
                        Drawable orig = iconsOriginals.get(i);
                        Drawable src = packIconOrNull(pkg);
                        if (src == null) src = orig;
                        if (src != null) {
                            bmp = IconsTinter.compose(src, iconsColor, iconsDesat, iconsLighten,
                                    iconsStrength, iconsBgEnabled, iconsBgColor,
                                    iconsScale, iconsFgScale, iconsRadius, iconsShape);
                        }
                    } catch (Throwable t) {
                        Log.e("GCTune", "render " + pkg + " failed: " + t);
                    }
                    if (bmp != null) {
                        try {
                            FileOutputStream os = new FileOutputStream(new File(dir, pkg + ".png"));
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                            os.close();
                            written++;
                        } catch (Throwable t) {
                            Log.e("GCTune", "write " + pkg + " failed: " + t);
                            failed++;
                        }
                        bmp.recycle();
                    } else {
                        failed++;
                    }
                    done++;
                    if (done % 8 == 0 || done == total) {
                        final int d = done;
                        h.post(new Runnable() {
                            @Override public void run() {
                                iconsStatus("已着色 " + d + " / " + total, Ui.ACCENT);
                            }
                        });
                    }
                }
                // Origin Icons writes the dialer under its component name too
                // (com.android.dialer.TwelveKeyDialer) — the launcher looks that
                // file up separately; mirror the fallback from IconPackEngine.
                try {
                    File dialer = new File(dir, "com.android.dialer.TwelveKeyDialer.png");
                    File contacts = new File(dir, "com.android.contacts.png");
                    if (!dialer.exists() && contacts.exists()) {
                        java.io.FileInputStream in = new java.io.FileInputStream(contacts);
                        java.io.FileOutputStream os = new java.io.FileOutputStream(dialer);
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                        in.close();
                        os.close();
                    }
                // Write matching launcher mask and border assets so OriginOS launcher
                // renders the selected shape & size accurately without clipping into square
                try {
                    File launcherDir = new File(dir, "launcher");
                    launcherDir.mkdirs();

                    Bitmap mask320 = IconsTinter.generateMask(320, 320, iconsShape, iconsRadius, iconsScale);
                    FileOutputStream m320os = new FileOutputStream(new File(launcherDir, "icon_mask.png"));
                    mask320.compress(Bitmap.CompressFormat.PNG, 100, m320os);
                    m320os.close();

                    Bitmap mask192 = IconsTinter.generateMask(192, 192, iconsShape, iconsRadius, iconsScale);
                    FileOutputStream m192os = new FileOutputStream(new File(launcherDir, "icon_mask_5x9.png"));
                    mask192.compress(Bitmap.CompressFormat.PNG, 100, m192os);
                    m192os.close();

                    // Generate shape background with selected color (or tonal color from icon) so launcher's background tile matches shape
                    Bitmap bg320 = IconsTinter.generateBg(320, 320, iconsShape, iconsRadius, iconsScale,
                            iconsBgEnabled ? iconsBgColor : IconsTinter.getToneBgColor(iconsColor));
                    FileOutputStream bgOs = new FileOutputStream(new File(launcherDir, "icon_bg_default.png"));
                    bg320.compress(Bitmap.CompressFormat.PNG, 100, bgOs);
                    bgOs.close();
                    bg320.recycle();

                    mask320.recycle();
                    mask192.recycle();

                    // Transparent 240x240 border so launcher's default square border doesn't clash with shape
                    Bitmap emptyBorder = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888);
                    FileOutputStream bOs = new FileOutputStream(new File(launcherDir, "icon_border.png"));
                    emptyBorder.compress(Bitmap.CompressFormat.PNG, 100, bOs);
                    bOs.close();
                    emptyBorder.recycle();
                } catch (Throwable t) {
                    Log.e("GCTune", "Failed to generate launcher mask assets: " + t);
                }
                } catch (Throwable ignored) {
                }
                // force the launcher to re-read theme icons; done here, in the
                // app process — "settings put" in the channel NPEs in AppOps
                pendingOldStyle = readIconsStyle();
                styleToggleOk = toggleIconsStyle();
                final int fin = written;
                final int ferr = failed;
                h.post(new Runnable() {
                    @Override public void run() {
                        iconsStatus("已渲染 " + fin + " / " + total
                                + (ferr > 0 ? "（错误：" + ferr + "）" : "")
                                + "——正在通过通道安装…", Ui.ACCENT);
                        busy = false;
                        if (!apply(iconsCommand())) {
                            iconsRun = false;
                            hideHint();
                            iconsStatus("GC Tune 不在游戏列表中——请添加应用后重试", Ui.ERROR);
                        }
                    }
                });
            }
        }).start();
    }

    /** Reads the launcher icon-style setting (saved across recolors). */
    private String readIconsStyle() {
        try {
            String v = Settings.Global.getString(getContentResolver(), "theme_icons_style_explore");
            return (v == null || v.isEmpty()) ? "1" : v;
        } catch (Throwable t) {
            return "1";
        }
    }

    /**
     * Flips the launcher icon style 1 -> 2 -> 1 from the app process to force
     * the launcher to re-read /data/bbkcore/theme/icons/. "settings put" run
     * inside the uid1000 channel NPEs in AppOpsService (no calling package),
     * so this needs the WRITE_SECURE_SETTINGS grant (adb pm grant). Returns
     * false when the permission is missing.
     */
    private boolean toggleIconsStyle() {
        try {
            Settings.Global.putInt(getContentResolver(), "theme_icons_style_explore", 1);
            Settings.Global.putInt(getContentResolver(), "theme_icons_style_explore", 2);
            Settings.Global.putInt(getContentResolver(), "theme_icons_style_explore", 1);
            // Map iconsScale (40-160%) to OriginOS desktop icon size: 0 (small), 1 (normal), 2 (large), 3 (huge)
            int originSize = (iconsScale <= 70) ? 0 : (iconsScale <= 95 ? 1 : (iconsScale <= 125 ? 2 : 3));
            try {
                Settings.System.putInt(getContentResolver(), "current_desktop_iconsize", originSize);
                Settings.System.putInt(getContentResolver(), "deform_icons_size_explore", originSize);
            } catch (Throwable ignored) {}
            return true;
        } catch (Throwable t) {
            Log.e("GCTune", "toggleIconsStyle failed: " + t);
            return false;
        }
    }

    /** Puts the user's own icon style back after the recolor. */
    private void restoreIconsStyle(String old) {
        try {
            Settings.Global.putInt(getContentResolver(), "theme_icons_style_explore",
                    Integer.parseInt(old.trim()));
            int originSize = (iconsScale <= 70) ? 0 : (iconsScale <= 95 ? 1 : (iconsScale <= 125 ? 2 : 3));
            try {
                Settings.System.putInt(getContentResolver(), "current_desktop_iconsize", originSize);
                Settings.System.putInt(getContentResolver(), "deform_icons_size_explore", originSize);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {
        }
    }

    /**
     * Icon recolor command: the uid 1000 channel copies the rendered PNGs into
     * /data/bbkcore/theme/icons/ and restarts the launcher once. The
     * theme_icons_style_explore flip is done by the app itself (see
     * toggleIconsStyle) because "settings put" in the channel NPEs in
     * AppOpsService (no calling package). A lock file aborts duplicate runs
     * (a second copy raced the first one and deleted icons after they were
     * copied — that is why many apps stayed uncolored). chown is omitted on
     * purpose: uid 1000 has no CAP_CHOWN and the launcher reads the files
     * anyway; all diagnostic noise is suppressed so the output stays parseable.
     */
    private String iconsCommand() {
        final StringBuilder sb = new StringBuilder();
        sb.append("COLOR=").append(String.format("#%06X", iconsColor & 0xFFFFFF)).append(";");
        sb.append("SRC=").append(getExternalFilesDir(null).getAbsolutePath()).append("/icons;");
        sb.append("TD=/data/bbkcore/theme;");
        sb.append("LOCK=$TD/.icons_gctune_lock;");
        // Stale locks must die: the lock is a directory and the old "find
        // -mmin -delete" probe is not supported by every toybox build, which
        // left "上一次安装仍在进行中" hanging forever. Age is checked
        // with stat/date (both always available), stale (>3 min) is removed.
        sb.append("if ! mkdir $LOCK 2>/dev/null; then ");
        sb.append("ts=$(stat -c %Y $LOCK 2>/dev/null); now=$(date +%s); ");
        sb.append("if [ -n \"$ts\" ] && [ $((now-ts)) -gt 180 ]; then ");
        sb.append("rm -rf $LOCK 2>/dev/null; mkdir $LOCK 2>/dev/null || { echo BUSY; exit 0; }; ");
        sb.append("else echo BUSY; exit 0; fi; fi;");
        sb.append("trap 'rmdir $LOCK 2>/dev/null' EXIT;");
        // icons are rendered by the app into $SRC before this command runs.
        sb.append("echo SRC_ICONS=$(ls \"$SRC\" 2>/dev/null | wc -l);");
        // 1) stage the new icons first so nothing is half-swapped if we bail out
        sb.append("NEW=$TD/.icons_gctune_new;");
        sb.append("rm -rf $NEW 2>/dev/null; mkdir -p $NEW;");
        sb.append("for f in \"$SRC\"/*.png; do [ -f \"$f\" ] && cp \"$f\" $NEW/ 2>/dev/null; done;");
        sb.append("[ -n \"$(ls -A $NEW)\" ] || { echo NO_ICONS; rm -rf $NEW; exit 0; };");
        // 2) ensure the launcher template exists (icon_bg / icon_mask / icon_border)
        sb.append("if [ ! -d $TD/launcher ]; then ");
        sb.append("mkdir -p $TD; ");
        // NB: archive members are stored as "./launcher/..." — extracting the bare
        // name "launcher" silently matches nothing (tar exits 0, extracts 0 files)
        sb.append("tar -xzf /sdcard/Download/gctune/origin_theme_template.tgz -C $TD ./launcher 2>/dev/null; ");
        sb.append("fi; ");
        // Overwrite launcher mask and border with the unconstrained masks
        sb.append("if [ -d \"$SRC\"/launcher ]; then ");
        sb.append("cp \"$SRC\"/launcher/icon_border.png $TD/launcher/icon_border.png 2>/dev/null; ");
        sb.append("cp \"$SRC\"/launcher/icon_border.png $TD/launcher/icon_border_two.png 2>/dev/null; ");
        sb.append("for bf in $TD/launcher/icon_border*.png; do ");
        sb.append("[ -f \"$bf\" ] && cp \"$SRC\"/launcher/icon_border.png \"$bf\" 2>/dev/null; ");
        sb.append("done; ");
        sb.append("cp \"$SRC\"/launcher/icon_mask.png $TD/launcher/icon_mask.png 2>/dev/null; ");
        sb.append("cp \"$SRC\"/launcher/icon_mask.png $TD/launcher/icon_mask_two.png 2>/dev/null; ");
        sb.append("cp \"$SRC\"/launcher/icon_mask.png $TD/launcher/icon_mask_three.png 2>/dev/null; ");
        sb.append("for mf in $TD/launcher/icon_mask*.png; do ");
        sb.append("[ -f \"$mf\" ] && cp \"$SRC\"/launcher/icon_mask.png \"$mf\" 2>/dev/null; ");
        sb.append("done; ");
        sb.append("for bg in $TD/launcher/icon_bg_*.png; do ");
        sb.append("[ -f \"$bg\" ] && cp \"$SRC\"/launcher/icon_bg_default.png \"$bg\" 2>/dev/null; ");
        sb.append("done; ");
        sb.append("chmod -R 777 $TD/launcher 2>/dev/null; ");
        sb.append("restorecon -RF $TD/launcher 2>/dev/null; ");
        sb.append("fi; ");
        // 3) The launcher keys its icon cache on theme_icons_style_explore —
        // without a flip it keeps serving the OLD bitmaps for already-cached
        // apps and only "new" icons get recolored (that is why recoloring was
        // partial). Origin Icons purges the cache the same way: empty the icon
        // dir, flip the style around restarts, THEN install the icons and
        // restart once more. The user's own style value is restored at the end
        // (one more observer reload, now with the new icons in place).
        sb.append("OLD=$(settings get system theme_icons_style_explore 2>/dev/null);");
        sb.append("rm -rf $TD/icons 2>/dev/null;");
        sb.append("settings put system theme_icons_style_explore 1 >/dev/null 2>&1;");
        sb.append("settings put global theme_icons_style_explore 1 >/dev/null 2>&1;");
        sb.append("am force-stop com.bbk.launcher2;");
        sb.append("am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1; sleep 1;");
        sb.append("echo FLIP=$(settings get system theme_icons_style_explore 2>/dev/null);");
        sb.append("settings put system theme_icons_style_explore 2 >/dev/null 2>&1;");
        sb.append("settings put global theme_icons_style_explore 2 >/dev/null 2>&1;");
        sb.append("am force-stop com.bbk.launcher2;");
        sb.append("am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1; sleep 1;");
        sb.append("settings put system theme_icons_style_explore 1 >/dev/null 2>&1;");
        sb.append("settings put global theme_icons_style_explore 1 >/dev/null 2>&1;");
        sb.append("am force-stop com.bbk.launcher2;");
        sb.append("am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1; sleep 1;");
        // 4) install the new icons while the launcher just reloaded with an
        // empty theme dir, then bring it up for real
        sb.append("mkdir -p $TD/icons 2>/dev/null;");
        sb.append("for f in $NEW/*.png; do [ -f \"$f\" ] && cp \"$f\" $TD/icons/ 2>/dev/null; done;");
        sb.append("rm -rf $NEW;");
        sb.append("chmod -R 777 $TD/icons 2>/dev/null;");
        sb.append("restorecon -RF $TD/icons 2>/dev/null;");
        sb.append("N=$(ls $TD/icons 2>/dev/null | wc -l);");
        sb.append("if [ -n \"$OLD\" ] && [ \"$OLD\" != null ]; then ");
        sb.append("settings put system theme_icons_style_explore \"$OLD\" >/dev/null 2>&1;");
        sb.append("settings put global theme_icons_style_explore \"$OLD\" >/dev/null 2>&1; fi;");
        sb.append("am force-stop com.bbk.launcher2;");
        sb.append("am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1;");
        sb.append("sleep 2;");
        sb.append("echo APPLIED_ICONS=$N;");
        sb.append("echo APPLIED;");
        return sb.toString();
    }

    private void iconsStatus(String s, int color) {
        if (iconsStatusText == null) return;
        iconsStatusText.setText(s);
        iconsStatusText.setTextColor(color);
    }

    /** Turns the uid 1000 output of the icons command into a human status line. */
    private void iconsResult(boolean ok, Entry e) {
        // the launcher restarts finished — give the user's own style back
        if (pendingOldStyle != null) {
            restoreIconsStyle(pendingOldStyle);
            pendingOldStyle = null;
        }
        iconsRun = false;
        hideHint();
        String out = (ok && e != null && e.out != null) ? e.out : "";
        if (!ok) {
            iconsStatus("uid 1000 无响应——请打开游戏模式后重试", Ui.ERROR);
        } else if (out.contains("NO_ICONS")) {
            iconsStatus("没有已着色的图标——请重试", Ui.ERROR);
        } else if (out.contains("BUSY")) {
            iconsStatus("上一次安装仍在进行——请稍候后重试", Ui.ERROR);
        } else if (out.contains("APPLIED")) {
            String n = "";
            int i = out.indexOf("APPLIED_ICONS=");
            if (i >= 0) {
                StringBuilder sb = new StringBuilder();
                for (int j = i + "APPLIED_ICONS=".length(); j < out.length(); j++) {
                    char c = out.charAt(j);
                    if (c < '0' || c > '9') break;
                    sb.append(c);
                }
                if (sb.length() > 0) n = "（" + sb + " 个图标）";
            }
            iconsStatus("图标已应用" + n + "——请检查桌面启动器"
                    + (styleToggleOk ? "" : "（样式未切换：缺少 WRITE_SECURE_SETTINGS）"),
                    Ui.ACCENT);
        } else {
            iconsStatus("详情见控制台（Shell 标签页）", Ui.TEXT);
        }
    }

    // --------------------------------------------------------------- settings
    private View buildSettingsPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);

        LinearLayout root = Ui.col(this);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 30));
        scroll.addView(root, new FrameLayout.LayoutParams(-1, -2));

        TextView title = Ui.label(this, "设置", 28f, Ui.TEXT);
        root.addView(pageTitle(title, "通道与应用参数"));

        // Expressive Card Container for settings options
        LinearLayout card = Ui.col(this);
        card.setBackground(Ui.shape(this, Ui.SURFACE, 24f, Ui.BORDER_SOFT, 1.2f));
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));

        card.addView(settingsSwitchRow());
        card.addView(settingsDivider());
        card.addView(settingsTimeoutRow());
        card.addView(settingsDivider());
        card.addView(settingsNavRow("支持的游戏", new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(SetupActivity.gameSettingsIntent());
                } catch (Throwable t) {
                    try { startActivity(SetupActivity.gameMainIntent()); } catch (Throwable ignored) {}
                }
            }
        }));
        card.addView(settingsDivider());
        card.addView(settingsNavRow("初始设置", new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().putBoolean("setup_done", false).apply();
                startActivity(new Intent(Main.this, SetupActivity.class));
            }
        }));

        root.addView(card, new LinearLayout.LayoutParams(-1, -2));

        // 诊断与反馈：通道出问题时不用电脑，直接导出日志发给作者
        LinearLayout diagCard = Ui.col(this);
        diagCard.setBackground(Ui.shape(this, Ui.SURFACE, 24f, Ui.BORDER_SOFT, 1.2f));
        diagCard.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));

        TextView diagTitle = Ui.text(this, "诊断与反馈", 15f, Ui.TEXT);
        diagTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        diagTitle.setPadding(Ui.dp(this, 2), Ui.dp(this, 14), Ui.dp(this, 2), Ui.dp(this, 3));
        diagCard.addView(diagTitle);
        TextView diagSub = Ui.text(this, "不需要电脑：先自检通道，或直接导出日志发给作者", 11.5f, Ui.MUTED);
        diagSub.setLineSpacing(0f, 1.15f);
        diagSub.setPadding(Ui.dp(this, 2), 0, Ui.dp(this, 2), Ui.dp(this, 8));
        diagCard.addView(diagSub);

        diagCard.addView(settingsNavRow("通道自检", new View.OnClickListener() {
            @Override public void onClick(View v) { startDiagSelfCheck(); }
        }));
        diagCard.addView(settingsDivider());
        diagCard.addView(settingsNavRow("导出诊断日志", new View.OnClickListener() {
            @Override public void onClick(View v) { exportDiag(); }
        }));
        diagCard.addView(settingsDivider());

        diagNote = Ui.text(this, "通道无响应时，可在上面导出诊断日志发给作者", 11.5f, Ui.MUTED);
        diagNote.setLineSpacing(0f, 1.18f);
        diagNote.setPadding(Ui.dp(this, 2), Ui.dp(this, 10), Ui.dp(this, 2), Ui.dp(this, 12));
        diagCard.addView(diagNote);

        LinearLayout.LayoutParams diagLp = new LinearLayout.LayoutParams(-1, -2);
        diagLp.topMargin = Ui.dp(this, 14);
        root.addView(diagCard, diagLp);
        refreshDiagNote();

        String verName = "1.0";
        try {
            verName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {}
        TextView about = Ui.text(this, "GC Tune " + verName + " · Game Cube 通道", 12f, Ui.MUTED);
        about.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams aboutLp = new LinearLayout.LayoutParams(-2, -2);
        aboutLp.gravity = Gravity.CENTER_HORIZONTAL;
        aboutLp.topMargin = Ui.dp(this, 28);
        root.addView(about, aboutLp);
        return scroll;
    }

    private View settingsSwitchRow() {
        LinearLayout row = settingsRowBase();
        row.addView(Ui.text(this, "要求游戏状态", 14f, Ui.TEXT),
                new LinearLayout.LayoutParams(0, -2, 1f));

        MaterialSwitch sw = new MaterialSwitch(this);
        sw.setChecked(prefs.getBoolean("require_game", true));
        tintSwitch(sw);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton btn, boolean checked) {
                prefs.edit().putBoolean("require_game", checked).apply();
            }
        });
        row.addView(sw);
        return row;
    }

    /** Material 3 Expressive styled Switch across the app. */
    private void tintSwitch(MaterialSwitch sw) {
        Ui.applyM3Switch(sw);
    }

    private View settingsTimeoutRow() {
        LinearLayout row = settingsRowBase();
        row.addView(Ui.text(this, "超时（秒）", 14f, Ui.TEXT),
                new LinearLayout.LayoutParams(0, -2, 1f));

        settingsTimeout = new EditText(this);
        settingsTimeout.setText(String.valueOf(prefs.getInt("timeout_sec", 60)));
        settingsTimeout.setInputType(InputType.TYPE_CLASS_NUMBER);
        settingsTimeout.setTextSize(13f);
        settingsTimeout.setTextColor(Ui.TEXT);
        settingsTimeout.setGravity(Gravity.CENTER);
        settingsTimeout.setBackground(Ui.shape(this, Ui.INPUT_BG, 8f, Ui.BORDER, 1f));
        settingsTimeout.setPadding(Ui.dp(this, 8), Ui.dp(this, 5), Ui.dp(this, 8), Ui.dp(this, 5));
        settingsTimeout.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { storeSettingsTimeout(); }
        });
        row.addView(settingsTimeout, new LinearLayout.LayoutParams(Ui.dp(this, 64), -2));
        return row;
    }

    private View settingsNavRow(String label, View.OnClickListener onClick) {
        LinearLayout row = settingsRowBase();
        row.setBackground(Ui.ripple(this, 0x00000000, 12f, 0x3382E5A6));
        row.setPadding(Ui.dp(this, 10), Ui.dp(this, 15), Ui.dp(this, 10), Ui.dp(this, 15));
        row.addView(Ui.text(this, label, 14f, Ui.TEXT),
                new LinearLayout.LayoutParams(0, -2, 1f));

        ImageView chevron = new ImageView(this);
        chevron.setImageDrawable(M3Icons.chevron(Ui.MUTED, Ui.dp(this, 18)));
        chevron.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(chevron, new LinearLayout.LayoutParams(-2, -2));

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.tap(v);
                onClick.onClick(v);
            }
        });
        row.setClickable(true);
        return row;
    }

    private LinearLayout settingsRowBase() {
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 15), 0, Ui.dp(this, 15));
        row.setMinimumHeight(Ui.dp(this, 30));
        return row;
    }

    /** Hairline separator between settings rows (inset like the row text). */
    private View settingsDivider() {
        View v = new View(this);
        v.setBackgroundColor(Ui.BORDER_SOFT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 1));
        lp.leftMargin = Ui.dp(this, 2);
        lp.rightMargin = Ui.dp(this, 2);
        v.setLayoutParams(lp);
        return v;
    }

    private void storeSettingsTimeout() {
        if (settingsTimeout == null) return;
        int v;
        try {
            v = Integer.parseInt(settingsTimeout.getText().toString().trim());
        } catch (Exception e) {
            return;
        }
        if (v < 3) v = 3;
        if (v > 90) v = 90;
        prefs.edit().putInt("timeout_sec", v).apply();
    }

    // ------------------------------------------------------ customization hub
    /** Hub page: big blocks that open each section as its own activity. */
    private View buildCustomPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);

        LinearLayout root = Ui.col(this);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 30));
        scroll.addView(root, new FrameLayout.LayoutParams(-1, -2));

        root.addView(pageTitle(Ui.label(this, "个性化", 26f, Ui.TEXT),
                "主题、图标与背景"));

        root.addView(customBlock(M3Icons.palette(Ui.ACCENT, Ui.dp(this, 24)),
                "主题", "安装 .itz", ThemeActivity.class));
        root.addView(customBlock(M3Icons.tune(Ui.ACCENT, Ui.dp(this, 24)),
                "系统设置", "强调色与 Monet 着色", SettingsThemeActivity.class));
        root.addView(customBlock(M3Icons.grid(Ui.ACCENT, Ui.dp(this, 24)),
                "图标", "着色与图标包", IconsActivity.class));
        root.addView(customBlock(M3Icons.font(Ui.ACCENT, Ui.dp(this, 24)),
                "字体", "系统字体", FontActivity.class));
        root.addView(customBlock(M3Icons.shield(Ui.ACCENT, Ui.dp(this, 24)),
                "其他", "后台保护与 ADB 桥", MiscActivity.class));
        return scroll;
    }

    /** Big launcher-style card: expressive capsule icon tile + title + subtitle + chevron. */
    private View customBlock(M3Icons.Icon icon, String title, String sub,
                             final Class<? extends Activity> target) {
        LinearLayout card = Ui.row(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        // Expressive Card Container: 24dp radius, subtle micro-contrast border
        card.setBackground(Ui.ripple(this, Ui.SURFACE, 24f, 0x406CE5A8));
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 15), Ui.dp(this, 16), Ui.dp(this, 15));
        card.setElevation(Ui.dp(this, 2));

        FrameLayout tile = new FrameLayout(this);
        // Expressive pill / capsule shape for icon tile
        tile.setBackground(Ui.pillShape(this, Ui.PRIMARY, 0, 0));
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(icon);
        ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        tile.addView(ic, new FrameLayout.LayoutParams(
                Ui.dp(this, 24), Ui.dp(this, 24), Gravity.CENTER));
        card.addView(tile, new LinearLayout.LayoutParams(
                Ui.dp(this, 50), Ui.dp(this, 50)));

        LinearLayout col = Ui.col(this);
        col.addView(Ui.label(this, title, 16.5f, Ui.TEXT));
        TextView subT = Ui.text(this, sub, 12f, Ui.MUTED);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-2, -2);
        subLp.topMargin = Ui.dp(this, 3);
        col.addView(subT, subLp);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, -2, 1f);
        colLp.leftMargin = Ui.dp(this, 15);
        colLp.rightMargin = Ui.dp(this, 8);
        card.addView(col, colLp);

        ImageView chev = new ImageView(this);
        chev.setImageDrawable(M3Icons.chevron(Ui.MUTED, Ui.dp(this, 20)));
        chev.setScaleType(ImageView.ScaleType.FIT_CENTER);
        card.addView(chev, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.topMargin = Ui.dp(this, 12);
        card.setLayoutParams(cardLp);

        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.tap(v);
                startActivity(new Intent(Main.this, target));
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            }
        });
        return card;
    }

    /** Font installation page ported 1:1 from the fork. */
    private View buildFontPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);

        LinearLayout root = Ui.col(this);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 30));
        scroll.addView(root, new FrameLayout.LayoutParams(-1, -2));

        TextView title = Ui.label(this, "字体", 26f, Ui.TEXT);
        root.addView(pageTitle(title, "替换 /data/vfonts 中的 TTF"));

        fontThemeHint = hintCard();
        fontThemeHint.setVisibility(View.GONE);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.bottomMargin = Ui.dp(this, 14);
        root.addView(fontThemeHint, hintLp);

        // ---- file card
        LinearLayout fileCard = Ui.col(this);
        fileCard.setBackground(Ui.shape(this, Ui.SURFACE, 12f, Ui.BORDER_SOFT, 1f));
        fileCard.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        fileCard.addView(Ui.section(this, "字体文件（.ttf）"));

        fontThemeFileText = Ui.text(this, "未选择文件", 14f, Ui.TEXT);
        LinearLayout.LayoutParams fileLp = new LinearLayout.LayoutParams(-2, -2);
        fileLp.topMargin = Ui.dp(this, 8);
        fileCard.addView(fontThemeFileText, fileLp);

        View choose = pillButton(M3Icons.folder(Ui.ACCENT, Ui.dp(this, 18)), "选择 .ttf",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { pickFontFile(); }
                });
        LinearLayout.LayoutParams chooseLp = new LinearLayout.LayoutParams(-1, -2);
        chooseLp.topMargin = Ui.dp(this, 12);
        fileCard.addView(choose, chooseLp);

        LinearLayout.LayoutParams fileCardLp = new LinearLayout.LayoutParams(-1, -2);
        fileCardLp.bottomMargin = Ui.dp(this, 14);
        root.addView(fileCard, fileCardLp);

        // ---- install button
        View install = pillButton(M3Icons.play(Ui.ACCENT, Ui.dp(this, 18)),
                "安装并打开 iTheme",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { runFontInstall(); }
                });
        fontInstallBtn = install;
        LinearLayout.LayoutParams installLp = new LinearLayout.LayoutParams(-1, -2);
        installLp.bottomMargin = Ui.dp(this, 14);
        root.addView(install, installLp);

        // ---- status card
        LinearLayout statusCard = Ui.col(this);
        statusCard.setBackground(Ui.shape(this, Ui.INPUT_BG, 12f, Ui.BORDER, 1.2f));
        statusCard.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        fontThemeStatusText = Ui.text(this, "请选择 .ttf 并点击「安装」", 13f, Ui.MUTED);
        statusCard.addView(fontThemeStatusText);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.bottomMargin = Ui.dp(this, 14);
        root.addView(statusCard, statusLp);

        TextView note = Ui.text(this,
                "副本位于 Download/gctune。替换前会创建 .bak 备份。", 11.5f, Ui.MUTED);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
        noteLp.bottomMargin = Ui.dp(this, 24);
        root.addView(note, noteLp);

        return scroll;
    }

    // ---------------------------------------------------- settings theme page
    private View buildSettingsThemePage() {
        stMonet = prefs.getBoolean("st_monet", true);
        stColor = prefs.getInt("st_color", 0xFF3D7D56);
        if (stMonet) {
            stColor = getMonetPrimaryColor();
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);

        LinearLayout root = Ui.col(this);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 30));
        scroll.addView(root, new FrameLayout.LayoutParams(-1, -2));

        TextView title = Ui.label(this, "系统设置", 26f, Ui.TEXT);
        root.addView(pageTitle(title, "强调色与开关着色"));

        stHint = hintCard();
        stHint.setVisibility(View.GONE);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.bottomMargin = Ui.dp(this, 14);
        root.addView(stHint, hintLp);

        // ---- preview card
        LinearLayout prevCard = Ui.col(this);
        prevCard.setBackground(Ui.shape(this, Ui.SURFACE, 12f, Ui.BORDER_SOFT, 1f));
        prevCard.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        prevCard.addView(Ui.section(this, "设置强调色预览"));

        LinearLayout prevRow = new LinearLayout(this);
        prevRow.setOrientation(LinearLayout.HORIZONTAL);
        prevRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(-1, -2);
        prLp.topMargin = Ui.dp(this, 12);

        stPreviewSwatch = new ImageView(this);
        stPreviewSwatch.setImageDrawable(Ui.pillShape(this, stColor, 0, 0));
        prevRow.addView(stPreviewSwatch, new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38)));

        LinearLayout prevTextCol = Ui.col(this);
        prevTextCol.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
        stPreviewAccentText = Ui.text(this, String.format("#%06X", stColor & 0xFFFFFF), 15f, stColor);
        stPreviewAccentText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextView prevSub = Ui.text(this, "设置中开关、按钮和滑块的强调色", 11.5f, Ui.MUTED);
        prevTextCol.addView(stPreviewAccentText);
        prevTextCol.addView(prevSub);
        prevRow.addView(prevTextCol, new LinearLayout.LayoutParams(0, -2, 1f));

        stPreviewSwitch = new MaterialSwitch(this);
        stPreviewSwitch.setChecked(true);
        updateStPreviewSwitchTint();
        prevRow.addView(stPreviewSwitch);
        prevCard.addView(prevRow, prLp);

        LinearLayout.LayoutParams prevCardLp = new LinearLayout.LayoutParams(-1, -2);
        prevCardLp.bottomMargin = Ui.dp(this, 14);
        root.addView(prevCard, prevCardLp);

        // ---- mode card: Monet (Wallpaper) vs Manual
        LinearLayout modeCard = Ui.col(this);
        modeCard.setBackground(Ui.shape(this, Ui.SURFACE, 12f, Ui.BORDER_SOFT, 1f));
        modeCard.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        modeCard.addView(Ui.section(this, "颜色模式"));

        LinearLayout monetRow = new LinearLayout(this);
        monetRow.setOrientation(LinearLayout.HORIZONTAL);
        monetRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams monetRowLp = new LinearLayout.LayoutParams(-1, -2);
        monetRowLp.topMargin = Ui.dp(this, 8);

        LinearLayout monetTextCol = Ui.col(this);
        monetTextCol.addView(Ui.text(this, "Monet（壁纸取色）", 14f, Ui.TEXT));
        monetTextCol.addView(Ui.text(this, "自动从当前壁纸获取强调色", 11.5f, Ui.MUTED));
        monetRow.addView(monetTextCol, new LinearLayout.LayoutParams(0, -2, 1f));

        final MaterialSwitch stMonetSw = new MaterialSwitch(this);
        stMonetSw.setChecked(stMonet);
        tintSwitch(stMonetSw);
        monetRow.addView(stMonetSw);
        modeCard.addView(monetRow, monetRowLp);

        // Manual color selector container
        stManualBox = Ui.col(this);
        stManualBox.setVisibility(stMonet ? View.GONE : View.VISIBLE);

        String[] presets = {"#3D7D56", "#E53935", "#1E88E5", "#FDD835", "#8E24AA",
                "#FB8C00", "#00ACC1", "#F06292", "#5D4037", "#455A64"};
        LinearLayout swatchRow = new LinearLayout(this);
        swatchRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(-1, -2);
        swLp.topMargin = Ui.dp(this, 12);
        stManualBox.addView(swatchRow, swLp);
        for (String hex : presets) {
            final int color = parseColor(hex);
            View sw = swatch(color);
            sw.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Ui.tap(v);
                    setSettingsThemeColor(color);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34));
            lp.rightMargin = Ui.dp(this, 8);
            swatchRow.addView(sw, lp);
        }

        // HEX input row
        LinearLayout hexRow = new LinearLayout(this);
        hexRow.setOrientation(LinearLayout.HORIZONTAL);
        hexRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hexLp = new LinearLayout.LayoutParams(-1, -2);
        hexLp.topMargin = Ui.dp(this, 12);
        stManualBox.addView(hexRow, hexLp);
        hexRow.addView(Ui.label(this, "HEX:", 13f, Ui.MUTED));
        stColorInput = new EditText(this);
        stColorInput.setText(String.format("#%06X", stColor & 0xFFFFFF));
        stColorInput.setInputType(InputType.TYPE_CLASS_TEXT);
        stColorInput.setTextSize(13f);
        stColorInput.setTextColor(Ui.TEXT);
        stColorInput.setGravity(Gravity.CENTER);
        stColorInput.setBackground(Ui.shape(this, Ui.INPUT_BG, 8f, Ui.BORDER, 1f));
        stColorInput.setPadding(Ui.dp(this, 8), Ui.dp(this, 5), Ui.dp(this, 8), Ui.dp(this, 5));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, -2, 1f);
        inputLp.leftMargin = Ui.dp(this, 10);
        hexRow.addView(stColorInput, inputLp);
        stColorInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                try {
                    setSettingsThemeColor(parseColor(s.toString().trim()));
                } catch (Throwable ignored) {}
            }
        });

        modeCard.addView(stManualBox);

        stMonetSw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Ui.tap(b);
                stMonet = checked;
                stManualBox.setVisibility(checked ? View.GONE : View.VISIBLE);
                if (checked) {
                    setSettingsThemeColor(getMonetPrimaryColor());
                    toast("强调色已从壁纸获取（Monet）");
                }
                saveSettingsThemePrefs();
            }
        });

        LinearLayout.LayoutParams modeCardLp = new LinearLayout.LayoutParams(-1, -2);
        modeCardLp.bottomMargin = Ui.dp(this, 14);
        root.addView(modeCard, modeCardLp);

        // ---- apply button
        View apply = pillButton(M3Icons.play(Ui.ACCENT, Ui.dp(this, 18)),
                "重新着色设置",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { runSettingsThemeApply(); }
                });
        LinearLayout.LayoutParams applyLp = new LinearLayout.LayoutParams(-1, -2);
        applyLp.bottomMargin = Ui.dp(this, 14);
        root.addView(apply, applyLp);

        // ---- status card
        LinearLayout statusCard = Ui.col(this);
        statusCard.setBackground(Ui.shape(this, Ui.INPUT_BG, 12f, Ui.BORDER, 1.2f));
        statusCard.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        stStatusText = Ui.text(this, "请选择颜色模式并点击「重新着色设置」", 13f, Ui.MUTED);
        statusCard.addView(stStatusText);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.bottomMargin = Ui.dp(this, 14);
        root.addView(statusCard, statusLp);

        TextView note = Ui.text(this,
                "通过 Fabricated Overlay 与 OriginOS 主题生成系统强调色。需要游戏模式。",
                11.5f, Ui.MUTED);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
        noteLp.bottomMargin = Ui.dp(this, 24);
        root.addView(note, noteLp);

        return scroll;
    }

    private void updateStPreviewSwitchTint() {
        if (stPreviewSwitch != null) {
            try {
                stPreviewSwitch.setThumbTintList(android.content.res.ColorStateList.valueOf(stColor));
                int trackColor = Color.argb(90, Color.red(stColor), Color.green(stColor), Color.blue(stColor));
                stPreviewSwitch.setTrackTintList(android.content.res.ColorStateList.valueOf(trackColor));
            } catch (Throwable ignored) {}
        }
    }

    private void setSettingsThemeColor(int color) {
        stColor = color;
        if (stColorInput != null) {
            String hex = String.format("#%06X", color & 0xFFFFFF);
            if (!stColorInput.getText().toString().equals(hex)) {
                stColorInput.setText(hex);
                stColorInput.setSelection(hex.length());
            }
        }
        if (stPreviewSwatch != null) {
            stPreviewSwatch.setImageDrawable(Ui.pillShape(this, color, 0, 0));
        }
        if (stPreviewAccentText != null) {
            stPreviewAccentText.setText(String.format("#%06X", color & 0xFFFFFF));
            stPreviewAccentText.setTextColor(color);
        }
        updateStPreviewSwitchTint();
        saveSettingsThemePrefs();
    }

    private void saveSettingsThemePrefs() {
        prefs.edit()
                .putBoolean("st_monet", stMonet)
                .putInt("st_color", stColor)
                .apply();
    }

    private void runSettingsThemeApply() {
        if (busy) {
            toast("请等待当前命令执行完成");
            return;
        }
        saveSettingsThemePrefs();
        stRun = true;
        stStatus("正在将强调色应用到系统设置…", Ui.ACCENT);
        showStHint();
        String cmd = settingsThemeCommand();
        if (!apply(cmd)) {
            stRun = false;
            hideStHint();
            stStatus("GC Tune 不在游戏列表中——请添加应用后重试", Ui.ERROR);
        }
    }

    private String settingsThemeCommand() {
        int c = stColor;
        String hex = String.format("#%06X", c & 0xFFFFFF);
        String argb = String.format("0x%08X", c);
        final StringBuilder sb = new StringBuilder();
        sb.append("COLOR='").append(hex).append("'; ");
        sb.append("COLOR_INT='").append(argb).append("'; ");
        sb.append("echo COLOR=$COLOR; ");
        // 1) Fabricate runtime resource overlay for settings and framework
        sb.append("cmd overlay fabricate --target android --name GCTuneAccent android:color/colorAccent 0x1c $COLOR_INT 2>/dev/null; ");
        sb.append("cmd overlay fabricate --target com.android.settings --name GCTuneSettings theme_color 0x1c $COLOR_INT 2>/dev/null; ");
        sb.append("cmd overlay fabricate --target com.android.settings --name GCTuneSettings colorAccent 0x1c $COLOR_INT 2>/dev/null; ");
        // 2) Enable overlays
        sb.append("cmd overlay enable android:GCTuneAccent 2>/dev/null; ");
        sb.append("cmd overlay enable com.android.settings:GCTuneSettings 2>/dev/null; ");
        // 3) OriginOS / BBK theme settings support
        sb.append("settings put system theme_color \"$COLOR\" 2>/dev/null; ");
        sb.append("settings put global theme_color \"$COLOR\" 2>/dev/null; ");
        sb.append("settings put secure sys_accent_color \"$COLOR\" 2>/dev/null; ");
        sb.append("settings put secure theme_customization_overlay_packages '{\"android.theme.customization.accent_color\":\"'$COLOR'\"}' 2>/dev/null; ");
        // 4) Restart settings to apply changes cleanly
        sb.append("am force-stop com.android.settings 2>/dev/null; ");
        sb.append("am start -a android.settings.SETTINGS >/dev/null 2>&1; ");
        sb.append("echo SETTINGS_APPLIED; ");
        return sb.toString();
    }

    private void stStatus(String msg, int color) {
        if (stStatusText != null) {
            stStatusText.setText(msg);
            stStatusText.setTextColor(color);
        }
    }

    private void showStHint() {
        if (stHint != null) {
            stHint.setVisibility(View.VISIBLE);
            if (stHintPulse == null) {
                stHintPulse = ObjectAnimator.ofFloat(stHint, View.ALPHA, 1f, 0.35f);
                stHintPulse.setDuration(700L);
                stHintPulse.setRepeatCount(ValueAnimator.INFINITE);
                stHintPulse.setRepeatMode(ValueAnimator.REVERSE);
            }
            stHintPulse.start();
        }
    }

    private void hideStHint() {
        if (stHintPulse != null) stHintPulse.cancel();
        if (stHint != null) {
            stHint.setAlpha(1f);
            stHint.setVisibility(View.GONE);
        }
    }

    private void settingsThemeResult(boolean ok, Entry e) {
        stRun = false;
        hideStHint();
        String out = (ok && e != null && e.out != null) ? e.out : "";
        if (!ok) {
            stStatus("uid 1000 无响应——请打开游戏模式后重试", Ui.ERROR);
        } else if (out.contains("SETTINGS_APPLIED")) {
            stStatus("强调色已应用到设置！设置已重启。", Ui.ACCENT);
            toast("设置已重新着色！");
        } else {
            stStatus("命令已执行——请检查系统设置", Ui.TEXT);
        }
    }

    private void pickFontFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/octet-stream", "font/ttf", "application/x-font-ttf", "*/*"});
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "字体文件（.ttf）"), REQ_PICK_FONT);
        } catch (Throwable t) {
            toast("文件管理器不可用");
        }
    }

    private void fontStatus(String msg, int color) {
        if (fontThemeStatusText != null) {
            fontThemeStatusText.setText(msg);
            fontThemeStatusText.setTextColor(color);
        }
    }

    private void showFontHint() {
        if (fontThemeHint != null) {
            fontThemeHint.setVisibility(View.VISIBLE);
            if (fontHintPulse == null) {
                fontHintPulse = ObjectAnimator.ofFloat(fontThemeHint, View.ALPHA, 1f, 0.35f);
                fontHintPulse.setDuration(700L);
                fontHintPulse.setRepeatCount(ValueAnimator.INFINITE);
                fontHintPulse.setRepeatMode(ValueAnimator.REVERSE);
            }
            fontHintPulse.start();
        }
    }

    private void hideFontHint() {
        if (fontHintPulse != null) fontHintPulse.cancel();
        if (fontThemeHint != null) {
            fontThemeHint.setAlpha(1f);
            fontThemeHint.setVisibility(View.GONE);
        }
    }

    private void runFontInstall() {
        if (busy) {
            toast("请等待当前命令执行完成");
            return;
        }
        if (fontStaging) {
            toast("文件仍在准备中，请稍候");
            return;
        }
        if (fontStagedPath == null || fontStagedPath.isEmpty()) {
            toast("请先选择 .ttf");
            return;
        }
        fontThemeRun = true;
        fontStatus("命令已发送——请打开游戏模式…", Ui.ACCENT);
        showFontHint();
        String cmd = fontCommand();
        if (!apply(cmd)) {
            fontThemeRun = false;
            hideFontHint();
            fontStatus("GC Tune 不在游戏列表中——请添加应用后重试", Ui.ERROR);
        }
    }

    private String fontCommand() {
        return FontInstaller.command(fontStagedPath, fontStagedMd5);
    }

    /** Turns the uid 1000 output of the font command into a human status line. */
    private void fontResult(boolean ok, Entry e) {
        fontThemeRun = false;
        hideFontHint();
        String out = (ok && e != null && e.out != null) ? e.out : "";
        if (!ok) {
            fontStatus("uid 1000 无响应——请打开游戏模式后重试", Ui.ERROR);
        } else if (out.contains("SRC_UNREADABLE")) {
            fontStatus("uid 1000 无法读取来源文件", Ui.ERROR);
        } else if (out.contains("SRC_STALE")) {
            fontStatus("来源与所选文件不一致——请重新选择", Ui.ERROR);
        } else if (out.contains("NO_FONT_FOUND")) {
            fontStatus("/data/vfonts/ 中没有可用的 TTF 文件。请在 iTheme 中下载字体后重试。", Ui.ERROR);
        } else if (out.contains("FAIL:MISSING_TTF:") && !out.contains("OK:")) {
            fontStatus("安装过程中 TTF 文件消失。请重新查找。详情见 Shell 标签页。", Ui.ERROR);
        } else if (out.contains("NO_PKG")) {
            fontStatus("无法确定 iTheme 包——详情见控制台", Ui.ERROR);
        } else if (!out.contains("OK:")) {
            fontStatus("写入失败——输出见控制台（Shell 标签页）", Ui.ERROR);
        } else {
            int okCount = countOccurrences(out, "OK:");
            int failCount = countOccurrences(out, "FAIL:");
            String msg = "已替换文件：" + okCount
                    + (failCount > 0 ? "，错误：" + failCount : "");
            if (out.contains("APP_FAILED")) {
                fontStatus(msg + "——iTheme 未打开，输出见控制台", Ui.ERROR);
            } else if (out.contains("APP_OPENED")) {
                fontStatus(msg + "，iTheme 已打开——请应用字体并重启——应用主题", Ui.ACCENT);
            } else {
                fontStatus(msg + "——详情见控制台", Ui.TEXT);
            }
        }
    }

    /** Mirrors stageThemeFile: direct path if readable, else copy to a known location. */
    private void stageFontFile(final Uri src) {
        if (src == null) return;
        if (fontStaging) {
            toast("文件仍在准备中");
            return;
        }
        fontStaging = true;
        fontStagedPath = null;
        fontStagedMd5 = null;
        fontStatus("正在读取文件…", Ui.MUTED);
        final String picked = queryDisplayName(src);
        if (fontThemeFileText != null) fontThemeFileText.setText("准备中…");

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String path = null;
                    String md5 = null;

                    String real = resolveRealPath(src);
                    if (real != null && real.startsWith("/")
                            && (real.startsWith("/storage/") || real.startsWith("/sdcard/"))
                            && !real.contains("/Android/") && new File(real).isFile()) {
                        try {
                            md5 = md5File(new File(real));
                            path = real;
                        } catch (Throwable t) {
                            path = null;
                        }
                    }

                    if (path == null) {
                        String staged = null;
                        Throwable firstErr = null;
                        if (Build.VERSION.SDK_INT >= 29) {
                            try {
                                staged = copyFontToDownloads(src);
                            } catch (Throwable t) {
                                firstErr = t;
                            }
                        }
                        if (staged == null) {
                            try {
                                staged = copyFontToAppFiles(src);
                            } catch (Throwable t) {
                                if (firstErr == null) firstErr = t;
                            }
                        }
                        if (staged == null) {
                            throw firstErr != null ? firstErr : new Exception("复制失败");
                        }
                        md5 = md5File(new File(staged));
                        path = staged;
                    }

                    // Validate TrueType font 1:1 from the fork
                    File f = new File(path);
                    FontInstaller.validate(f);

                    final String fp = path;
                    final String fm = md5;
                    final long size = f.length();
                    final String sizeTxt = humanSize(size);

                    h.post(new Runnable() {
                        @Override public void run() {
                            fontStaging = false;
                            fontStagedPath = fp;
                            fontStagedMd5 = fm;
                            fontStagedBytes = size;
                            fontThemeFileText.setText(picked + " · " + sizeTxt + "\n" + fp);
                            fontStatus("文件已就绪，md5 " + fm + "——可以安装", Ui.TEXT);
                        }
                    });
                } catch (final Throwable t) {
                    h.post(new Runnable() {
                        @Override public void run() {
                            fontStaging = false;
                            fontStagedPath = null;
                            fontStagedMd5 = null;
                            fontThemeFileText.setText("未选择文件");
                            fontStatus("准备出错：" + t.getMessage(), Ui.ERROR);
                        }
                    });
                }
            }
        }).start();
    }

    /** Copy into Download/gctune/font.ttf — the location uid 1000 can read. */
    private String copyFontToDownloads(Uri src) throws Exception {
        String rel = Environment.DIRECTORY_DOWNLOADS + "/" + FONT_STAGE_DIR;

        // a previous font.ttf would make MediaStore rename the row ("font (1).ttf")
        Cursor old = cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                        + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                new String[]{FONT_STAGE_NAME, rel + "/"}, null);
        if (old != null) {
            while (old.moveToNext()) {
                try {
                    cr.delete(ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, old.getLong(0)), null, null);
                } catch (Throwable ignored) {}
            }
            old.close();
        }

        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, FONT_STAGE_NAME);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
        Uri dst = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (dst == null) throw new Exception("MediaStore insert failed");
        try (InputStream in = cr.openInputStream(src)) {
            if (in == null) throw new Exception("来源不可用");
            try (OutputStream out = cr.openOutputStream(dst)) {
                if (out == null) throw new Exception("存储不可用");
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
        String path = null;
        Cursor q = cr.query(dst, new String[]{MediaStore.MediaColumns.DATA}, null, null, null);
        if (q != null) {
            if (q.moveToFirst()) path = q.getString(0);
            q.close();
        }
        if (path == null || path.isEmpty()) {
            File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            path = new File(dl, FONT_STAGE_DIR + "/" + FONT_STAGE_NAME).getAbsolutePath();
        }
        return path;
    }

    private String copyFontToAppFiles(Uri src) throws Exception {
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        File dst = new File(dir, FONT_STAGE_NAME);
        try (InputStream in = cr.openInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return dst.getAbsolutePath();
    }

    // ----------------------------------------------------------------- misc
    private static final String ADB_EXAMPLES =
            "# 通过 uid1000 通道执行命令\n"
            + "adb shell content call --uri content://com.poc.gctune.bridge --method exec --arg \"id\" --extra wait:i:8\n\n"
            + "# 结果：ack|STATUS|rc|base64\n"
            + "adb shell settings get secure gc_tune_adb_out\n\n"
            + "# 或来自仓库的脚本\n"
            + ".\\gctune-adb.ps1 \"id\"";

    /** Whitelist + appops + standby for ANY package, applied via uid1000. */
    private String hardenCommand(String pkg) {
        return "cmd deviceidle whitelist +" + pkg + " >/dev/null 2>&1;"
                + "dumpsys deviceidle whitelist +" + pkg + " >/dev/null 2>&1;"
                + "cmd appops set " + pkg + " RUN_IN_BACKGROUND allow >/dev/null 2>&1;"
                + "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1;"
                + "cmd appops set " + pkg + " START_FOREGROUND allow >/dev/null 2>&1;"
                + "cmd appops set " + pkg + " WAKE_LOCK allow >/dev/null 2>&1;"
                + "cmd appops set " + pkg + " BOOT_COMPLETED allow >/dev/null 2>&1;"
                + "am set-inactive " + pkg + " false >/dev/null 2>&1;"
                + "[ -f /data/bbkcore/forbidBgStartAppsName.txt ] && sed -i '/" + pkg + "/d' /data/bbkcore/forbidBgStartAppsName.txt 2>/dev/null;"
                + "settings put system vivo_game_keep_alive 1 >/dev/null 2>&1;"
                + "echo DOZE=$(cmd deviceidle whitelist =" + pkg + " 2>/dev/null);"
                + "echo BGOP=$(appops get " + pkg + " RUN_ANY_IN_BACKGROUND 2>/dev/null)";
    }

    /** Remove package from whitelist and reset background restrictions. */
    private String unhardenCommand(String pkg) {
        return "cmd deviceidle whitelist -" + pkg + " >/dev/null 2>&1;"
                + "dumpsys deviceidle whitelist -" + pkg + " >/dev/null 2>&1;"
                + "cmd appops set " + pkg + " RUN_IN_BACKGROUND default >/dev/null 2>&1;"
                + "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND default >/dev/null 2>&1;"
                + "echo DOZE=$(cmd deviceidle whitelist =" + pkg + " 2>/dev/null)";
    }

    /**
     * Soft-but-deep freeze of any package via uid1000: kill its background,
     * cut background launch, then suspend it (disable-user as a fallback).
     * Read-back: SUSP=1 suspended, DIS=1 disabled.
     */
    private String freezeCommand(String pkg) {
        return "cmd deviceidle whitelist -" + pkg + " >/dev/null 2>&1;"
                + "am kill " + pkg + ";"
                + "am set-inactive " + pkg + " true;"
                + "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND ignore >/dev/null 2>&1;"
                + "cmd appops set " + pkg + " RUN_IN_BACKGROUND ignore >/dev/null 2>&1;"
                + "pm suspend " + pkg + " >/dev/null 2>&1 || "
                + "pm disable-user --user 0 " + pkg + " >/dev/null 2>&1;"
                + "echo SUSP=$(dumpsys package " + pkg + " | grep -c suspended=true);"
                + "echo DIS=$(pm list packages -d | grep -cx package:" + pkg + ")";
    }

    /** Reverses freezeCommand: unsuspend/enable + allow background again. */
    private String unfreezeCommand(String pkg) {
        return "pm unsuspend " + pkg + " >/dev/null 2>&1;"
                + "pm enable " + pkg + " >/dev/null 2>&1;"
                + "cmd deviceidle whitelist +" + pkg + " >/dev/null 2>&1;"
                + "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1;"
                + "cmd appops set " + pkg + " RUN_IN_BACKGROUND allow >/dev/null 2>&1;"
                + "am set-inactive " + pkg + " false >/dev/null 2>&1;"
                + "echo SUSP=$(dumpsys package " + pkg + " | grep -c suspended=true);"
                + "echo DIS=$(pm list packages -d | grep -cx package:" + pkg + ")";
    }

    private View buildMiscPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);

        LinearLayout root = Ui.col(this);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 30));
        scroll.addView(root, new FrameLayout.LayoutParams(-1, -2));

        root.addView(pageTitle(Ui.label(this, "其他", 26f, Ui.TEXT),
                "后台保活与 ADB 桥"));

        // ---- unkillable background: pick any installed app, toggle protection & freeze
        LinearLayout keepCard = Ui.col(this);
        keepCard.setBackground(Ui.shape(this, Ui.SURFACE, 24f, Ui.BORDER_SOFT, 1.2f));
        keepCard.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
        keepCard.addView(Ui.section(this, "应用防清理保护"));

        TextView desc = Ui.text(this,
                "加入 Doze 电池白名单（cmd deviceidle whitelist）+ 允许无限制后台运行。",
                11.5f, Ui.MUTED);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(-1, -2);
        descLp.topMargin = Ui.dp(this, 4);
        keepCard.addView(desc, descLp);

        // Current target row with toggle
        LinearLayout targetBox = Ui.row(this);
        targetBox.setGravity(Gravity.CENTER_VERTICAL);
        targetBox.setBackground(Ui.shape(this, Ui.INPUT_BG, 18f, Ui.BORDER, 1f));
        targetBox.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));

        LinearLayout targetCol = Ui.col(this);
        miscHardenTarget = Ui.text(this, "目标：GC Tune", 14f, Ui.TEXT);
        targetCol.addView(miscHardenTarget);
        TextView targetSub = Ui.text(this, "点击「从列表选择」以更改", 11f, Ui.MUTED);
        LinearLayout.LayoutParams tsLp = new LinearLayout.LayoutParams(-1, -2);
        tsLp.topMargin = Ui.dp(this, 2);
        targetCol.addView(targetSub, tsLp);
        targetBox.addView(targetCol, new LinearLayout.LayoutParams(0, -2, 1f));

        targetSw = new MaterialSwitch(this);
        boolean isProtected = prefs.getBoolean("pkg_protected_" + hardenPkg(), PKG.equals(hardenPkg()));
        targetSw.setChecked(isProtected);
        tintSwitch(targetSw);
        targetSw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                Ui.tap(b);
                String p = hardenPkg();
                prefs.edit().putBoolean("pkg_protected_" + p, on).apply();
                if (on) runHarden(); else runUnharden();
            }
        });
        targetBox.addView(targetSw);

        LinearLayout.LayoutParams tbLp = new LinearLayout.LayoutParams(-1, -2);
        tbLp.topMargin = Ui.dp(this, 12);
        keepCard.addView(targetBox, tbLp);

        LinearLayout btnRow = Ui.row(this);
        View pickBtn = pillButton(M3Icons.folder(Ui.ACCENT, Ui.dp(this, 18)),
                "应用列表",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { pickHardenApp(); }
                });
        btnRow.addView(pickBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        View frzBtn = pillButton(M3Icons.stop(Ui.ACCENT, Ui.dp(this, 18)),
                "冻结",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { freezeRun(); }
                });
        LinearLayout.LayoutParams frzLp = new LinearLayout.LayoutParams(0, -2, 1f);
        frzLp.leftMargin = Ui.dp(this, 10);
        btnRow.addView(frzBtn, frzLp);
        LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(-1, -2);
        btnRowLp.topMargin = Ui.dp(this, 12);
        keepCard.addView(btnRow, btnRowLp);

        miscHardenStatus = Ui.text(this, "", 11.5f, Ui.MUTED);
        LinearLayout.LayoutParams hardenStLp = new LinearLayout.LayoutParams(-1, -2);
        hardenStLp.topMargin = Ui.dp(this, 8);
        keepCard.addView(miscHardenStatus, hardenStLp);

        LinearLayout keepRow = Ui.row(this);
        keepRow.addView(Ui.label(this, "自有后台服务", 14f, Ui.TEXT),
                new LinearLayout.LayoutParams(0, -2, 1f));

        keepSwitch = new MaterialSwitch(this);
        keepSwitch.setChecked(prefs.getBoolean("keep_alive", false));
        tintSwitch(keepSwitch);
        keepSwitch.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                        Ui.tap(b);
                        setKeepAlive(on);
                    }
                });
        keepRow.addView(keepSwitch);
        LinearLayout.LayoutParams keepRowLp = new LinearLayout.LayoutParams(-1, -2);
        keepRowLp.topMargin = Ui.dp(this, 14);
        keepCard.addView(keepRow, keepRowLp);

        miscKeepStatus = Ui.text(this, "", 11.5f, Ui.MUTED);
        LinearLayout.LayoutParams keepStLp = new LinearLayout.LayoutParams(-1, -2);
        keepStLp.topMargin = Ui.dp(this, 6);
        keepCard.addView(miscKeepStatus, keepStLp);

        LinearLayout.LayoutParams keepCardLp = new LinearLayout.LayoutParams(-1, -2);
        keepCardLp.bottomMargin = Ui.dp(this, 14);
        root.addView(keepCard, keepCardLp);

        // ---- adb bridge card
        LinearLayout bridgeCard = Ui.col(this);
        bridgeCard.setBackground(Ui.shape(this, Ui.SURFACE, 24f, Ui.BORDER_SOFT, 1.2f));
        bridgeCard.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
        bridgeCard.addView(Ui.section(this, "ADB 桥"));

        LinearLayout bridgeRow = Ui.row(this);
        LinearLayout bridgeLbl = Ui.col(this);
        bridgeLbl.addView(Ui.label(this, "通过 ADB 执行命令", 14f, Ui.TEXT));
        TextView bridgeSub = Ui.text(this, "仅限 adb shell 和 root", 11.5f, Ui.MUTED);
        LinearLayout.LayoutParams bridgeSubLp = new LinearLayout.LayoutParams(-1, -2);
        bridgeSubLp.topMargin = Ui.dp(this, 3);
        bridgeLbl.addView(bridgeSub, bridgeSubLp);
        bridgeRow.addView(bridgeLbl, new LinearLayout.LayoutParams(0, -2, 1f));

        bridgeSwitch = new MaterialSwitch(this);
        bridgeSwitch.setChecked(prefs.getBoolean("adb_bridge", true));
        tintSwitch(bridgeSwitch);
        bridgeSwitch.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                        Ui.tap(b);
                        prefs.edit().putBoolean("adb_bridge", on).apply();
                        updateMiscStatus();
                    }
                });
        bridgeRow.addView(bridgeSwitch);
        LinearLayout.LayoutParams bridgeRowLp = new LinearLayout.LayoutParams(-1, -2);
        bridgeRowLp.topMargin = Ui.dp(this, 10);
        bridgeCard.addView(bridgeRow, bridgeRowLp);

        miscBridgeStatus = Ui.text(this, "", 11.5f, Ui.MUTED);
        LinearLayout.LayoutParams bridgeStLp = new LinearLayout.LayoutParams(-1, -2);
        bridgeStLp.topMargin = Ui.dp(this, 8);
        bridgeCard.addView(miscBridgeStatus, bridgeStLp);

        // command examples (monospace, selectable)
        LinearLayout exCard = Ui.col(this);
        exCard.setBackground(Ui.shape(this, Ui.INPUT_BG, 10f, Ui.BORDER, 1f));
        exCard.setPadding(Ui.dp(this, 10), Ui.dp(this, 9), Ui.dp(this, 10), Ui.dp(this, 9));
        TextView ex = Ui.text(this, ADB_EXAMPLES, 10.5f, Ui.PROMPT);
        ex.setTypeface(Typeface.MONOSPACE);
        ex.setTextIsSelectable(true);
        exCard.addView(ex);
        LinearLayout.LayoutParams exLp = new LinearLayout.LayoutParams(-1, -2);
        exLp.topMargin = Ui.dp(this, 10);
        bridgeCard.addView(exCard, exLp);

        View copyEx = pillButton(M3Icons.clipboard(Ui.ACCENT, Ui.dp(this, 18)),
                "复制命令",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try {
                            android.content.ClipboardManager cl =
                                    (android.content.ClipboardManager)
                                            getSystemService(CLIPBOARD_SERVICE);
                            cl.setPrimaryClip(android.content.ClipData.newPlainText(
                                    "GC Tune ADB", ADB_EXAMPLES));
                            toast("命令已复制");
                        } catch (Throwable t) {
                            toast("复制失败");
                        }
                    }
                });
        LinearLayout.LayoutParams copyExLp = new LinearLayout.LayoutParams(-1, -2);
        copyExLp.topMargin = Ui.dp(this, 10);
        bridgeCard.addView(copyEx, copyExLp);

        TextView bridgeWarn = Ui.text(this, "仅在需要时开启。", 11f, Ui.MUTED);
        LinearLayout.LayoutParams warnLp = new LinearLayout.LayoutParams(-1, -2);
        warnLp.topMargin = Ui.dp(this, 10);
        bridgeCard.addView(bridgeWarn, warnLp);

        root.addView(bridgeCard, new LinearLayout.LayoutParams(-1, -2));

        updateMiscStatus();
        return scroll;
    }

    /** Toggle: sticky foreground service + watchdog alarm for GC Tune itself. */
    private void setKeepAlive(boolean on) {
        prefs.edit().putBoolean("keep_alive", on).apply();
        if (on) {
            KeepAliveReceiver.schedule(this,
                    System.currentTimeMillis() + KeepAliveReceiver.INTERVAL);
            KeepAliveService.start(this);
        } else {
            KeepAliveReceiver.cancel(this);
            KeepAliveService.stop(this);
        }
        updateMiscStatus();
    }

    /** One entry of the themed app picker: label, package, launcher icon. */
    private static final class AppRow {
        final String label, pkg;
        final Drawable icon;
        AppRow(String label, String pkg, Drawable icon) {
            this.label = label;
            this.pkg = pkg;
            this.icon = icon;
        }
    }

    /**
     * Themed app picker: rounded dialog, live search, icon rows, current target
     * highlighted. Picking a row stores it and hardens it at once.
     */
    private void pickHardenApp() {
        try {
            final Dialog dlg = new Dialog(this);
            dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

            final LinearLayout body = Ui.col(this);
            body.setBackground(Ui.shape(this, Ui.SURFACE, 18f, Ui.BORDER_SOFT, 1f));
            int pad = Ui.dp(this, 14);
            body.setPadding(pad, pad, pad, pad);

            body.addView(Ui.section(this, "应用"));

            final EditText q = new EditText(this);
            q.setHint("搜索");
            q.setHintTextColor(Ui.MUTED);
            q.setTextColor(Ui.TEXT);
            q.setTextSize(14f);
            q.setSingleLine(true);
            q.setBackground(Ui.shape(this, Ui.INPUT_BG, 10f, Ui.BORDER, 1f));
            q.setPadding(Ui.dp(this, 10), Ui.dp(this, 5), Ui.dp(this, 10), Ui.dp(this, 5));
            LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(-1, -2);
            qLp.topMargin = Ui.dp(this, 10);
            body.addView(q, qLp);

            final ScrollView sc = new ScrollView(this);
            sc.setVerticalScrollBarEnabled(false);
            sc.setPadding(0, Ui.dp(this, 8), 0, 0);
            final LinearLayout list = Ui.col(this);
            sc.addView(list);
            body.addView(sc, new LinearLayout.LayoutParams(-1, Ui.dp(this, 380)));

            FrameLayout wrap = new FrameLayout(this);
            wrap.setPadding(Ui.dp(this, 22), 0, Ui.dp(this, 22), 0);
            wrap.addView(body, new FrameLayout.LayoutParams(-1, -2));
            dlg.setContentView(wrap);
            Window w = dlg.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT);
                w.setDimAmount(0.55f);
            }

            final List<AppRow> rows = new ArrayList<>();
            final Runnable render = new Runnable() {
                @Override public void run() {
                    try {
                        String f = q.getText().toString().trim()
                                .toLowerCase(java.util.Locale.ROOT);
                        String cur = hardenPkg();
                        list.removeAllViews();
                        for (AppRow r : rows) {
                            if (!f.isEmpty() && !r.label.toLowerCase(java.util.Locale.ROOT)
                                    .contains(f) && !r.pkg.contains(f)) {
                                continue;
                            }
                            list.addView(appRow(dlg, r, r.pkg.equals(cur)));
                        }
                        if (list.getChildCount() == 0) {
                            TextView none = Ui.text(Main.this,
                                    "未找到任何内容", 13f, Ui.MUTED);
                            none.setGravity(Gravity.CENTER);
                            LinearLayout.LayoutParams nLp =
                                    new LinearLayout.LayoutParams(-1, -2);
                            nLp.topMargin = Ui.dp(Main.this, 18);
                            list.addView(none, nLp);
                        }
                    } catch (Throwable ignored) {}
                }
            };
            q.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) { render.run(); }
            });

            dlg.show();

            // labels + icons off the UI thread, then render once
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        final android.content.pm.PackageManager pm = getPackageManager();
                        List<android.content.pm.ApplicationInfo> apps =
                                new ArrayList<>(pm.getInstalledApplications(0));
                        java.util.Collections.sort(apps,
                                new java.util.Comparator<android.content.pm.ApplicationInfo>() {
                                    @Override
                                    public int compare(android.content.pm.ApplicationInfo a,
                                                       android.content.pm.ApplicationInfo b) {
                                        return appLabel(a).compareToIgnoreCase(appLabel(b));
                                    }
                                });
                        final List<AppRow> built = new ArrayList<>();
                        for (android.content.pm.ApplicationInfo ai : apps) {
                            Drawable icon;
                            try {
                                icon = pm.getApplicationIcon(ai);
                            } catch (Throwable t) {
                                icon = null;
                            }
                            built.add(new AppRow(appLabel(ai), ai.packageName, icon));
                        }
                        h.post(new Runnable() {
                            @Override public void run() {
                                try {
                                    rows.addAll(built);
                                    render.run();
                                } catch (Throwable ignored) {}
                            }
                        });
                    } catch (Throwable ignored) {}
                }
            }).start();
        } catch (Throwable t) {
            toast("应用列表不可用");
        }
    }

    /** Icon + label + package row with toggle inside the app picker dialog. */
    private View appRow(final Dialog dlg, final AppRow r, boolean selected) {
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.ripple(this, selected ? Ui.PRIMARY : 0x00000000,
                12f, 0x4082E5A6));
        int p = Ui.dp(this, 8);
        row.setPadding(p, p, p, p);

        ImageView ic = new ImageView(this);
        if (r.icon != null) ic.setImageDrawable(r.icon);
        ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));

        LinearLayout col = Ui.col(this);
        TextView lb = Ui.text(this, r.label, 14f, selected ? Ui.ACCENT : Ui.TEXT);
        col.addView(lb);
        TextView pk = Ui.text(this, r.pkg, 10.5f, Ui.MUTED);
        LinearLayout.LayoutParams pkLp = new LinearLayout.LayoutParams(-2, -2);
        pkLp.topMargin = Ui.dp(this, 1);
        col.addView(pk, pkLp);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, -2, 1f);
        colLp.leftMargin = Ui.dp(this, 10);
        colLp.rightMargin = Ui.dp(this, 8);
        row.addView(col, colLp);

        final MaterialSwitch sw = new MaterialSwitch(this);
        boolean isProt = prefs.getBoolean("pkg_protected_" + r.pkg, PKG.equals(r.pkg));
        sw.setChecked(isProt);
        tintSwitch(sw);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                Ui.tap(b);
                prefs.edit().putBoolean("pkg_protected_" + r.pkg, on).apply();
                prefs.edit().putString("harden_pkg", r.pkg)
                        .putString("harden_label", r.label).apply();
                updateMiscStatus();
                if (on) runHarden(); else runUnharden();
            }
        });
        row.addView(sw);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.bottomMargin = Ui.dp(this, 2);
        row.setLayoutParams(rowLp);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.tap(v);
                prefs.edit().putString("harden_pkg", r.pkg)
                        .putString("harden_label", r.label).apply();
                updateMiscStatus();
                sw.toggle();
            }
        });
        return row;
    }

    private String appLabel(android.content.pm.ApplicationInfo ai) {
        try {
            return getPackageManager().getApplicationLabel(ai).toString();
        } catch (Throwable t) {
            return ai.packageName;
        }
    }

    private String hardenPkg() {
        String p = prefs.getString("harden_pkg", PKG);
        return p == null || p.isEmpty() ? PKG : p;
    }

    /** Sends any misc probe for the stored target through the channel. */
    private void runMisc(String action, String cmd, String progress) {
        miscAction = action;
        if (miscHardenStatus != null) {
            miscHardenStatus.setText(progress);
            miscHardenStatus.setTextColor(Ui.PROMPT);
        }
        miscRun = true;
        if (!apply(cmd)) {
            miscRun = false;
            if (miscHardenStatus != null) {
                miscHardenStatus.setText("未发送——没有游戏状态或通道被占用");
                miscHardenStatus.setTextColor(Ui.ERROR);
            }
        }
    }

    /** Sends the hardening probe for the stored target through the channel. */
    private void runHarden() {
        runMisc("harden", hardenCommand(hardenPkg()), "正在加入 Doze 白名单…");
    }

    private void runUnharden() {
        runMisc("unharden", unhardenCommand(hardenPkg()), "正在从 Doze 白名单移除…");
    }

    private void freezeRun() {
        runMisc("freeze", freezeCommand(hardenPkg()),
                "正在冻结 " + prefs.getString("harden_label", "GC Tune") + "…");
    }

    private void unfreezeRun() {
        runMisc("unfreeze", unfreezeCommand(hardenPkg()), "正在解冻…");
    }

    /** Short read-back of the DOZE/SUSP/DIS lines produced by the commands. */
    private void miscResult(boolean ok, Entry e) {
        miscRun = false;
        if (miscHardenStatus == null) return;
        String out = (ok && e != null && e.out != null) ? e.out : "";
        if (!ok) {
            miscHardenStatus.setText("无响应——请打开游戏模式");
            miscHardenStatus.setTextColor(Ui.ERROR);
            return;
        }
        // SUSP=/DIS= markers: present and non-zero means the heavy part landed
        boolean susp = out.contains("SUSP=") && !out.contains("SUSP=0");
        boolean dis = out.contains("DIS=") && !out.contains("DIS=0");
        boolean hasMarkers = out.contains("SUSP=") || out.contains("DIS=");
        if ("freeze".equals(miscAction)) {
            if (susp || dis) {
                miscHardenStatus.setText("已冻结"
                        + (dis ? "（已停用）" : "（已暂停）"));
                miscHardenStatus.setTextColor(Ui.ACCENT);
            } else if (hasMarkers) {
                miscHardenStatus.setText("后台受限，suspend 不可用");
                miscHardenStatus.setTextColor(Ui.MUTED);
            } else {
                miscHardenStatus.setText(out.replace("\n", " · "));
                miscHardenStatus.setTextColor(Ui.TEXT);
            }
        } else if ("unfreeze".equals(miscAction)) {
            if (hasMarkers && !susp && !dis) {
                miscHardenStatus.setText("已解冻");
                miscHardenStatus.setTextColor(Ui.ACCENT);
            } else if (hasMarkers) {
                miscHardenStatus.setText("部分完成——输出见控制台");
                miscHardenStatus.setTextColor(Ui.ERROR);
            } else {
                miscHardenStatus.setText(out.replace("\n", " · "));
                miscHardenStatus.setTextColor(Ui.TEXT);
            }
        } else if ("unharden".equals(miscAction)) {
            miscHardenStatus.setText("保护已移除（已从白名单删除）");
            miscHardenStatus.setTextColor(Ui.MUTED);
        } else if (out.contains("DOZE=false")) {
            miscHardenStatus.setText("不在白名单中——请检查控制台");
            miscHardenStatus.setTextColor(Ui.ERROR);
        } else if (out.contains("DOZE=true") || out.contains("DOZE=")) {
            miscHardenStatus.setText("已保护：Doze 白名单 + 后台已允许");
            miscHardenStatus.setTextColor(Ui.ACCENT);
        } else {
            miscHardenStatus.setText(out.replace("\n", " · "));
            miscHardenStatus.setTextColor(Ui.TEXT);
        }
    }

    private void updateMiscStatus() {
        boolean keep = prefs.getBoolean("keep_alive", false);
        boolean br = prefs.getBoolean("adb_bridge", true);
        String curPkg = hardenPkg();
        if (miscHardenTarget != null) {
            miscHardenTarget.setText("目标："
                    + prefs.getString("harden_label", "GC Tune"));
        }
        if (targetSw != null) {
            boolean isProtected = prefs.getBoolean("pkg_protected_" + curPkg, PKG.equals(curPkg));
            targetSw.setChecked(isProtected);
        }
        if (miscKeepStatus != null) {
            miscKeepStatus.setText(keep ? "服务已激活" : "关");
            miscKeepStatus.setTextColor(keep ? Ui.ACCENT : Ui.MUTED);
        }
        if (miscBridgeStatus != null) {
            StringBuilder sb = new StringBuilder(br ? "开" : "关");
            if (br && bridgeLastCmd != null && !bridgeLastCmd.isEmpty()) {
                sb.append(" · ").append(bridgeLastCmd);
            }
            miscBridgeStatus.setText(sb.toString());
            miscBridgeStatus.setTextColor(br ? Ui.ACCENT : Ui.MUTED);
        }
    }

    // ----------------------------------------------------------- adb bridge
    private static String normalizeCmd(String s) {
        String n = s.replace("\r\n", ";").replace('\n', ';').replace('\r', ';');
        return n.replaceAll(";\\s*;", ";");
    }

    private static Bundle bridgeErr(String status, String msg, long ack) {
        Bundle b = new Bundle();
        b.putString("status", status);
        b.putString("msg", msg);
        b.putLong("ack", ack);
        return b;
    }

    /** Single stable line for adb scripts: ack|STATUS|rc|base64(utf8). */
    private void writeAdbMirror(long ack, String status, String rc, String out) {
        try {
            String b64 = out == null || out.isEmpty() ? ""
                    : Base64.encodeToString(out.getBytes(StandardCharsets.UTF_8),
                            Base64.NO_WRAP);
            Settings.Secure.putString(cr, BridgeProvider.OUT_KEY,
                    ack + "|" + status + "|" + rc + "|" + b64);
        } catch (Throwable t) {
            Log.e("GCTune", "adb mirror write failed: " + t);
        }
    }

    /**
     * Runs one adb command through the GUI channel. Called by BridgeProvider
     * on its binder thread; the actual send happens on the UI thread (busy /
     * game-status / console state all live there) and finishRun() releases
     * the waiting binder call.
     */
    private Bundle bridgeSend(String cmd) {
        if (busy) return bridgeErr("busy", "通道被占用——请等待当前命令执行完成", 0);
        boolean game = isGame();
        boolean req = prefs.getBoolean("require_game", true);
        if (req && !game) {
            String msg = "没有游戏状态——请将 GC Tune 添加到游戏列表"
                    + "（电竞模式 → 支持的游戏）";
            writeAdbMirror(0, "ERROR", "-", msg);
            return bridgeErr("no_game", msg, 0);
        }
        String c = normalizeCmd(cmd);
        bridgeLastCmd = c;
        updateMiscStatus();
        if (!apply(c)) {
            String msg = "未发送——通道被占用或没有游戏状态";
            writeAdbMirror(0, "ERROR", "-", msg);
            return bridgeErr("send_failed", msg, 0);
        }
        bridgeAck = curAck;
        writeAdbMirror(curAck, "PENDING", "-", "");
        return null; // 结果将经由 finishRun → resolveBridge 返回
    }

    /** Releases the waiting provider call and mirrors the final result. */
    private void resolveBridge(boolean ok, Entry e, String failReason) {
        long ack = bridgeAck;
        if (ack == 0) return;
        bridgeAck = 0;
        int rc = (e != null) ? e.rc : RC_UNKNOWN;
        String out;
        if (ok) {
            out = e != null && e.out != null ? e.out : "";
        } else {
            out = failReason != null ? failReason : "uid 1000 无响应";
            rc = RC_UNKNOWN;
        }
        writeAdbMirror(ack, ok ? "OK" : "FAIL", String.valueOf(rc), out);

        Bundle b = new Bundle();
        b.putString("status", ok ? "ok" : "fail");
        b.putLong("ack", ack);
        b.putInt("rc", rc);
        b.putString("out", out);
        bridgeResult = b;
        CountDownLatch l = bridgeLatch;
        bridgeLatch = null;
        if (l != null) l.countDown();
    }

    /** Registered as BridgeProvider.bridge while the UI is alive. */
    private final BridgeProvider.Bridge bridgeImpl = new BridgeProvider.Bridge() {
        @Override
        public Bundle exec(final String cmd, final int waitSec) {
            if (cmd == null || cmd.trim().isEmpty()) {
                return bridgeErr("empty", "空命令", 0);
            }
            if (!prefs.getBoolean("adb_bridge", true)) {
                return bridgeErr("disabled",
                        "桥已关闭——请在「其他」页面开启「通过 ADB 执行命令」", 0);
            }
            bridgeResult = null;
            final CountDownLatch latch = new CountDownLatch(1);
            bridgeLatch = latch;
            h.post(new Runnable() {
                @Override
                public void run() {
                    Bundle r;
                    try {
                        r = bridgeSend(cmd);
                    } catch (Throwable t) {
                        r = bridgeErr("error", String.valueOf(t), 0);
                    }
                    if (r != null) {
                        bridgeResult = r;
                        bridgeLatch = null;
                        latch.countDown();
                    }
                }
            });
            try {
                if (latch.await(Math.max(1, waitSec), TimeUnit.SECONDS)) {
                    Bundle r = bridgeResult;
                    if (r != null) return r;
                }
            } catch (InterruptedException ignored) {}
            Bundle b = new Bundle();
            b.putString("status", "pending");
            b.putLong("ack", bridgeAck);
            b.putString("cmd", cmd);
            return b;
        }

        @Override
        public Bundle status() {
            Bundle b = new Bundle();
            b.putString("status", "ok");
            b.putBoolean("main", true);
            b.putBoolean("busy", busy);
            b.putBoolean("bridge", prefs.getBoolean("adb_bridge", true));
            b.putBoolean("keep_alive", prefs.getBoolean("keep_alive", false));
            b.putBoolean("game", isGame());
            b.putString("last_cmd", bridgeLastCmd);
            b.putLong("ack", bridgeAck);
            return b;
        }
    };

    // --------------------------------------------------------------- console
    private void appendStyled(SpannableStringBuilder sb, String text, int color, float sizeSp) {
        int start = sb.length();
        sb.append(text);
        sb.setSpan(new ForegroundColorSpan(color), start, sb.length(),
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new AbsoluteSizeSpan(Ui.sp(this, sizeSp)), start, sb.length(),
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void renderLog() {
        if (logView == null) return;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (Entry e : entries) {
            if (e.status) {
                appendStyled(sb, stamp(e.ts) + " ", Ui.MUTED, 10f);
                appendStyled(sb, e.out + "\n", Ui.MUTED, 11.5f);
            } else {
                String cmd = e.cmd != null && e.cmd.length() > 96
                        ? e.cmd.substring(0, 96) + "…" : String.valueOf(e.cmd);
                appendStyled(sb, stamp(e.ts) + " ", Ui.MUTED, 10f);
                appendStyled(sb, "uid1000", Ui.PROMPT, 11f);
                appendStyled(sb, "$ ", Ui.ACCENT, 12f);
                appendStyled(sb, cmd + "\n", Ui.TEXT, 12f);
                if (e.pending) {
                    appendStyled(sb, "…正在执行\n", Ui.PROMPT, 11f);
                } else if (e.failed) {
                    appendStyled(sb, "uid 1000 无响应\n", Ui.ERROR, 11f);
                } else {
                    boolean err = e.rc != RC_UNKNOWN && e.rc != 0;
                    String out = e.out != null ? e.out : "";
                    boolean empty = NO_OUTPUT.equals(out);
                    appendStyled(sb, out + "\n", empty ? Ui.MUTED : (err ? Ui.ERROR : Ui.TEXT),
                            empty ? 11f : 12f);
                    if (e.rc != RC_UNKNOWN) {
                        appendStyled(sb, "rc " + e.rc + "\n", err ? Ui.ERROR : Ui.PROMPT, 10f);
                    }
                }
            }
            sb.append('\n');
        }
        logView.setText(sb, TextView.BufferType.SPANNABLE);
        logScroll.post(new Runnable() {
            @Override public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
        });
    }

    private void logStatus(String s) {
        Entry e = new Entry();
        e.status = true;
        e.out = s;
        addEntry(e);
    }

    private Entry addEntry(Entry e) {
        if (e != null) {
            Diag.record("ui", (e.status ? "状态 " : "命令 ") + (e.status ? e.out : e.cmd));
        }
        entries.add(e);
        while (entries.size() > MAX_ENTRIES) entries.remove(0);
        renderLog();
        return e;
    }

    private Entry lastCommandEntry() {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (!entries.get(i).status) return entries.get(i);
        }
        return null;
    }

    // -------------------------------------------------------------- decoding
    private String decodeOutput(String base64Output) {
        if (base64Output == null || base64Output.trim().isEmpty()) {
            return "";
        }
        try {
            byte[] data = Base64.decode(base64Output, Base64.DEFAULT);
            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "解码出错：" + e.getMessage();
        }
    }

    /** Splits "stdout/stderr + __RC__<n>" into its parts. */
    private static void splitResult(String raw, Entry out) {
        if (raw == null) raw = "";
        Matcher m = RC_PAT.matcher(raw);
        if (m.find()) {
            try {
                out.rc = Integer.parseInt(m.group(1));
            } catch (Exception ignored) {
                out.rc = RC_UNKNOWN;
            }
            raw = raw.substring(0, m.start());
        }
        raw = stripTrailing(raw);
        out.out = raw.isEmpty() ? NO_OUTPUT : raw;
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    // ------------------------------------------------------------- file import
    private void pickScriptFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        String[] mimeTypes = {"text/plain", "application/x-sh", "text/x-shellscript", "text/*"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "脚本文件（.sh / .txt）"),
                    REQ_IMPORT_FILE);
        } catch (Throwable t) {
            toast("无法打开文件管理器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMPORT_FILE && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            importFromFile(data.getData());
        }
        if (requestCode == REQ_PICK_ITZ && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            stageThemeFile(data.getData());
        }
        if (requestCode == REQ_PICK_FONT && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            stageFontFile(data.getData());
        }
    }

    private void importFromFile(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            int lines = 0;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                // double quotes would break the shell channel: normalise them
                if (line.indexOf('"') >= 0) line = line.replace('"', '\'');
                if (sb.length() > 0 && !sb.toString().endsWith(";")) sb.append(";\n");
                sb.append(line);
                lines++;
            }
            if (sb.length() > 0 && !sb.toString().endsWith(";")) sb.append(";");
            cmdInput.setText(sb.toString());
            cmdInput.setSelection(cmdInput.getText().length());
            toast("已导入行数：" + lines);
        } catch (Exception e) {
            logStatus("导入出错：" + e.getMessage());
        }
    }

    private void pasteFromClipboard() {
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()
                && clipboard.getPrimaryClip().getItemCount() > 0) {
            CharSequence pasteData = clipboard.getPrimaryClip().getItemAt(0).getText();
            if (pasteData != null) {
                cmdInput.setText(pasteData);
                cmdInput.setSelection(cmdInput.getText().length());
                toast("已从剪贴板粘贴");
            }
        }
    }

    // -------------------------------------------------------------- execution
    private void executeCurrentCommand() {
        String raw = cmdInput.getText().toString().trim();
        if (raw.isEmpty()) {
            toast("请输入命令");
            return;
        }

        // normalise a multi-line script into a single shell pipeline
        String normalized = raw.replace("\r\n", ";").replace('\n', ';').replace('\r', ';');
        normalized = normalized.replaceAll(";\\s*;", ";");

        if (normalized.indexOf('"') >= 0) {
            normalized = normalized.replace('"', '\'');
            cmdInput.setText(normalized);
        }
        apply(normalized);
    }

    /** Sends the command through the layer-name channel. Returns false if it was not sent. */
    private boolean apply(String cmd) {
        if (busy) return false;

        boolean game = isGame();
        boolean req = prefs.getBoolean("require_game", true);
        if (req && !game) {
            logStatus("GC Tune 不在 Game Cube 游戏列表中");
            toast("请添加 GC Tune：电竞模式 → 支持的游戏 → 其他");
            refreshStatus();
            return false;
        }

        timeoutSec = prefs.getInt("timeout_sec", 60);
        if (timeoutSec < 3) timeoutSec = 3;
        if (timeoutSec > 90) timeoutSec = 90;

        curAck = ++ackCounter;

        String c = cmd.trim();
        if (!c.endsWith(";") && !c.endsWith("&")) c = c + ";";

        // Body: output travels through the broadcast extras; the settings
        // value is kept as a fallback channel. The user command itself never
        // contains double quotes (executeCurrentCommand strips them), and the
        // generated parts may quote freely - $( ... ) is parsed as its own
        // context inside the double-quoted layer argument.
        String body = "b=$({ " + c
                + " echo " + RC_MARK + "$?; } 2>&1 | base64 -w 0 | head -c 8000);"
                + "b=${b:-Cg==};"
                + "se=$( { settings put system " + ACK_KEY + " " + curAck + "\\|$b 2>&1; echo RC$?; } | base64 -w 0);"
                + "se=${se:-Cg==};"
                + "am broadcast -a com.poc.gctune.ACK --ei n " + curAck
                + " --es o $b --es e $se >/dev/null 2>&1";
        boolean daemon = prefs.getBoolean("daemon_on", true);
        String payload = "x$(" + (daemon ? DAEMON_BOOT : "") + body + ")";

        setWinTitle(payload);

        // Publish the runnable body for the uid1000 daemon. Written via the
        // Java API so the shell metacharacters ($(), quotes, spaces) are stored
        // verbatim - no round-trip through a shell-parsed title/layer string,
        // which is what used to truncate the payload and kill the daemon loop.
        // NOTE: this needs WRITE_SECURE_SETTINGS. After an uninstall/reinstall
        // the adb grant is gone, putString throws, we swallow it - and the
        // daemon keeps executing the STALE body (old ack number!) from the
        // previous install. So verify the write and surface the failure.
        boolean published = false;
        try {
            Settings.Secure.putString(cr, "gc_tune_cmd", body);
            published = body.equals(Settings.Secure.getString(cr, "gc_tune_cmd"));
        } catch (Throwable ignored) {}
        if (!published) {
            toast("命令未能发布给守护进程：缺少 WRITE_SECURE_SETTINGS");
            logStatus("缺少 WRITE_SECURE_SETTINGS——请执行：adb shell pm grant com.poc.gctune android.permission.WRITE_SECURE_SETTINGS");
        }

        Diag.record("apply", "游戏状态=" + game + " 要求游戏=" + req
                + " 命令已发布=" + published + " 图层名长度=" + payload.length());
        Diag.runStarted(curAck, c, timeoutSec, daemon);

        Entry e = new Entry();
        e.cmd = c;
        e.pending = true;
        addEntry(e);

        busy = true;
        deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        // service owns the layer, the ACK receiver and the wake retries
        ChannelService.start(this, curAck, PKG + "/" + payload, deadline, daemon);
        refreshStatus();
        return true;
    }

    private void cancelRun() {
        ChannelService.stop(this);
        diagSelfCheck = false;
        Diag.record("run", "用户取消，停止等待");
        Diag.runFinished(false, null, RC_UNKNOWN);
        Entry e = lastCommandEntry();
        if (e != null && e.pending) {
            e.pending = false;
            e.failed = true;
            e.out = null;
        }
        busy = false;
        resolveBridge(false, e, "已取消");
        if (themeRun) {
            themeRun = false;
            hideHint();
            themeStatus("已取消", Ui.MUTED);
        }
        if (themeDiagRun) {
            themeDiagRun = false;
            hideHint();
            themeStatus("已取消", Ui.MUTED);
        }
        if (themeExportRun) {
            themeExportRun = false;
            hideHint();
            themeStatus("已取消", Ui.MUTED);
        }
        if (themeRestoreRun) {
            themeRestoreRun = false;
            hideHint();
            themeStatus("已取消", Ui.MUTED);
        }
        if (fontThemeRun) {
            fontThemeRun = false;
            hideFontHint();
            fontStatus("已取消", Ui.MUTED);
        }
        setWinTitle(BENIGN_TITLE);
        renderLog();
        refreshStatus();
    }

    private void finishRun(boolean ok, String blob) {
        busy = false;
        boolean wasSelfCheck = diagSelfCheck;
        diagSelfCheck = false;
        setWinTitle(BENIGN_TITLE);

        Entry e = lastCommandEntry();
        if (e != null) {
            e.pending = false;
            if (ok) {
                e.failed = false;
                splitResult(decodeOutput(blob), e);
            } else {
                e.failed = true;
                e.out = null;
                e.rc = RC_UNKNOWN;
            }
        }
        renderLog();
        Diag.runFinished(ok, e != null ? e.out : null, e != null ? e.rc : RC_UNKNOWN);
        if (themeRun) themeResult(ok, e);
        if (themeDiagRun) themeDiagResult(ok, e);
        if (themeRestoreRun) themeRestoreResult(ok, e);
        if (themeExportRun) themeExportResult(ok, e);
        if (iconsRun) iconsResult(ok, e);
        if (fontThemeRun) fontResult(ok, e);
        if (stRun) settingsThemeResult(ok, e);
        if (miscRun) miscResult(ok, e);
        resolveBridge(ok, e, "uid 1000 无响应");
        if (wasSelfCheck) {
            diagSelfCheckResult(ok, e);
        } else if (!ok) {
            toast("无响应——请打开 Game Cube 面板");
            offerDiagAfterFailure();
        }
        refreshStatus();
    }

    // ---------------------------------------------------------- 诊断与反馈 UI
    /** 通道自检：走一次真实命令，看 uid 1000 到底有没有回应。 */
    private void startDiagSelfCheck() {
        if (busy) { toast("正在执行，请稍候…"); return; }
        Diag.entryPoint("通道自检");
        diagSelfCheck = true;
        if (!apply(DIAG_SELF_CHECK)) {
            diagSelfCheck = false;
            return;
        }
        logStatus("通道自检已发送：等待 uid 1000 响应…");
    }

    private void diagSelfCheckResult(boolean ok, Entry e) {
        String out = e != null && e.out != null ? e.out.trim() : "";
        if (ok) {
            String body = out.isEmpty() ? "（命令已执行，无文本输出）" : out;
            if (body.length() > 1400) body = body.substring(0, 1400) + "…";
            new AlertDialog.Builder(this)
                    .setTitle("通道自检：正常")
                    .setMessage("uid 1000 已正常响应，Game Cube 通道可用。\n\n" + body)
                    .setPositiveButton("导出诊断日志", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { exportDiag(); }
                    })
                    .setNegativeButton("关闭", null)
                    .show();
            return;
        }
        final File f = Diag.save(this);
        refreshDiagNote();
        new AlertDialog.Builder(this)
                .setTitle("通道自检：uid 1000 无响应")
                .setMessage("命令已经发出，但 " + timeoutSec + " 秒内没有收到 uid 1000 的回应，"
                        + "说明 Game Cube 通道没有执行它。\n\n"
                        + (f == null ? "诊断日志生成失败，请检查存储空间。"
                                     : "已生成诊断日志（" + f.getName() + "），可直接发给作者定位原因。"))
                .setPositiveButton("分享日志", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { shareDiag(f); }
                })
                .setNeutralButton("复制文本", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { copyDiag(); }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 设置页「导出诊断日志」：生成报告，然后分享或复制。 */
    private void exportDiag() {
        Diag.entryPoint("设置页");
        final File f = Diag.save(this);
        refreshDiagNote();
        String msg;
        if (f == null) {
            msg = "诊断日志生成失败。";
        } else {
            msg = "已生成：" + f.getName() + "\n" + f.getAbsolutePath()
                    + "\n\nAndroid 10 及以上会同时复制一份到「下载/gctune/diag」，"
                    + "也可以直接在文件管理器里找到。";
        }
        new AlertDialog.Builder(this)
                .setTitle("导出诊断日志")
                .setMessage(msg)
                .setPositiveButton("分享文件", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { shareDiag(f); }
                })
                .setNeutralButton("复制文本", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { copyDiag(); }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 通道失败后自动存一份日志，并提示用户直接发给作者。 */
    private void offerDiagAfterFailure() {
        Diag.entryPoint("命令失败");
        final File f = Diag.save(this);
        refreshDiagNote();
        if (f == null) return;
        logStatus("诊断日志已保存: " + f.getAbsolutePath());
        long now = System.currentTimeMillis();
        if (now - diagDialogAt < 60000L) {
            toast("诊断日志已保存，可在设置页分享给作者");
            return;
        }
        diagDialogAt = now;
        new AlertDialog.Builder(this)
                .setTitle("通道无响应")
                .setMessage("已自动生成诊断日志：机型、权限、通道状态和这次失败的细节都在里面。\n\n"
                        + "把日志发给作者就能定位原因——不需要电脑，也不用截图。")
                .setPositiveButton("发送给作者", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { shareDiag(f); }
                })
                .setNeutralButton("复制文本", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { copyDiag(); }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void shareDiag(File f) {
        if (Diag.share(this, f)) return;
        copyDiag();
        toast("无法打开分享面板，已复制到剪贴板");
    }

    private void copyDiag() {
        Diag.copy(this);
        toast("诊断日志已复制到剪贴板");
    }

    private void refreshDiagNote() {
        if (diagNote == null) return;
        File f = Diag.lastFile();
        diagNote.setText(f == null
                ? "通道无响应时，可在上面导出诊断日志发给作者"
                : "最近一份：" + f.getName() + "\n" + f.getAbsolutePath());
    }

    private void setWinTitle(String t) {
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.setTitle(t);
            getWindow().setAttributes(lp);
        } catch (Throwable e) {
            Log.e("GCTune", "title fail: " + e);
        }
    }

    // ------------------------------------------------------------- state
    private boolean isGame() {
        try {
            return PKG.equals(Settings.System.getString(cr, "current_game_package"));
        } catch (Throwable t) {
            return false;
        }
    }

    private void refreshStatus() {
        boolean game = isGame();
        if (statusDot == null || statusText == null || runBtn == null) return;

        if (busy) {
            statusDot.setImageDrawable(iconDotOn);
            statusText.setText("正在执行…");
            statusText.setTextColor(Ui.ACCENT);
            runBtn.setImageDrawable(iconStop);
            runBtn.setAlpha(1f);
            setDotPulse(true);
            return;
        }
        setDotPulse(false);

        runBtn.setImageDrawable(iconPlay);
        boolean req = prefs.getBoolean("require_game", true);
        boolean enabled = !req || game;
        runBtn.setEnabled(enabled);
        runBtn.setAlpha(enabled ? 1f : 0.35f);

        if (game) {
            statusDot.setImageDrawable(iconDotOn);
            statusText.setText("在游戏列表中");
            statusText.setTextColor(Ui.ACCENT);
        } else {
            statusDot.setImageDrawable(iconDotOff);
            statusText.setText("不在游戏列表中");
            statusText.setTextColor(Ui.MUTED);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
