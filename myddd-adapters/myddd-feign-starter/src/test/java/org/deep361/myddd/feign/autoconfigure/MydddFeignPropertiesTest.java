package org.deep361.myddd.feign.autoconfigure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MydddFeignProperties} 的缺省值契约：三个开关默认全开，业务一行配置都不写就能拿到完整能力。
 *
 * <p>本类不做启动期校验（设计与理由见类内 javadoc），因此这里也只钉缺省值与"嵌套结构不可被替换"两件事；
 * 键绑定与开关效果的验收在 {@link MydddFeignAutoConfigurationTest} 里通过真实绑定完成。
 */
class MydddFeignPropertiesTest {

    @Test
    void should_default_every_switch_to_enabled() {
        MydddFeignProperties properties = new MydddFeignProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getTrace().isEnabled()).isTrue();
        assertThat(properties.getErrorDecoder().isEnabled()).isTrue();
    }

    @Test
    void should_keep_nested_settings_as_read_only_holders() {
        MydddFeignProperties properties = new MydddFeignProperties();

        properties.getTrace().setEnabled(false);
        properties.getErrorDecoder().setEnabled(false);

        assertThat(properties.getTrace()).isSameAs(properties.getTrace());
        assertThat(properties.getTrace().isEnabled()).isFalse();
        assertThat(properties.getErrorDecoder().isEnabled()).isFalse();
        assertThat(properties.isEnabled()).as("子开关不影响总开关").isTrue();
    }
}
