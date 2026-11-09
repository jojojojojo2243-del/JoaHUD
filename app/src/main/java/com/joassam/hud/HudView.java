package com.joassam.hud;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.Base64;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** HUD 화면 그리기: 큰 화살표 + 남은 거리 + 안내문구 + 속도 */
public class HudView extends View {

    private NavData nav;
    private long lastRecv = 0;
    private float speed = -1f;
    private int color = Color.rgb(0, 255, 102);
    private float speedScale = 1.8f; // 길안내 중 속도 글자 배율

    public void setSpeedScale(float s) {
        speedScale = s;
        invalidate();
    }

    private Bitmap iconBmp;
    private Bitmap frame;
    private long lastFrame = 0;
    private String iconSrc = "";

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.KOREA);

    public HudView(Context c) {
        super(c);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        setClickable(true);
    }

    public void setColor(int c) {
        color = c;
        invalidate();
    }

    public void setSpeed(float kmh) {
        speed = kmh;
        invalidate();
    }

    /** 티맵 화면 조각 수신 */
    public void setFrame(Bitmap b) {
        frame = b;
        lastFrame = System.currentTimeMillis();
        invalidate();
    }

    public void setNav(NavData d) {
        nav = d;
        lastRecv = System.currentTimeMillis();
        if (d.icon != null && !d.icon.isEmpty()) {
            if (!d.icon.equals(iconSrc)) {
                try {
                    byte[] b = Base64.decode(d.icon, Base64.DEFAULT);
                    iconBmp = BitmapFactory.decodeByteArray(b, 0, b.length);
                    iconSrc = d.icon;
                } catch (Exception e) {
                    iconBmp = null;
                }
            }
        } else {
            iconBmp = null;
            iconSrc = "";
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawColor(Color.BLACK);
        boolean landscape = w > h;
        long now = System.currentTimeMillis();
        boolean stale = lastRecv == 0 || now - lastRecv > 6000;
        boolean guiding = nav != null && nav.active && !stale;

        stroke.setColor(color);
        fill.setColor(color);

        boolean hasFrame = frame != null && now - lastFrame < 2500;

        // ── 가로 화면: 순정 HUD 스타일 (왼쪽 회전안내 · 가운데 큰 속도 · 위 시계) ──
        if (landscape) {
            drawLandscapeHud(c, w, h, guiding, stale, hasFrame);
            drawPublicAlert(c, w, h, true, guiding);
            return;
        }
        if (!landscape) {
            drawPortraitHud(c, w, h, guiding, stale, hasFrame);
            drawPublicAlert(c, w, h, false, guiding);
            return;
        }

        // 티맵 화면 전송 모드: 받은 안내판을 그대로 크게
        if (hasFrame) {
            float mx = w * 0.06f, top0 = h * 0.06f;
            float speedH = Math.min(h * 0.09f * speedScale, h * 0.35f);
            float bottom0 = h * 0.86f - speedH * 1.2f;
            float availW = w - 2 * mx, availH = bottom0 - top0;
            float sc = Math.min(availW / frame.getWidth(), availH / frame.getHeight());
            float fw = frame.getWidth() * sc, fh = frame.getHeight() * sc;
            float left = (w - fw) / 2f, top = top0 + (availH - fh) / 2f;
            bmpPaint.setColorFilter(null);
            c.drawBitmap(frame, null, new RectF(left, top, left + fw, top + fh), bmpPaint);
            drawSpeed(c, w / 2f, h * 0.86f, speedH, false);
            return;
        }

        if (!guiding) {
            drawSpeed(c, w / 2f, h * 0.55f, Math.min(w, h) * 0.45f, true);
            text.setColor(Color.GRAY);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(Math.min(w, h) * 0.06f);
            String msg = lastRecv == 0 ? "메인폰 신호 기다리는 중…" : (stale ? "신호 끊김" : "길안내 없음");
            c.drawText(msg + "   " + clock.format(new Date()), w / 2f, h * 0.82f, text);
            return;
        }

        {
            float sp = Math.min(h * 0.08f * speedScale, h * 0.2f);
            float shift = sp - h * 0.08f; // 속도가 커진 만큼 위 요소를 올림
            float size = Math.min(w * 0.7f, h * 0.36f);
            drawTurn(c, w / 2f, h * 0.24f - shift * 0.3f, size);
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(color);
            fitText(c, nav.dist, w / 2f, h * 0.55f - shift * 0.6f, w * 0.92f, h * 0.13f);
            text.setColor(Color.WHITE);
            fitText(c, nav.text, w / 2f, h * 0.64f - shift * 0.7f, w * 0.94f, h * 0.045f);
            text.setColor(Color.LTGRAY);
            fitText(c, nav.sub, w / 2f, h * 0.70f - shift * 0.75f, w * 0.94f, h * 0.035f);
            drawSpeed(c, w / 2f, h * 0.88f, sp, false);
        }
    }

    /**
     * 가로 HUD
     *  ┌──────────────────────────────────────┐
     *  │                               11:13  │
     *  │  ↰ 7.3km          64                 │
     *  │  교차로            📍 목적지           │
     *  └──────────────────────────────────────┘
     */
    private void drawLandscapeHud(Canvas c, int w, int h, boolean guiding, boolean stale, boolean hasFrame) {
        float pad = h * 0.08f;
        // 위: 시계 (오른쪽)
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.RIGHT);
        text.setTextSize(h * 0.10f);
        c.drawText(clock.format(new Date()), w - pad, pad + h * 0.08f, text);
        if (guiding && !remainText().isEmpty()) {
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(Color.LTGRAY);
            text.setTextSize(h * 0.09f);
            c.drawText(remainText(), pad, pad + h * 0.08f, text);
        }

        float splitX = w * 0.60f;          // 왼쪽 안내 영역 끝 (안내판 크게)
        float speedCx = w * 0.80f;         // 속도 가운데
        float speedSize = Math.min(h * 0.36f * speedScale / 1.8f, h * 0.50f);
        float speedBase = h * 0.56f;       // 오른쪽 아래 구석은 티맵 미니 창 자리로 비움

        if (hasFrame) {
            // 차량 화면에서 잘라온 안내 카드를 왼쪽에
            float top0 = h * 0.08f, bottom0 = h * 0.96f;
            float availW = splitX - pad * 1.2f, availH = bottom0 - top0;
            float sc = Math.min(availW / frame.getWidth(), availH / frame.getHeight());
            float fw = frame.getWidth() * sc, fh = frame.getHeight() * sc;
            float left = pad, top = top0 + (availH - fh) / 2f;
            bmpPaint.setColorFilter(null);
            c.drawBitmap(frame, null, new RectF(left, top, left + fw, top + fh), bmpPaint);
        } else if (guiding) {
            // 순정 HUD처럼 초록 안내 카드: [화살표 + 거리] / [회전 지점]
            RectF card = new RectF(pad, h * 0.22f, splitX - pad * 0.5f, h * 0.86f);
            Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            cardPaint.setColor(Color.rgb(46, 125, 79));
            c.drawRoundRect(card, h * 0.05f, h * 0.05f, cardPaint);
            int saved = color;
            color = Color.WHITE;           // 카드 안은 흰 글씨·흰 화살표
            stroke.setColor(Color.WHITE);
            fill.setColor(Color.WHITE);
            float arrow = card.height() * 0.48f;
            float ax = card.left + pad * 0.4f + arrow * 0.5f, ay = card.top + card.height() * 0.36f;
            boolean hasArrow = (nav.dir != null && !nav.dir.equals("NONE")) || iconBmp != null;
            if (hasArrow) drawTurn(c, ax, ay, arrow);
            float dx = hasArrow ? ax + arrow * 0.6f : card.left + pad * 0.5f;
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(Color.WHITE);
            fitText(c, nav.dist, dx, card.top + card.height() * 0.50f, card.right - dx - pad * 0.3f, card.height() * 0.36f);
            // 구분선 아래: 회전 지점 이름(교차로 등)
            Paint line = new Paint();
            line.setColor(Color.argb(120, 255, 255, 255));
            float ly = card.top + card.height() * 0.64f;
            c.drawLine(card.left + pad * 0.3f, ly, card.right - pad * 0.3f, ly, line);
            if (nav.nextDir != null && !nav.nextDir.isEmpty() && !nav.nextDir.equals("NONE") && nav.nextDist > 0) {
                // 다음다음 회전: 작은 화살표 + 거리 (순정 HUD 아래칸)
                String keepDir = nav.dir;
                nav.dir = nav.nextDir;
                float na = card.height() * 0.24f;
                drawTurn(c, card.left + pad * 0.5f + na * 0.5f, card.top + card.height() * 0.80f, na);
                nav.dir = keepDir;
                String nd = nav.nextDist >= 1000 ? String.format(Locale.KOREA, "%.1fkm", nav.nextDist / 1000f) : nav.nextDist + "m";
                fitText(c, nd, card.left + pad * 0.5f + na * 1.2f, card.top + card.height() * 0.88f,
                        card.width() - na * 1.4f - pad, card.height() * 0.2f);
            } else {
                fitText(c, nav.text, card.left + pad * 0.5f, card.top + card.height() * 0.88f,
                        card.width() - pad, card.height() * 0.2f);
            }
            color = saved;
            stroke.setColor(color);
            fill.setColor(color);
        } else {
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(Color.GRAY);
            text.setTextSize(h * 0.08f);
            String msg = lastRecv == 0 ? "신호 기다리는 중" : (stale ? "신호 끊김" : "길안내 없음");
            c.drawText(msg, pad, h * 0.55f, text);
        }

        // 가운데 큰 속도
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(speedColor());
        text.setTextSize(speedSize);
        String v = speed < 0 ? "--" : String.valueOf(Math.round(speed));
        c.drawText(v, speedCx, speedBase + speedSize * 0.35f, text);

        // 단속·위험 안내 문구가 있으면 오른쪽에 주황 삼각형 경고
        if (guiding && nav.limit > 0) drawLimit(c, w * 0.66f, h * 0.24f, h * 0.10f);
        if (guiding && nav.sdiDist > 0) drawCamera(c, w * 0.66f, h * 0.56f, h * 0.17f);
        String all = (nav != null ? nav.text + " " + nav.sub : "");
        if (guiding && nav.sdiDist <= 0 && nav.limit <= 0 && all.matches(".*(단속|카메라|과속|사고|위험|어린이|보호구역).*")) {
            float tx = w * 0.66f, ty = h * 0.45f, ts = h * 0.22f;
            Path tri = new Path();
            tri.moveTo(tx, ty - ts * 0.55f);
            tri.lineTo(tx + ts * 0.55f, ty + ts * 0.45f);
            tri.lineTo(tx - ts * 0.55f, ty + ts * 0.45f);
            tri.close();
            Paint warn = new Paint(Paint.ANTI_ALIAS_FLAG);
            warn.setColor(Color.rgb(255, 140, 0));
            c.drawPath(tri, warn);
            text.setColor(Color.BLACK);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(ts * 0.45f);
            c.drawText("!", tx, ty + ts * 0.32f, text);
        }

        // 속도 아래: 목적지
        if (guiding && !hasFrame && nav.sub != null && !nav.sub.isEmpty()) {
            text.setColor(Color.LTGRAY);
            text.setTextAlign(Paint.Align.CENTER);
            fitText(c, "◉ " + nav.sub, speedCx, h * 0.72f, w - splitX - pad, h * 0.07f);
        }
    }

    /**
     * 세로 HUD (순정 HUD 스타일)
     *        ↰
     *   859m | 교차로
     *      ╱  ▄▄  ╲        ← 차로 그래픽
     *     ╱  ◠◠◠   ╲
     *   ◉   95 km/h   ⚠
     *      목적지
     */
    private void drawPortraitHud(Canvas c, int w, int h, boolean guiding, boolean stale, boolean hasFrame) {
        float cx = w / 2f;
        float pad = w * 0.06f;

        // 맨 위 시계
        text.setTextAlign(Paint.Align.RIGHT);
        text.setColor(Color.GRAY);
        text.setTextSize(w * 0.06f);
        c.drawText(clock.format(new Date()), w - pad, h * 0.07f, text);
        if (guiding && !remainText().isEmpty()) {
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(Color.LTGRAY);
            c.drawText(remainText(), pad, h * 0.07f, text);
        }

        // ── 위: 회전 안내 ──
        if (hasFrame) {
            float top0 = h * 0.09f, bottom0 = h * 0.52f;
            float availW = w - 2 * pad, availH = bottom0 - top0;
            float sc = Math.min(availW / frame.getWidth(), availH / frame.getHeight());
            float fw = frame.getWidth() * sc, fh = frame.getHeight() * sc;
            float left = (w - fw) / 2f, top = top0 + (availH - fh) / 2f;
            bmpPaint.setColorFilter(null);
            c.drawBitmap(frame, null, new RectF(left, top, left + fw, top + fh), bmpPaint);
        } else if (guiding) {
            boolean hasArrow = (nav.dir != null && !nav.dir.equals("NONE")) || iconBmp != null;
            if (hasArrow) drawTurn(c, cx, h * 0.17f, w * 0.30f);
            // "859m | 교차로" 한 줄
            String line = nav.dist + (nav.text != null && !nav.text.isEmpty() ? "  |  " + nav.text : "");
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(Color.WHITE);
            fitText(c, line, cx, hasArrow ? h * 0.33f : h * 0.25f, w - 2 * pad, w * 0.10f);
        } else {
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(Color.GRAY);
            text.setTextSize(w * 0.065f);
            c.drawText(lastRecv == 0 ? "신호 기다리는 중" : (stale ? "신호 끊김" : "길안내 없음"), cx, h * 0.22f, text);
        }

        // ── 아래: 큰 속도 ──
        float sp = Math.min(w * 0.24f * speedScale / 1.8f + w * 0.06f, w * 0.38f);
        float sy = h * 0.70f;              // 아래 구석은 티맵 미니 창 자리로 비움
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(speedColor());
        text.setTextSize(sp);
        String v = speed < 0 ? "--" : String.valueOf(Math.round(speed));
        c.drawText(v, cx, sy, text);
        float vw = text.measureText(v);
        text.setTextSize(sp * 0.22f);
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText("km/h", cx + vw / 2f + sp * 0.05f, sy, text);

        // 오른쪽: 단속·위험 경고 삼각형
        if (guiding && nav.limit > 0) drawLimit(c, w - pad - w * 0.08f, sy - sp * 0.35f, w * 0.08f);
        if (guiding && nav.sdiDist > 0) drawCamera(c, pad + w * 0.10f, sy - sp * 0.35f, w * 0.13f);
        String all = (nav != null ? nav.text + " " + nav.sub : "");
        if (guiding && nav.sdiDist <= 0 && nav.limit <= 0 && all.matches(".*(단속|카메라|과속|사고|위험|어린이|보호구역).*")) {
            float tx = w - pad - w * 0.06f, ty = sy - sp * 0.3f, ts = w * 0.13f;
            Path tri = new Path();
            tri.moveTo(tx, ty - ts * 0.55f);
            tri.lineTo(tx + ts * 0.55f, ty + ts * 0.45f);
            tri.lineTo(tx - ts * 0.55f, ty + ts * 0.45f);
            tri.close();
            Paint warn = new Paint(Paint.ANTI_ALIAS_FLAG);
            warn.setColor(Color.rgb(255, 140, 0));
            c.drawPath(tri, warn);
            text.setColor(Color.BLACK);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(ts * 0.5f);
            c.drawText("!", tx, ty + ts * 0.35f, text);
        }

        // 맨 아래: 목적지
        if (guiding && nav.sub != null && !nav.sub.isEmpty()) {
            text.setColor(Color.LTGRAY);
            text.setTextAlign(Paint.Align.CENTER);
            if (!hasFrame) fitText(c, "◉ " + nav.sub, cx, h * 0.78f, w - 2 * pad, w * 0.05f);
        }
    }

    /** 남은 시간·거리 "00시간18분  13km" */
    private String remainText() {
        if (nav == null || nav.remainDist <= 0) return "";
        int t = nav.remainTime / 60;
        String time = String.format(Locale.KOREA, "%02d시간%02d분", t / 60, t % 60);
        String dist = nav.remainDist >= 1000
                ? String.format(Locale.KOREA, "%.1fkm", nav.remainDist / 1000f) : nav.remainDist + "m";
        return time + "   " + dist;
    }

    /** 지금 적용되는 제한속도 (티맵 공식 연동 → 공공데이터 순) */
    private int effLimit() {
        if (nav != null && nav.active && nav.limit > 0 && System.currentTimeMillis() - lastRecv < 6000) return nav.limit;
        Alerts.Hit a = Alerts.now();
        return a != null ? a.limit : 0;
    }

    /** 제한속도를 넘으면 속도 숫자를 빨갛게 깜빡임 */
    private int speedColor() {
        int lim = effLimit();
        if (lim > 0 && speed > lim + 2) {
            return (System.currentTimeMillis() / 400) % 2 == 0 ? Color.rgb(255, 40, 40) : Color.rgb(255, 150, 150);
        }
        return Color.WHITE;
    }

    /** 공공데이터 경고: 단속카메라(거리) · 제한속도 · 어린이보호구역 */
    private void drawPublicAlert(Canvas c, int w, int h, boolean landscape, boolean guiding) {
        // 티맵 공식 연동 정보가 있으면 그쪽을 그대로 씀
        if (guiding && (nav.limit > 0 || nav.sdiDist > 0)) return;
        Alerts.Hit a = Alerts.now();
        if (a == null) return;
        float limX, limY, limR, camX, camY, camS, labX, labY, labS;
        if (landscape) {
            limX = w * 0.66f; limY = h * 0.24f; limR = h * 0.10f;
            camX = w * 0.66f; camY = h * 0.56f; camS = h * 0.17f;
            labX = w * 0.80f; labY = h * 0.72f; labS = h * 0.075f;
        } else {
            float sp = Math.min(w * 0.24f * speedScale / 1.8f + w * 0.06f, w * 0.38f);
            float sy = h * 0.70f, pad = w * 0.05f;
            limX = w - pad - w * 0.08f; limY = sy - sp * 0.35f; limR = w * 0.08f;
            camX = pad + w * 0.10f; camY = sy - sp * 0.35f; camS = w * 0.13f;
            labX = w / 2f; labY = h * 0.59f; labS = w * 0.06f;
        }
        int keepLimit = 0, keepDist = 0;
        boolean temp = nav == null;
        if (!temp) {
            keepLimit = nav.limit;
            keepDist = nav.sdiDist;
        } else {
            nav = new NavData();
        }
        nav.limit = a.limit;
        nav.sdiDist = a.dist;
        if (a.limit > 0) drawLimit(c, limX, limY, limR);
        drawCamera(c, camX, camY, camS);
        nav.limit = keepLimit;
        nav.sdiDist = keepDist;
        if (temp) nav = null;
        if (a.school || a.section) {
            String label = a.school ? "어린이보호구역" : "구간단속";
            text.setTextSize(labS);
            text.setTextAlign(Paint.Align.CENTER);
            float tw = text.measureText(label);
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(a.school ? Color.rgb(255, 200, 0) : Color.rgb(255, 140, 0));
            c.drawRoundRect(new RectF(labX - tw / 2f - labS * 0.4f, labY - labS * 1.05f,
                    labX + tw / 2f + labS * 0.4f, labY + labS * 0.35f), labS * 0.3f, labS * 0.3f, bg);
            text.setColor(Color.BLACK);
            c.drawText(label, labX, labY, text);
        }
    }

    /** 제한속도 표지판 (빨간 테두리 원) */
    private void drawLimit(Canvas c, float cx, float cy, float r) {
        if (nav == null || nav.limit <= 0) return;
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setColor(Color.WHITE);
        c.drawCircle(cx, cy, r, ring);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(r * 0.22f);
        ring.setColor(Color.rgb(220, 30, 30));
        c.drawCircle(cx, cy, r * 0.89f, ring);
        // 제한속도 넘으면 숫자도 빨갛게
        text.setColor(speed > nav.limit + 2 ? Color.rgb(220, 30, 30) : Color.BLACK);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(r * (nav.limit >= 100 ? 0.85f : 1.0f));
        c.drawText(String.valueOf(nav.limit), cx, cy + r * 0.35f, text);
    }

    /** 단속 카메라 경고 (주황 삼각형 + 남은 거리) */
    private void drawCamera(Canvas c, float cx, float cy, float s) {
        if (nav == null || nav.sdiDist <= 0) return;
        Path tri = new Path();
        tri.moveTo(cx, cy - s * 0.55f);
        tri.lineTo(cx + s * 0.55f, cy + s * 0.45f);
        tri.lineTo(cx - s * 0.55f, cy + s * 0.45f);
        tri.close();
        Paint warn = new Paint(Paint.ANTI_ALIAS_FLAG);
        warn.setColor(Color.rgb(255, 140, 0));
        c.drawPath(tri, warn);
        text.setColor(Color.BLACK);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(s * 0.45f);
        c.drawText("!", cx, cy + s * 0.32f, text);
        text.setColor(Color.rgb(255, 170, 60));
        text.setTextSize(s * 0.32f);
        c.drawText(nav.sdiDist + "m", cx, cy + s * 0.9f, text);
    }

    private void drawSpeed(Canvas c, float cx, float baseline, float size, boolean big) {
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(big ? color : speedColor());
        text.setTextSize(size);
        String v = speed < 0 ? "--" : String.valueOf(Math.round(speed));
        c.drawText(v, cx, baseline, text);
        float vw = text.measureText(v);
        text.setTextSize(size * 0.25f);
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText("km/h", cx + vw / 2f + size * 0.05f, baseline, text);
    }

    /** 글자가 maxW를 넘으면 줄여서, 그래도 넘으면 잘라서 그림 */
    private void fitText(Canvas c, String s, float x, float baseline, float maxW, float size) {
        if (s == null || s.isEmpty()) return;
        float ts = size;
        text.setTextSize(ts);
        while (text.measureText(s) > maxW && ts > size * 0.2f) {
            ts *= 0.92f;
            text.setTextSize(ts);
        }
        String out = s;
        // 짧은 글자(거리 등)는 자르지 않고 줄이기만 함
        if (s.length() <= 10) {
            while (text.measureText(s) > maxW && ts > 8) {
                ts *= 0.9f;
                text.setTextSize(ts);
            }
            c.drawText(s, x, baseline, text);
            return;
        }
        while (out.length() > 1 && text.measureText(out + "…") > maxW) {
            out = out.substring(0, out.length() - 1);
        }
        if (!out.equals(s)) out = out + "…";
        c.drawText(out, x, baseline, text);
    }

    private void drawTurn(Canvas c, float cx, float cy, float size) {
        String d = nav.dir == null ? "NONE" : nav.dir;
        stroke.setStrokeWidth(size * 0.13f);
        c.save();
        c.translate(cx, cy);
        c.scale(size, size);
        stroke.setStrokeWidth(0.13f);
        boolean mirror = d.equals("LEFT") || d.equals("KEEP_LEFT");
        if (mirror) c.scale(-1f, 1f);
        switch (d) {
            case "LEFT":
            case "RIGHT":
                arrow(c, new float[]{-0.15f, 0.5f, -0.15f, -0.05f, 0.45f, -0.05f});
                break;
            case "KEEP_LEFT":
            case "KEEP_RIGHT":
                arrow(c, new float[]{-0.1f, 0.5f, -0.1f, 0.05f, 0.35f, -0.4f});
                break;
            case "STRAIGHT":
                arrow(c, new float[]{0f, 0.5f, 0f, -0.45f});
                break;
            case "UTURN": {
                // 오른쪽에서 올라가 왼쪽으로 내려오는 유턴
                int n = 16;
                float[] pts = new float[(n + 3) * 2];
                int k = 0;
                pts[k++] = 0.25f;
                pts[k++] = 0.5f;
                for (int i = 0; i <= n; i++) {
                    double a = Math.PI * i / n; // 0 → π
                    pts[k++] = (float) (0.25 * Math.cos(a));
                    pts[k++] = (float) (-0.1 - 0.25 * Math.sin(a));
                }
                pts[k++] = -0.25f;
                pts[k] = 0.25f;
                arrow(c, pts);
                break;
            }
            case "ROUND":
                c.drawCircle(0f, -0.02f, 0.2f, stroke);
                stroke.setStrokeCap(Paint.Cap.ROUND);
                c.drawLine(0f, 0.5f, 0f, 0.18f, stroke);
                arrow(c, new float[]{0.14f, -0.16f, 0.4f, -0.42f});
                break;
            case "ARRIVE": {
                Path pin = new Path();
                pin.addCircle(0f, -0.15f, 0.25f, Path.Direction.CW);
                pin.moveTo(-0.2f, 0f);
                pin.lineTo(0f, 0.45f);
                pin.lineTo(0.2f, 0f);
                pin.close();
                c.drawPath(pin, fill);
                Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
                hole.setColor(Color.BLACK);
                c.drawCircle(0f, -0.15f, 0.1f, hole);
                break;
            }
            default:
                c.restore();
                drawIcon(c, cx, cy, size);
                return;
        }
        c.restore();
    }

    /** 내비 앱이 보낸 화살표 아이콘을 HUD 색으로 칠해서 그림 */
    private void drawIcon(Canvas c, float cx, float cy, float size) {
        if (iconBmp == null) return;
        float r = Color.red(color) / 255f, g = Color.green(color) / 255f, b = Color.blue(color) / 255f;
        // 밝기 → 지정 색, 알파 유지
        ColorMatrix m = new ColorMatrix(new float[]{
                r * 0.33f, r * 0.33f, r * 0.33f, 0, 0,
                g * 0.33f, g * 0.33f, g * 0.33f, 0, 0,
                b * 0.33f, b * 0.33f, b * 0.33f, 0, 0,
                0, 0, 0, 1, 0});
        bmpPaint.setColorFilter(new ColorMatrixColorFilter(m));
        float half = size / 2f;
        c.drawBitmap(iconBmp, null, new RectF(cx - half, cy - half, cx + half, cy + half), bmpPaint);
    }

    /** 단위 좌표(-0.5~0.5) 폴리라인 + 끝에 삼각 화살촉 */
    private void arrow(Canvas c, float[] p) {
        int n = p.length / 2;
        float ex = p[(n - 1) * 2], ey = p[(n - 1) * 2 + 1];
        float px = p[(n - 2) * 2], py = p[(n - 2) * 2 + 1];
        float dx = ex - px, dy = ey - py;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len == 0) len = 1;
        dx /= len;
        dy /= len;
        float headLen = 0.28f, headW = 0.36f;

        Path line = new Path();
        line.moveTo(p[0], p[1]);
        for (int i = 1; i < n - 1; i++) line.lineTo(p[i * 2], p[i * 2 + 1]);
        line.lineTo(ex - dx * headLen * 0.6f, ey - dy * headLen * 0.6f);
        c.drawPath(line, stroke);

        float bx = ex - dx * headLen, by = ey - dy * headLen;
        float nx = -dy, ny = dx;
        Path head = new Path();
        head.moveTo(ex, ey);
        head.lineTo(bx + nx * headW / 2f, by + ny * headW / 2f);
        head.lineTo(bx - nx * headW / 2f, by - ny * headW / 2f);
        head.close();
        c.drawPath(head, fill);
    }
}
