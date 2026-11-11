package dev.ptraaa.godhand;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.StateSet;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

// Milestone 2b shell: same job as 2a (pick the owner's own God Hand (USA)
// ISO once, start the game, read/share the boot log), hardened after the
// 2a on-device test on the owner's POCO F5 (Android 15 / HyperOS 2,
// targetSdk 35). There, under the legacy framework Material action-bar
// theme, the launcher's first three children (title, status, pick button)
// never became visible even though the shipped dex proves they were built
// and added exactly like the visible ones, and START left no trace
// anywhere because nothing on the Java side wrote anything before the
// native glue ran. So this version:
//   - runs on a NoActionBar theme and gives EVERY view explicit colors
//     (dark background, light text, colored buttons) and generous
//     explicit heights in code - no theme-dependent rendering;
//   - pads the content for the status/navigation bars in code (resource
//     fallback first, exact window insets when they arrive);
//   - appends a timestamped line to godhand-boot.log from the JAVA side
//     the moment START is pressed, and around the GameActivity launch,
//     so every press leaves evidence whatever dies afterwards;
//   - routes START through the ISO picker first when no ISO is stored.
//
// Milestone 3 (0.4-m3): the port boots on the phone but cannot mount the
// disc through the borrowed /proc/self/fd path (0.3-m2c log: "[CDIMAGE]
// cannot open /proc/self/fd/187", port returned 3). So the launcher now
// copies the picked ISO ONCE into the app's internal files dir
// (getFilesDir()/godhand.iso) and the port is handed that real file path
// instead; the picker fd stays only as a fallback when no copy exists.
// The copy is keyed by size: it is reused while godhand.iso has exactly
// the picked document's size (and was copied from the same picked
// document); picking a different ISO replaces it. Copy progress lives on
// the status line; every copy start/finish/failure is written to the
// boot log and shown on the status line - never a crash.
//
// Milestone 8 (0.9-m8): the owner reported "Gabisa digeser" - the page
// could not be scrolled. He was right: every row lived in one plain
// LinearLayout set directly as the content view, so once the Milestone
// 5 settings and remap rows made the content taller than the screen,
// everything below the fold (Share boot log included) was simply
// unreachable. The whole page now sits inside a ScrollView; the boot
// log pane keeps its own small fixed-height scroll area inside the
// page, and Share boot log moved to the top of the page, directly
// under Start, so it is reachable without scrolling at all.
public class LauncherActivity extends Activity {
    static final String PREFS = "godhand_shell";
    static final String KEY_ISO_URI = "iso_uri";
    // Size of the currently picked document (from the provider at pick
    // time; -1 when the provider would not tell us).
    static final String KEY_ISO_SIZE = "iso_size";
    // Uri the completed godhand.iso copy was made from, and its size.
    static final String KEY_COPY_URI = "iso_copy_uri";
    static final String KEY_COPY_SIZE = "iso_copy_size";
    static final String ISO_COPY_NAME = "godhand.iso";
    private static final int REQ_PICK_ISO = 1;

    // Milestone 5: the owner's settings. Written here, read by
    // GameActivity at game start (the glue turns scale/threads/vsync
    // into the port's environment; opacity/size/FPS shape the overlay;
    // the mapping keys live in PadMapping). Defaults: scale 1, FPS on,
    // threads off (Milestone 6: the Milestone 5 governor stays selectable
    // but is no longer the default until it is proven on device), vsync
    // on, opacity high, button size 100%.
    static final String KEY_SET_SCALE = "set_scale";
    static final String KEY_SET_FPS = "set_fps";
    static final String KEY_SET_THREADS = "set_threads";
    static final String KEY_SET_VSYNC = "set_vsync";
    // Milestone 7: disc prefetch (the port's whole-ISO background read).
    // Default off, as it has effectively been inside the app all along
    // via the glue's silent env default; now the owner controls it.
    static final String KEY_SET_PREFETCH = "set_prefetch";
    // Milestone 10: GPU renderer (the port's GSGpuBackend, PS2X_GS_GPU).
    // Default off: the CPU rasteriser is the proven path. The GPU
    // backend's GL context creation is Windows-only in this tree (see
    // M10.md), so ON is an experiment the owner runs for evidence.
    static final String KEY_SET_GPU = "set_gpu";
    // Path B stage B6: widescreen 16:9 (game aspect patch + composed 16:9
    // frame in the path B build). Stage B7: default off (owner opt-in).
    // m41: default ON (Pablo's order - true 16:9 is the app's shipped
    // face). Only the unset-preference fallback changed; a value the
    // owner already saved is read back untouched.
    static final String KEY_SET_WIDE = "set_wide";
    static final String KEY_SET_OPACITY = "set_opacity";
    static final String KEY_SET_BTNSIZE = "set_btnsize";
    // m40: the player's control scheme (TouchOverlay STYLE_A/B/C from
    // the design mockups - all three are built in, the player picks).
    // Default A (Brawler Clean), the designer's recommendation.
    static final String KEY_SET_CONTROL_STYLE = "set_control_style";
    private static final String[] STYLE_VALUES = {"a", "b", "c"};
    private static final String[] STYLE_LABELS = {"Brawler Clean", "Gesture Evade", "Radial Pro"};
    // m40: app mode. Player (the default) is the clean player-facing
    // surface: no boot log card, no remap cards, no technical toggles,
    // no in-game debug text. Developer shows everything from m39.
    static final String KEY_SET_MODE = "set_mode";
    static final String MODE_PLAYER = "player";
    static final String MODE_DEVELOPER = "developer";
    private static final String[] MODE_VALUES = {MODE_PLAYER, MODE_DEVELOPER};
    private static final String[] MODE_LABELS = {"Player", "Developer"};
    private static final String[] SCALE_VALUES = {"0.5", "0.75", "1", "1.5", "2", "3"};
    private static final String[] OPACITY_VALUES = {"high", "medium", "low"};
    private static final String[] SIZE_VALUES = {"80", "90", "100", "110", "125", "140"};

