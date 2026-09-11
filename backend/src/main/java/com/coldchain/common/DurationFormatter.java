package com.coldchain.common;

/** 时长统一展示口径（后端评估摘要与前端保持一致的中文格式） */
public final class DurationFormatter {

    private DurationFormatter() {
    }

    public static String chinese(long seconds) {
        long s = Math.max(0, seconds);
        long d = s / 86400;
        long h = (s % 86400) / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("天");
        if (h > 0) sb.append(h).append("小时");
        if (m > 0) sb.append(m).append("分");
        if (d == 0 && h == 0 && m == 0) sb.append(sec).append("秒");
        return sb.toString();
    }
}
