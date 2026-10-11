package com.bgmi;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatButton;
import androidx.cardview.widget.CardView;
import androidx.core.content.FileProvider;

import net_62v.external.MetaActivationManager;
import com.mundo.MundoCore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.FileChannel;

import com.bgmi.logger.ArkCrashLogger;
import com.bgmi.utils.Downtwo;
import com.bgmi.utils.IntegrityGuard;
import com.bgmi.utils.PanelCrypto;
import com.bgmi.utils.Prefs;

@SuppressWarnings({"unused", "deprecation"})
public class LoginActivity extends BaseActivity {

    static {
        try { System.loadLibrary("shaitaan"); }
        catch (UnsatisfiedLinkError ignored) {}
    }

    private static final String USER      = "USER";
    private static final String PKG_FB   = "com.facebook.katana";
    private static final String TAG_FB   = "ARK_FB";
    private static final String PKG_GMS  = "com.google.android.gms";
    private static final String PKG_PLAY = "com.android.vending";
    private static final String TAG_GMS  = "ARK_GMS";

    private CardView fbBtn;
    private TextView fbBtnText;
    private volatile boolean fbBusy = false;
    private long lastFbLaunchMs = 0L;

    private CardView gmsBtn;
    private TextView gmsBtnText;
    private volatile boolean gmsBusy = false;
    private long lastGmsLaunchMs = 0L;

    private Prefs prefs;
    private EditText etKey;
    private CardView btnLogin;
    private View btnPaste;
    private ImageButton eyeToggle;
    private boolean passwordVisible = false;

    // Once set, login is blocked until server confirms update not needed
    private volatile boolean updateBlocked = false;

    private LinearLayout processingCard;
    private ProgressBar progressBar, downloadProgressBar;
    private TextView loadingText, progressText, percentText, downloadSizeText, speedText;
    private View speedRow;
    private AppCompatButton okButton;

    private long dlStartMs = 0;

    private static final int RC_STORAGE  = 100;
    private static final int RC_INSTALL  = 200;
    private static final int RC_OVERLAY  = 300;
    private static final String KEY_PERMS_DONE = "ark_perms_done";

