package com.joassam.hud;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 엑셀(.xlsx) 또는 CSV에서 "이름, 주소" 두 칸을 읽어 자주 가는 곳으로 등록.
 * A열 = 이름, B열 = 주소. 첫 줄이 "이름/주소" 같은 제목이면 건너뜀.
 */
final class FavImport {

    private FavImport() {
    }

    /** 깨진 글자·제어문자가 섞였거나 너무 긴 값 */
    static boolean broken(String s) {
        if (s.length() > 120) return true;
        for (char c : s.toCharArray()) {
            if (c == '\uFFFD' || (c < 0x20 && c != '\t')) return true;
            // 한글·영문·숫자·흔한 기호 외의 한자 등이 섞이면 깨진 것으로 봄
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) return true;
        }
        return false;
    }

    static List<String[]> parse(byte[] data, String fileName) throws Exception {
        // 옛 엑셀(.xls) 파일은 못 읽음 → 안내
        if (data.length > 8 && (data[0] & 0xff) == 0xD0 && (data[1] & 0xff) == 0xCF && (data[2] & 0xff) == 0x11 && (data[3] & 0xff) == 0xE0) {
            throw new Exception("옛 엑셀(.xls) 파일이에요. 엑셀에서 '다른 이름으로 저장 → Excel 통합 문서(.xlsx)'로 저장한 뒤 불러와 주세요");
        }
        boolean zip = data.length > 4 && data[0] == 'P' && data[1] == 'K';
        List<String[]> rows = zip ? xlsx(data) : csv(data);
        List<String[]> out = new ArrayList<>();
        int bad = 0;
        for (String[] r : rows) for (String v : r) if (v != null && broken(v)) bad++;
        if (!zip && bad > rows.size() / 2 && bad > 2) throw new Exception("파일 글자가 깨져서 읽을 수 없어요. 엑셀 .xlsx 또는 CSV(UTF-8)로 저장해 주세요");
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            String n = r.length > 0 && r[0] != null ? r[0].trim() : "";
            String a = r.length > 1 && r[1] != null ? r[1].trim() : "";
            if (n.isEmpty() || a.isEmpty()) continue;
            if (i == 0 && (n.contains("이름") || n.contains("장소") || a.contains("주소"))) continue; // 제목 줄
            if (broken(n) || broken(a)) continue;
            n = n.replace("=", " ").replace("\n", " ");
            a = a.replace("\n", " ");
            out.add(new String[]{n, a});
        }
        return out;
    }

    /** 기존 목록 + 새 목록 (같은 이름은 새 주소로 바꿈), "이름=주소" 줄 글로 */
    static String merge(String existing, List<String[]> add) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : existing.split("\n")) {
            int i = line.indexOf('=');
            if (i > 0) m.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
        }
        for (String[] r : add) m.put(r[0], r[1]);
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (b.length() > 0) b.append('\n');
            b.append(e.getKey()).append('=').append(e.getValue());
        }
        return b.toString();
    }

    // ───────── CSV ─────────
    private static List<String[]> csv(byte[] data) {
        String s = new String(data, StandardCharsets.UTF_8);
        if (s.indexOf('�') >= 0) {
            try {
                s = new String(data, "MS949"); // 한글 엑셀이 저장한 CSV
            } catch (Exception ignored) {
            }
        }
        if (s.startsWith("﻿")) s = s.substring(1);
        List<String[]> rows = new ArrayList<>();
        for (String line : s.split("\r?\n")) {
            if (line.trim().isEmpty()) continue;
            rows.add(splitCsv(line));
        }
        return rows;
    }

    private static String[] splitCsv(String line) {
        List<String> f = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        char sep = line.indexOf('\t') >= 0 && line.indexOf(',') < 0 ? '\t' : ',';
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (q) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') q = false;
                else cur.append(c);
            } else if (c == '"') q = true;
            else if (c == sep) {
                f.add(cur.toString());
                cur.setLength(0);
            } else cur.append(c);
        }
        f.add(cur.toString());
        return f.toArray(new String[0]);
    }

    // ───────── XLSX (첫 번째 시트) ─────────
    private static List<String[]> xlsx(byte[] data) throws Exception {
        String shared = null, sheet = null, firstSheetName = null;
        Map<String, String> sheets = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName();
                if (n.equals("xl/sharedStrings.xml")) shared = read(z);
                else if (n.startsWith("xl/worksheets/sheet") && n.endsWith(".xml")) sheets.put(n, read(z));
            }
        }
        if (sheets.isEmpty()) throw new Exception("엑셀 시트를 찾지 못했어요");
        sheet = sheets.containsKey("xl/worksheets/sheet1.xml") ? sheets.get("xl/worksheets/sheet1.xml") : sheets.values().iterator().next();

        List<String> strings = new ArrayList<>();
        if (shared != null) {
            Matcher si = Pattern.compile("<si>(.*?)</si>", Pattern.DOTALL).matcher(shared);
            Pattern t = Pattern.compile("<t[^>]*>(.*?)</t>", Pattern.DOTALL);
            while (si.find()) {
                Matcher tm = t.matcher(si.group(1));
                StringBuilder b = new StringBuilder();
                while (tm.find()) b.append(tm.group(1));
                strings.add(unescape(b.toString()));
            }
        }
        List<String[]> rows = new ArrayList<>();
        Matcher row = Pattern.compile("<row[^>]*>(.*?)</row>", Pattern.DOTALL).matcher(sheet);
        Pattern cell = Pattern.compile("<c\\s([^>]*?)(?:/>|>(.*?)</c>)", Pattern.DOTALL);
        Pattern ref = Pattern.compile("r=\"([A-Z]+)\\d+\"");
        Pattern typ = Pattern.compile("t=\"([a-zA-Z]+)\"");
        Pattern val = Pattern.compile("<v>(.*?)</v>", Pattern.DOTALL);
        Pattern inl = Pattern.compile("<t[^>]*>(.*?)</t>", Pattern.DOTALL);
        while (row.find()) {
            String[] r = new String[2];
            Matcher c = cell.matcher(row.group(1));
            int idx = 0;
            while (c.find()) {
                String attrs = c.group(1), body = c.group(2) == null ? "" : c.group(2);
                Matcher rm = ref.matcher(attrs);
                int col = rm.find() ? colIndex(rm.group(1)) : idx;
                idx = col + 1;
                if (col > 1) continue;
                Matcher tm = typ.matcher(attrs);
                String type = tm.find() ? tm.group(1) : "";
                String v;
                if (type.equals("inlineStr")) {
                    Matcher im = inl.matcher(body);
                    StringBuilder b = new StringBuilder();
                    while (im.find()) b.append(im.group(1));
                    v = unescape(b.toString());
                } else {
                    Matcher vm = val.matcher(body);
                    v = vm.find() ? vm.group(1) : "";
                    if (type.equals("s")) {
                        try {
                            v = strings.get(Integer.parseInt(v.trim()));
                        } catch (Exception ex) {
                            v = "";
                        }
                    } else {
                        v = unescape(v);
                    }
                }
                r[col] = v;
            }
            rows.add(r);
        }
        return rows;
    }

    private static int colIndex(String letters) {
        int n = 0;
        for (char ch : letters.toCharArray()) n = n * 26 + (ch - 'A' + 1);
        return n - 1;
    }

    private static String unescape(String s) {
        // &#51665; / &#xD558; 같은 숫자 표기도 글자로
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int cp;
            try {
                cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
            } catch (NumberFormatException e) {
                cp = '?';
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        s = sb.toString();
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        return o.toString("UTF-8");
    }
}
