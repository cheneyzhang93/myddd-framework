package org.deep361.myddd.job.xxljob.config;

import com.xxl.job.core.executor.XxlJobExecutor;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code myddd.job.*} 在 XXL-Job provider 侧的绑定与校验（{@code IQ} 组）。 */
class MydddJobXxlJobPropertiesTest {

    /** IQ1：缺省值，并把厂商缺省摆在同一条用例里对照。 */
    @Test
    void should_default_to_enabled_unselected_provider_and_our_own_executor_identity() {
        MydddJobXxlJobProperties properties = new MydddJobXxlJobProperties();
        XxlJobExecutor vendorDefault = new XxlJobExecutor();
        assertTrue(properties.isEnabled());
        assertNull(properties.getProvider(),
                "provider 不该有缺省值：缺省即'未写'，歧义判定归 JobProviderSelection");
        assertNull(properties.getXxljob().getAdminAddresses());
        assertEquals("myddd-executor", properties.getXxljob().getExecutorAppName());
        assertNull(properties.getXxljob().getAccessToken());
        assertEquals(9999, properties.getXxljob().getExecutorPort());
        assertEquals(0, vendorDefault.getPort(),
                "厂商缺省端口是 0（= 从 9999 起找空闲端口并回写字段），我们的 9999 必须是显式设出来的");
        assertNull(vendorDefault.getAppname(),
                "厂商不要求执行器名；我们的缺省值是让零配置也能注册（§1.2 键表登记的缺省）");
    }

    /** IQ2：调度中心地址缺失或空白都拒，且消息点出键名。 */
    @Test
    void should_reject_a_missing_or_blank_admin_address_with_the_offending_key() {
        MydddJobXxlJobProperties blank = new MydddJobXxlJobProperties();
        blank.getXxljob().setAdminAddresses("   ");
        FrameworkException blankFailure = assertThrows(FrameworkException.class, blank::validate);
        assertThat(blankFailure.getMessage()).contains("myddd.job.xxljob.admin-addresses");

        MydddJobXxlJobProperties absent = new MydddJobXxlJobProperties();
        FrameworkException absentFailure = assertThrows(FrameworkException.class, absent::validate);
        assertThat(absentFailure.getMessage()).contains("myddd.job.xxljob.admin-addresses", "http://");
    }

    /** IQ3：执行器名空白 ⇒ 拒（调度中心按它寻址，空白名在作业配置里选不到本执行器）。 */
    @Test
    void should_reject_a_blank_executor_app_name() {
        MydddJobXxlJobProperties properties = new MydddJobXxlJobProperties();
        properties.getXxljob().setAdminAddresses("http://127.0.0.1:9099");
        properties.getXxljob().setExecutorAppName(" ");
        FrameworkException failure = assertThrows(FrameworkException.class, properties::validate);
        assertThat(failure.getMessage()).contains("myddd.job.xxljob.executor-app-name");
    }

    /** IQ4：端口下界 1024（不是"排除 0"）。 */
    @Test
    void should_reject_a_privileged_port() {
        MydddJobXxlJobProperties properties = sample();
        properties.getXxljob().setExecutorPort(1023);
        FrameworkException failure = assertThrows(FrameworkException.class, properties::validate);
        assertThat(failure.getMessage()).contains("myddd.job.xxljob.executor-port", "1023", "1024");
    }

    /** IQ5：0 也拒，并且消息里写清"0 在厂商侧意味着自动换端口"。 */
    @Test
    void should_reject_zero_port_because_vendor_silently_switches_it() {
        MydddJobXxlJobProperties properties = sample();
        properties.getXxljob().setExecutorPort(0);
        FrameworkException failure = assertThrows(FrameworkException.class, properties::validate);
        assertThat(failure.getMessage()).contains("myddd.job.xxljob.executor-port", "空闲端口");
    }

    /** IQ6：按 §1.2 键表的字面 kebab 键名绑定，读得到才算绑上（字段名的钉子）。 */
    @Test
    void should_bind_the_documented_kebab_case_keys() {
        MydddJobXxlJobProperties properties = new MydddJobXxlJobProperties();
        new Binder(new MapConfigurationPropertySource(Map.of(
                "myddd.job.xxljob.admin-addresses", "http://127.0.0.1:9099",
                "myddd.job.xxljob.executor-app-name", "order-executor",
                "myddd.job.xxljob.access-token", "secret",
                "myddd.job.xxljob.executor-port", "18000")))
                .bind("myddd.job", Bindable.ofInstance(properties));
        assertEquals("http://127.0.0.1:9099", properties.getXxljob().getAdminAddresses());
        assertEquals("order-executor", properties.getXxljob().getExecutorAppName());
        assertEquals("secret", properties.getXxljob().getAccessToken());
        assertEquals(18000, properties.getXxljob().getExecutorPort());
        properties.validate();
    }

    /** IQ7：同前缀下另一侧 provider 的子树不许报错。 */
    @Test
    void should_ignore_the_sibling_provider_section_under_the_same_prefix() {
        MydddJobXxlJobProperties properties = new MydddJobXxlJobProperties();
        new Binder(new MapConfigurationPropertySource(Map.of(
                "myddd.job.provider", "xxljob",
                "myddd.job.xxljob.admin-addresses", "http://127.0.0.1:9099",
                "myddd.job.spring.pool-size", "7")))
                .bind("myddd.job", Bindable.ofInstance(properties));
        assertEquals("xxljob", properties.getProvider());
        assertEquals(9999, properties.getXxljob().getExecutorPort());
        assertDoesNotThrow(properties::validate,
                "绑定到 spring 侧键上的值不该让 xxljob 侧的属性类失败：两个 jar 共存是合法形态");
    }

    /** IQ8：access-token 允许缺失与空白（两种方向的部署都存在）。 */
    @Test
    void should_accept_an_absent_or_blank_access_token() {
        MydddJobXxlJobProperties absent = sample();
        assertDoesNotThrow(absent::validate);

        MydddJobXxlJobProperties blank = sample();
        blank.getXxljob().setAccessToken("");
        assertDoesNotThrow(blank::validate,
                "把空口令判成非法会剥夺'我自己就是没设 token'这一形态；改成非空缺省则每个用户第一次注册都被拒");
        assertTrue(blank.getXxljob().getAccessToken().isEmpty());
    }

    private static MydddJobXxlJobProperties sample() {
        MydddJobXxlJobProperties properties = new MydddJobXxlJobProperties();
        properties.getXxljob().setAdminAddresses("http://127.0.0.1:9099");
        return properties;
    }
}
