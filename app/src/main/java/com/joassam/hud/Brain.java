package com.joassam.hud;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;

/**
 * HUD의 '두뇌': 정해진 명령어가 아닌 자유로운 말을 구글 제미나이(Flash-Lite, 무료 한도)로 알아듣고
 * ① HUD 명령으로 바꿔 실행 ② 목적지로 길안내 ③ 짧게 대답 중 하나를 돌려줌.
 * 키는 aistudio.google.com 에서 무료로 받음. 키가 없으면 이 기능은 쉼.
 */
final class Brain {
    static final String DEFAULT_MODEL = "gemini-flash-lite-latest";
    private static final String FALLBACK_MODEL = "gemini-2.5-flash-lite";

    /** 답: command(HUD 명령 문장) / navigate(목적지) / say(말할 문장) 중 채워진 것 */
    static final class Answer {
        String command = "", navigate = "", say = "";
        String error = "";
    }

    interface Callback {
        void done(Answer a);
    }

    private static final ArrayDeque<String[]> history = new ArrayDeque<>(); // {role, text}
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Brain() {
    }

    static String key(Context c) {
        return c.getSharedPreferences("hud", Context.MODE_PRIVATE).getString("gemini_key", "").trim();
    }

    static boolean enabled(Context c) {
        SharedPreferences p = c.getSharedPreferences("hud", Context.MODE_PRIVATE);
        return p.getBoolean("brain_on", true) && !key(c).isEmpty();
    }

    /** 앱이 할 수 있는 일 설명 (AI에게 알려 줌) */
    private static String system(Context c, String situation) {
        SharedPreferences p = c.getSharedPreferences("hud", Context.MODE_PRIVATE);
        StringBuilder favs = new StringBuilder();
        for (String[] f : DestActivity.favorites(p)) {
            if (favs.length() > 0) favs.append(", ");
            favs.append(f[0]);
        }
        return "너는 차 앞유리 HUD 앱 '조아 HUD'의 음성 도우미야. 운전자가 운전 중에 말로 부탁해. "
                + "항상 아래 JSON 하나로만 답해: {\"command\":\"\",\"navigate\":\"\",\"say\":\"\"}\n"
                + "- command: HUD를 조작해야 하면 아래 명령 중 하나를 그대로 적어. "
                + "[뒤집어, 가로로, 세로로, 자동 회전, 좌우 반전, 상하 반전, 초록색, 흰색, 하늘색, 노란색, 주황색, 색 바꿔, "
                + "크게(속도 글자), 작게(속도 글자), 밝게, 어둡게, 자동 밝기, 소리 크게, 소리 작게, 경고 꺼, 경고 켜, "
                + "주차 번호 꺼, 주차 번호 켜, 지도 보여, 경로 다시, 목적지 바꿔, 안내 종료, 티맵 꺼, HUD 꺼, 다시 시작, 구간 끝]\n"
                + "- navigate: 어디로 가자고 하면 목적지 이름이나 주소. 자주 가는 곳 목록에 비슷한 게 있으면 그 이름을 그대로 써. "
                + "자주 가는 곳: [" + favs + "]\n"
                + "- say: 운전자에게 소리로 들려줄 한국어 한두 문장 (짧게, 존댓말, 20자 안팎). 명령을 실행할 때는 비워 둬도 돼.\n"
                + "운전 중이니 길게 설명하지 말고, 위험한 부탁(휴대폰 조작 유도 등)은 정중히 거절해. "
                + "모르는 사실(실시간 교통, 주변 가게 영업 여부 등)은 지어내지 말고 모른다고 말해.\n"
                + "지금 상황: " + situation;
    }

    /** 자유로운 말을 AI에게 물어봄 (백그라운드), 결과는 메인 스레드에서 cb로 */
    static void ask(Context ctx, String said, String situation, Callback cb) {
        final Context c = ctx.getApplicationContext();
        new Thread(() -> {
            Answer a = new Answer();
            try {
                SharedPreferences p = c.getSharedPreferences("hud", Context.MODE_PRIVATE);
                String model = p.getString("gemini_model", DEFAULT_MODEL);
                String raw = call(c, model, said, situation);
                if (raw == null && !model.equals(FALLBACK_MODEL)) {
                    raw = call(c, FALLBACK_MODEL, said, situation);
                    if (raw != null) p.edit().putString("gemini_model", FALLBACK_MODEL).apply();
                }
                if (raw == null) {
                    a.error = lastError;
                } else {
                    JSONObject o = new JSONObject(clean(raw));
                    a.command = o.optString("command", "").trim();
                    a.navigate = o.optString("navigate", "").trim();
                    a.say = o.optString("say", "").trim();
                    remember("user", said);
                    remember("model", raw);
                }
            } catch (Exception e) {
                a.error = "AI 답을 읽지 못했어요";
            }
            main.post(() -> cb.done(a));
        }, "brain").start();
    }

    private static String lastError = "";

    /** ```json ... ``` 같은 감싸기 제거 */
    private static String clean(String s) {
        s = s.trim();
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        return a >= 0 && b > a ? s.substring(a, b + 1) : s;
    }

    private static synchronized void remember(String role, String text) {
        history.addLast(new String[]{role, text});
        while (history.size() > 8) history.removeFirst(); // 최근 4번 주고받은 것만 기억
    }

    private static String call(Context c, String model, String said, String situation) {
        HttpURLConnection con = null;
        try {
            JSONObject body = new JSONObject();
            JSONObject sys = new JSONObject();
            sys.put("parts", new JSONArray().put(new JSONObject().put("text", system(c, situation))));
            body.put("systemInstruction", sys);
            JSONArray contents = new JSONArray();
            synchronized (Brain.class) {
                for (String[] h : history) {
                    contents.put(new JSONObject().put("role", h[0])
                            .put("parts", new JSONArray().put(new JSONObject().put("text", h[1]))));
                }
            }
            contents.put(new JSONObject().put("role", "user")
                    .put("parts", new JSONArray().put(new JSONObject().put("text", said))));
            body.put("contents", contents);
            body.put("generationConfig", new JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("temperature", 0.3)
                    .put("maxOutputTokens", 256));

            con = (HttpURLConnection) new URL("https://generativelanguage.googleapis.com/v1beta/models/"
                    + model + ":generateContent").openConnection();
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setConnectTimeout(6000);
            con.setReadTimeout(10000);
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            con.setRequestProperty("x-goog-api-key", key(c));
            try (OutputStream out = con.getOutputStream()) {
                out.write(body.toString().getBytes("UTF-8"));
            }
            int code = con.getResponseCode();
            if (code != 200) {
                lastError = code == 429 ? "오늘 AI 무료 사용량을 다 썼어요. 정해진 명령은 계속 돼요"
                        : code == 400 || code == 401 || code == 403 ? "AI 키를 확인해 주세요"
                        : "AI가 응답하지 않아요 (" + code + ")";
                NavListenerService.log("AI 두뇌 오류 " + code + " (" + model + ")");
                return null;
            }
            String resp = read(con.getInputStream());
            JSONObject r = new JSONObject(resp);
            JSONArray cands = r.getJSONArray("candidates");
            JSONArray parts = cands.getJSONObject(0).getJSONObject("content").getJSONArray("parts");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) sb.append(parts.getJSONObject(i).optString("text", ""));
            return sb.toString();
        } catch (Exception e) {
            lastError = "인터넷이 약해서 AI에 못 물어봤어요";
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toString("UTF-8");
    }

    /** 대화 기억 지우기 (시동 꺼짐 등) */
    static synchronized void forget() {
        history.clear();
    }
}
