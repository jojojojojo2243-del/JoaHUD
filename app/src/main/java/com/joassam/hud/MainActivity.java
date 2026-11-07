package com.joassam.hud;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashSet;
import java.util.Set;

public class MainActivity extends Activity {

    static volatile boolean visible = false;
    private static final int REQ_CAPTURE = 11;

    private SharedPreferences p;
    private TextView status, capStatus, logView, editMode, autoStatus;
    private LinearLayout familyBox;
    private CropPreview cropView;
    private final SeekBar[] crop = new SeekBar[4];
    private boolean editLand = false;
    private int testStep = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            updateCapture();
            handler.postDelayed(this, 700);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = getSharedPreferences("hud", MODE_PRIVATE);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        sv.addView(root);

        // ── 차량용 서브폰 자동 모드 ──
        root.addView(header("🚗 차량 자동 모드 (서브폰)"));
        root.addView(note("서브폰을 차량 안드로이드 오토에 등록해 두면, 시동을 걸어 연결되는 순간 "
                + "차량 화면에는 티맵, 서브폰 화면에는 HUD가 자동으로 나옵니다.\n"
                + "아래 ①~⑤를 한 번만 설정하세요."));
        autoStatus = new TextView(this);
        autoStatus.setTextSize(15);
        root.addView(autoStatus);

        CheckBox autoMode = new CheckBox(this);
        autoMode.setText("자동 모드 켜기 (폰을 켜면 자동 시작)");
        autoMode.setChecked(p.getBoolean("auto_mode", false));
        autoMode.setOnCheckedChangeListener((c, on) -> {
            p.edit().putBoolean("auto_mode", on).apply();
            if (on) {
                askBluetooth();
                AutoService.start(this);
            } else {
                stopService(new Intent(this, AutoService.class));
            }
        });
        root.addView(autoMode);

        Button notiPerm = button("① 알림 접근 허용 (티맵 길안내 읽기)");
        notiPerm.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        root.addView(notiPerm);

