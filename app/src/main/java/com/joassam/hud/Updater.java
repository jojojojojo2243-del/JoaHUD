package com.joassam.hud;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GitHub 최신 릴리스를 확인해서 새 버전이면 내려받아 설치.
 * 저장소는 공개(Public)여야 함.
 */
public class Updater {

    static final String REPO = "jojojojo2243-del/JoaHUD";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    public interface Callback {
        void on(String message);
    }

    static volatile boolean busy = false;
    static volatile String lastMessage = "";

    public static int currentVersion(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** @param install true면 새 버전 발견 시 바로 설치 */
    public static void check(Context ctx, boolean install, Callback cb) {
        final Context c = ctx.getApplicationContext();
        if (busy) {
            post(cb, "이미 확인 중입니다");
            return;
        }
        busy = true;
        EXEC.execute(() -> {
            try {
                JSONObject rel = new JSONObject(get(API));
                String tag = rel.optString("tag_name", "v0");
                String digits = tag.replaceAll("[^0-9]", "");
                int latest = digits.isEmpty() ? 0 : Integer.parseInt(digits);
                int mine = currentVersion(c);
                String url = null;
                JSONArray assets = rel.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.optString("name").endsWith(".apk")) {
                            url = a.optString("browser_download_url");
                            break;
                        }
                    }
                }
                if (latest <= mine || url == null) {
                    finish(cb, "최신 버전입니다 (v" + mine + ")");
                    return;
                }
                if (!install) {
                    finish(cb, "새 버전 v" + latest + " 있음 (현재 v" + mine + ")");
                    return;
                }
                post(cb, "v" + latest + " 내려받는 중…");
                installFrom(c, url);
                finish(cb, "v" + latest + " 설치 진행 중…");
            } catch (java.io.FileNotFoundException e) {
                finish(cb, "업데이트 확인 실패: 저장소를 '공개'로 바꾸고, 빌드가 끝났는지 확인하세요");
            } catch (Exception e) {
                finish(cb, "업데이트 확인 실패: " + e.getMessage());
            }
        });
    }

    private static void installFrom(Context c, String url) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            // 이 앱이 직접 설치한 적이 있으면 확인 창 없이 업데이트
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = pi.createSession(params);
        PackageInstaller.Session s = pi.openSession(id);
        HttpURLConnection con = open(url);
        try (InputStream in = con.getInputStream();
             OutputStream out = s.openWrite("JoaHUD.apk", 0, -1)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            s.fsync(out);
        } finally {
            con.disconnect();
        }
        Intent i = new Intent(c, InstallResultReceiver.class);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent p = PendingIntent.getBroadcast(c, id, i, fl);
        s.commit(p.getIntentSender());
        s.close();
    }

    private static String get(String url) throws Exception {
        HttpURLConnection con = open(url);
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            con.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setInstanceFollowRedirects(true);
        con.setConnectTimeout(15000);
        con.setReadTimeout(60000);
        con.setRequestProperty("User-Agent", "JoaHUD-Updater");
        con.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
        int code = con.getResponseCode();
        // 다른 호스트로의 리다이렉트를 직접 따라감
        int hops = 0;
        while ((code == 301 || code == 302 || code == 307 || code == 308) && hops++ < 5) {
            String loc = con.getHeaderField("Location");
            con.disconnect();
            con = (HttpURLConnection) new URL(loc).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(60000);
            con.setRequestProperty("User-Agent", "JoaHUD-Updater");
            code = con.getResponseCode();
        }
        if (code == 404) throw new java.io.FileNotFoundException(url);
        if (code >= 400) throw new Exception("HTTP " + code);
        return con;
    }

    private static void finish(Callback cb, String msg) {
        busy = false;
        post(cb, msg);
    }

    private static void post(Callback cb, String msg) {
        lastMessage = msg;
        NavListenerService.log("업데이트: " + msg);
        if (cb != null) new Handler(Looper.getMainLooper()).post(() -> cb.on(msg));
    }

    /** 설치 결과: 사용자 확인이 필요하면 확인 화면을 띄움 */
    public static class InstallResultReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        c.startActivity(confirm);
                    } catch (Exception ignored) {
                    }
                }
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                lastMessage = "업데이트 완료";
            } else {
                String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                lastMessage = "설치 실패: " + m
                        + (st == PackageInstaller.STATUS_FAILURE_CONFLICT || st == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE
                        ? " (기존 앱을 한 번 삭제 후 새로 설치하세요)" : "");
                NavListenerService.log(lastMessage);
            }
        }
    }
}
