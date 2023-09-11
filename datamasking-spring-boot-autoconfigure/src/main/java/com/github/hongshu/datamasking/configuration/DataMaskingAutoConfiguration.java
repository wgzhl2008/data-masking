package com.github.hongshu.datamasking.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 引入配置
 */

@Configuration
@EnableConfigurationProperties({DataMaskingProperties.class})
@ConditionalOnProperty(name = "data-masking.enable", havingValue = "true")
@ComponentScan(
        basePackages = {"com.github.hongshu.datamasking"}
)
public class DataMaskingAutoConfiguration {


}
