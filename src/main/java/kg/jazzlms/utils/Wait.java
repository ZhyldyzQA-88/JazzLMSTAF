package kg.jazzlms.utils;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * События между сервисами идут через Kafka асинхронно: notification, analytics и gamification
 * видят изменение не сразу. Вместо Thread.sleep опрашиваем, пока условие не выполнится.
 */
public class Wait {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    private static final long POLL_MS = 500;

    public static <T> T until(String what, Supplier<T> supplier, java.util.function.Predicate<T> condition) {
        return until(what, DEFAULT_TIMEOUT, supplier, condition);
    }

    public static <T> T until(String what, Duration timeout, Supplier<T> supplier, java.util.function.Predicate<T> condition) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        T last = null;
        while (System.currentTimeMillis() < deadline) {
            last = supplier.get();
            if (condition.test(last)) {
                return last;
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Не дождались за " + timeout.getSeconds() + " с: " + what + ". Последнее значение: " + last);
    }
}
