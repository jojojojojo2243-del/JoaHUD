package com.joassam.hud;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashSet;
import java.util.Set;

/** 메인 화면: 상태 한눈에 + HUD 시작 + 남은 설정만 + 고급 설정(접힘) */
public class MainActivity extends Activity {

    static volatile boolean visible = false;

    private SharedPreferences p;
    private TextView statusTitle, statusDetail, versionLine, logView;
    private LinearLayout todoBox, advanced, familyBox;
    private Button advToggle;
    private int testStep = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = getSharedPreferences("hud", MODE_PRIVATE);
        // 처음 쓰는 사람도 자동 모드는 기본으로 켜 둠
        if (!p.contains("auto_mode")) p.edit().putBoolean("auto_mode", true).apply();

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        sv.addView(root);

        // ── 1. 상태 카드 ──
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(245, 245, 245));
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        statusTitle = new TextView(this);
        statusTitle.setTextSize(22);
        statusTitle.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(statusTitle);
        statusDetail = new TextView(this);
        statusDetail.setTextSize(15);
        statusDetail.setTextColor(Color.DKGRAY);
        statusDetail.setPadding(0, dp(6), 0, 0);
        card.addView(statusDetail);
        root.addView(card);

        // ── 2. 큰 시작 버튼 ──
        Button start = new Button(this);
        start.setText("▶  HUD 시작");
        start.setTextSize(20);
        start.setAllCaps(false);
        start.setTextColor(Color.WHITE);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(Color.rgb(30, 140, 70));
        sbg.setCornerRadius(dp(14));
        start.setBackground(sbg);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(64));
        slp.topMargin = dp(14);
        root.addView(start, slp);
        start.setOnClickListener(v -> startHud());

        // ── 3. 아직 안 한 설정만 ──
        todoBox = new LinearLayout(this);
        todoBox.setOrientation(LinearLayout.VERTICAL);
        todoBox.setPadding(0, dp(10), 0, 0);
        root.addView(todoBox);

        // ── 4. 버전 한 줄 ──
        versionLine = new TextView(this);
        versionLine.setTextSize(13);
        versionLine.setTextColor(Color.GRAY);
        versionLine.setGravity(Gravity.CENTER);
        versionLine.setPadding(0, dp(18), 0, 0);
        versionLine.setOnClickListener(v -> Updater.check(this, true, m -> refresh()));
        root.addView(versionLine);

        // ── 5. 고급 설정 (접힘) ──
        advToggle = new Button(this);
        advToggle.setAllCaps(false);
        advToggle.setText("고급 설정 ▸");
        advToggle.setBackgroundColor(Color.TRANSPARENT);
        advToggle.setTextColor(Color.GRAY);
        root.addView(advToggle);
        advanced = new LinearLayout(this);
        advanced.setOrientation(LinearLayout.VERTICAL);
        advanced.setVisibility(View.GONE);
        root.addView(advanced);
        advToggle.setOnClickListener(v -> {
            boolean show = advanced.getVisibility() != View.VISIBLE;
            advanced.setVisibility(show ? View.VISIBLE : View.GONE);
            advToggle.setText(show ? "고급 설정 ▾" : "고급 설정 ▸");
            if (show) loadFamily();
        });
        buildAdvanced();

        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
        if (CaptureService.running) {
            startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP));
        }
        if (p.getBoolean("auto_mode", true)) AutoService.start(this);
        if (p.getBoolean("auto_update", true)) Updater.check(this, true, m -> refresh());
    }

    // ───────── 상태 점검 ─────────

    private boolean notiOk() {
        String s = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(getPackageName());
    }

    private boolean overlayOk() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    private boolean a11yOk() {
        String s = Settings.Secure.getString(getContentResolver(), "enabled_accessibility_services");
        return TmapA11yService.connected || (s != null && s.contains(getPackageName()));
    }

    private boolean batteryOk() {
        if (Build.VERSION.SDK_INT < 23) return true;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private boolean btOk() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean installOk() {
        return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
    }

    private void refresh() {
        boolean car = AutoService.carState != 0;
        boolean net = AutoService.netStatus.startsWith("✅");

        // 해야 할 일 목록
        todoBox.removeAllViews();
        int todo = 0;
        if (!notiOk()) {
            todo++;
            addTodo("티맵 길안내 읽기 허용", "목록에서 '조아 HUD' 켜기",
                    () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        }
        A11yGuard.ensure(this);
        if (!a11yOk()) {
            todo++;
            addTodo("차량 화면 안내판 읽기 허용", "설치된 앱 → '조아 HUD 티맵 안내판 읽기' 켜기\n막히면: 앱 정보 → ⋮ → 제한된 설정 허용",
                    () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        }
        if (!A11yGuard.canAutoEnable(this)) {
            todo++;
            addTodo("안내판 읽기 '항상 켜짐' 만들기 (PC로 1회)",
                    "업데이트 때마다 꺼지는 것 방지. PC에 USB로 연결 후 명령 1줄 — 사용법은 README 참고",
                    () -> Toast.makeText(this, "PC 명령: adb shell pm grant com.joassam.hud android.permission.WRITE_SECURE_SETTINGS", Toast.LENGTH_LONG).show());
        }
        if (!overlayOk()) {
            todo++;
            addTodo("HUD 자동 실행 허용", "'다른 앱 위에 표시' 켜기",
                    () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()))));
        }
        if (!btOk()) {
            todo++;
            addTodo("블루투스 권한 허용", "차량 연결 감지에 필요",
                    () -> requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 3));
        }
        if (!batteryOk()) {
            todo++;
            addTodo("배터리 제한 해제", "꺼짐 방지 → '허용'",
                    () -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName())));
                        }
                    });
        }
        if (RouteGuide.key(this).isEmpty()) {
            todo++;
            addTodo("티맵 화살표 켜기 (TMAP API 키 입력)",
                    "티맵 공식 연동으로 회전·제한속도·카메라를 받습니다. 눌러서 키 입력",
                    this::askKey);
        }
        if (!installOk()) {
            todo++;
            addTodo("자동 업데이트 설치 허용", "'이 출처 허용' 켜기",
                    () -> startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName()))));
        }

        // 상태 카드
        if (todo > 0) {
            statusTitle.setText("⚠ 설정 " + todo + "개 남음");
            statusTitle.setTextColor(Color.rgb(200, 90, 0));
        } else if (car) {
            statusTitle.setText("🚗 운행 중");
            statusTitle.setTextColor(Color.rgb(30, 140, 70));
        } else {
            statusTitle.setText("✅ 준비 완료");
            statusTitle.setTextColor(Color.rgb(30, 140, 70));
        }
        StringBuilder d = new StringBuilder();
        d.append(car ? "차량 연결됨" : "시동 걸면 자동으로 HUD가 켜져요");
        d.append("\n").append(net ? "인터넷 연결됨" : "인터넷 없음 — 메인폰 핫스팟에 연결하세요");
        if (!RouteGuide.key(this).isEmpty()) d.append("\n티맵 공식 연동: ").append(TmapEdc.getState());
        if (!TmapEdc.ok() && !RouteGuide.status.isEmpty()) d.append("\n화살표(보조): ").append(RouteGuide.status);
        if (car && TmapA11yService.connected && !TmapA11yService.status.isEmpty()) {
            d.append("\n").append(TmapA11yService.status);
        }
        statusDetail.setText(d.toString());

        String up = Updater.lastMessage.isEmpty() ? "" : " · " + Updater.lastMessage;
        versionLine.setText("v" + Updater.currentVersion(this) + up + "  (눌러서 업데이트 확인)");
        if (logView != null && advanced.getVisibility() == View.VISIBLE) {
            logView.setText(NavListenerService.logText());
        }
    }

    private void askKey() {
        EditText in = new EditText(this);
        in.setHint("TMAP appKey 붙여넣기");
        in.setText(RouteGuide.key(this));
        new android.app.AlertDialog.Builder(this)
                .setTitle("티맵 API 키")
                .setMessage("openapi.sk.com 가입 → 앱 만들기(appKey 발급) → TMAP 상품에서 무료(Free) 요금제 신청 → appKey를 붙여넣으세요 (화살표는 무료로 충분)")
                .setView(in)
                .setPositiveButton("저장", (dlg, w) -> {
                    p.edit().putString("tmap_key", in.getText().toString().trim()).apply();
                    refresh();
                })
                .setNeutralButton("가입 페이지 열기", (dlg, w) ->
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://openapi.sk.com"))))
                .setNegativeButton("취소", null)
                .show();
    }

    private void addTodo(String title, String hint, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(255, 243, 224));
        bg.setCornerRadius(dp(12));
        row.setBackground(bg);
        TextView t = new TextView(this);
        t.setText("👉 " + title);
        t.setTextSize(17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.rgb(120, 60, 0));
        row.addView(t);
        TextView h = new TextView(this);
        h.setText(hint);
        h.setTextSize(13);
        h.setTextColor(Color.DKGRAY);
        row.addView(h);
        row.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        todoBox.addView(row, lp);
    }

    // ───────── 고급 설정 ─────────

    private void buildAdvanced() {
        CheckBox autoMode = check("자동 모드 (시동 걸면 HUD 자동 실행)", "auto_mode", true);
        autoMode.setOnCheckedChangeListener((c, on) -> {
            p.edit().putBoolean("auto_mode", on).apply();
            if (on) AutoService.start(this);
            else stopService(new Intent(this, AutoService.class));
        });
        advanced.addView(autoMode);
        advanced.addView(check("시동 끄면 HUD 자동 종료", "auto_close", true));
        advanced.addView(check("자동 업데이트", "auto_update", true));
        advanced.addView(check("안내판의 흰 글씨만 HUD 색으로", "white_only", true));

        advanced.addView(sub("인터넷 받을 폰 (블루투스 테더링용, 핫스팟을 쓰면 필요 없음)"));
        familyBox = new LinearLayout(this);
        familyBox.setOrientation(LinearLayout.VERTICAL);
        advanced.addView(familyBox);

        Button keyBtn = button("티맵 API 키 입력/변경");
        keyBtn.setOnClickListener(v -> askKey());
        advanced.addView(keyBtn);

        Button edc = button("티맵 공식 연동 지금 시작 (인증 1회 = 요금 1건)");
        edc.setOnClickListener(v -> {
            TmapEdc.start(this);
            Toast.makeText(this, "티맵 연동 시작 — 티맵에서 길안내를 켜세요", Toast.LENGTH_SHORT).show();
        });
        advanced.addView(edc);

        Button test = button("테스트 신호 보내기");
        test.setOnClickListener(v -> {
            NavListenerService.sendTest(testStep++);
            Toast.makeText(this, "보냄 — HUD 화면을 확인하세요", Toast.LENGTH_SHORT).show();
        });
        advanced.addView(test);

        Button appInfo = button("앱 정보 열기 (제한된 설정 허용)");
        appInfo.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        advanced.addView(appInfo);

        advanced.addView(sub("추가로 읽을 내비 앱 패키지명 (쉼표로 구분)"));
        EditText extra = new EditText(this);
        extra.setText(p.getString("extra_pkgs", ""));
        extra.setHint("예: com.example.navi");
        advanced.addView(extra);
        CheckBox all = check("모든 앱 알림 기록 (패키지명 확인용)", "log_all", false);
        advanced.addView(all);
        Button save = button("저장");
        save.setOnClickListener(v -> {
            p.edit().putString("extra_pkgs", extra.getText().toString().trim()).apply();
            Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show();
        });
        advanced.addView(save);

        advanced.addView(sub("기록 (문제가 있을 때 이 화면을 찍어 보내 주세요)"));
        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        advanced.addView(logView);
    }

    private void loadFamily() {
        familyBox.removeAllViews();
        if (!btOk()) {
            familyBox.addView(sub("(블루투스 권한이 필요합니다)"));
            return;
        }
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
        if (ad == null || !ad.isEnabled()) {
            familyBox.addView(sub("(블루투스를 켜 주세요)"));
            return;
        }
        Set<String> fam = new HashSet<>(p.getStringSet("family", new HashSet<>()));
        for (BluetoothDevice d : ad.getBondedDevices()) {
            CheckBox cb = new CheckBox(this);
            cb.setText(d.getName() == null ? d.getAddress() : d.getName());
            cb.setChecked(fam.contains(d.getAddress()));
            final String addr = d.getAddress();
            cb.setOnCheckedChangeListener((c, on) -> {
                Set<String> s = new HashSet<>(p.getStringSet("family", new HashSet<>()));
                if (on) s.add(addr);
                else s.remove(addr);
                p.edit().putStringSet("family", s).apply();
            });
            familyBox.addView(cb);
        }
    }

    // ───────── 수명주기 ─────────

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        visible = false;
        handler.removeCallbacks(poll);
        super.onPause();
    }

    private void startHud() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
            return;
        }
        startActivity(new Intent(this, HudActivity.class));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) startActivity(new Intent(this, HudActivity.class));
        if (requestCode == 3 && p.getBoolean("auto_mode", true)) AutoService.start(this);
        refresh();
    }

    // ───────── UI 도우미 ─────────

    private CheckBox check(String label, String key, boolean def) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextSize(15);
        cb.setChecked(p.getBoolean(key, def));
        cb.setOnCheckedChangeListener((c, on) -> p.edit().putBoolean(key, on).apply());
        return cb;
    }

    private TextView sub(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(Color.GRAY);
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }

    private Button button(String s) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(4);
        btn.setLayoutParams(lp);
        return btn;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