        Button overlay = button("② 다른 앱 위에 표시 허용 (HUD 자동 실행)");
        overlay.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 23) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            }
        });
        root.addView(overlay);

        Button batt = button("③ 배터리 제한 없음 (꺼짐 방지)");
        batt.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        root.addView(batt);

        root.addView(note("④ 인터넷 받을 가족 폰 선택 (먼저 블루투스로 페어링되어 있어야 함, 여러 대 가능)\n"
                + "   각 가족 폰: 설정 → 연결 → 모바일 핫스팟 및 테더링 → '블루투스 테더링' 켜기"));
        familyBox = new LinearLayout(this);
        familyBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(familyBox);
        Button reloadFam = button("페어링된 폰 목록 새로고침");
        reloadFam.setOnClickListener(v -> {
            askBluetooth();
            loadFamily();
        });
        root.addView(reloadFam);
        Button btSet = button("블루투스 설정 열기 (폰 ⚙ → '인터넷 액세스' 켜기)");
        btSet.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        root.addView(btSet);

        root.addView(note("⑤ 차량 블루투스 설정에서 서브폰은 '통화' 끄고 '안드로이드 오토/미디어'만 사용. "
                + "통화는 메인폰(가족 폰)이 차량에 연결되어 받습니다."));

        CheckBox autoClose = new CheckBox(this);
        autoClose.setText("안드로이드 오토가 끊기면 HUD 자동 종료");
        autoClose.setChecked(p.getBoolean("auto_close", true));
        autoClose.setOnCheckedChangeListener((c, on) -> p.edit().putBoolean("auto_close", on).apply());
        root.addView(autoClose);

        // ── 서브폰 ──
        root.addView(header("서브폰 (HUD 화면)"));
        root.addView(note("메인폰 핫스팟에 연결한 뒤 시작하세요. 화면을 탭하면 반전/색상 메뉴가 나옵니다."));
        Button hud = button("▶ HUD 시작");
        hud.setOnClickListener(v -> startHud());
        root.addView(hud);

        // ── 메인폰: 티맵 화면 전송 ──
        root.addView(header("메인폰 ① 티맵 화면 전송 (권장)"));
        root.addView(note("티맵 화면의 회전 안내판을 잘라 서브폰에 그대로 띄웁니다.\n"
                + "화살표·차로·단속카메라까지 티맵과 똑같이 보입니다.\n"
                + "• 시작 → '전체 화면' 공유 선택 (Android 14+는 '티맵' 앱만 선택해도 됨)\n"
                + "• 티맵 길안내 화면을 켜 두세요"));
        capStatus = new TextView(this);
        capStatus.setTextSize(15);
        root.addView(capStatus);

        LinearLayout row = new LinearLayout(this);
        Button start = button("● 전송 시작");
        start.setOnClickListener(v -> startCapture());
        Button stop = button("■ 중지");
        stop.setOnClickListener(v -> startService(new Intent(this, CaptureService.class)
                .setAction(CaptureService.ACTION_STOP)));
        row.addView(start, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(stop, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(row);

        Button tmap = button("티맵 열기");
        tmap.setOnClickListener(v -> {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.skt.tmap.ku");
            if (i != null) startActivity(i);
            else Toast.makeText(this, "티맵이 설치되어 있지 않습니다", Toast.LENGTH_SHORT).show();
        });
        root.addView(tmap);

        root.addView(header("자를 영역 설정"));
        root.addView(note("전송 중에 티맵 길안내 화면을 띄웠다가 이 앱으로 돌아오면, 마지막 티맵 화면에 빨간 네모가 표시됩니다. "
                + "네모가 회전 안내판을 감싸도록 조절하세요. 세로/가로는 따로 저장됩니다."));
        editMode = new TextView(this);
        editMode.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(editMode);
        LinearLayout orow = new LinearLayout(this);
        Button bp = button("세로 화면용");
        bp.setOnClickListener(v -> setEditLand(false));
        Button bl = button("가로 화면용");
        bl.setOnClickListener(v -> setEditLand(true));
        Button reset = button("기본값");
        reset.setOnClickListener(v -> {
            int[] d = editLand ? CropPresets.LAND_DEFAULT : CropPresets.PORT_DEFAULT;
            SharedPreferences.Editor e = p.edit();
            for (int i = 0; i < 4; i++) {
                crop[i].setProgress(d[i]);
                e.putInt(key() + "ltrb".charAt(i), d[i]);
            }
            e.apply();
            cropView.invalidate();
        });
        orow.addView(bp, new LinearLayout.LayoutParams(0, -2, 1f));
        orow.addView(bl, new LinearLayout.LayoutParams(0, -2, 1f));
        orow.addView(reset, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(orow);

        cropView = new CropPreview(this);
        root.addView(cropView, new LinearLayout.LayoutParams(-1, dp(360)));

        String[] names = {"왼쪽", "위", "오른쪽", "아래"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            root.addView(note(names[i] + " (%)"));
            crop[i] = new SeekBar(this);
            crop[i].setMax(100);
            crop[i].setOnSeekBarChangeListener(new SimpleSeek(v -> {
                p.edit().putInt(key() + "ltrb".charAt(idx), v).apply();
                cropView.invalidate();
            }));
            root.addView(crop[i]);
        }

        root.addView(note("검정 처리 강도 (어두운 배경을 완전 검정으로 → 앞유리에 뿌옇게 비치는 것 방지)"));
        SeekBar black = new SeekBar(this);
        black.setMax(160);
        black.setProgress(p.getInt("black", 70));
        black.setOnSeekBarChangeListener(new SimpleSeek(v -> p.edit().putInt("black", v).apply()));
        root.addView(black);

        root.addView(note("초당 전송 장수 (높을수록 부드럽지만 배터리·발열 증가)"));
        SeekBar fps = new SeekBar(this);
        fps.setMax(15);
        fps.setProgress(p.getInt("fps", 8));
        fps.setOnSeekBarChangeListener(new SimpleSeek(v -> p.edit().putInt("fps", Math.max(1, v)).apply()));
        root.addView(fps);

        // ── 메인폰: 알림 방식 ──
        root.addView(header("메인폰 ② 알림 읽기 방식 (보조)"));
        status = new TextView(this);
        status.setTextSize(15);
        root.addView(status);
        root.addView(note("티맵 화면 전송이 끊겼을 때 대신 쓰는 방식입니다. 알림 접근 허용이 필요합니다.\n"
                + "'제한된 설정' 이라며 막히면: 앱 정보 → 우측 위 ⋮ → '제한된 설정 허용'"));
        Button perm = button("알림 접근 허용 열기");
        perm.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        root.addView(perm);
        Button appInfo = button("앱 정보 열기 (제한된 설정 허용용)");
        appInfo.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        root.addView(appInfo);

        // ── 공통 ──
        root.addView(header("연결"));
        Button hotspot = button("핫스팟 설정 열기");
        hotspot.setOnClickListener(v -> {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName("com.android.settings", "com.android.settings.TetherSettings"));
                startActivity(i);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
            }
        });
        root.addView(hotspot);
        Button battery = button("배터리 최적화 해제 (끊김 방지)");
        battery.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, "설정 → 앱 → 조아 HUD → 배터리 → 제한 없음", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(battery);
        Button test = button("테스트 신호 보내기 (누를 때마다 바뀜)");
        test.setOnClickListener(v -> {
            NavListenerService.sendTest(testStep++);
            Toast.makeText(this, "보냄 — 서브폰 화면을 확인하세요", Toast.LENGTH_SHORT).show();
        });
        root.addView(test);

        // ── 고급 ──
        root.addView(header("고급"));
        CheckBox all = new CheckBox(this);
        all.setText("모든 앱 알림 기록 (내비 앱 패키지명 확인용)");
        all.setChecked(p.getBoolean("log_all", false));
        all.setOnCheckedChangeListener((c, on) -> p.edit().putBoolean("log_all", on).apply());
        root.addView(all);
        root.addView(note("추가로 감시할 내비 앱 패키지명 (쉼표로 구분)"));
        EditText extra = new EditText(this);
        extra.setText(p.getString("extra_pkgs", ""));
        extra.setHint("예: com.example.navi");
        root.addView(extra);
        Button save = button("저장");
        save.setOnClickListener(v -> {
            p.edit().putString("extra_pkgs", extra.getText().toString().trim()).apply();
            Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show();
        });
        root.addView(save);

        root.addView(header("받은 알림 기록"));
        Button refresh = button("새로고침");
        refresh.setOnClickListener(v -> refresh());
        root.addView(refresh);
        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        root.addView(logView);

        setContentView(sv);
        setEditLand(false);
        loadFamily();
        if (p.getBoolean("auto_mode", false)) AutoService.start(this);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
    }

    private String key() {
        return editLand ? "land_" : "port_";
    }

    private void setEditLand(boolean land) {
        editLand = land;
        editMode.setText(land ? "편집 중: 가로 화면용 영역" : "편집 중: 세로 화면용 영역");
        int[] d = land ? CropPresets.LAND_DEFAULT : CropPresets.PORT_DEFAULT;
        String k = key();
        String ks = "ltrb";
        for (int i = 0; i < 4; i++) {
            int v = p.getInt(k + ks.charAt(i), d[i]);
            crop[i].setProgress(v);
        }
        cropView.invalidate();
    }

    private int cropVal(int i) {
        int[] d = editLand ? CropPresets.LAND_DEFAULT : CropPresets.PORT_DEFAULT;
        return p.getInt(key() + "ltrb".charAt(i), d[i]);
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        Bitmap snap = CaptureService.snapshot;
        if (snap != null && (snap.getWidth() > snap.getHeight()) != editLand) {
            setEditLand(snap.getWidth() > snap.getHeight());
        }
        refresh();
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        visible = false;
        handler.removeCallbacks(poll);
        super.onPause();
    }

    private void askBluetooth() {
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 3);
        }
    }

    private void loadFamily() {
        familyBox.removeAllViews();
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            familyBox.addView(note("(블루투스 권한을 허용해야 목록이 보입니다)"));
            return;
        }
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
        if (ad == null || !ad.isEnabled()) {
            familyBox.addView(note("(블루투스를 켜 주세요)"));
            return;
        }
        Set<String> fam = new HashSet<>(p.getStringSet("family", new HashSet<>()));
        for (BluetoothDevice d : ad.getBondedDevices()) {
            CheckBox cb = new CheckBox(this);
            String name = d.getName() == null ? d.getAddress() : d.getName();
            cb.setText(name);
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
        if (familyBox.getChildCount() == 0) familyBox.addView(note("(페어링된 기기가 없습니다)"));
    }

    private void updateCapture() {
        if (autoStatus != null) {
            String car = AutoService.carState == 0 ? "차량 미연결" : "🚗 안드로이드 오토 연결됨";
            autoStatus.setText((AutoService.running ? "🟢 자동 모드 동작 중 · " : "⚪ 자동 모드 꺼짐 · ")
                    + car + (AutoService.netStatus.isEmpty() ? "" : " · " + AutoService.netStatus));
        }
        String s = CaptureService.running ? "🟢 " + CaptureService.status : "⚪ 화면 전송 꺼짐 " + CaptureService.status;
        capStatus.setText(s);
        cropView.invalidate();
    }

    private void refresh() {
        boolean enabled = listenerEnabled();
        status.setText(enabled ? "✅ 알림 접근 허용됨" : "❌ 알림 접근 꺼짐");
        status.setTextColor(enabled ? Color.rgb(0, 140, 60) : Color.rgb(200, 40, 40));
        logView.setText(NavListenerService.logText());
        updateCapture();
    }

    private boolean listenerEnabled() {
        String s = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(getPackageName());
    }

    private void startCapture() {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "화면 공유가 취소되었습니다", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, CaptureService.class)
                .putExtra(CaptureService.EXTRA_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        Toast.makeText(this, "전송 시작 — 티맵을 열어 길안내를 켜세요", Toast.LENGTH_LONG).show();
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
        if (requestCode == 3) {
            loadFamily();
            if (p.getBoolean("auto_mode", false)) AutoService.start(this);
        }
    }

    /** 마지막 티맵 화면 + 자를 영역(빨간 네모) */
    private class CropPreview extends View {
        private final Paint red = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dim = new Paint();
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);

        CropPreview(Context c) {
            super(c);
            red.setStyle(Paint.Style.STROKE);
            red.setStrokeWidth(dp(3));
            red.setColor(Color.RED);
            dim.setColor(Color.argb(150, 0, 0, 0));
            txt.setColor(Color.WHITE);
            txt.setTextSize(dp(14));
            txt.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            c.drawColor(Color.rgb(30, 30, 30));
            Bitmap snap = CaptureService.snapshot;
            float bw = editLand ? 16 : 9, bh = editLand ? 9 : 16;
            if (snap != null && (snap.getWidth() > snap.getHeight()) == editLand) {
                bw = snap.getWidth();
                bh = snap.getHeight();
            } else {
                snap = null;
            }
            float sc = Math.min(w / bw, h / bh);
            float fw = bw * sc, fh = bh * sc;
            float left = (w - fw) / 2f, top = (h - fh) / 2f;
            RectF area = new RectF(left, top, left + fw, top + fh);
            if (snap != null) {
                c.drawBitmap(snap, null, area, null);
            } else {
                c.drawText("전송 시작 후 티맵 화면을 띄웠다 오면", w / 2f, h / 2f - dp(10), txt);
                c.drawText("여기에 그 화면이 보입니다", w / 2f, h / 2f + dp(12), txt);
            }
            float l = left + fw * cropVal(0) / 100f, t = top + fh * cropVal(1) / 100f;
            float r = left + fw * cropVal(2) / 100f, b = top + fh * cropVal(3) / 100f;
            // 영역 밖 어둡게
            c.drawRect(left, top, left + fw, t, dim);
            c.drawRect(left, b, left + fw, top + fh, dim);
            c.drawRect(left, t, l, b, dim);
            c.drawRect(r, t, left + fw, b, dim);
            c.drawRect(l, t, r, b, red);
        }
    }

    private interface IntCb {
        void on(int v);
    }

    private static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        private final IntCb cb;

        SimpleSeek(IntCb cb) {
            this.cb = cb;
        }

        @Override
        public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
            if (fromUser) cb.on(v);
        }

        @Override
        public void onStartTrackingTouch(SeekBar s) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar s) {
        }
    }

    private TextView header(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(19);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(20), 0, dp(6));
        return t;
    }

    private TextView note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14);
        t.setTextColor(Color.DKGRAY);
        t.setPadding(0, dp(2), 0, dp(4));
        return t;
    }

    private Button button(String s) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        btn.setLayoutParams(lp);
        return btn;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
