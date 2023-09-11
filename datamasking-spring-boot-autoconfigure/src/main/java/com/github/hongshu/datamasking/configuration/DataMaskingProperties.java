package com.github.hongshu.datamasking.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties()
@ConfigurationProperties(prefix = "data-masking")
public class DataMaskingProperties {
    /**
     * 数据脱敏开关
     */
    private Boolean enable = Boolean.FALSE;

    public Boolean getEnable() {
        return enable;
    }

    public void setEnable(Boolean enable) {
        this.enable = enable;
    }
}