    // All URLs from native — no hardcoded panel endpoints
    private static native String nativeApkKillUrl();
    private static native String nativeApkDownloadUrl();
    private static native boolean nativeVerifyLoginToken(String token);
    private static native String nativeGetSerial();
    private static native String Check(android.content.Context ctx, String key);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF080400);
        getWindow().setNavigationBarColor(0xFF080400);
        setContentView(buildLoginLayout());

        IntegrityGuard.verifyOrDie(this);

        prefs = Prefs.with(this);
        // views already bound in buildLoginLayout()
        setupListeners();
        etKey.setText(prefs.read(USER, ""));
        checkPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (updateBlocked) {
            showProcessing("Checking...");
            checkApkUpdate();
        }
    }

    @Override
    public void onBackPressed() {
        if (updateBlocked) return;
        super.onBackPressed();
    }

    // ── Permission chain ────────────────────────────────────────────────────
    private void checkPermissions() {
        if (!hasStorage()) { askStorage(); return; }
        if (!hasInstall()) { askInstall(); return; }
        if (!hasOverlay()) { askOverlay(); return; }
        onPermissionsReady();
    }

    private boolean hasStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            return Environment.isExternalStorageManager();
        return androidx.core.content.ContextCompat.checkSelfPermission(this,
            android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
    private boolean hasInstall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            return getPackageManager().canRequestPackageInstalls();
        return true;
    }
    private boolean hasOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            return Settings.canDrawOverlays(this);
        return true;
    }

    private void askStorage() {
        showSheet("Storage Permission", "ArkCheat needs storage access to copy game files.",
            "Grant", () -> {
                dismissSheet();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    startActivityForResult(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())), RC_STORAGE);
                } else {
                    androidx.core.app.ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                     android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, RC_STORAGE);
                }
            },
            "Exit", () -> { dismissSheet(); finish(); System.exit(0); }
        );
    }

    private void askInstall() {
        showSheet("Install Unknown Apps",
            "ArkCheat needs permission to install game components. Please allow Install from Unknown Sources.",
            "Grant", () -> {
                dismissSheet();
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())), RC_INSTALL);
            },
            "Exit", () -> { dismissSheet(); finish(); System.exit(0); }
        );
    }

    private void askOverlay() {
        showSheet("Display Over Apps",
            "ArkCheat needs to display over other apps to show the cheat menu while in-game.",
            "Grant", () -> {
                dismissSheet();
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())), RC_OVERLAY);
            },
            "Exit", () -> { dismissSheet(); finish(); System.exit(0); }
        );
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == RC_STORAGE) {
            if (!hasInstall()) { askInstall(); return; }
            if (!hasOverlay()) { askOverlay(); return; }
            onPermissionsReady();
        } else if (req == RC_INSTALL) {
            if (!hasOverlay()) { askOverlay(); return; }
            onPermissionsReady();
        } else if (req == RC_OVERLAY) {
            if (!prefs.read(KEY_PERMS_DONE, "").equals("1")) {
                prefs.write(KEY_PERMS_DONE, "1");
                showSheet("Restart Required",
                    "Please restart ArkCheat once so it can initialize properly.",
                    "Restart", () -> {
                        dismissSheet();
                        android.os.Process.killProcess(android.os.Process.myPid());
                        System.exit(0);
                    },
                    "Skip", () -> { dismissSheet(); onPermissionsReady(); }
                );
            } else {
                onPermissionsReady();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == RC_STORAGE) {
            if (!hasInstall()) { askInstall(); return; }
            if (!hasOverlay()) { askOverlay(); return; }
            onPermissionsReady();
        }
    }

    private void onPermissionsReady() {
        checkApkUpdate();
    }

    // ── APK Update / Kill Check ─────────────────────────────────────────────
    // Panel returns XOR-encoded JSON. kill=1 → process kill. force_update=1 → block login.
    // Network error → retry dialog (updateBlocked stays as-is, not cleared).
    private void checkApkUpdate() {
        new Thread(() -> {
            try {
                String panelUrl = nativeApkKillUrl();
                HttpURLConnection c = (HttpURLConnection)
                    new URL(panelUrl + "?t=" + System.currentTimeMillis()).openConnection();
                c.setConnectTimeout(8000); c.setReadTimeout(8000);
                c.setRequestProperty("Cache-Control", "no-cache");
                c.connect();
                if (c.getResponseCode() != 200) {
                    runOnUiThread(this::showRetryDialog); return;
                }
                java.util.Scanner sc = new java.util.Scanner(c.getInputStream(), "UTF-8");
                String encoded = sc.hasNextLine() ? sc.nextLine().trim() : "";
                sc.close(); c.disconnect();

                if (encoded.isEmpty()) { runOnUiThread(this::showLoginUI); return; }

                String json = PanelCrypto.xorDecode(encoded);
                org.json.JSONObject obj = new org.json.JSONObject(json);

                if (obj.optInt("kill", 0) == 1) {
                    final String msg = obj.optString("msg", "App disabled by developer.");
                    runOnUiThread(() -> showMandatoryUpdateSheet(
                        "Service Unavailable", msg,
                        "Exit", () -> {
                            android.os.Process.killProcess(android.os.Process.myPid());
                            System.exit(0);
                        }
                    ));
                    return;
                }

                if (obj.optInt("force_update", 0) == 1) {
                    updateBlocked = true;
                    String ver = obj.optString("version", "");
                    runOnUiThread(() -> showUpdateDialog(ver));
                } else {
                    runOnUiThread(this::showLoginUI);
                }

            } catch (Exception e) {
                runOnUiThread(this::showRetryDialog);
            }
        }).start();
    }

    private void showRetryDialog() {
        showSheet("Connection Error", "Could not reach server. Check internet and retry.",
            "Retry", () -> { dismissSheet(); showProcessing("Checking..."); checkApkUpdate(); },
            "Exit", () -> { dismissSheet(); finish(); System.exit(0); }
        );
    }

    private void showUpdateDialog(String newVersion) {
        if (etKey != null) etKey.setEnabled(false);
        if (btnLogin != null) btnLogin.setEnabled(false);
        String body = newVersion.isEmpty()
            ? "A new version is required.\nYou must update to keep using ArkCheat."
            : "Version " + newVersion + " is required.\nYou must update to keep using ArkCheat.";
        showMandatoryUpdateSheet("Update Required", body,
            "Update Now", () -> { dismissSheet(); downloadAndInstallApk(); }
        );
    }

    @SuppressLint("SetTextI18n")
    private void downloadAndInstallApk() {
        runOnUiThread(() -> {
            if (processingCard != null) processingCard.setVisibility(View.VISIBLE);
            if (loadingText != null)   loadingText.setText("DOWNLOADING UPDATE");
            if (progressText != null)  progressText.setText("Please wait...");
            if (progressBar != null)   progressBar.setVisibility(View.GONE);
            if (downloadProgressBar != null) {
                downloadProgressBar.setVisibility(View.VISIBLE);
                downloadProgressBar.setProgress(0);
            }
            if (speedRow != null) speedRow.setVisibility(View.VISIBLE);
            if (okButton != null) okButton.setVisibility(View.GONE);
        });

        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection)
                    new URL(nativeApkDownloadUrl()).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(15000); conn.setReadTimeout(120000);
                conn.connect();

                long total = conn.getContentLengthLong();
                InputStream in = conn.getInputStream();
                File apk = new File(getCacheDir(), "ArkCheat_update.apk");
                FileOutputStream out = new FileOutputStream(apk);
                byte[] buf = new byte[32768]; long done = 0; int n;
                dlStartMs = System.currentTimeMillis();
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n); done += n;
                    final long dF = done, tF = total;
                    final int pct = total > 0 ? (int)((done * 100L) / total) : 0;
                    runOnUiThread(() -> updateDownloadUI(pct, dF, tF));
                }
                out.flush(); out.close(); in.close(); conn.disconnect();
                final File apkFinal = apk;
                runOnUiThread(() -> installApk(apkFinal));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (processingCard != null) processingCard.setVisibility(View.GONE);
                    Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    showRetryDialog();
                });
            }
        }).start();
    }

    @SuppressLint("SetTextI18n")
    private void updateDownloadUI(int pct, long done, long total) {
        if (progressBar != null)    progressBar.setVisibility(View.GONE);
        if (progressText != null)   progressText.setText("Downloading update...");
        if (downloadProgressBar != null) {
            downloadProgressBar.setVisibility(View.VISIBLE);
            downloadProgressBar.setProgress(pct);
        }
        if (percentText != null) { percentText.setVisibility(View.VISIBLE); percentText.setText(pct + "%"); }
        if (downloadSizeText != null) {
            downloadSizeText.setVisibility(View.VISIBLE);
            downloadSizeText.setText(total > 0 ? fmt(done) + " / " + fmt(total) : fmt(done));
        }
        if (speedText != null && dlStartMs > 0) {
            long elapsed = System.currentTimeMillis() - dlStartMs;
            if (elapsed > 300) {
                if (speedRow != null) speedRow.setVisibility(View.VISIBLE);
                speedText.setVisibility(View.VISIBLE);
                speedText.setText(fmt((long)(done / (elapsed / 1000.0))) + "/s");
            }
        }
    }

    private void installApk(File apk) {
        if (processingCard != null) processingCard.setVisibility(View.GONE);
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            // updateBlocked stays true — onResume re-checks server
        } catch (Exception e) {
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        }
    }

    private static String fmt(long b) {
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        return String.format("%.1f MB", b / (1024.0 * 1024));
    }

    // ── Login UI ────────────────────────────────────────────────────────────
    private void showLoginUI() {
        if (updateBlocked) return;
        if (processingCard != null) processingCard.setVisibility(View.GONE);
        if (etKey != null)    etKey.setEnabled(true);
        if (btnLogin != null) btnLogin.setEnabled(true);
        if (fbBtn != null) {
            fbBtn.setVisibility(View.VISIBLE);
            fbBtn.setAlpha(0f);
            fbBtn.animate().alpha(1f).setDuration(280).start();
            restoreFbBtnState();
        }
        if (gmsBtn != null) {
            gmsBtn.setVisibility(View.VISIBLE);
            gmsBtn.setAlpha(0f);
            gmsBtn.animate().alpha(1f).setDuration(320).start();
            restoreGmsBtnState();
        }
        runEntryAnim();
    }

    private void runEntryAnim() {
        if (loginCard == null) return;
        loginCard.setAlpha(0f); loginCard.setTranslationY(dp(30));
        loginCard.animate().alpha(1f).translationY(0f)
                .setDuration(420).setInterpolator(new DecelerateInterpolator(1.5f)).start();
    }

    // ── PROGRAMMATIC LOGIN LAYOUT ─────────────────────────────────────────────
    // View store for programmatic layout
    private View loginCard;

    private android.view.View buildLoginLayout() {
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#080400"));

        // Radial orange glow at top center
        android.view.View glowTop = new android.view.View(this);
        android.graphics.drawable.GradientDrawable glowBg = new android.graphics.drawable.GradientDrawable();
        glowBg.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
        glowBg.setColors(new int[]{0x44FF6600, 0x00000000});
        glowBg.setGradientRadius(700);
        glowTop.setBackground(glowBg);
        root.addView(glowTop, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, dp(600)));

        // Radial orange glow at bottom-right
        android.view.View glowBr = new android.view.View(this);
        android.graphics.drawable.GradientDrawable glowBrBg = new android.graphics.drawable.GradientDrawable();
        glowBrBg.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
        glowBrBg.setColors(new int[]{0x22FF6600, 0x00000000});
        glowBrBg.setGradientRadius(400);
        glowBr.setBackground(glowBrBg);
        android.widget.FrameLayout.LayoutParams brLp = new android.widget.FrameLayout.LayoutParams(dp(300), dp(300));
        brLp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
        root.addView(glowBr, brLp);

        // Scrollable content
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(28), dp(56), dp(28), dp(40));

        // A logo circle at top
        android.widget.ImageView logoCircle = new android.widget.ImageView(this);
        logoCircle.setImageResource(R.drawable.ark_logo_circle);
        logoCircle.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        android.widget.LinearLayout.LayoutParams lcLp =
            new android.widget.LinearLayout.LayoutParams(dp(100), dp(100));
        lcLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        lcLp.bottomMargin = dp(14);
        logoCircle.setLayoutParams(lcLp);
        col.addView(logoCircle);

        // Flame "aRkCheat" text image
        android.widget.ImageView flameText = new android.widget.ImageView(this);
        flameText.setImageResource(R.drawable.ark_flame_text);
        flameText.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        android.widget.LinearLayout.LayoutParams ftLp =
            new android.widget.LinearLayout.LayoutParams(dp(280), dp(154));
        ftLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        ftLp.bottomMargin = dp(8);
        flameText.setLayoutParams(ftLp);
        col.addView(flameText);

        // PLAY • IMPROVE • DOMINATE
        android.widget.TextView tagline = new android.widget.TextView(this);
        tagline.setText("PLAY  •  IMPROVE  •  DOMINATE");
        try { tagline.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.bold)); } catch (Throwable ignored) {}
        tagline.setTextColor(Color.parseColor("#99FF8833"));
        tagline.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        tagline.setLetterSpacing(0.18f);
        tagline.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams tgLp =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        tgLp.bottomMargin = dp(32);
        tagline.setLayoutParams(tgLp);
        col.addView(tagline);

        // ── License Key card ──────────────────────────────────────────────
        android.widget.LinearLayout card = new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(22));
        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
        cardBg.setCornerRadius(dp(14));
        cardBg.setColor(Color.parseColor("#0E0800"));
        cardBg.setStroke(dp(1), Color.parseColor("#CC4400"));
        // Corner notch effect via clip-style is complex; stroke is enough
        card.setBackground(cardBg);
        loginCard = card;

        // "License Key" label row with shield icon
        android.widget.LinearLayout labelRow = new android.widget.LinearLayout(this);
        labelRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        labelRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.LinearLayout.LayoutParams lrLp =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lrLp.bottomMargin = dp(12);
        labelRow.setLayoutParams(lrLp);

        android.widget.ImageView shieldIco = new android.widget.ImageView(this);
        shieldIco.setImageResource(R.drawable.ic_shield);
        shieldIco.setColorFilter(Color.parseColor("#FF8800"), android.graphics.PorterDuff.Mode.SRC_IN);
        labelRow.addView(shieldIco, new android.widget.LinearLayout.LayoutParams(dp(18), dp(18)));

        android.widget.TextView keyLabel = new android.widget.TextView(this);
        keyLabel.setText("  License Key");
        keyLabel.setTextColor(Color.WHITE);
        keyLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        try { keyLabel.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.bold)); }
        catch (Throwable ignored) {}
        labelRow.addView(keyLabel);
        card.addView(labelRow);

        // Key input field — LinearLayout row: icon | EditText | PASTE | eye  (no overlap)
        android.widget.LinearLayout inputFrame = new android.widget.LinearLayout(this);
        inputFrame.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        inputFrame.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable inputBg = new android.graphics.drawable.GradientDrawable();
        inputBg.setCornerRadius(dp(10));
        inputBg.setColor(Color.parseColor("#0A0600"));
        inputBg.setStroke(dp(1), Color.parseColor("#FF6600"));
        inputFrame.setBackground(inputBg);
        android.widget.LinearLayout.LayoutParams ifLp =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        ifLp.bottomMargin = dp(14);
        inputFrame.setLayoutParams(ifLp);
        inputFrame.setPadding(dp(4), dp(2), dp(4), dp(2));

        // Key icon left
        android.widget.ImageView keyIco = new android.widget.ImageView(this);
        keyIco.setImageResource(R.drawable.ic_check);
        keyIco.setColorFilter(Color.parseColor("#FF8800"), android.graphics.PorterDuff.Mode.SRC_IN);
        android.widget.LinearLayout.LayoutParams kiLp =
            new android.widget.LinearLayout.LayoutParams(dp(22), dp(22));
        kiLp.leftMargin = dp(8); kiLp.rightMargin = dp(4);
        keyIco.setLayoutParams(kiLp);
        inputFrame.addView(keyIco);

        // EditText (takes remaining space)
        etKey = new android.widget.EditText(this);
        etKey.setHint("Enter your license key");
        try { etKey.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.bold)); } catch (Throwable ignored) {}
        etKey.setHintTextColor(Color.parseColor("#555533"));
        etKey.setTextColor(Color.WHITE);
        etKey.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        etKey.setBackground(null);
        etKey.setPadding(dp(4), dp(12), dp(4), dp(12));
        etKey.setSingleLine(true);
        inputFrame.addView(etKey, new android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // PASTE button
        android.widget.TextView pasteBtn = new android.widget.TextView(this);
        pasteBtn.setText("PASTE");
        try { pasteBtn.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.bold)); } catch (Throwable ignored) {}
        pasteBtn.setTextColor(Color.parseColor("#FF8800"));
        pasteBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 9);
        pasteBtn.setLetterSpacing(0.1f);
        android.graphics.drawable.GradientDrawable pasteBg = new android.graphics.drawable.GradientDrawable();
        pasteBg.setCornerRadius(dp(4));
        pasteBg.setColor(Color.parseColor("#1A220000"));
        pasteBg.setStroke(dp(1), Color.parseColor("#55440000"));
        pasteBtn.setBackground(pasteBg);
        pasteBtn.setPadding(dp(5), dp(3), dp(5), dp(3));
        android.widget.LinearLayout.LayoutParams pasteLp2 =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        pasteLp2.rightMargin = dp(6);
        pasteBtn.setLayoutParams(pasteLp2);
        pasteBtn.setOnClickListener(v -> {
            android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()
                    && cm.getPrimaryClip() != null
                    && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence txt = cm.getPrimaryClip().getItemAt(0).getText();
                if (txt != null) etKey.setText(txt.toString().trim());
            }
        });
        inputFrame.addView(pasteBtn);

        // Eye toggle
        eyeToggle = new android.widget.ImageButton(this);
        eyeToggle.setImageResource(R.drawable.ic_eye_off);
        eyeToggle.setColorFilter(Color.parseColor("#FF8800"), android.graphics.PorterDuff.Mode.SRC_IN);
        eyeToggle.setBackground(null);
        android.widget.LinearLayout.LayoutParams etogLp =
            new android.widget.LinearLayout.LayoutParams(dp(28), dp(28));
        etogLp.rightMargin = dp(8);
        eyeToggle.setLayoutParams(etogLp);
        inputFrame.addView(eyeToggle);

        card.addView(inputFrame);

        // LOGIN button — large orange gradient pill with → icon
        btnLogin = new androidx.cardview.widget.CardView(this);
        ((androidx.cardview.widget.CardView) btnLogin).setRadius(dp(28));
        ((androidx.cardview.widget.CardView) btnLogin).setCardElevation(dp(4));
        ((androidx.cardview.widget.CardView) btnLogin).setCardBackgroundColor(Color.TRANSPARENT);
        android.widget.LinearLayout.LayoutParams btnLp =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        btnLp.bottomMargin = dp(6);
        btnLogin.setLayoutParams(btnLp);

        android.widget.FrameLayout btnInner = new android.widget.FrameLayout(this);
        android.graphics.drawable.GradientDrawable btnBg = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{Color.parseColor("#CC5500"), Color.parseColor("#FF8800")});
        btnBg.setCornerRadius(dp(28));
        btnInner.setBackground(btnBg);

        android.widget.LinearLayout btnRow = new android.widget.LinearLayout(this);
        btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        btnRow.setGravity(android.view.Gravity.CENTER);
        android.widget.TextView loginTxt = new android.widget.TextView(this);
        loginTxt.setText("LOGIN");
        loginTxt.setTextColor(Color.WHITE);
        loginTxt.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        loginTxt.setLetterSpacing(0.20f);
        try { loginTxt.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.bold)); }
        catch (Throwable ignored) {}
        btnRow.addView(loginTxt);
        btnInner.addView(btnRow, new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        ((androidx.cardview.widget.CardView) btnLogin).addView(btnInner,
            new androidx.cardview.widget.CardView.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        card.addView(btnLogin);

        // Paste button (invisible tap target on key icon area)
        btnPaste = keyIco;

        col.addView(card);

        // ── GMS Clone / Launch button ─────────────────────────────────────
        gmsBtn = new androidx.cardview.widget.CardView(this);
        gmsBtn.setRadius(dp(12));
        gmsBtn.setCardElevation(dp(3));
        gmsBtn.setCardBackgroundColor(android.graphics.Color.TRANSPARENT);
        android.widget.LinearLayout.LayoutParams gmsLp =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        gmsLp.topMargin = dp(10);
        gmsLp.bottomMargin = dp(4);
        gmsBtn.setLayoutParams(gmsLp);

        android.widget.FrameLayout gmsBtnInner = new android.widget.FrameLayout(this);
        android.graphics.drawable.GradientDrawable gmsBg = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{android.graphics.Color.parseColor("#1A237E"), android.graphics.Color.parseColor("#1565C0")});
        gmsBg.setCornerRadius(dp(12));
        gmsBtnInner.setBackground(gmsBg);

        android.widget.LinearLayout gmsBtnRow = new android.widget.LinearLayout(this);
        gmsBtnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        gmsBtnRow.setGravity(android.view.Gravity.CENTER);
        gmsBtnRow.setPadding(dp(16), 0, dp(16), 0);

        android.widget.ImageView gmsIcon = new android.widget.ImageView(this);
        gmsIcon.setImageResource(android.R.drawable.ic_menu_manage);
        gmsIcon.setColorFilter(android.graphics.Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
        android.widget.LinearLayout.LayoutParams gmsIconLp =
            new android.widget.LinearLayout.LayoutParams(dp(20), dp(20));
        gmsIconLp.rightMargin = dp(10);
        gmsIcon.setLayoutParams(gmsIconLp);
        gmsBtnRow.addView(gmsIcon);

        gmsBtnText = new android.widget.TextView(this);
        gmsBtnText.setText("CLONE GMS");
        gmsBtnText.setTextColor(android.graphics.Color.WHITE);
        gmsBtnText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        gmsBtnText.setLetterSpacing(0.12f);
        try { gmsBtnText.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.bold)); }
        catch (Throwable ignored) {}
        gmsBtnRow.addView(gmsBtnText);

        gmsBtnInner.addView(gmsBtnRow, new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        gmsBtn.addView(gmsBtnInner, new androidx.cardview.widget.CardView.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        gmsBtn.setVisibility(android.view.View.GONE);
        col.addView(gmsBtn);

        // ── Processing card (loading/error overlay) ───────────────────────
        processingCard = new android.widget.LinearLayout(this);
        ((android.widget.LinearLayout) processingCard).setOrientation(android.widget.LinearLayout.VERTICAL);
        ((android.widget.LinearLayout) processingCard).setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        android.graphics.drawable.GradientDrawable pcBg = new android.graphics.drawable.GradientDrawable();
        pcBg.setCornerRadius(dp(14));
        pcBg.setColor(Color.parseColor("#0E0800"));
        pcBg.setStroke(dp(1), Color.parseColor("#442200"));
        ((android.widget.LinearLayout) processingCard).setBackground(pcBg);
        ((android.widget.LinearLayout) processingCard).setPadding(dp(20), dp(22), dp(20), dp(22));
        android.widget.LinearLayout.LayoutParams pcLp =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        pcLp.topMargin = dp(14);
        processingCard.setLayoutParams(pcLp);

        progressBar = new android.widget.ProgressBar(this);
        progressBar.setIndeterminate(true);
        android.widget.LinearLayout.LayoutParams pbLp =
            new android.widget.LinearLayout.LayoutParams(dp(40), dp(40));
        pbLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        pbLp.bottomMargin = dp(10);
        progressBar.setLayoutParams(pbLp);
        ((android.widget.LinearLayout) processingCard).addView(progressBar);

        loadingText = new android.widget.TextView(this);
        loadingText.setText("LOADING");
        loadingText.setTextColor(Color.WHITE);
        loadingText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        loadingText.setGravity(android.view.Gravity.CENTER);
        loadingText.setLetterSpacing(0.12f);
        ((android.widget.LinearLayout) processingCard).addView(loadingText,
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        progressText = new android.widget.TextView(this);
        progressText.setText("Please wait...");
        progressText.setTextColor(Color.parseColor("#888866"));
        progressText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        progressText.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams ptLp =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        ptLp.topMargin = dp(4); ptLp.bottomMargin = dp(10);
        progressText.setLayoutParams(ptLp);
        ((android.widget.LinearLayout) processingCard).addView(progressText);

        downloadProgressBar = new android.widget.ProgressBar(this, null,
            android.R.attr.progressBarStyleHorizontal);
        downloadProgressBar.setMax(100);
        downloadProgressBar.setVisibility(android.view.View.GONE);
        ((android.widget.LinearLayout) processingCard).addView(downloadProgressBar,
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(6)));

        percentText = new android.widget.TextView(this);
        percentText.setTextColor(Color.parseColor("#FF8800"));
        percentText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        percentText.setGravity(android.view.Gravity.CENTER);
        percentText.setVisibility(android.view.View.GONE);
        ((android.widget.LinearLayout) processingCard).addView(percentText,
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        downloadSizeText = new android.widget.TextView(this);
        downloadSizeText.setTextColor(Color.parseColor("#666644"));
        downloadSizeText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        downloadSizeText.setGravity(android.view.Gravity.CENTER);
        downloadSizeText.setVisibility(android.view.View.GONE);
        ((android.widget.LinearLayout) processingCard).addView(downloadSizeText,
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        speedRow = new android.widget.LinearLayout(this);
        speedText = new android.widget.TextView(this);
        speedText.setTextColor(Color.parseColor("#666644"));
        speedText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        speedText.setGravity(android.view.Gravity.CENTER);
        speedText.setVisibility(android.view.View.GONE);
        ((android.widget.LinearLayout) speedRow).addView(speedText);
        speedRow.setVisibility(android.view.View.GONE);
        ((android.widget.LinearLayout) processingCard).addView(speedRow,
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        okButton = new androidx.appcompat.widget.AppCompatButton(this);
        okButton.setText("OK");
        okButton.setVisibility(android.view.View.GONE);
        ((android.widget.LinearLayout) processingCard).addView(okButton,
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        processingCard.setVisibility(android.view.View.GONE);
        col.addView(processingCard);

        scroll.addView(col, new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        return root;
    }

    private int dp(float v) {
        return (int) android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void bindViews() { /* all bound in buildLoginLayout() */ }
    private void setTitle()  { /* title is the flame image, not a TextView */ }

    private void setupListeners() {
        if (etKey != null) etKey.setTransformationMethod(PasswordTransformationMethod.getInstance());
        if (eyeToggle != null) {
            eyeToggle.setImageResource(R.drawable.ic_eye_off);
            eyeToggle.setOnClickListener(v -> {
                passwordVisible = !passwordVisible;
                etKey.setTransformationMethod(passwordVisible
                    ? HideReturnsTransformationMethod.getInstance()
                    : PasswordTransformationMethod.getInstance());
                etKey.setSelection(etKey.getText().length());
                eyeToggle.setImageResource(passwordVisible ? R.drawable.ic_eye_open : R.drawable.ic_eye_off);
            });
        }
        if (btnPaste != null) {
            btnPaste.setOnClickListener(v -> {
                ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cb != null && cb.hasPrimaryClip()) {
                    ClipData.Item item = cb.getPrimaryClip().getItemAt(0);
                    if (item != null && item.getText() != null) {
                        String txt = item.getText().toString().trim();
                        if (txt.length() > 4) etKey.setText(txt);
                        else Toast.makeText(this, "Invalid key", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
        if (btnLogin != null) {
            btnLogin.setOnClickListener(v -> {
                SplashActivity.playTap(this);
                String key = etKey.getText().toString().trim();
                if (key.isEmpty()) { etKey.setError("Enter your license key"); return; }
                prefs.write(USER, key);
                doLogin(key);
            });
        }
        if (fbBtn != null) {
            fbBtn.setOnClickListener(v -> {
                if (fbBusy) return;
                long now = System.currentTimeMillis();
                if (isFbClonedSafe()) {
                    if (now - lastFbLaunchMs < 4000L) {
                        Toast.makeText(this, "Opening Facebook...", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    lastFbLaunchMs = now;
                    setFbBtnText("OPENING...");
                    new Thread(() -> { launchClonedFb(); runOnUiThread(this::restoreFbBtnState); }).start();
                } else {
                    fbBusy = true;
                    setFbBtnText("CLONING...");
                    new Thread(() -> { cloneFb(); fbBusy = false; runOnUiThread(this::restoreFbBtnState); }).start();
                }
            });
            fbBtn.setOnLongClickListener(v -> {
                if (fbBusy) return true;
                fbBusy = true;
                setFbBtnText("RE-CLONING...");
                new Thread(() -> {
                    try { MundoCore.get().uninstallPackageAsUser(PKG_FB, 0); } catch (Throwable ignored) {}
                    cloneFb(); fbBusy = false; runOnUiThread(this::restoreFbBtnState);
                }).start();
                return true;
            });
        }
        if (gmsBtn != null) {
            gmsBtn.setOnClickListener(v -> {
                if (gmsBusy) return;
                long now = System.currentTimeMillis();
                if (isGmsClonedSafe()) {
                    if (now - lastGmsLaunchMs < 4000L) {
                        Toast.makeText(this, "Opening Play Store...", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    lastGmsLaunchMs = now;
                    setGmsBtnText("OPENING...");
                    new Thread(() -> { launchClonedGms(); runOnUiThread(this::restoreGmsBtnState); }).start();
                } else {
                    if (!isSupportGmsSafe()) {
                        Toast.makeText(this, "Google Play Services not installed on this device", Toast.LENGTH_LONG).show();
                        return;
                    }
                    gmsBusy = true;
                    setGmsBtnText("CLONING GMS...");
                    new Thread(() -> { cloneGms(); gmsBusy = false; runOnUiThread(this::restoreGmsBtnState); }).start();
                }
            });
            gmsBtn.setOnLongClickListener(v -> {
                if (gmsBusy) return true;
                gmsBusy = true;
                setGmsBtnText("RE-CLONING...");
                new Thread(() -> {
                    try { MundoCore.get().uninstallGms(0); } catch (Throwable ignored) {}
                    cloneGms(); gmsBusy = false; runOnUiThread(this::restoreGmsBtnState);
                }).start();
                return true;
            });
        }
    }

    // ── GMS clone/launch ─────────────────────────────────────────────────────
    private boolean isGmsClonedSafe() {
        try { return MundoCore.get() != null && MundoCore.get().isInstallGms(0); }
        catch (Throwable t) { return false; }
    }
    private boolean isSupportGmsSafe() {
        try { return MundoCore.get() != null && MundoCore.get().isSupportGms(); }
        catch (Throwable t) { return false; }
    }
    private void setGmsBtnText(String text) {
        runOnUiThread(() -> { if (gmsBtnText != null) gmsBtnText.setText(text); });
    }
    private void restoreGmsBtnState() {
        if (gmsBtnText == null) return;
        if (!isSupportGmsSafe()) {
            gmsBtnText.setText("GMS NOT AVAILABLE");
            if (gmsBtn != null) gmsBtn.setAlpha(0.45f);
        } else if (isGmsClonedSafe()) {
            gmsBtnText.setText("LAUNCH PLAY STORE");
            if (gmsBtn != null) { gmsBtn.setAlpha(1f); setGmsBtnColor(0xFF2E7D32, 0xFF43A047); }
        } else {
            gmsBtnText.setText("CLONE GMS");
            if (gmsBtn != null) { gmsBtn.setAlpha(1f); setGmsBtnColor(0xFF1A237E, 0xFF1565C0); }
        }
    }
    private void setGmsBtnColor(int from, int to) {
        if (gmsBtn == null || gmsBtn.getChildCount() == 0) return;
        android.view.View inner = gmsBtn.getChildAt(0);
        if (inner == null) return;
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, new int[]{from, to});
        gd.setCornerRadius(dp(12));
        inner.setBackground(gd);
    }
    private void cloneGms() {
        try {
            android.util.Log.i(TAG_GMS, "cloneGms: start");
            ensureMundoActivated();
            com.mundo.entity.pm.InstallResult result = MundoCore.get().installGms(0);
            boolean ok = isGmsClonedSafe();
            android.util.Log.i(TAG_GMS, "cloneGms: success=" + (result != null ? result.success : "null") + " cloned=" + ok);
            runOnUiThread(() -> {
                restoreGmsBtnState();
                Toast.makeText(this,
                    ok ? "GMS cloned! Tap to launch Play Store."
                       : "Could not clone GMS. Make sure Google Play Services is installed.",
                    Toast.LENGTH_LONG).show();
            });
        } catch (Exception e) {
            android.util.Log.e(TAG_GMS, "cloneGms FAIL: " + e, e);
            runOnUiThread(() -> {
                restoreGmsBtnState();
                Toast.makeText(this, "GMS clone failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            });
        }
    }
    private void launchClonedGms() {
        ensureMundoActivated();
        try { MundoCore.get().launchApk(PKG_PLAY, 0); }
        catch (Throwable t) { android.util.Log.e(TAG_GMS, "launchApk Play Store failed: " + t); }
    }

    // ── Facebook clone/launch ────────────────────────────────────────────────
    private boolean isFbClonedSafe() {
        try { return MundoCore.get() != null && MundoCore.get().isInstalled(PKG_FB, 0); }
        catch (Throwable t) { return false; }
    }
    private void setFbBtnText(String text) {
        runOnUiThread(() -> { if (fbBtnText != null) fbBtnText.setText(text); });
    }
    private void restoreFbBtnState() {
        if (fbBtnText == null) return;
        fbBtnText.setText(isFbClonedSafe() ? "LAUNCH FACEBOOK" : "CLONE FACEBOOK");
    }
    private void ensureMundoActivated() {
        try {
            boolean active = false;
            try { active = MetaActivationManager.getActivatedStatus(); } catch (Throwable ignored) {}
            if (!active) {
                try { MetaActivationManager.activateSdk(ArkApplication.getSdkKey()); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }
    private void cloneFb() {
        try {
            android.util.Log.i(TAG_FB, "cloneFb start");
            ensureMundoActivated();

            try { MundoCore.get().uninstallPackageAsUser(PKG_FB, 0); } catch (Throwable ignored) {}
            android.util.Log.i(TAG_FB, "after uninstall isFbCloned=" + isFbClonedSafe());

            // Hcore: installPackageAsUser handles split APKs internally
            com.mundo.entity.pm.InstallResult installResult = null;
            try { installResult = MundoCore.get().installPackageAsUser(PKG_FB, 0); } catch (Throwable t) { android.util.Log.e(TAG_FB, "installPackageAsUser threw " + t); }
            android.util.Log.i(TAG_FB, "installPackageAsUser result=" + installResult + " isFbCloned=" + isFbClonedSafe());
            boolean ok = isFbClonedSafe();
            android.util.Log.i(TAG_FB, "clone result=" + ok);
            runOnUiThread(() -> {
                restoreFbBtnState();
                Toast.makeText(this,
                    ok ? "Facebook cloned! Tap to launch."
                       : "Could not clone Facebook. Make sure it is installed and try again.",
                    Toast.LENGTH_LONG).show();
            });
        } catch (Exception e) {
            android.util.Log.e(TAG_FB, "cloneFb FAIL: " + e, e);
            runOnUiThread(() -> {
                restoreFbBtnState();
                Toast.makeText(this, "Facebook not installed on device", Toast.LENGTH_LONG).show();
            });
        }
    }
    private void launchClonedFb() {
        ensureMundoActivated();
        try { MundoCore.get().launchApk(PKG_FB, 0); }
        catch (Throwable t) { android.util.Log.e(TAG_FB, "launchApk FB failed: " + t); }
    }
    private static void copyFileFast(File src, File dst) throws Exception {
        dst.getParentFile().mkdirs();
        try (FileInputStream fin = new FileInputStream(src);
             FileOutputStream fout = new FileOutputStream(dst);
             FileChannel inCh = fin.getChannel();
             FileChannel outCh = fout.getChannel()) {
            long size = inCh.size(), pos = 0L;
            final long CHUNK = 16L * 1024 * 1024;
            while (pos < size) {
                long t = inCh.transferTo(pos, Math.min(CHUNK, size - pos), outCh);
                if (t <= 0) break;
                pos += t;
            }
            outCh.force(true);
        }
    }

    // ── Login ────────────────────────────────────────────────────────────────
    private void doLogin(String key) {
        if (updateBlocked) {
            showProcessing("Checking...");
            checkApkUpdate();
            return;
        }
        ArkCrashLogger.setLicenseKey(key);
        showProcessing("Verifying License");
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper(),
            msg -> {
                if (msg.what == 0) {
                    hideProcessing();
                    String serial = "";
                    try { serial = nativeGetSerial(); } catch (Throwable ignored) {}
                    writeLicFile(key, serial);
                    try {
                        // Pass the native serial (the SAME device id the panel registered
                        // at login) so the heartbeat always matches — not raw ANDROID_ID.
                        HeartbeatManager.onLoginSuccess(LoginActivity.this, key, serial);
                    } catch (Throwable ignored) {}
                    startDownloadLib(key, serial);
                } else {
                    showError("Invalid Key", (String) msg.obj);
                }
                return true;
            });
        new Thread(() -> {
            String result = "";
            boolean loginSuccess = false;
            try {
                for (int attempt = 0; attempt < 3; attempt++) {
                    result = Check(this, key);
                    // Check() returns the login token on success — verify it natively
                    // (anti-smali-patch). nativeVerifyLoginToken consumes the token, so
                    // it is called exactly once per attempt.
                    if (result != null && !result.isEmpty() && nativeVerifyLoginToken(result)) {
                        loginSuccess = true;
                        break;
                    }
                    if (result != null && (
                            result.toLowerCase().contains("invalid server") ||
                            result.toLowerCase().contains("response") ||
                            result.toLowerCase().contains("json") ||
                            result.toLowerCase().contains("timeout") ||
                            result.toLowerCase().contains("network") ||
                            result.toLowerCase().contains("mismatch") ||
                            result.toLowerCase().contains("failed") ||
                            result.toLowerCase().contains("curl"))) {
                        try { Thread.sleep(800); } catch (InterruptedException ignored) {}
                        continue;
                    }
                    break;
                }
            } catch (Throwable t) {
                result = t.getMessage() != null ? t.getMessage() : "Error";
            }
            android.os.Message m = android.os.Message.obtain();
            m.what = loginSuccess ? 0 : 1;
            m.obj  = loginSuccess ? null : ((result != null && !result.isEmpty()) ? result : "Key is invalid");
            h.sendMessage(m);
        }).start();
    }

    // Writes Android/ArkCheat.lic with key, serial, packageName (New UI only)
    private void writeLicFile(String key, String serial) {
        try {
            java.io.File dir = new java.io.File(Environment.getExternalStorageDirectory(), "Android");
            if (!dir.exists()) dir.mkdirs();
            java.io.File lic = new java.io.File(dir, "ArkCheat.lic");
            String data = (key != null ? key : "") + "\n"
                        + (serial != null ? serial : "") + "\n"
                        + getPackageName();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(lic, false);
            fos.write(data.getBytes("UTF-8"));
            fos.flush(); fos.close();
            try { lic.setReadable(true, false); } catch (Exception ignored) {}
        } catch (Exception e) {
            android.util.Log.e("ARK_LIC", "writeLicFile failed: " + e.getMessage());
        }
    }

    private void startDownloadLib(String key, String serial) {
        SplashActivity.playLaunch(this);
        showProcessing("Downloading Components");
        dlStartMs = System.currentTimeMillis();
        Downtwo dl = new Downtwo(this, success -> {
            hideProcessing();
            // Download failure is NON-FATAL (Mundo flow) — the loader can still fetch the
            // library later. Never trap the user on the login screen over a download hiccup.
            if (!success) {
                Toast.makeText(this, "Download failed, proceeding...", Toast.LENGTH_SHORT).show();
            }
            startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
        });
        dl.setCredentials(key, serial, getPackageName());
        dl.setSizeProgressListener((pct, done, total) ->
            runOnUiThread(() -> updateDownloadUI(pct, done, total)));
        dl.execute();
    }

    // ── Processing helpers ──────────────────────────────────────────────────
    private void showProcessing(String msg) {
        runOnUiThread(() -> {
            if (processingCard != null) processingCard.setVisibility(View.VISIBLE);
            if (fbBtn != null)          fbBtn.setVisibility(View.GONE);
            if (gmsBtn != null)         gmsBtn.setVisibility(View.GONE);
            if (loadingText != null) {
                loadingText.setText(msg.toUpperCase());
                try { loadingText.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(LoginActivity.this, R.font.bold)); } catch (Throwable ignored) {}
            }
            if (progressText != null) {
                progressText.setText("Please wait...");
                try { progressText.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(LoginActivity.this, R.font.bold)); } catch (Throwable ignored) {}
            }
            if (progressBar != null)    progressBar.setVisibility(View.VISIBLE);
            if (downloadProgressBar != null) downloadProgressBar.setVisibility(View.GONE);
            if (percentText != null)    percentText.setVisibility(View.GONE);
            if (downloadSizeText != null) downloadSizeText.setVisibility(View.GONE);
            if (speedRow != null)       speedRow.setVisibility(View.GONE);
            if (okButton != null)       okButton.setVisibility(View.GONE);
        });
    }

    private void hideProcessing() {
        if (processingCard != null) processingCard.setVisibility(View.GONE);
    }

    @SuppressLint("SetTextI18n")
    private void showError(String title, String msg) {
        runOnUiThread(() -> {
            if (processingCard != null) processingCard.setVisibility(View.VISIBLE);
            if (fbBtn != null)          fbBtn.setVisibility(View.GONE);
            if (loadingText != null)    { loadingText.setText(title); loadingText.setTextColor(0xFFCC1A1A); }
            if (progressText != null)   progressText.setText(msg);
            if (progressBar != null)    progressBar.setVisibility(View.GONE);
            if (downloadProgressBar != null) downloadProgressBar.setVisibility(View.GONE);
            if (percentText != null)    percentText.setVisibility(View.GONE);
            if (downloadSizeText != null) { downloadSizeText.setVisibility(View.GONE); downloadSizeText.setText(""); }
            if (speedRow != null)       speedRow.setVisibility(View.GONE);
            if (speedText != null)      { speedText.setVisibility(View.GONE); speedText.setText(""); }
            if (okButton != null) {
                okButton.setVisibility(View.VISIBLE);
                okButton.setOnClickListener(v -> {
                    if (loadingText != null) loadingText.setTextColor(0xFFFFFFFF);
                    hideProcessing();
                    if (fbBtn != null) {
                        fbBtn.setVisibility(View.VISIBLE);
                        fbBtn.setAlpha(0f);
                        fbBtn.animate().alpha(1f).setDuration(280).start();
                        restoreFbBtnState();
                    }
                    if (gmsBtn != null) {
                        gmsBtn.setVisibility(View.VISIBLE);
                        gmsBtn.setAlpha(0f);
                        gmsBtn.animate().alpha(1f).setDuration(320).start();
                        restoreGmsBtnState();
                    }
                });
            }
        });
    }

    // setTitle replaced by buildLoginLayout() — flame image is the brand now
    private void setTitle_unused() {
        android.widget.TextView tv = findViewById(R.id.splashTitle);
        if (tv == null) return;
        try {
            tv.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.bold));
        } catch (Throwable ignored) {}
        tv.setShadowLayer(26f, 0f, 0f, 0xFFFF5900);
        android.text.SpannableString ss = new android.text.SpannableString("ArkCheat");
        ss.setSpan(new android.text.style.ForegroundColorSpan(0xFFFF7A18), 0, 3,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new android.text.style.ForegroundColorSpan(0xFFFF6A02), 3, 8,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        tv.setText(ss);
    }
}