    private static final int COL_BG = Color.rgb(13, 14, 18);
    private static final int COL_ACCENT = Color.rgb(255, 122, 26);
    private static final int COL_ACCENT_DARK = Color.rgb(190, 84, 10);
    private static final int COL_ROW = Color.rgb(38, 40, 48);
    private static final int COL_PANEL = Color.rgb(27, 28, 36);
    private static final int COL_TEXT = Color.rgb(240, 240, 240);
    private static final int COL_STATUS = Color.rgb(255, 213, 79);
    private static final int COL_LOG = Color.rgb(204, 204, 204);
    private static final int COL_PICK = Color.rgb(21, 101, 192);
    private static final int COL_START = Color.rgb(46, 125, 50);
    private static final int COL_PLAIN = Color.rgb(55, 55, 60);

    private TextView status;
    private TextView logView;
    private Button pickButton;
    private Button startButton;
    private Button scaleButton;
    private Button fpsButton;
    private Button threadsButton;
    private Button vsyncButton;
    private Button prefetchButton;
    private Button gpuButton;
    private Button wideButton;
    private Button opacityButton;
    private Button sizeButton;
    private Button modeButton;
    private Button controlStyleButton;
    // m42: the one PERFORMANCE-card line shown only in Player mode,
    // where the technical toggles are hidden - the options they
    // control run automatically there (GameActivity forces them for
    // the run), and this note says so in their place.
    private TextView perfAutoNote;
    // Mode-gated surfaces (hidden in Player mode): the remap cards,
    // the reset-mapping button and the boot log card.
    private LinearLayout touchRemapCard;
    private LinearLayout padRemapCard;
    private Button resetMappingButton;
    private LinearLayout logCard;
    private final Map<String, Button> touchRemapButtons = new HashMap<>();
    private final Map<String, Button> padRemapButtons = new HashMap<>();
    private boolean startAfterPick;

