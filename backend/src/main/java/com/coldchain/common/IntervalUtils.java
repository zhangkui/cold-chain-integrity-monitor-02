package com.coldchain.common;

import java.util.Comparator;
import java.util.List;

/** 时间区间（epochSecond）并集计算：避免重叠的超温/离线区间被重复扣减 */
public final class IntervalUtils {

    private IntervalUtils() {
    }

    /** 区间并集总长度（秒）；区间为 [start, end)，自动忽略负长度 */
    public static long unionLength(List<long[]> intervals) {
        List<long[]> valid = intervals.stream()
                .filter(iv -> iv.length == 2 && iv[1] > iv[0])
                .sorted(Comparator.comparingLong(a -> a[0]))
                .toList();
        if (valid.isEmpty()) {
            return 0;
        }
        long total = 0;
        long curStart = valid.get(0)[0];
        long curEnd = valid.get(0)[1];
        for (int i = 1; i < valid.size(); i++) {
            long[] iv = valid.get(i);
            if (iv[0] <= curEnd) {
                curEnd = Math.max(curEnd, iv[1]);
            } else {
                total += curEnd - curStart;
                curStart = iv[0];
                curEnd = iv[1];
            }
        }
        total += curEnd - curStart;
        return total;
    }

    /** 两区间重叠长度（秒） */
    public static long overlap(long aStart, long aEnd, long bStart, long bEnd) {
        long lo = Math.max(aStart, bStart);
        long hi = Math.min(aEnd, bEnd);
        return Math.max(0, hi - lo);
    }
}
