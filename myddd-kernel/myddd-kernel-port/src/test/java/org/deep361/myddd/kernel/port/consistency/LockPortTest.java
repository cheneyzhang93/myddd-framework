package org.deep361.myddd.kernel.port.consistency;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class LockPortTest {

    static final class RecordingLockPort implements LockPort {
        final List<String> calls = new ArrayList<>();
        final List<Object> results = new ArrayList<>();

        @Override
        public <T> T executeLocked(String key, Duration waitTime, Duration leaseTime, Supplier<T> action) {
            calls.add(key + "|" + waitTime + "|" + leaseTime);
            T result = action.get();
            results.add(result);
            return result;
        }
    }

    @Test
    void should_execute_action_under_lock_with_explicit_timings() {
        RecordingLockPort port = new RecordingLockPort();

        String result = port.executeLocked("order:1", Duration.ofMillis(100), Duration.ofSeconds(5), () -> "ok");

        assertThat(result).isEqualTo("ok");
        assertThat(port.calls).containsExactly("order:1|PT0.1S|PT5S");
        assertThat(port.results).containsExactly("ok");
    }

    @Test
    void default_overload_should_use_zero_wait_and_thirty_seconds_lease() {
        RecordingLockPort port = new RecordingLockPort();

        assertThat(port.executeLocked("order:1", () -> "ok")).isEqualTo("ok");
        assertThat(port.calls).containsExactly("order:1|PT0S|PT30S");
        assertThat(port.results).containsExactly("ok");
    }

    @Test
    void run_locked_should_delegate_and_tolerate_void_action() {
        RecordingLockPort port = new RecordingLockPort();
        List<String> executed = new ArrayList<>();

        port.runLocked("order:1", () -> executed.add("ran"));

        assertThat(executed).containsExactly("ran");
        assertThat(port.calls).containsExactly("order:1|PT0S|PT30S");
        assertThat(port.results).containsExactly((Object) null);
    }
}
