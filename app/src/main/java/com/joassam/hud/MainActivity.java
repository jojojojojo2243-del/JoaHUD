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

        // 업데이트 버튼 (GitHub 최신 버전 바로 확인·설치)
        Button upd = new Button(this);
        upd.setAllCaps(false);
        upd.setText("🔄  업데이트 확인");
        upd.setTextSize(16);
        upd.setOnClickListener(v -> {
            Toast.makeText(this, "최신 버전 확인 중…", Toast.LENGTH_SHORT).show();
            Updater.check(this, true, m -> {
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                refresh();
            });
        });
        LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(-1, -2);
        ul.topMargin = dp(6);
        root.addView(upd, ul);

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

        java.util.List<String> perms = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        // 목적지 음성: 앱 안에서 직접 들으려면 마이크 권한 필요
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECORD_AUDIO);
        }
        if (!perms.isEmpty()) requestPermissions(perms.toArray(new String[0]), 2);
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
        if (Alerts.key(this).isEmpty()) {
            todo++;
            addTodo("단속카메라·스쿨존 경고 켜기 (공공데이터 키, 무료)",
                    "공공데이터포털 가입 → 단속카메라 데이터 '활용신청'(자동 승인) → 인증키 붙여넣기",
                    this::askPubKey);
        }
        if (p.getString("park_phone", "").isEmpty() && p.getBoolean("park_show", true)) {
            todo++;
            addTodo("주차 전화번호 입력", "시동 끄면 서브폰에 크게 보여 줄 번호 — 고급 설정 ▸ 🅿 에서 입력",
                    () -> {
                        advanced.setVisibility(View.VISIBLE);
                        Toast.makeText(this, "아래 '🅿 주차 때 보여줄 전화번호'에 입력 후 저장", Toast.LENGTH_LONG).show();
                    });
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
        d.append(car || AutoService.ignOn ? "운행 중" : "시동 걸면(충전 시작) '어디로 갈까요?' → 티맵 → HUD");
        d.append("\n").append(net ? "인터넷 연결됨" : "인터넷 없음 — 메인폰 핫스팟에 연결하세요");
        if (!RouteGuide.key(this).isEmpty()) d.append("\n티맵 공식 연동: ").append(TmapEdc.getState());
        if (!TmapEdc.ok() && !RouteGuide.status.isEmpty()) d.append("\n화살표(보조): ").append(RouteGuide.status);
        if (!Alerts.key(this).isEmpty()) {
            Alerts.load(this);
            d.append("\n경고 자료: ").append(Alerts.status);
        }
        if ((car || AutoService.ignOn) && TmapA11yService.connected && !TmapA11yService.status.isEmpty()) {
            d.append("\n").append(TmapA11yService.status);
        }
        statusDetail.setText(d.toString());

        String up = Updater.lastMessage.isEmpty() ? "" : " · " + Updater.lastMessage;
        versionLine.setText("현재 v" + Updater.currentVersion(this) + up);
        if (logView != null && advanced.getVisibility() == View.VISIBLE) {
            logView.setText(NavListenerService.logText());
        }
    }

    private void askPubKey() {
        EditText in = new EditText(this);
        in.setHint("공공데이터 인증키(Decoding 또는 Encoding) 붙여넣기");
        in.setText(Alerts.key(this));
        new android.app.AlertDialog.Builder(this)
                .setTitle("단속카메라 경고 (공공데이터)")
                .setMessage("1. data.go.kr 회원가입\n2. '전국무인교통단속카메라표준데이터' 페이지에서 [활용신청] (자동 승인, 무료)\n3. 마이페이지 → 개발계정 → 일반 인증키 복사\n4. 여기에 붙여넣고 저장\n\n저장하면 바로 전국 자료를 받고(1~2분), 이후 일주일마다 자동 업데이트돼요.")
                .setView(in)
                .setPositiveButton("저장", (dlg, w) -> {
                    p.edit().putString("pub_key", in.getText().toString().trim()).putLong("pub_updated", 0).apply();
                    Alerts.update(this);
                    Toast.makeText(this, "단속카메라 자료 받는 중… (1~2분)", Toast.LENGTH_LONG).show();
                    refresh();
                })
                .setNeutralButton("신청 페이지 열기", (dlg, w) ->
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.data.go.kr/data/15028200/standard.do"))))
                .setNegativeButton("취소", null)
                .show();
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
        advanced.addView(check("HUD가 티맵 미니 창을 피해서 그리기", "avoid_mini", true));
        advanced.addView(check("티맵 미니 창 검게 가리기 (안드로이드 14 이상, 앞유리에 안 비침)", "hide_mini", true));
        advanced.addView(check("HUD 밝기: 폰 자동 밝기 따르기 (끄면 항상 최대)", "auto_bright", true));
        Button pubBtn = button("단속카메라 공공데이터 키 입력/변경");
        pubBtn.setOnClickListener(v -> askPubKey());
        advanced.addView(pubBtn);
        Button pubNow = button("단속카메라 자료 지금 새로 받기");
        pubNow.setOnClickListener(v -> {
            Alerts.update(this);
            Toast.makeText(this, "받는 중… 상태 카드에서 확인하세요", Toast.LENGTH_SHORT).show();
        });
        advanced.addView(pubNow);

        advanced.addView(sub("🔑 시동 연동 (서브폰 충전이 시작되면 시동 켜짐으로 봄)"));
        advanced.addView(check("시동 켜면 '어디로 갈까요?' 화면 (음성/주소 입력)", "ign_dest", true));
        advanced.addView(check("목적지 화면에서 바로 음성 듣기", "dest_voice", true));
        advanced.addView(check("시동 끄면 주차 전화번호 화면", "park_show", true));
        advanced.addView(check("시동 끄면 티맵 길안내도 종료", "stop_tmap", true));
        advanced.addView(check("목적지 도착하면 '다음 목적지 있으세요?' 묻기", "ask_next", true));
        Button ssidBtn = button(carSsidLabel());
        ssidBtn.setOnClickListener(v -> {
            String cur = AutoService.currentSsid(this);
            if (!p.getString("car_ssid", "").isEmpty()) {
                p.edit().remove("car_ssid").apply();
                Toast.makeText(this, "해제됨 — 충전만 시작돼도 시동으로 봅니다", Toast.LENGTH_LONG).show();
            } else if (cur.isEmpty()) {
                Toast.makeText(this, "지금 연결된 와이파이를 못 읽었어요 (위치 켜기 + 메인폰 핫스팟 연결 후 다시)", Toast.LENGTH_LONG).show();
            } else {
                p.edit().putString("car_ssid", cur).apply();
                Toast.makeText(this, "'" + cur + "'에 연결된 채 충전될 때만 시동으로 봅니다", Toast.LENGTH_LONG).show();
            }
            ssidBtn.setText(carSsidLabel());
        });
        advanced.addView(ssidBtn);

        advanced.addView(sub("⭐ 자주 가는 곳 — 한 줄에 '이름=주소' (예: 집=충남 공주시 계룡면 ○○길 12)"));
        Button imp = button("📄 엑셀/CSV 파일로 한 번에 불러오기 (A열 이름, B열 주소)");
        imp.setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel"});
            try {
                startActivityForResult(pick, REQ_IMPORT);
            } catch (Exception e) {
                Toast.makeText(this, "파일 선택 화면을 열 수 없어요", Toast.LENGTH_LONG).show();
            }
        });
        advanced.addView(imp);
        // 자주 가는 곳 목록: 줄마다 [수정] [삭제], 아래 [+ 추가] [전체 삭제]
        favList = new LinearLayout(this);
        favList.setOrientation(LinearLayout.VERTICAL);
        advanced.addView(favList);
        LinearLayout favBtns = new LinearLayout(this);
        favBtns.setOrientation(LinearLayout.HORIZONTAL);
        Button add = new Button(this);
        add.setAllCaps(false);
        add.setText("＋ 추가");
        add.setOnClickListener(v -> editFav(-1));
        Button clear = new Button(this);
        clear.setAllCaps(false);
        clear.setText("전체 삭제");
        clear.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setMessage("자주 가는 곳을 모두 지울까요?")
                .setPositiveButton("모두 지우기", (d, w) -> {
                    p.edit().putString("favs", "").apply();
                    refreshFavList();
                })
                .setNegativeButton("취소", null).show());
        favBtns.addView(add, new LinearLayout.LayoutParams(0, -2, 1f));
        favBtns.addView(clear, new LinearLayout.LayoutParams(0, -2, 1f));
        advanced.addView(favBtns);
        refreshFavList();

        advanced.addView(sub("🅿 주차 때 보여줄 전화번호"));
        EditText phone = new EditText(this);
        phone.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        phone.setHint("예: 010-1234-5678");
        phone.setText(p.getString("park_phone", ""));
        advanced.addView(phone);
        EditText msg = new EditText(this);
        msg.setHint("위에 보일 문구 (기본: 주차 중 연락처)");
        msg.setText(p.getString("park_msg", ""));
        advanced.addView(msg);
        Button phoneSave = button("전화번호 저장");
        phoneSave.setOnClickListener(v -> {
            String m = msg.getText().toString().trim();
            p.edit().putString("park_phone", phone.getText().toString().trim())
                    .putString("park_msg", m.isEmpty() ? "주차 중 연락처" : m).apply();
            Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show();
        });
        advanced.addView(phoneSave);
        Button parkTest = button("주차 화면 미리보기");
        parkTest.setOnClickListener(v -> startActivity(new Intent(this, ParkActivity.class)));
        advanced.addView(parkTest);
        Button destTest = button("목적지 화면 미리보기");
        destTest.setOnClickListener(v -> startActivity(new Intent(this, DestActivity.class)));
        advanced.addView(destTest);
        advanced.addView(check("안내판을 HUD 한 가지 색으로 (끄면 티맵 원래 색 그대로)", "white_only", false));

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

    private static final int REQ_IMPORT = 31;
    private LinearLayout favList;

    /** 자주 가는 곳 목록 다시 그리기 */
    private void refreshFavList() {
        if (favList == null) return;
        favList.removeAllViews();
        java.util.List<String[]> favs = DestActivity.favorites(p);
        // 깨진 글자 줄이 저장돼 있으면 자동으로 정리
        if (p.getString("favs", "").split("\n").length != favs.size() && !p.getString("favs", "").isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (String[] f : favs) {
                if (b.length() > 0) b.append('\n');
                b.append(f[0]).append('=').append(f[1]);
            }
            p.edit().putString("favs", b.toString()).apply();
        }
        if (favs.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("(아직 없음 — ＋ 추가 또는 엑셀 불러오기)");
            t.setTextColor(Color.GRAY);
            t.setPadding(dp(8), dp(6), 0, dp(6));
            favList.addView(t);
            return;
        }
        for (int i = 0; i < favs.size(); i++) {
            final int idx = i;
            String[] f = favs.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(4), 0, dp(4));
            TextView t = new TextView(this);
            t.setText((i + 1) + ". " + f[0] + "\n   " + f[1]);
            t.setTextSize(14);
            t.setTextColor(Color.BLACK);
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            Button ed = new Button(this);
            ed.setText("수정");
            ed.setOnClickListener(v -> editFav(idx));
            row.addView(ed, new LinearLayout.LayoutParams(-2, -2));
            Button del = new Button(this);
            del.setText("삭제");
            del.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                    .setMessage("'" + f[0] + "'을(를) 지울까요?")
                    .setPositiveButton("삭제", (d, w) -> {
                        java.util.List<String[]> l = DestActivity.favorites(p);
                        if (idx < l.size()) l.remove(idx);
                        saveFavs(l);
                    })
                    .setNegativeButton("취소", null).show());
            row.addView(del, new LinearLayout.LayoutParams(-2, -2));
            favList.addView(row);
        }
    }

    /** idx = -1 이면 새로 추가, 아니면 그 줄 수정 */
    private void editFav(int idx) {
        java.util.List<String[]> l = DestActivity.favorites(p);
        String[] cur = idx >= 0 && idx < l.size() ? l.get(idx) : new String[]{"", ""};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText name = new EditText(this);
        name.setHint("이름 (예: 집, 농장, 하레하레 본점)");
        name.setText(cur[0]);
        name.setSingleLine(true);
        EditText addr = new EditText(this);
        addr.setHint("주소 (예: 충남 공주시 계룡면 ○○길 12)");
        addr.setText(cur[1]);
        box.addView(name);
        box.addView(addr);
        new android.app.AlertDialog.Builder(this)
                .setTitle(idx < 0 ? "자주 가는 곳 추가" : "자주 가는 곳 수정")
                .setView(box)
                .setPositiveButton("저장", (d, w) -> {
                    String n = name.getText().toString().trim().replace("=", " ");
                    String a = addr.getText().toString().trim().replace("\n", " ");
                    if (n.isEmpty() || a.isEmpty()) {
                        Toast.makeText(this, "이름과 주소를 모두 넣어 주세요", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    java.util.List<String[]> list = DestActivity.favorites(p);
                    if (idx >= 0 && idx < list.size()) list.set(idx, new String[]{n, a});
                    else list.add(new String[]{n, a});
                    saveFavs(list);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void saveFavs(java.util.List<String[]> l) {
        StringBuilder b = new StringBuilder();
        for (String[] f : l) {
            if (b.length() > 0) b.append('\n');
            b.append(f[0]).append('=').append(f[1]);
        }
        p.edit().putString("favs", b.toString()).apply();
        refreshFavList();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMPORT || res != RESULT_OK || data == null || data.getData() == null) return;
        try (java.io.InputStream in = getContentResolver().openInputStream(data.getData())) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            java.util.List<String[]> rows = FavImport.parse(o.toByteArray(), String.valueOf(data.getData()));
            if (rows.isEmpty()) {
                Toast.makeText(this, "읽은 장소가 없어요 — A열 이름, B열 주소인지 확인해 주세요", Toast.LENGTH_LONG).show();
                return;
            }
            String merged = FavImport.merge(p.getString("favs", ""), rows);
            p.edit().putString("favs", merged).apply();
            refreshFavList();
            NavListenerService.log("자주 가는 곳 " + rows.size() + "곳 불러옴");
            Toast.makeText(this, rows.size() + "곳 등록됐어요 (같은 이름은 새 주소로 바뀜)", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "불러오기 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String carSsidLabel() {
        String s = p.getString("car_ssid", "");
        return s.isEmpty() ? "차 와이파이 등록 (지금 연결된 메인폰 핫스팟 = 차 안)" : "차 와이파이: " + s + " (눌러서 해제)";
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
