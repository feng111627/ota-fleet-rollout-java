package com.github.feng111627.otafleetrolloutjava.compat;

/**
 * 点分数字版本号比较器，支持如 {@code 1}、{@code 1.2}、{@code 1.2.10}、
 * {@code 2.0.0-beta} 这类形式：每一段按数字比较，数字段长度不同时短段补 0；
 * 若某一段不是纯数字（预发布后缀），退化为字符串比较，避免解析异常。
 */
public final class VersionComparator {

    private VersionComparator() {
    }

    /**
     * 比较两个版本号。
     *
     * @return 负数表示 v1 旧于 v2，0 表示相同，正数表示 v1 新于 v2
     */
    public static int compare(String v1, String v2) {
        String[] a = v1.trim().split("\\.");
        String[] b = v2.trim().split("\\.");
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            String sa = i < a.length ? a[i] : "0";
            String sb = i < b.length ? b[i] : "0";
            int cmp;
            if (sa.chars().allMatch(Character::isDigit)
                    && sb.chars().allMatch(Character::isDigit)) {
                cmp = Long.compare(Long.parseLong(sa), Long.parseLong(sb));
            } else {
                cmp = sa.compareTo(sb);
            }
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    /** v1 是否低于 v2 */
    public static boolean isLessThan(String v1, String v2) {
        return compare(v1, v2) < 0;
    }
}
