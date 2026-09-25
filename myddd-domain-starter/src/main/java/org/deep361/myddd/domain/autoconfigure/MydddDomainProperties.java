package org.deep361.myddd.domain.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** 领域装配配置：{@code myddd.domain.*}。 */
@ConfigurationProperties(prefix = "myddd.domain")
public class MydddDomainProperties {

    /**
     * 扫描 {@code @Domain} 领域模块的基础包（含子包）；为空时回退到 Spring Boot 自动配置包
     * （{@code @SpringBootApplication} 所在包）。
     */
    private List<String> scanPackages = new ArrayList<>();

    public List<String> getScanPackages() {
        return scanPackages;
    }

    public void setScanPackages(List<String> scanPackages) {
        this.scanPackages = (scanPackages == null) ? new ArrayList<>() : scanPackages;
    }
}
