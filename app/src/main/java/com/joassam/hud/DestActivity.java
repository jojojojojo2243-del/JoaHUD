package com.joassam.hud;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 시동을 걸면 뜨는 "어디로 갈까요?" 화면.
 * 음성으로 말하거나 주소를 입력하면 → 티맵 길안내 → HUD 자동 전환.
 */
public class DestActivity extends Activity {

    static volatile boolean visible = false;
    private static final int REQ_VOICE = 11;
    private static final long IDLE_MS = 60_000L;

    private SharedPreferences p;
    private EditText input;
    private TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable idle = () -> {
        // 아무 입력이 없으면 목적지 없이 HUD로
        if (!isFinishing()) openHudOnly();
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = getSharedPreferences("hud", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(20), dp(24), dp(20), dp(20));

        TextView title = new TextView(this);
        title.setText("어디로 갈까요?");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        status = new TextView(this);
        status.setText("🎤 말씀하시거나 주소를 입력하세요");
        status.setTextColor(0xFFAAAAAA);
        status.setTextSize(15);
        status.setPadding(0, dp(6), 0, dp(14));
        root.addView(status);

        Button mic = big("🎤  말로 목적지 말하기", 0xFF1E8E3E);
        mic.setOnClickListener(v -> startVoice());
        root.addView(mic);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, 0);
        input = new EditText(this);
        input.setHint("주소나 장소 이름 입력");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF777777);
        input.setTextSize(20);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_DONE
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                go(input.getText().toString());
                return true;
            }
            return false;
        });
        input.setOnFocusChangeListener((v, f) -> handler.removeCallbacks(idle));
        row.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        Button goBtn = new Button(this);
        goBtn.setText("안내");
        goBtn.setTextSize(18);
        goBtn.setOnClickListener(v -> go(input.getText().toString()));
        row.addView(goBtn);
        root.addView(row);

        List<String[]> favs = favorites(p);
        if (!favs.isEmpty()) {
            TextView ft = new TextView(this);
            ft.setText("자주 가는 곳");
            ft.setTextColor(0xFFAAAAAA);
            ft.setTextSize(14);
            ft.setPadding(0, dp(18), 0, dp(4));
            root.addView(ft);
            for (String[] f : favs) {
                Button fb = big("⭐ " + f[0], 0xFF24476B);
                fb.setOnClickListener(v -> go(f[0]));
                root.addView(fb);
            }
        }

        List<String> recent = recent();
        for (String[] f : favs) recent.remove(f[0]);
        if (!recent.isEmpty()) {
            TextView rt = new TextView(this);
            rt.setText("최근 목적지");
            rt.setTextColor(0xFFAAAAAA);
            rt.setTextSize(14);
            rt.setPadding(0, dp(18), 0, dp(4));
            root.addView(rt);
            for (String r : recent) {
                Button rb = big("📍 " + r, 0xFF2A2A2A);
                rb.setTextSize(18);
                rb.setOnClickListener(v -> go(r));
                root.addView(rb);
            }
        }

        Button skip = big("목적지 없이 HUD만 켜기", 0xFF333333);
        skip.setTextSize(16);
        skip.setOnClickListener(v -> openHudOnly());
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = dp(18);
        root.addView(skip, sl);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.BLACK);
        sv.addView(root);
        setContentView(sv);

        handler.postDelayed(idle, IDLE_MS);
        tts = new android.speech.tts.TextToSpeech(this, st -> {
            if (st == android.speech.tts.TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.KOREAN);
                tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                    @Override
                    public void onStart(String id) {
                    }

                    @Override
                    public void onDone(String id) {
                        runOnUiThread(() -> runAfterSpeech(id));
                    }

                    @Override
                    public void onError(String id) {
                        runOnUiThread(() -> runAfterSpeech(id));
                    }
                });
                ttsReady = true;
            }
        });
        // 손 안 대고: 소리로 "어디로 갈까요?" → 듣기
        nextMode = getIntent() != null && getIntent().getBooleanExtra("next", false);
        if (nextMode) handler.postDelayed(() -> ask("도착했어요. 다음 목적지 있으세요?"), 800);
        else if (p.getBoolean("dest_voice", true)) handler.postDelayed(() -> ask("어디로 갈까요?"), 800);
    }

    // ───────── 손 안 대고 말로만 목적지 정하기 ─────────
    private android.speech.tts.TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean nextMode = false;
    private int misses = 0;
    private List<String> choices = null;
    private String prompt = "어디로 갈까요?";
    private String afterId = null;
    private Runnable after = null;

    /** 소리로 말한 뒤 then 실행 (음성 출력이 안 되면 바로 실행) */
    private void speak(String text, Runnable then) {
        status.setText("🔊 " + text);
        String id = "u" + System.nanoTime();
        afterId = id;
        after = then;
        if (ttsReady) {
            Bundle b = new Bundle();
            tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, b, id);
            // 혹시 끝 신호가 안 오면 8초 뒤 그냥 진행
            handler.postDelayed(() -> runAfterSpeech(id), 8000);
        } else {
            handler.postDelayed(() -> runAfterSpeech(id), 400);
        }
    }

    private void runAfterSpeech(String id) {
        if (id == null || !id.equals(afterId)) return;
        afterId = null;
        Runnable r = after;
        after = null;
        if (r != null && !isFinishing()) r.run();
    }

    /** 물어보고 → 듣기 */
    private void ask(String q) {
        prompt = q;
        // 음성 출력 준비가 아직이면 잠깐 기다림 (최대 2초)
        if (!ttsReady && waitTts++ < 5) {
            handler.postDelayed(() -> ask(q), 400);
            return;
        }
        speak(q, this::startVoice);
    }

    private int waitTts = 0;

    private static String ro(String name) {
        char last = name.isEmpty() ? 'a' : name.charAt(name.length() - 1);
        if (last >= 0xAC00 && last <= 0xD7A3) {
            int jong = (last - 0xAC00) % 28;
            return name + (jong == 0 || jong == 8 ? "로" : "으로");
        }
        return name + "로";
    }

    private static String listText(List<String> c) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < c.size(); i++) b.append(i + 1).append("번 ").append(c.get(i)).append(". ");
        return b.toString();
    }

    private List<String> knownNames() {
        List<String> n = new ArrayList<>();
        for (String[] f : favorites(p)) n.add(f[0]);
        for (String r : recent()) if (!n.contains(r)) n.add(r);
        return n;
    }

    private void choose(String name) {
        choices = null;
        speak(ro(name) + " 안내합니다", () -> go(name));
    }

    /** 들은 말 처리 */
    private void heard(List<String> alts) {
        if (nextMode && choices == null) {
            String a0 = alts.get(0).replace(" ", "");
            if (a0.matches(".*(없어|없습니다|아니|됐어|괜찮아|그만|끝|안가|없음).*") && a0.length() <= 8) {
                speak("알겠습니다", this::finish);
                return;
            }
            if (a0.matches("(네|예|응|있어|있습니다|어)")) {
                ask("어디로 갈까요?");
                return;
            }
        }
        if (choices != null) {
            for (String a : alts) {
                int n = VoicePick.number(a);
                if (n >= 1 && n <= choices.size()) {
                    choose(choices.get(n - 1));
                    return;
                }
            }
            List<String> c = VoicePick.candidates(choices, alts);
            if (c.size() == 1) {
                choose(c.get(0));
                return;
            }
            missed("번호로 말씀해 주세요. " + listText(choices));
            return;
        }
        List<String> c = VoicePick.candidates(knownNames(), alts);
        if (c.size() == 1) {
            choose(c.get(0));
        } else if (c.size() >= 2) {
            choices = c;
            ask(listText(c) + "몇 번으로 갈까요?");
        } else {
            String said = VoicePick.clean(alts.get(0));
            if (said.replace(" ", "").length() >= 2) {
                // 저장 안 된 곳: 주소·장소 이름으로 바로 찾기
                speak(ro(said) + " 찾아볼게요", () -> go(said));
            } else {
                missed(null);
            }
        }
    }

    /** 못 알아들음: 1번째 → 다시, 2번째 → 자주 가는 곳 번호로, 3번째 → HUD만 */
    private void missed(String repeat) {
        misses++;
        if (nextMode && misses >= 2) {
            finish(); // 도착 후 물음: 대답이 없으면 조용히 닫기
            return;
        }
        if (misses >= 4) {
            speak("목적지 없이 HUD를 켤게요", this::openHudOnly);
        } else if (repeat != null) {
            ask(repeat);
        } else if (misses == 1) {
            ask("잘 못 들었어요. 다시 말씀해 주세요");
        } else {
            List<String> favNames = new ArrayList<>();
            for (String[] f : favorites(p)) favNames.add(f[0]);
            if (favNames.isEmpty()) {
                ask("다시 한 번 말씀해 주세요");
                return;
            }
            choices = favNames.size() > 5 ? new ArrayList<>(favNames.subList(0, 5)) : favNames;
            ask(listText(choices) + "몇 번으로 갈까요?");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (sr != null) {
            try {
                sr.destroy();
            } catch (Exception ignored) {
            }
        }
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    private android.speech.SpeechRecognizer sr;
    private int silent = 0;

    /** 앱 안에서 직접 듣기 (구글 창 없이). 말이 늦어도 기다리고, 조용하면 알아서 다시 들음 */
    private void startVoice() {
        handler.removeCallbacks(idle);
        boolean canInApp = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                && android.speech.SpeechRecognizer.isRecognitionAvailable(this);
        if (!canInApp) {
            startVoiceDialog();
            return;
        }
        try {
            if (sr == null) {
                sr = android.speech.SpeechRecognizer.createSpeechRecognizer(this);
                sr.setRecognitionListener(new android.speech.RecognitionListener() {
                    @Override public void onReadyForSpeech(Bundle b) { status.setText("🎤 듣고 있어요… 말씀하세요"); }
                    @Override public void onBeginningOfSpeech() { status.setText("🎤 듣는 중…"); }
                    @Override public void onRmsChanged(float v) { }
                    @Override public void onBufferReceived(byte[] b) { }
                    @Override public void onEndOfSpeech() { status.setText("🔎 알아듣는 중…"); }
                    @Override public void onEvent(int t, Bundle b) { }

                    @Override
                    public void onPartialResults(Bundle b) {
                        ArrayList<String> r = b.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                        if (r != null && !r.isEmpty()) status.setText("🎤 " + r.get(0));
                    }

                    @Override
                    public void onResults(Bundle b) {
                        ArrayList<String> r = b.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                        handler.postDelayed(idle, IDLE_MS);
                        if (r != null && !r.isEmpty() && !r.get(0).trim().isEmpty()) {
                            silent = 0;
                            input.setText(VoicePick.clean(r.get(0)));
                            NavListenerService.log("음성: " + r);
                            heard(r);
                        } else {
                            onError(android.speech.SpeechRecognizer.ERROR_NO_MATCH);
                        }
                    }

                    @Override
                    public void onError(int e) {
                        boolean nothing = e == android.speech.SpeechRecognizer.ERROR_NO_MATCH
                                || e == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
                        if (nothing && silent < 2) {
                            // 아무 말도 못 들음 → 묻지 않고 조용히 다시 듣기 (최대 2번)
                            silent++;
                            handler.postDelayed(DestActivity.this::listen, 300);
                            return;
                        }
                        silent = 0;
                        if (!nothing) NavListenerService.log("음성 인식 오류 " + e + " → 구글 창으로");
                        if (e == android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                                || e == android.speech.SpeechRecognizer.ERROR_CLIENT) {
                            startVoiceDialog();
                            return;
                        }
                        handler.postDelayed(idle, IDLE_MS);
                        missed(null);
                    }
                });
            }
            listen();
        } catch (Exception e) {
            startVoiceDialog();
        }
    }

    private void listen() {
        if (isFinishing() || sr == null) return;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // 말을 조금 늦게 시작하거나 중간에 쉬어도 끊지 않게
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 6000L);
        try {
            sr.cancel();
            sr.startListening(i);
        } catch (Exception e) {
            startVoiceDialog();
        }
    }

    /** 예전 방식: 구글 음성 창 */
    private void startVoiceDialog() {
        try {
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt);
            startActivityForResult(i, REQ_VOICE);
        } catch (Exception e) {
            status.setText("음성 인식을 쓸 수 없어요 — 주소를 입력해 주세요");
            input.requestFocus();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_VOICE) return;
        ArrayList<String> r = data != null ? data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) : null;
        handler.postDelayed(idle, IDLE_MS);
        if (res == RESULT_OK && r != null && !r.isEmpty()) {
            input.setText(VoicePick.clean(r.get(0)));
            NavListenerService.log("음성: " + r);
            heard(r);
        } else {
            missed(null);
        }
    }

    private void go(String q) {
        final String dest = q == null ? "" : q.trim();
        if (dest.isEmpty()) {
            Toast.makeText(this, "목적지를 말하거나 입력해 주세요", Toast.LENGTH_SHORT).show();
            return;
        }
        handler.removeCallbacks(idle);
        // "집", "농장"처럼 저장한 이름이면 그 주소로
        String fixed = dest;
        if (favAddress(p, dest).equals(dest)) {
            // 음성을 조금 잘못 알아들었으면 자주 가는 곳·최근 목적지 중 가장 비슷한 것으로
            String near = closest(dest);
            if (near != null && !near.equals(dest)) {
                fixed = near;
                Toast.makeText(this, "'" + near + "'(으)로 알아들었어요", Toast.LENGTH_SHORT).show();
                NavListenerService.log("목적지 보정: '" + dest + "' → '" + near + "'");
            }
        }
        final String dest2 = fixed;
        final String addr = favAddress(p, dest2);
        saveRecent(dest2);
        status.setText("🔎 '" + dest2 + "'" + (addr.equals(dest2) ? "" : " (" + addr + ")") + " 찾는 중…");
        new Thread(() -> {
            double[] ll = geocode(addr);
            runOnUiThread(() -> openTmap(addr.equals(dest2) ? dest2 : addr, dest2, ll));
        }).start();
    }

    /** 휴대폰 내장 주소 검색(무료)으로 좌표 찾기 */
    private double[] geocode(String q) {
        try {
            if (!Geocoder.isPresent()) return null;
            Geocoder g = new Geocoder(this, Locale.KOREA);
            List<Address> a = null;
            // 지금 위치 근처(약 50km)에서 먼저 찾기 → 같은 이름이 여러 곳이면 가까운 곳
            try {
                android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
                android.location.Location here = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER);
                if (here == null) here = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER);
                if (here != null) {
                    double la = here.getLatitude(), lo = here.getLongitude();
                    a = g.getFromLocationName(q, 1, la - 0.45, lo - 0.55, la + 0.45, lo + 0.55);
                }
            } catch (SecurityException ignored) {
            }
            if (a == null || a.isEmpty()) a = g.getFromLocationName(q, 1);
            // 못 찾으면 주소 뒤쪽(건물명·층·호수)을 잘라 가며 다시
            if (a == null || a.isEmpty()) {
                for (String t : trims(q)) {
                    a = g.getFromLocationName(t, 1);
                    if (a != null && !a.isEmpty()) {
                        NavListenerService.log("주소 줄여서 찾음: " + t);
                        break;
                    }
                }
            }
            if (a != null && !a.isEmpty() && a.get(0).hasLatitude()) {
                return new double[]{a.get(0).getLatitude(), a.get(0).getLongitude()};
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void openTmap(String dest, String label, double[] ll) {
        NavListenerService.destFlowAt = System.currentTimeMillis();
        String enc = Uri.encode(dest);
        List<String> urls = new ArrayList<>();
        if (ll != null) {
            // "tmap://?…" 형식: 티맵이 바로 경로 화면(안내 버튼)으로 감. "tmap://route"는 경로 검색 화면에서 멈춤
            // 티맵 버전마다 받는 이름이 달라서 두 가지(goal…, rGo…)를 함께 넣음
            String n = Uri.encode(label);
            urls.add("tmap://route?goalname=" + n + "&goalx=" + ll[1] + "&goaly=" + ll[0]
                    + "&rGoName=" + n + "&rGoX=" + ll[1] + "&rGoY=" + ll[0]);
            TmapA11yService.autoStartUntil = System.currentTimeMillis() + 20_000L; // [안내] 버튼 자동 누르기
        }
        urls.add("tmap://search?name=" + enc);
        for (String u : urls) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u))
                        .setPackage(TmapA11yService.TMAP)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                NavListenerService.log("목적지 '" + dest + "' → 티맵 " + (u.contains("goalx") ? "길안내(좌표 " + String.format(Locale.ROOT, "%.4f,%.4f", ll[0], ll[1]) + ")" : "검색(좌표 못 찾음)") + " 실행");
                finish();
                return;
            } catch (Exception ignored) {
            }
        }
        // 마지막 방법: 티맵만 열기
        Intent launch = getPackageManager().getLaunchIntentForPackage(TmapA11yService.TMAP);
        if (launch != null) {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Toast.makeText(this, "티맵에서 '" + dest + "'를 검색해 주세요", Toast.LENGTH_LONG).show();
            finish();
        } else {
            status.setText("티맵이 설치되어 있지 않아요");
        }
    }

    /** "대전 서구 원도안로26번길 15-5 1층 ○○빌딩" → ["대전 서구 원도안로26번길 15-5", "대전 서구 원도안로26번길", …] */
    static List<String> trims(String q) {
        List<String> out = new ArrayList<>();
        String s = q.replaceAll("\\(.*?\\)", " ").replaceAll("[,·]", " ").replaceAll("\\s+", " ").trim();
        // 도로명 + 건물번호까지만 (예: "원도안로26번길 15-5", "태리로 236")
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*?[가-힣0-9]+(로|길)(\\d+번?길)?\\s*\\d+(-\\d+)?)(\\s|$)").matcher(s);
        if (m.find()) out.add(m.group(1).trim());
        // 지번 주소: 동·리 + 번지 (예: "용암동 3087", "월암리 23-5")
        java.util.regex.Matcher j = java.util.regex.Pattern.compile("^(.*?[가-힣]+(동|리|가)\\s*\\d+(-\\d+)?)(\\s|$)").matcher(s);
        if (j.find() && !out.contains(j.group(1).trim())) out.add(j.group(1).trim());
        return out;
    }

    private void openHudOnly() {
        startActivity(new Intent(this, HudActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    /** 자주 가는 곳: 한 줄에 "이름=주소" */
    static List<String[]> favorites(SharedPreferences p) {
        List<String[]> out = new ArrayList<>();
        for (String line : p.getString("favs", "").split("\n")) {
            int i = line.indexOf('=');
            if (i <= 0) continue;
            String n = line.substring(0, i).trim(), a = line.substring(i + 1).trim();
            // 깨진 글자(잘못 읽은 파일 찌꺼기)는 버림
            if (n.isEmpty() || a.isEmpty() || FavImport.broken(n) || FavImport.broken(a)) continue;
            out.add(new String[]{n, a});
        }
        return out;
    }

    /** 말한 내용에 저장된 이름이 들어 있으면 그 주소 (가장 긴 이름 우선) */
    static String favAddress(SharedPreferences p, String said) {
        String s = said.replace(" ", "");
        String best = null;
        int bestLen = 0;
        for (String[] f : favorites(p)) {
            String n = f[0].replace(" ", "");
            if ((s.equals(n) || s.contains(n)) && n.length() > bestLen) {
                best = f[1];
                bestLen = n.length();
            }
        }
        return best != null ? best : said;
    }

    /** 자주 가는 곳·최근 목적지 중 소리(자모)가 가장 비슷한 것 (70% 이상 비슷할 때만) */
    private String closest(String said) {
        String a = jamo(said);
        if (a.length() < 2) return null;
        String best = null;
        double bestScore = 0;
        List<String> cands = new ArrayList<>();
        for (String[] f : favorites(p)) cands.add(f[0]);
        cands.addAll(recent());
        for (String c : cands) {
            String b = jamo(c);
            if (b.isEmpty()) continue;
            int d = lev(a, b);
            double score = 1.0 - (double) d / Math.max(a.length(), b.length());
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return bestScore >= 0.70 ? best : null;
    }

    /** 한글을 자음·모음으로 풀기 (하레→ㅎㅏㄹㅔ), 띄어쓰기 무시 */
    static String jamo(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.replace(" ", "").toCharArray()) {
            if (ch >= 0xAC00 && ch <= 0xD7A3) {
                int i = ch - 0xAC00;
                b.append((char) (0x1100 + i / 588)).append((char) (0x1161 + (i % 588) / 28));
                if (i % 28 != 0) b.append((char) (0x11A7 + i % 28));
            } else {
                b.append(Character.toLowerCase(ch));
            }
        }
        return b.toString();
    }

    static int lev(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    private List<String> recent() {
        List<String> out = new ArrayList<>();
        for (String s : p.getString("recent_dest", "").split("\n")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    private void saveRecent(String d) {
        List<String> r = recent();
        r.remove(d);
        r.add(0, d);
        while (r.size() > 5) r.remove(r.size() - 1);
        p.edit().putString("recent_dest", String.join("\n", r)).apply();
    }

    private Button big(String s, int color) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(20);
        btn.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        btn.setPadding(dp(18), dp(14), dp(18), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(12));
        btn.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        btn.setLayoutParams(lp);
        return btn;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
