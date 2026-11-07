package com.lanlink.shopping.module.notify;

/** 重投节奏：指数退避 + 上限 + 最大次数，防一次 broker 故障后无限重投。 */
public final class NotifyOutboxPolicy {

    public static final int MAX_RETRY = 8;
    private static final long BASE_SECONDS = 30;
    private static final long CAP_SECONDS = 3600;

    private NotifyOutboxPolicy() {
    }

    public static int backoffSeconds(int retryCount) {
        if (retryCount <= 0) {
            return (int) BASE_SECONDS;
        }
        long shift = Math.min(retryCount, 16);
        long seconds = BASE_SECONDS << shift;
        return (int) Math.min(seconds, CAP_SECONDS);
    }

    public static boolean exhausted(int nextRetryCount) {
        return nextRetryCount >= MAX_RETRY;
    }
}
