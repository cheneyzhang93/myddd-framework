package org.deep361.myddd.testing.contract;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.port.auth.AuthCredentials;
import org.deep361.myddd.kernel.port.auth.AuthPort;
import org.deep361.myddd.kernel.port.auth.AuthUser;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link AuthPortContractTestBase} 的进程内夹具（Task 32 Step 13 要求的配套交付物）。
 *
 * <p>"请求作用域"在真厂商那里是各自的 ThreadLocal（Sa-Token 的上下文盒子 / {@code SecurityContextHolder}），
 * 夹具用一个实例布尔近似它；{@link #currentUserWithoutRequestScope()} 因此必须换一条全新的线程去读，
 * 否则这条缝就成了自证。
 */
class AuthPortFakeContractTest extends AuthPortContractTestBase {

    private static final AuthUser KNOWN_USER = new AuthUser("u-1001", "alice",
            Set.of("operator"), Set.of("order:read", "order:write"));

    private static final Map<String, String> PASSWORDS = Map.of("alice", "right-pwd", "bob", "other-pwd");

    private final AtomicReference<AuthUser> session = new AtomicReference<>();
    private boolean scopeOpen;
    private final AuthPort port = new AuthPort() {

        @Override
        public Optional<AuthUser> currentUser() {
            // R9：没有请求上下文就返回空，而不是抛
            return scopeOpen ? Optional.ofNullable(session.get()) : Optional.empty();
        }

        @Override
        public void login(AuthCredentials credentials) {
            String expected = PASSWORDS.get(credentials.username());
            if (expected == null || !expected.equals(credentials.password())) {
                session.set(null);
                throw new BizException(CommonErrorCode.UNAUTHORIZED);
            }
            session.set(KNOWN_USER);
        }

        @Override
        public void logout() {
            session.set(null);
        }

        @Override
        public boolean hasPermission(String permission) {
            if (permission == null || permission.isBlank()) {
                throw new IllegalArgumentException("permission 不能为 null 或空白");
            }
            AuthUser current = currentUser().orElse(null);
            return current != null && current.permissions().contains(permission);
        }
    };

    @Override
    protected AuthPort authPort() {
        return port;
    }

    @Override
    protected AuthCredentials validCredentials() {
        return new AuthCredentials("alice", "right-pwd");
    }

    /** 用户名必须存在：否则两个厂商会走"用户不存在"与"口令错误"两条不同分支，等价性就无从谈起。 */
    @Override
    protected AuthCredentials brokenCredentials() {
        return new AuthCredentials("alice", "wrong-pwd");
    }

    @Override
    protected AuthUser knownUser() {
        return KNOWN_USER;
    }

    @Override
    protected void openRequestScope() {
        scopeOpen = true;
    }

    @Override
    protected void closeRequestScope() {
        session.set(null);
        scopeOpen = false;
    }

    @Override
    protected Optional<AuthUser> currentUserWithoutRequestScope() {
        AtomicReference<Optional<AuthUser>> observed = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                observed.set(port.currentUser());
            } catch (Throwable unexpected) {
                failure.set(unexpected);
            }
        });
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待新线程结束失败", interrupted);
        }
        if (failure.get() != null) {
            throw new IllegalStateException("无请求上下文的线程上端口抛了异常（R9 要求返回空）", failure.get());
        }
        return observed.get();
    }
}
