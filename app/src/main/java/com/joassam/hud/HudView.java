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

        // 티맵 화면 전송 모드: 받은 안내판을 그대로 크게
        if (frame != null && now - lastFrame < 2500) {
            // 화면 가장자리(둥근 모서리·곡면)에 잘리지 않게 안쪽 영역만 사용
            float mx = w * 0.06f, top0 = h * 0.06f;
            float speedH = landscape ? h * 0.16f : h * 0.09f;
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
            // 길안내 없음: 속도 크게 + 상태
            drawSpeed(c, w / 2f, h * 0.55f, Math.min(w, h) * 0.45f, true);
            text.setColor(Color.GRAY);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(Math.min(w, h) * 0.06f);
            String msg = lastRecv == 0 ? "메인폰 신호 기다리는 중…" : (stale ? "신호 끊김" : "길안내 없음");
            c.drawText(msg + "   " + clock.format(new Date()), w / 2f, h * 0.82f, text);
            return;
        }

        if (landscape) {
            float size = Math.min(w * 0.42f, h * 0.8f);
            drawTurn(c, w * 0.25f, h * 0.48f, size);
            float x = w * 0.5f, right = w * 0.97f;
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(color);
            fitText(c, nav.dist, x, h * 0.45f, right - x, h * 0.36f);
            text.setColor(Color.WHITE);
            fitText(c, nav.text, x, h * 0.63f, right - x, h * 0.1f);
            text.setColor(Color.LTGRAY);
            fitText(c, nav.sub, x, h * 0.76f, right - x, h * 0.075f);
            drawSpeed(c, w * 0.82f, h * 0.86f, h * 0.15f, false);
        } else {
            float size = Math.min(w * 0.75f, h * 0.42f);
            drawTurn(c, w / 2f, h * 0.27f, size);
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(color);
            fitText(c, nav.dist, w / 2f, h * 0.62f, w * 0.92f, h * 0.15f);
            text.setColor(Color.WHITE);
            fitText(c, nav.text, w / 2f, h * 0.72f, w * 0.94f, h * 0.05f);
            text.setColor(Color.LTGRAY);
            fitText(c, nav.sub, w / 2f, h * 0.78f, w * 0.94f, h * 0.04f);
            drawSpeed(c, w / 2f, h * 0.87f, h * 0.08f, false);
        }
    }

    private void drawSpeed(Canvas c, float cx, float baseline, float size, boolean big) {
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(big ? color : Color.WHITE);
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