    // ISO copy state (Milestone 3). copying/progress are touched from the
    // copy thread; all UI writes go through runOnUiThread.
    private volatile boolean copying;
    private volatile boolean pendingLaunch;
    private volatile String copyStatusText = "";

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private Button makeButton(String text, int color) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(color);
        normal.setCornerRadius(dp(10));
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(Color.rgb(
                (int) (Color.red(color) * 0.65),
                (int) (Color.green(color) * 0.65),
                (int) (Color.blue(color) * 0.65)));
        pressed.setCornerRadius(dp(10));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_pressed}, pressed);
        background.addState(StateSet.WILD_CARD, normal);
        button.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        params.topMargin = dp(10);
        button.setLayoutParams(params);
        return button;
    }

    // ---- m39 ready-to-play design helpers --------------------------------
    // One design language across the app: near-black background, dark
    // panel cards with rounded corners, one accent (orange), big touch
    // targets. EVERY view keeps explicit colours/sizes in code (the
    // m2a/m2b lesson: no theme-dependent rendering).

    private LinearLayout.LayoutParams rowParams(int heightDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp));
        params.topMargin = dp(8);
        return params;
    }

    private Button rowButton(LinearLayout parent, android.view.View.OnClickListener listener) {
        Button row = makeButton("", COL_ROW);
        row.setTextSize(14);
        row.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), 0, dp(10), 0);
        row.setLayoutParams(rowParams(52));
        row.setOnClickListener(listener);
        parent.addView(row);
        return row;
    }

    private LinearLayout card(LinearLayout root, String titleText) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COL_PANEL);
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        card.setPadding(dp(14), dp(12), dp(14), dp(14));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dp(16);
        card.setLayoutParams(cardParams);
        TextView label = new TextView(this);
        label.setText(titleText);
        label.setTextSize(13);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(COL_ACCENT);
        card.addView(label);
        root.addView(card);
        return card;
    }

    private void updatePlayButton() {
        if (startButton == null)
            return;
        if (copying)
            startButton.setText("Copying ISO - please wait");
        else if (storedIso().isEmpty())
            startButton.setText("Pick an ISO to play");
        else if (copyReady())
            startButton.setText("PLAY");
        else
            startButton.setText("Copy ISO and play");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COL_BG);
        final int pad = dp(16);
        root.setPadding(pad, pad + statusBarHeight(), pad, pad);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(
                    pad + insets.getSystemWindowInsetLeft(),
                    pad + insets.getSystemWindowInsetTop(),
                    pad + insets.getSystemWindowInsetRight(),
                    pad + insets.getSystemWindowInsetBottom());
            return insets;
        });

        // ---- Hero: the app icon (same asset as the launcher icon),
        // name and version - a game header, not a debug tool.
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable heroBg = new GradientDrawable();
        heroBg.setColor(Color.rgb(24, 20, 18));
        heroBg.setCornerRadius(dp(16));
        heroBg.setStroke(dp(1), Color.rgb(92, 44, 10));
        hero.setBackground(heroBg);
        hero.setPadding(dp(16), dp(16), dp(16), dp(16));
        ImageView icon = new ImageView(this);
        int iconRes = getResources().getIdentifier("ic_launcher", "mipmap", getPackageName());
        if (iconRes != 0)
            icon.setImageResource(iconRes);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(72), dp(72));
        iconParams.rightMargin = dp(14);
        icon.setLayoutParams(iconParams);
        hero.addView(icon);
        LinearLayout heroText = new LinearLayout(this);
        heroText.setOrientation(LinearLayout.VERTICAL);
        TextView eyebrow = new TextView(this);
        eyebrow.setText("PTRAAA - RECOMPILED");
        eyebrow.setTextSize(11);
        eyebrow.setTypeface(Typeface.DEFAULT_BOLD);
        eyebrow.setTextColor(COL_ACCENT);
        heroText.addView(eyebrow);
        TextView title = new TextView(this);
        title.setText("GOD HAND");
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(COL_TEXT);
        heroText.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText("Ready to play on your phone");
        subtitle.setTextSize(13);
        subtitle.setTextColor(COL_LOG);
        heroText.addView(subtitle);
        TextView version = new TextView(this);
        version.setText("Version 0.43-m42-playerperf");
        version.setTextSize(11);
        version.setTextColor(COL_ACCENT);
        heroText.addView(version);
        hero.addView(heroText);
        root.addView(hero);

        // ---- Status card: where the ISO stands, in one clean card.
        TextView statusLabel = new TextView(this);
        statusLabel.setText("GAME STATUS");
        statusLabel.setTextSize(12);
        statusLabel.setTypeface(Typeface.DEFAULT_BOLD);
        statusLabel.setTextColor(COL_LOG);
        LinearLayout.LayoutParams statusLabelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusLabelParams.topMargin = dp(18);
        statusLabel.setLayoutParams(statusLabelParams);
        root.addView(statusLabel);
        status = new TextView(this);
        status.setTextSize(14);
        status.setTextColor(COL_STATUS);
        GradientDrawable statusBg = new GradientDrawable();
        statusBg.setColor(COL_PANEL);
        statusBg.setCornerRadius(dp(14));
        statusBg.setStroke(dp(1), Color.rgb(92, 44, 10));
        status.setBackground(statusBg);
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        status.setMinHeight(dp(52));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(8);
        status.setLayoutParams(statusParams);
        root.addView(status);

        // ---- The one big primary action. Its label follows the ISO
        // state (updatePlayButton); the behaviour is the unchanged
        // onStartPressed flow: pick first if needed, copy if needed,
        // then launch.
        startButton = makeButton("PLAY", COL_ACCENT);
        startButton.setTextSize(21);
        startButton.setTypeface(Typeface.DEFAULT_BOLD);
        startButton.setLayoutParams(rowParams(72));
        startButton.setOnClickListener(v -> onStartPressed());
        root.addView(startButton);

        // ---- Secondary: picking the ISO by hand.
        pickButton = makeButton("Pick God Hand (USA) ISO", COL_PLAIN);
        pickButton.setTextSize(15);
        pickButton.setLayoutParams(rowParams(54));
        pickButton.setOnClickListener(v -> {
            if (copying) {
                status.setText("ISO copy in progress - please wait for it to finish.");
                BootLog.append(this, "pick pressed during ISO copy - ignored");
                return;
            }
            startAfterPick = false;
            openPicker();
        });
        root.addView(pickButton);

        // ---- Settings, grouped into cards by subject.
        LinearLayout perfCard = card(root, "PERFORMANCE");
        scaleButton = rowButton(perfCard, v -> {
            SharedPreferences prefs = settingsPrefs();
            String next = nextOf(SCALE_VALUES, prefs.getString(KEY_SET_SCALE, "1"));
            prefs.edit().putString(KEY_SET_SCALE, next).apply();
            refreshSettingsButtons();
        });
        threadsButton = rowButton(perfCard, v -> {
            SharedPreferences prefs = settingsPrefs();
            prefs.edit().putBoolean(KEY_SET_THREADS, !prefs.getBoolean(KEY_SET_THREADS, false)).apply();
            refreshSettingsButtons();
        });
        prefetchButton = rowButton(perfCard, v -> {
            SharedPreferences prefs = settingsPrefs();
            prefs.edit().putBoolean(KEY_SET_PREFETCH, !prefs.getBoolean(KEY_SET_PREFETCH, false)).apply();
            refreshSettingsButtons();
        });
        gpuButton = rowButton(perfCard, v -> {
            SharedPreferences prefs = settingsPrefs();
            prefs.edit().putBoolean(KEY_SET_GPU, !prefs.getBoolean(KEY_SET_GPU, false)).apply();
            refreshSettingsButtons();
        });
        // m42: shown only in Player mode (applyModeVisibility), in the
        // space the hidden toggles leave: one quiet line, same style
        // as the CONTROLS card's hint, so the player knows performance
        // is handled without a single technical switch on screen.
        perfAutoNote = new TextView(this);
        perfAutoNote.setText("Performance options run automatically in Player mode.");
        perfAutoNote.setTextSize(13);
        perfAutoNote.setTextColor(COL_LOG);
        LinearLayout.LayoutParams perfNoteParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        perfNoteParams.topMargin = dp(8);
        perfAutoNote.setLayoutParams(perfNoteParams);
        perfCard.addView(perfAutoNote);

        LinearLayout displayCard = card(root, "DISPLAY AND BUTTONS");
        fpsButton = rowButton(displayCard, v -> {
            SharedPreferences prefs = settingsPrefs();
            prefs.edit().putBoolean(KEY_SET_FPS, !prefs.getBoolean(KEY_SET_FPS, true)).apply();
            refreshSettingsButtons();
        });
        vsyncButton = rowButton(displayCard, v -> {
            SharedPreferences prefs = settingsPrefs();
            prefs.edit().putBoolean(KEY_SET_VSYNC, !prefs.getBoolean(KEY_SET_VSYNC, true)).apply();
            refreshSettingsButtons();
        });
        wideButton = rowButton(displayCard, v -> {
            SharedPreferences prefs = settingsPrefs();
            prefs.edit().putBoolean(KEY_SET_WIDE, !prefs.getBoolean(KEY_SET_WIDE, true)).apply();
            refreshSettingsButtons();
        });
        opacityButton = rowButton(displayCard, v -> {
            SharedPreferences prefs = settingsPrefs();
            String next = nextOf(OPACITY_VALUES, prefs.getString(KEY_SET_OPACITY, "high"));
            prefs.edit().putString(KEY_SET_OPACITY, next).apply();
            refreshSettingsButtons();
        });
        sizeButton = rowButton(displayCard, v -> {
            SharedPreferences prefs = settingsPrefs();
            String next = nextOf(SIZE_VALUES, prefs.getString(KEY_SET_BTNSIZE, "100"));
            prefs.edit().putString(KEY_SET_BTNSIZE, next).apply();
            refreshSettingsButtons();
        });

        LinearLayout controlsCard = card(root, "CONTROLS");
        // Mode first: it gates what else this page shows, and it must
        // stay reachable in BOTH modes (it lives here, in a card that
        // Player mode keeps).
        modeButton = rowButton(controlsCard, v -> {
            SharedPreferences prefs = settingsPrefs();
            String next = nextOf(MODE_VALUES, prefs.getString(KEY_SET_MODE, MODE_PLAYER));
            prefs.edit().putString(KEY_SET_MODE, next).apply();
            refreshSettingsButtons();
            applyModeVisibility();
        });
        // The control style is a player feature: visible in both modes,
        // takes effect the next time the game starts.
        controlStyleButton = rowButton(controlsCard, v -> {
            SharedPreferences prefs = settingsPrefs();
            String next = nextOf(STYLE_VALUES, prefs.getString(KEY_SET_CONTROL_STYLE, "a"));
            prefs.edit().putString(KEY_SET_CONTROL_STYLE, next).apply();
            refreshSettingsButtons();
        });
        Button resetLayoutButton = makeButton("Reset button layout", COL_ROW);
        resetLayoutButton.setTextSize(14);
        resetLayoutButton.setLayoutParams(rowParams(52));
        resetLayoutButton.setOnClickListener(v -> {
            SharedPreferences.Editor editor = settingsPrefs().edit();
            // All schemes' controls (and the retired m39 ones) live in
            // TouchOverlay's list, so a reset covers every style.
            for (String id : TouchOverlay.ALL_LAYOUT_IDS) {
                editor.remove("lay_" + id + "_x");
                editor.remove("lay_" + id + "_y");
            }
            editor.apply();
            status.setText("Button layout reset - every control style will use its default positions. "
                    + "In the game, the EDIT button (top centre) moves the controls.");
        });
        controlsCard.addView(resetLayoutButton);
        TextView editHint = new TextView(this);
        editHint.setText("Control style applies the next time the game starts. "
                + "Move the controls: in the game, tap EDIT at the top, drag them where you want them, tap DONE. "
                + "Each style remembers its own positions.");
        editHint.setTextSize(13);
        editHint.setTextColor(COL_LOG);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(8);
        editHint.setLayoutParams(hintParams);
        controlsCard.addView(editHint);
        refreshSettingsButtons();

        // ---- Remap cards, below settings in visual priority.
        // Developer-mode surfaces (applyModeVisibility hides them in
        // Player mode): the raw remap table is a technical surface.
        LinearLayout touchCard = card(root, "REMAP TOUCH BUTTONS");
        touchRemapCard = touchCard;
        for (String control : PadMapping.TOUCH_CONTROLS) {
            final String id = control;
            Button row = rowButton(touchCard, v -> {
                SharedPreferences prefs = settingsPrefs();
                String next = PadMapping.nextTarget(PadMapping.touchTarget(prefs, id));
                prefs.edit().putString(PadMapping.prefKeyTouch(id), next).apply();
                refreshRemapButtons();
            });
            row.setTextSize(13);
            touchRemapButtons.put(id, row);
        }
        LinearLayout padCard = card(root, "REMAP GAMEPAD BUTTONS");
        padRemapCard = padCard;
        for (String key : PadMapping.PAD_KEYS) {
            final String id = key;
            Button row = rowButton(padCard, v -> {
                SharedPreferences prefs = settingsPrefs();
                String next = PadMapping.nextTarget(PadMapping.padTarget(prefs, id));
                prefs.edit().putString(PadMapping.prefKeyPad(id), next).apply();
                refreshRemapButtons();
            });
            row.setTextSize(13);
            padRemapButtons.put(id, row);
        }
        resetMappingButton = makeButton("Reset mapping", COL_PLAIN);
        resetMappingButton.setTextSize(14);
        resetMappingButton.setLayoutParams(rowParams(50));
        resetMappingButton.setOnClickListener(v -> {
            SharedPreferences.Editor editor = settingsPrefs().edit();
            for (String control : PadMapping.TOUCH_CONTROLS)
                editor.remove(PadMapping.prefKeyTouch(control));
            for (String key : PadMapping.PAD_KEYS)
                editor.remove(PadMapping.prefKeyPad(key));
            editor.apply();
            refreshRemapButtons();
            status.setText("Mapping reset to the standard layout.");
        });
        root.addView(resetMappingButton);
        refreshRemapButtons();

        // ---- Boot log: a quiet developer detail at the very bottom,
        // with Share / Reload as small secondary buttons. Developer
        // mode only - in Player mode the whole card is gone.
        logCard = card(root, "BOOT LOG - DEVELOPER DETAIL");
        LinearLayout logActions = new LinearLayout(this);
        logActions.setOrientation(LinearLayout.HORIZONTAL);
        Button share = makeButton("Share boot log", COL_ROW);
        share.setTextSize(13);
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(
                0, dp(46), 1f);
        shareParams.rightMargin = dp(8);
        share.setLayoutParams(shareParams);
        share.setOnClickListener(v -> shareLog());
        logActions.addView(share);
        Button refresh = makeButton("Reload boot log", COL_ROW);
        refresh.setTextSize(13);
        refresh.setLayoutParams(new LinearLayout.LayoutParams(0, dp(46), 1f));
        refresh.setOnClickListener(v -> refreshUi());
        logActions.addView(refresh);
        logCard.addView(logActions);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(11);
        logView.setTextColor(COL_LOG);
        logView.setPadding(dp(8), dp(8), dp(8), dp(8));
        scroll.addView(logView);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220));
        scrollParams.topMargin = dp(10);
        logCard.addView(scroll, scrollParams);

        // The whole page scrolls (Milestone 8).
        ScrollView page = new ScrollView(this);
        page.setBackgroundColor(COL_BG);
        page.setFillViewport(true);
        page.setClipToPadding(false);
        root.setLayoutParams(new ScrollView.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        page.addView(root);
        setContentView(page);
        root.requestApplyInsets();
        applyModeVisibility();
        BootLog.append(this, "launcher opened (0.43-m42-playerperf), mode="
                + settingsPrefs().getString(KEY_SET_MODE, MODE_PLAYER)
                + ", control style=" + settingsPrefs().getString(KEY_SET_CONTROL_STYLE, "a"));
        refreshUi();
    }

    // Player / Developer gating. Player mode is the clean game face:
    // the boot log card, both remap cards (and their reset), and the
    // technical toggles (thread placement, disc prefetch, GPU renderer,
    // FPS counter) are GONE - not disabled, gone. Developer mode shows
    // the full m39 surface. m42 adds one inverse surface: the
    // PERFORMANCE card's "runs automatically" note is visible only in
    // Player mode, where GameActivity forces those options on for the
    // run (the stored toggles themselves are never touched). Everything else (hero, status, PLAY, ISO
    // picker, resolution, VSync, widescreen, button opacity/size, the
    // CONTROLS card with Mode + Control Style + layout reset) stays in
    // both modes. Reading the one preference and re-applying visibility
    // is all a mode switch does, so it re-renders instantly and cannot
    // disturb any other setting.
    private void applyModeVisibility() {
        boolean developer = MODE_DEVELOPER.equals(
                settingsPrefs().getString(KEY_SET_MODE, MODE_PLAYER));
        int visibility = developer ? android.view.View.VISIBLE : android.view.View.GONE;
        threadsButton.setVisibility(visibility);
        prefetchButton.setVisibility(visibility);
        gpuButton.setVisibility(visibility);
        fpsButton.setVisibility(visibility);
        // m42: the automatic-performance note is the inverse surface -
        // it stands in for the hidden toggles in Player mode and is
        // gone in Developer mode, where the toggles speak for
        // themselves.
        if (perfAutoNote != null)
            perfAutoNote.setVisibility(developer ? android.view.View.GONE : android.view.View.VISIBLE);
        if (touchRemapCard != null)
            touchRemapCard.setVisibility(visibility);
        if (padRemapCard != null)
            padRemapCard.setVisibility(visibility);
        if (resetMappingButton != null)
            resetMappingButton.setVisibility(visibility);
        if (logCard != null)
            logCard.setVisibility(visibility);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    private String storedIso() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ISO_URI, "");
    }

    private void openPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_PICK_ISO);
            BootLog.append(this, "iso picker opened");
        } catch (Throwable t) {
            BootLog.append(this, "iso picker FAILED to open: " + BootLog.stack(t));
            status.setText("Could not open the ISO picker: " + t.getMessage());
            refreshLogView();
        }
    }

    private void onStartPressed() {
        boolean isoSet = !storedIso().isEmpty();
        // The evidence line: written before anything else can fail.
        BootLog.append(this, "start pressed, iso=" + (isoSet ? "set" : "unset"));
        if (!isoSet) {
            startAfterPick = true;
            status.setText("No ISO picked yet - pick it now and the game starts by itself.");
            openPicker();
            refreshLogView();
            return;
        }
        if (copying) {
            // A copy is already running for this ISO; just make sure the
            // game follows it.
            pendingLaunch = true;
            status.setText(copyStatusText);
            BootLog.append(this, "start pressed during ISO copy - game will start when the copy finishes");
            refreshLogView();
            return;
        }
        // Milestone 3: the port needs the real file copy, not the picker
        // fd. Copy first when there is no up-to-date copy, then start.
        ensureCopy(true);
    }

    private void launchGame() {
        try {
            startActivity(new Intent(this, GameActivity.class));
            BootLog.append(this, "GameActivity launch requested");
        } catch (Throwable t) {
            BootLog.append(this, "GameActivity launch FAILED: " + BootLog.stack(t));
            status.setText("Could not start the game: " + t.getMessage());
        }
        refreshLogView();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_ISO) {
            Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
            if (uri != null) {
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception e) {
                    BootLog.append(this, "takePersistableUriPermission failed: " + e.getMessage());
                }
                long size = queryDocumentSize(uri);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_ISO_URI, uri.toString())
                        .putLong(KEY_ISO_SIZE, size)
                        .apply();
                BootLog.append(this, "iso picked and stored (document size "
                        + (size > 0 ? size + " bytes" : "unknown") + ")");
                // Milestone 3: copy on pick, so the copy is already there
                // when he later taps Start; if he came here FROM Start,
                // the game follows the copy by itself.
                boolean launch = startAfterPick;
                startAfterPick = false;
                ensureCopy(launch);
            } else {
                BootLog.append(this, "iso pick cancelled or returned nothing");
                startAfterPick = false;
            }
        }
        refreshUi();
    }

    // ------------------------------------------------------------------
    // ISO copy (Milestone 3)
    // ------------------------------------------------------------------

    File isoCopyFile() {
        return new File(getFilesDir(), ISO_COPY_NAME);
    }

    // The picked document's size, asked from the provider: the document
    // column first, the descriptor's stat size as fallback. -1 = the
    // provider would not say; the copy logic then trusts uri+length of a
    // completed copy instead.
    private long queryDocumentSize(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.SIZE}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !cursor.isNull(idx)) {
                    long size = cursor.getLong(idx);
                    if (size > 0) return size;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        ParcelFileDescriptor pfd = null;
        try {
            pfd = getContentResolver().openFileDescriptor(uri, "r");
            if (pfd != null) {
                long size = pfd.getStatSize();
                if (size > 0) return size;
            }
        } catch (Throwable ignored) {
        } finally {
            if (pfd != null) {
                try { pfd.close(); } catch (Throwable ignored) { }
            }
        }
        return -1;
    }

    // Reuse rule: a completed copy of the SAME picked document whose
    // length still matches the copy record (and the provider-reported
    // document size, when known) is used as-is. Anything else - no copy,
    // a different document picked, a size drift - means (re)copy.
    private boolean copyReady() {
        File dest = isoCopyFile();
        if (!dest.isFile() || dest.length() == 0) return false;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String uri = prefs.getString(KEY_ISO_URI, "");
        if (uri.isEmpty()) return false;
        if (!uri.equals(prefs.getString(KEY_COPY_URI, ""))) return false;
        if (dest.length() != prefs.getLong(KEY_COPY_SIZE, -1)) return false;
        long docSize = prefs.getLong(KEY_ISO_SIZE, -1);
        return docSize <= 0 || dest.length() == docSize;
    }

    private static String mb(long bytes) {
        return Long.toString(Math.round(bytes / 1000000.0));
    }

    // Ensures godhand.iso matches the picked document, copying on a
    // background thread with status-line progress when it does not.
    // launchAfter (or a pending Start) starts the game on success.
    private void ensureCopy(final boolean launchAfter) {
        if (copyReady()) {
            BootLog.append(this, "iso copy up to date (" + isoCopyFile().length()
                    + " bytes) - no copy needed");
            if (launchAfter) launchGame();
            return;
        }
        if (copying) {
            pendingLaunch = pendingLaunch || launchAfter;
            return;
        }
        final String uriText = storedIso();
        if (uriText.isEmpty()) return;
        final Uri uri = Uri.parse(uriText);
        final long expected = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getLong(KEY_ISO_SIZE, -1);
        final File dest = isoCopyFile();
        final File tmp = new File(getFilesDir(), ISO_COPY_NAME + ".tmp");

        // Fail early and loudly rather than half-way through 1.5 GB.
        if (expected > 0) {
            long usable = getFilesDir().getUsableSpace();
            if (usable > 0 && usable < expected + 32L * 1024 * 1024) {
                String msg = "Not enough free space to copy the ISO into the app: need about "
                        + mb(expected) + " MB, only " + mb(usable) + " MB free.";
                BootLog.append(this, "iso copy FAILED before starting: " + msg);
                status.setText(msg);
                refreshLogView();
                return;
            }
        }

        copying = true;
        pendingLaunch = launchAfter;
        copyStatusText = "Copying the ISO into the app (one time): 0"
                + (expected > 0 ? " of " + mb(expected) : "") + " MB. Leave the app open.";
        status.setText(copyStatusText);
        pickButton.setEnabled(false);
        startButton.setEnabled(false);
        BootLog.append(this, "iso copy started: " + uriText
                + (expected > 0 ? " (expected " + expected + " bytes)" : " (document size unknown)"));
        refreshLogView();

        new Thread(() -> {
            long copied = 0;
            String failure = null;
            try {
                if (tmp.exists() && !tmp.delete()) {
                    throw new java.io.IOException("could not clear the old partial copy");
                }
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) throw new java.io.IOException("the provider returned no stream");
                FileOutputStream out = new FileOutputStream(tmp);
                try {
                    byte[] buffer = new byte[1024 * 1024];
                    long lastShown = 0;
                    int n;
                    while ((n = in.read(buffer)) >= 0) {
                        if (n == 0) continue;
                        out.write(buffer, 0, n);
                        copied += n;
                        if (copied - lastShown >= 16L * 1024 * 1024) {
                            lastShown = copied;
                            final long shownCopied = copied;
                            final String text = "Copying the ISO into the app (one time): "
                                    + mb(shownCopied)
                                    + (expected > 0
                                            ? " of " + mb(expected) + " MB (" + (100 * shownCopied / expected) + "%)"
                                            : " MB")
                                    + ". Leave the app open.";
                            runOnUiThread(() -> {
                                copyStatusText = text;
                                status.setText(text);
                            });
                        }
                    }
                } finally {
                    try { out.close(); } catch (Throwable ignored) { }
                    try { in.close(); } catch (Throwable ignored) { }
                }
                if (expected > 0 && copied != expected) {
                    throw new java.io.IOException("copied " + copied + " bytes but the document is "
                            + expected + " bytes");
                }
                if (dest.exists() && !dest.delete()) {
                    throw new java.io.IOException("could not replace the old ISO copy");
                }
                if (!tmp.renameTo(dest)) {
                    throw new java.io.IOException("could not move the finished copy into place");
                }
                final long doneBytes = copied;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_COPY_URI, uriText)
                        .putLong(KEY_COPY_SIZE, doneBytes)
                        .apply();
            } catch (Throwable t) {
                failure = t.getClass().getSimpleName() + ": " + t.getMessage();
                try { if (tmp.exists()) tmp.delete(); } catch (Throwable ignored) { }
            }
            final String failText = failure;
            final long totalCopied = copied;
            runOnUiThread(() -> {
                copying = false;
                pickButton.setEnabled(true);
                startButton.setEnabled(true);
                boolean launch = pendingLaunch;
                pendingLaunch = false;
                if (failText == null) {
                    BootLog.append(LauncherActivity.this, "iso copy done: godhand.iso is "
                            + totalCopied + " bytes - the port now gets the real file");
                    if (launch) {
                        status.setText("ISO copy done (" + mb(totalCopied) + " MB). Starting the game...");
                        launchGame();
                    } else {
                        status.setText("ISO copy done (" + mb(totalCopied)
                                + " MB). Tap PLAY to start the game.");
                    }
                } else {
                    BootLog.append(LauncherActivity.this, "iso copy FAILED after "
                            + totalCopied + " bytes: " + failText);
                    status.setText("ISO copy failed: " + failText + " Tap PLAY to try again.");
                }
                refreshLogView();
            });
        }, "iso-copy").start();
    }

    // ------------------------------------------------------------------
    // Settings + remap rows (Milestone 5)
    // ------------------------------------------------------------------

    private SharedPreferences settingsPrefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private TextView sectionTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(COL_TEXT);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(20);
        title.setLayoutParams(params);
        return title;
    }

    private Button addRow(LinearLayout root, android.view.View.OnClickListener listener) {
        Button row = makeButton("", COL_PLAIN);
        row.setOnClickListener(listener);
        root.addView(row);
        return row;
    }

    private static String nextOf(String[] values, String current) {
        for (int i = 0; i < values.length; i++)
            if (values[i].equals(current))
                return values[(i + 1) % values.length];
        return values[0];
    }

    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }

    private void refreshSettingsButtons() {
        SharedPreferences prefs = settingsPrefs();
        scaleButton.setText("Internal resolution: " + prefs.getString(KEY_SET_SCALE, "1") + "x");
        fpsButton.setText("FPS counter: " + onOff(prefs.getBoolean(KEY_SET_FPS, true)));
        threadsButton.setText("Thread placement (performance cores): "
                + onOff(prefs.getBoolean(KEY_SET_THREADS, false)));
        vsyncButton.setText("VSync: " + onOff(prefs.getBoolean(KEY_SET_VSYNC, true)));
        prefetchButton.setText("Disc prefetch (experimental): "
                + onOff(prefs.getBoolean(KEY_SET_PREFETCH, false)));
        gpuButton.setText("GPU renderer (experimental): "
                + onOff(prefs.getBoolean(KEY_SET_GPU, false)));
        wideButton.setText("Widescreen 16:9: "
                + onOff(prefs.getBoolean(KEY_SET_WIDE, true)));
        opacityButton.setText("Button opacity: " + prefs.getString(KEY_SET_OPACITY, "high"));
        sizeButton.setText("Button size: " + prefs.getString(KEY_SET_BTNSIZE, "100") + "%");
        if (modeButton != null)
            modeButton.setText("Mode: " + labelOf(MODE_VALUES, MODE_LABELS,
                    prefs.getString(KEY_SET_MODE, MODE_PLAYER)));
        if (controlStyleButton != null)
            controlStyleButton.setText("Control style: " + labelOf(STYLE_VALUES, STYLE_LABELS,
                    prefs.getString(KEY_SET_CONTROL_STYLE, "a")));
    }

    private static String labelOf(String[] values, String[] labels, String current) {
        for (int i = 0; i < values.length; i++)
            if (values[i].equals(current))
                return labels[i];
        return labels[0];
    }

    private void refreshRemapButtons() {
        SharedPreferences prefs = settingsPrefs();
        for (Map.Entry<String, Button> entry : touchRemapButtons.entrySet()) {
            String target = PadMapping.touchTarget(prefs, entry.getKey());
            entry.getValue().setText("Touch " + PadMapping.touchLabel(entry.getKey())
                    + " sends: " + PadMapping.targetLabel(target));
        }
        for (Map.Entry<String, Button> entry : padRemapButtons.entrySet()) {
            String target = PadMapping.padTarget(prefs, entry.getKey());
            entry.getValue().setText("Gamepad " + entry.getKey()
                    + " sends: " + PadMapping.targetLabel(target));
        }
    }

    // ------------------------------------------------------------------

    private String readLog() {
        File file = BootLog.file(this);
        if (!file.exists())
            return "(no boot log yet - start the game first)";
        try {
            FileInputStream in = new FileInputStream(file);
            byte[] bytes = new byte[(int) Math.min(file.length(), 512 * 1024)];
            int read = 0;
            while (read < bytes.length) {
                int n = in.read(bytes, read, bytes.length - read);
                if (n < 0) break;
                read += n;
            }
            in.close();
            return new String(bytes, 0, read, "UTF-8");
        } catch (Exception e) {
            return "(could not read the boot log: " + e.getMessage() + ")";
        }
    }

    private void refreshLogView() {
        logView.setText(readLog());
        updatePlayButton();
    }

    private void refreshUi() {
        if (copying) {
            // The copy thread owns the status line while it runs.
            status.setText(copyStatusText);
            refreshLogView();
            return;
        }
        String uri = storedIso();
        if (uri.isEmpty()) {
            status.setText("No ISO yet. Tap PLAY and you will be asked to pick it first.");
        } else if (copyReady()) {
            status.setText("Ready to play. Your ISO is inside the app.");
        } else {
            status.setText("ISO picked. Tap PLAY: the ISO is copied into the app first (one time), then the game starts.");
        }
        refreshLogView();
    }

    private void shareLog() {
        String text = readLog();
        if (text.length() > 60000)
            text = "...(log truncated for sharing)\n" + text.substring(text.length() - 60000);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "godhand-boot.log");
        intent.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(intent, "Share boot log"));
    }
}
