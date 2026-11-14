package com.joassam.hud;

import java.util.ArrayList;
import java.util.List;

/**
 * 음성으로 들은 말 → 자주 가는 곳 고르기 (안드로이드 없이도 시험 가능한 순수 자바)
 */
final class VoicePick {

    private VoicePick() {
    }

    /** "집으로 가자", "농장 안내해줘" → "집", "농장" */
    static String clean(String s) {
        if (s == null) return "";
        return s.trim()
                .replaceAll("(으로|로)?\\s*(가자|가줘|가 줘|가요|갈래|안내해 ?줘|안내해 ?주세요|안내|길안내|가주세요|가 주세요)\\s*$", "")
                .replaceAll("[.!?~]+$", "")
                .trim();
    }

    private static String ns(String s) {
        return s.replace(" ", "");
    }

    /** "1번", "일번", "첫 번째", "하나", "두번째" → 1, 2 … (모르면 -1) */
    static int number(String said) {
        if (said == null) return -1;
        String s = ns(said);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(s);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        String[][] words = {
                {"1", "일번", "첫번", "첫째", "하나", "한번", "일"},
                {"2", "이번", "두번", "둘째", "둘", "이"},
                {"3", "삼번", "세번", "셋째", "셋", "삼"},
                {"4", "사번", "네번", "넷째", "넷", "사"},
                {"5", "오번", "다섯", "오"},
                {"6", "육번", "여섯", "육"},
                {"7", "칠번", "일곱", "칠"},
                {"8", "팔번", "여덟", "팔"},
                {"9", "구번", "아홉", "구"},
        };
        // 긴 표현 먼저 (예: "일번"), 한 글자("일")는 말 전체가 그 글자일 때만
        for (String[] w : words) {
            for (int i = 1; i < w.length; i++) {
                if (w[i].length() >= 2 && s.contains(w[i])) return Integer.parseInt(w[0]);
            }
        }
        for (String[] w : words) {
            String last = w[w.length - 1];
            if (s.equals(last) || s.equals(last + "번")) return Integer.parseInt(w[0]);
        }
        return -1;
    }

    /** 한글을 자음·모음으로 풀기 */
    static String jamo(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : ns(s).toCharArray()) {
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

    static double similar(String a, String b) {
        String x = jamo(a), y = jamo(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        return 1.0 - (double) lev(x, y) / Math.max(x.length(), y.length());
    }

    /**
     * 들은 말(여러 후보)과 저장된 이름들 비교 → 고를 만한 이름 목록 (가장 그럴듯한 순).
     * 1개면 바로 안내, 2개 이상이면 번호로 물어보기, 0개면 모르는 곳(주소·장소로 검색).
     */
    static List<String> candidates(List<String> names, List<String> heard) {
        List<String> out = new ArrayList<>();
        if (names.isEmpty() || heard == null || heard.isEmpty()) return out;
        // 1) 이름과 똑같이 말함 → 그 하나
        for (String h : heard) {
            String c = ns(clean(h));
            for (String n : names) if (ns(n).equals(c)) return single(n);
        }
        // 2) 말 안에 이름이 통째로 들어 있음 → 가장 긴 이름 하나 ("하레하레 중촌점으로" → "하레하레 중촌점")
        String bestContained = null;
        for (String h : heard) {
            String c = ns(clean(h));
            for (String n : names) {
                String nn = ns(n);
                if (nn.length() >= 1 && c.contains(nn) && (bestContained == null || nn.length() > ns(bestContained).length())) {
                    bestContained = n;
                }
            }
        }
        // 3) 이름 안에 말이 들어 있음 ("하레하레" → 본점·중촌점 둘 다)
        List<String> containing = new ArrayList<>();
        for (String h : heard) {
            String c = ns(clean(h));
            if (c.length() < 2) continue;
            for (String n : names) if (ns(n).contains(c) && !containing.contains(n)) containing.add(n);
        }
        if (bestContained != null && (containing.isEmpty() || containing.size() == 1 && containing.get(0).equals(bestContained))) {
            return single(bestContained);
        }
        if (!containing.isEmpty()) {
            if (bestContained != null && !containing.contains(bestContained)) containing.add(0, bestContained);
            return containing.size() > 5 ? new ArrayList<>(containing.subList(0, 5)) : containing;
        }
        if (bestContained != null) return single(bestContained);
        // 4) 낱말이 겹치는 이름 ("화요일에 베이커리" → "하레하레베이커리카페": '베이커리'가 겹침)
        //    앞말을 잘못 알아들어도 뒷말이 맞으면 찾음. 더 많이(길게) 겹치는 이름 우선
        final java.util.Map<String, Integer> overlap = new java.util.HashMap<>();
        for (String h : heard) {
            for (String w : clean(h).split("[\\s,.]+")) {
                w = w.replaceAll("(으로|로|에|에서|의|은|는|이|가|을|를)$", "");
                if (w.length() < 2) continue;
                for (String n : names) {
                    if (ns(n).contains(w)) {
                        int o = overlap.getOrDefault(n, 0);
                        if (w.length() > o) overlap.put(n, w.length());
                    }
                }
            }
        }
        if (!overlap.isEmpty()) {
            int best = 0;
            for (int v : overlap.values()) best = Math.max(best, v);
            List<String> ov = new ArrayList<>();
            for (String n : names) if (overlap.getOrDefault(n, 0) == best) ov.add(n);
            return ov.size() > 5 ? new ArrayList<>(ov.subList(0, 5)) : ov;
        }
        // 5) 발음이 비슷한 이름 (잘못 알아들은 경우)
        final java.util.Map<String, Double> score = new java.util.HashMap<>();
        for (String h : heard) {
            String c = clean(h);
            for (String n : names) {
                double s = similar(c, n);
                // 앞부분만 비슷해도 인정 ("하래하래" ↔ "하레하레 본점")
                String nn = ns(n), cc = ns(c);
                if (cc.length() >= 2 && nn.length() > cc.length()) s = Math.max(s, similar(cc, nn.substring(0, cc.length())) - 0.05);
                if (s > score.getOrDefault(n, 0.0)) score.put(n, s);
            }
        }
        double top = 0;
        for (double v : score.values()) top = Math.max(top, v);
        if (top < 0.6) return out;
        for (String n : names) {
            Double v = score.get(n);
            if (v != null && v >= 0.6 && v >= top - 0.12) out.add(n);
        }
        out.sort((a, b) -> Double.compare(score.get(b), score.get(a)));
        return out.size() > 5 ? new ArrayList<>(out.subList(0, 5)) : out;
    }

    private static List<String> single(String n) {
        List<String> l = new ArrayList<>();
        l.add(n);
        return l;
    }
}
