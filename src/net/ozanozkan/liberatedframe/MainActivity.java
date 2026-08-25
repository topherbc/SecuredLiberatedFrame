package net.ozanozkan.liberatedframe;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.admin.DevicePolicyManager;
import android.app.PendingIntent;
import android.app.TimePickerDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements SyncEngine.Listener {

    private SharedPreferences prefs;
    private SyncEngine sync;
    private final Handler handler = new Handler();

    private FrameLayout root;
    private ImageView viewA, viewB;
    private boolean showingA = false;
    private TextView statusText;
    private LinearLayout settingsPanel;
    private ScrollView scrollWrap;
    private EditText urlInput, syncInput, slideInput;
    private Button startBtn, endBtn;
    private String schedStartVal = "08:00", schedEndVal = "00:30";
    private CheckBox schedCheck;
    private TextView infoText;
    private View blackView;
    private boolean displayAwake = true;

    private final Runnable schedTick = new Runnable() {
        public void run() {
            applyScheduleState();
            handler.postDelayed(this, 30000);
        }
    };

    private final List<File> photos = new ArrayList<>();
    private int index = -1;
    private String lastMessage = "not synced yet";

    private int tapCount = 0;
    private long lastTapTime = 0;

    private final Runnable slideTick = new Runnable() {
        public void run() {
            showNext();
            handler.postDelayed(this, slideSeconds() * 1000L);
        }
    };
    private final Runnable syncTick = new Runnable() {
        public void run() {
            sync.syncAsync();
            handler.postDelayed(this, syncMinutes() * 60000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        applyIntentConfig(getIntent());

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        viewA = new ImageView(this);
        viewB = new ImageView(this);
        applyScaleType();
        root.addView(viewA, new FrameLayout.LayoutParams(-1, -1));
        root.addView(viewB, new FrameLayout.LayoutParams(-1, -1));

        blackView = new View(this);
        blackView.setBackgroundColor(Color.BLACK);
        blackView.setVisibility(View.GONE);
        root.addView(blackView, new FrameLayout.LayoutParams(-1, -1));

        statusText = new TextView(this);
        statusText.setTextColor(Color.WHITE);
        statusText.setBackgroundColor(0xB0000000);
        statusText.setPadding(40, 24, 40, 24);
        statusText.setTextSize(18);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        root.addView(statusText, sp);

        buildSettingsPanel();
        setContentView(root);

        root.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (scrollWrap.getVisibility() == View.VISIBLE) { toggleSettings(false); return; }
                long now = System.currentTimeMillis();
                if (now - lastTapTime > 2500) tapCount = 0;
                lastTapTime = now;
                if (++tapCount >= 2) { tapCount = 0; toggleSettings(true); }
            }
        });

        sync = new SyncEngine(photoDir(), prefs, handler, this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        applyIntentConfig(intent);
    }

    private long lastResumeTime = 0;
    private static final long NIGHT_PEEK_MS = 2 * 60 * 1000; // button press at night shows photos this long

    @Override
    protected void onResume() {
        super.onResume();
        lastResumeTime = System.currentTimeMillis();
        hideBackChip();
        goImmersive();
        handler.removeCallbacks(slideTick);
        handler.removeCallbacks(syncTick);
        handler.removeCallbacks(schedTick);
        refreshPhotoList();
        if (prefs.getString("url", "").isEmpty()) {
            toggleSettings(true);
        } else {
            handler.post(syncTick);
            handler.postDelayed(slideTick, 1500);
        }
        handler.post(schedTick);
        updateStatus();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(slideTick);
        handler.removeCallbacks(syncTick);
        handler.removeCallbacks(schedTick);
        super.onPause();
    }

    // ---- SyncEngine.Listener ----
    public void onSyncFinished(String message, boolean changed) {
        lastMessage = message;
        if (changed) refreshPhotoList();
        updateStatus();
    }

    // ---- slideshow ----

    private File photoDir() {
        return new File(getExternalFilesDir(null), "photos");
    }

    private void refreshPhotoList() {
        photos.clear();
        File[] files = photoDir().listFiles();
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) if (f.getName().endsWith(".jpg")) photos.add(f);
        }
        if (index >= photos.size()) index = -1;
    }

    private void showNext() {
        if (!displayAwake) return;
        if (photos.isEmpty()) { updateStatus(); return; }
        index = (index + 1) % photos.size();
        Bitmap bmp = decodeForScreen(photos.get(index));
        if (bmp == null) return;
        ImageView in = showingA ? viewB : viewA;
        ImageView out = showingA ? viewA : viewB;
        showingA = !showingA;
        in.setImageBitmap(bmp);
        in.bringToFront();
        blackView.bringToFront();
        statusText.bringToFront();
        if (scrollWrap != null) scrollWrap.bringToFront();
        in.setAlpha(0f);
        in.animate().alpha(1f).setDuration(900).start();
        out.animate().alpha(0f).setDuration(900).start();
        updateStatus();
    }

    private Bitmap decodeForScreen(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int targetW = 1280, targetH = 800;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= targetW && o.outHeight / (sample * 2) >= targetH) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
            if (bmp == null) return null;
            // pre-scale to the exact displayed size with filtering, so the
            // ImageView never minifies (GPU bilinear minification aliases fine detail)
            float sw = (float) targetW / bmp.getWidth();
            float sh = (float) targetH / bmp.getHeight();
            float scale = "fill".equals(mode()) ? Math.max(sw, sh) : Math.min(sw, sh);
            if (scale < 0.999f) {
                Bitmap scaled = Bitmap.createScaledBitmap(bmp,
                        Math.max(1, Math.round(bmp.getWidth() * scale)),
                        Math.max(1, Math.round(bmp.getHeight() * scale)), true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    private void updateStatus() {
        boolean showMsg = photos.isEmpty();
        statusText.setVisibility(showMsg ? View.VISIBLE : View.GONE);
        if (showMsg) {
            String cfg = prefs.getString("url", "").isEmpty()
                    ? "No album configured.\nTap the screen twice to open settings."
                    : "Waiting for photos…\nlast sync: " + lastMessage;
            statusText.setText(cfg);
        }
    }

    // ---- settings ----

    private int syncMinutes() { return clamp(prefs.getInt("syncMin", 5), 1, 720); }
    private int slideSeconds() { return clamp(prefs.getInt("slideSec", 20), 3, 3600); }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    // ---- sleep schedule ----

    /** "HH:MM" -> minutes of day, or -1 if invalid. */
    private static int parseTime(String s) {
        try {
            String[] p = s.trim().split(":");
            if (p.length != 2) return -1;
            int h = Integer.parseInt(p[0].trim());
            int m = Integer.parseInt(p[1].trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
            return h * 60 + m;
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean isDisplayTime() {
        if (!prefs.getBoolean("schedOn", false)) return true;
        int start = parseTime(prefs.getString("schedStart", "08:00"));
        int end = parseTime(prefs.getString("schedEnd", "00:30"));
        if (start < 0 || end < 0 || start == end) return true;
        Calendar c = Calendar.getInstance();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        if (start < end) return now >= start && now < end;
        return now >= start || now < end; // window crosses midnight (e.g. 08:00 -> 00:30)
    }

    private void applyScheduleState() {
        // waking the screen at night (power button, boot) grants a photo peek, then sleep resumes
        boolean peeking = System.currentTimeMillis() - lastResumeTime < NIGHT_PEEK_MS;
        boolean awake = isDisplayTime() || peeking
                || (scrollWrap != null && scrollWrap.getVisibility() == View.VISIBLE);
        if (awake != displayAwake) {
            displayAwake = awake;
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            if (awake) {
                blackView.setVisibility(View.GONE);
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                handler.removeCallbacks(slideTick);
                handler.post(slideTick);
            } else {
                blackView.setVisibility(View.VISIBLE);
                blackView.bringToFront();
                if (scrollWrap != null) scrollWrap.bringToFront();
                lp.screenBrightness = 0f; // fallback dim; real off comes from lockNow below
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                handler.removeCallbacks(slideTick);
            }
            getWindow().setAttributes(lp);
        }
        if (!displayAwake) {
            armWakeAlarm();     // guarantees the morning screen-on even if the process dies
            maybeScreenOff();   // true backlight-off when device admin is granted
        }
    }

    /** Turn the screen fully off during sleep hours, with a 60s grace after any wake/touch. */
    private void maybeScreenOff() {
        try {
            if (System.currentTimeMillis() - lastResumeTime < 60000) return;
            if (scrollWrap != null && scrollWrap.getVisibility() == View.VISIBLE) return;
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(this, AdminReceiver.class);
            if (pm.isInteractive() && dpm.isAdminActive(admin)) {
                dpm.lockNow();
            }
        } catch (Throwable ignored) {} // no admin granted: black + dim fallback stays
    }

    private void armWakeAlarm() {
        try {
            if (!prefs.getBoolean("schedOn", false)) return;
            int start = parseTime(prefs.getString("schedStart", "08:00"));
            if (start < 0) return;
            Calendar now = Calendar.getInstance();
            Calendar t = (Calendar) now.clone();
            t.set(Calendar.HOUR_OF_DAY, start / 60);
            t.set(Calendar.MINUTE, start % 60);
            t.set(Calendar.SECOND, 0);
            t.set(Calendar.MILLISECOND, 0);
            if (!t.after(now)) t.add(Calendar.DAY_OF_YEAR, 1);
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            PendingIntent pi = PendingIntent.getBroadcast(this, 8,
                    new Intent(this, WakeReceiver.class), PendingIntent.FLAG_UPDATE_CURRENT);
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.getTimeInMillis(), pi);
        } catch (Throwable ignored) {}
    }

    private String mode() { return prefs.getString("mode", "smart"); }

    private void applyScaleType() {
        // smart/fit files are already letterbox-shaped, fill files are exactly screen-sized,
        // so FIT_CENTER displays everything 1:1; CENTER_CROP only matters for legacy fill files
        ImageView.ScaleType st = "fill".equals(mode())
                ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER;
        viewA.setScaleType(st);
        viewB.setScaleType(st);
    }

    private void applyIntentConfig(Intent intent) {
        if (intent == null) return;
        SharedPreferences.Editor e = prefs.edit();
        boolean any = false;
        String url = intent.getStringExtra("url");
        if (url != null && !url.isEmpty()) { e.putString("url", url.trim()); any = true; }
        String sm = intent.getStringExtra("syncMin");
        if (sm != null) { try { e.putInt("syncMin", Integer.parseInt(sm.trim())); any = true; } catch (Exception ignored) {} }
        String ss = intent.getStringExtra("slideSec");
        if (ss != null) { try { e.putInt("slideSec", Integer.parseInt(ss.trim())); any = true; } catch (Exception ignored) {} }
        String mode = intent.getStringExtra("mode");
        if (mode != null && ("smart".equals(mode) || "fill".equals(mode) || "fit".equals(mode))) {
            e.putString("mode", mode); any = true;
        }
        String fit = intent.getStringExtra("fit"); // legacy extra
        if (fit != null) { e.putString("mode", "true".equalsIgnoreCase(fit.trim()) ? "fit" : "fill"); any = true; }
        String so = intent.getStringExtra("schedOn");
        if (so != null) { e.putBoolean("schedOn", "true".equalsIgnoreCase(so.trim())); any = true; }
        String ss2 = intent.getStringExtra("schedStart");
        if (ss2 != null && parseTime(ss2) >= 0) { e.putString("schedStart", ss2.trim()); any = true; }
        String se = intent.getStringExtra("schedEnd");
        if (se != null && parseTime(se) >= 0) { e.putString("schedEnd", se.trim()); any = true; }
        if (any) e.apply();
    }

    private void buildSettingsPanel() {
        settingsPanel = new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        settingsPanel.setBackgroundColor(0xE0101010);
        settingsPanel.setPadding(60, 40, 60, 40);

        TextView title = new TextView(this);
        title.setText("LiberatedFrame settings");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        settingsPanel.addView(title);

        urlInput = addField("Google Photos shared album link",
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        syncInput = addField("Sync interval (minutes)", android.text.InputType.TYPE_CLASS_NUMBER);
        slideInput = addField("Seconds per photo", android.text.InputType.TYPE_CLASS_NUMBER);

        schedCheck = new CheckBox(this);
        schedCheck.setText("Sleep schedule (screen dark outside these hours)");
        schedCheck.setTextColor(Color.WHITE);
        settingsPanel.addView(schedCheck);
        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        startBtn = makeTimeButton(true);
        endBtn = makeTimeButton(false);
        timeRow.addView(startBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        timeRow.addView(endBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        settingsPanel.addView(timeRow);

        final Button fitBtn = new Button(this);
        settingsPanel.addView(fitBtn);
        fitBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String m = mode();
                String next = "smart".equals(m) ? "fill" : "fill".equals(m) ? "fit" : "smart";
                prefs.edit().putString("mode", next).apply();
                fitBtn.setText(modeLabel());
                applyScaleType();
                sync.syncAsync(); // mode change means new geometry — re-download
            }
        });
        fitBtn.setText(modeLabel());

        infoText = new TextView(this);
        infoText.setTextColor(0xFFAAAAAA);
        infoText.setTextSize(14);
        settingsPanel.addView(infoText);

        Button wifi = new Button(this);
        wifi.setText("Wi-Fi settings");
        settingsPanel.addView(wifi);
        wifi.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                launchExternal(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS));
            }
        });

        Button sys = new Button(this);
        sys.setText("Android settings");
        settingsPanel.addView(sys);
        sys.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                launchExternal(new Intent(android.provider.Settings.ACTION_SETTINGS));
            }
        });

        Button frameo = new Button(this);
        frameo.setText("Open Frameo");
        settingsPanel.addView(frameo);
        frameo.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_HOME);
                i.setPackage("net.frameo.frame");
                launchExternal(i);
            }
        });

        Button save = new Button(this);
        save.setText("Save & start");
        settingsPanel.addView(save);
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                SharedPreferences.Editor e = prefs.edit();
                String u = urlInput.getText().toString().trim();
                if (!u.isEmpty()) e.putString("url", u);
                try { e.putInt("syncMin", Integer.parseInt(syncInput.getText().toString().trim())); } catch (Exception ignored) {}
                try { e.putInt("slideSec", Integer.parseInt(slideInput.getText().toString().trim())); } catch (Exception ignored) {}
                e.putBoolean("schedOn", schedCheck.isChecked());
                e.putString("schedStart", schedStartVal);
                e.putString("schedEnd", schedEndVal);
                e.apply();
                toggleSettings(false);
                onResume();
            }
        });

        Button close = new Button(this);
        close.setText("Close");
        settingsPanel.addView(close);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleSettings(false); }
        });

        settingsPanel.setClickable(true); // consume taps so only taps OUTSIDE the panel close it

        scrollWrap = new ScrollView(this);
        scrollWrap.setVisibility(View.GONE);
        scrollWrap.addView(settingsPanel);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(900, -2, Gravity.CENTER);
        root.addView(scrollWrap, lp);
    }

    private String modeLabel() {
        String m = mode();
        if ("fill".equals(m)) return "Display: fill screen (always crop)";
        if ("fit".equals(m)) return "Display: fit (always whole photo)";
        return "Display: smart (crop only when it fits well)";
    }

    private Button makeTimeButton(final boolean isStart) {
        final Button b = new Button(this);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int t = parseTime(isStart ? schedStartVal : schedEndVal);
                if (t < 0) t = isStart ? 480 : 30;
                new TimePickerDialog(MainActivity.this, new TimePickerDialog.OnTimeSetListener() {
                    public void onTimeSet(TimePicker tp, int h, int m) {
                        String val = String.format(Locale.US, "%02d:%02d", h, m);
                        if (isStart) schedStartVal = val; else schedEndVal = val;
                        b.setText((isStart ? "ON at " : "OFF at ") + val);
                    }
                }, t / 60, t % 60, true).show();
            }
        });
        return b;
    }

    private EditText addField(String label, int inputType) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFFBBBBBB);
        tv.setTextSize(14);
        tv.setPadding(0, 16, 0, 0);
        settingsPanel.addView(tv);
        EditText et = new EditText(this);
        et.setTextColor(Color.WHITE);
        et.setInputType(inputType);
        et.setSingleLine(true);
        et.setImeOptions(EditorInfo.IME_ACTION_DONE); // "Done" key closes the keyboard
        settingsPanel.addView(et);
        return et;
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
        } catch (Throwable ignored) {}
    }

    private void toggleSettings(boolean show) {
        if (show) {
            urlInput.setText(prefs.getString("url", ""));
            syncInput.setText(String.valueOf(syncMinutes()));
            slideInput.setText(String.valueOf(slideSeconds()));
            schedCheck.setChecked(prefs.getBoolean("schedOn", false));
            schedStartVal = prefs.getString("schedStart", "08:00");
            schedEndVal = prefs.getString("schedEnd", "00:30");
            startBtn.setText("ON at " + schedStartVal);
            endBtn.setText("OFF at " + schedEndVal);
            long last = prefs.getLong("lastSync", 0);
            String when = last == 0 ? "never"
                    : new SimpleDateFormat("HH:mm", Locale.US).format(new Date(last));
            infoText.setText(prefs.getInt("photoCount", 0) + " photos · last sync " + when
                    + " · status: " + lastMessage);
            scrollWrap.setVisibility(View.VISIBLE);
            scrollWrap.bringToFront();
            scrollWrap.scrollTo(0, 0);
        } else {
            hideKeyboard();
            scrollWrap.setVisibility(View.GONE);
        }
        applyScheduleState(); // panel open counts as awake; re-evaluate on close
    }

    private View backChip;

    /** Launch an external screen with two ways home: a floating chip and a 10-min auto-return. */
    private void launchExternal(Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            scheduleAutoReturn();
            showBackChip();
            startActivity(intent);
            toggleSettings(false);
        } catch (Exception ignored) {
            hideBackChip();
        }
    }

    private void showBackChip() {
        if (backChip != null) return;
        try {
            Button b = new Button(this);
            b.setText("◀ Photos");
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    startActivity(goHomeIntent());
                    hideBackChip();
                }
            });
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.LEFT;
            lp.x = 20; lp.y = 20;
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(b, lp);
            backChip = b;
        } catch (Throwable t) {
            backChip = null; // overlay permission missing — the auto-return alarm still covers us
        }
    }

    private void hideBackChip() {
        if (backChip == null) return;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(backChip);
        } catch (Throwable ignored) {}
        backChip = null;
    }

    private Intent goHomeIntent() {
        // we are the default HOME app, so "go home" resolves to us and the
        // system reliably fronts the home task (a plain startActivity won't)
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_HOME);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    private void scheduleAutoReturn() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            PendingIntent pi = PendingIntent.getActivity(this, 7, goHomeIntent(),
                    PendingIntent.FLAG_UPDATE_CURRENT);
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 10 * 60000L, pi);
        } catch (Throwable ignored) {}
    }

    private void goImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
}
