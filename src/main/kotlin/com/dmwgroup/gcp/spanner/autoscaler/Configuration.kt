/*
 * Copyright 2020 Andrew James <andrew.james@dmwgroup.co.uk> and DMW Group Ltd.
 *
 * This file is subject to the terms and conditions defined in
 * file 'LICENSE.txt', which is part of this source code package.
 */
package com.dmwgroup.gcp.spanner.autoscaler

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.validation.annotation.Validated
import java.time.Duration
import javax.validation.constraints.Min

@Validated
@ConstructorBinding
@ConfigurationProperties(prefix = "application")
data class AppConfiguration(
    /**
     * The name of the project to query for metrics when using the BalancedScaler strategy
     */
    val monitoringProjectId: String?,
    /**
     * The number of threads to use when scheduling Cron triggers. The default value is 10, which is the limit for
     * number of simultaneous schedules that can be executed.
     */
    @Min(1)
    val scalerThreadPoolSize: Int = 10,
    /**
     * The interval between scheduled application metric checks when using the BalancedScaler strategy. The default is 5 minutes (5m)
     */
    val checkInterval: Duration = Duration.ofMinutes(5),
    /**
     * The aggregation period for metrics from Cloud Spanner. The default is the same as the check interval
     */
    val metricAggregationDuration: Duration = checkInterval,
    /**
     * The number of minutes to wait between scaling up nodes within a Spanner instance. The default and
     * strongly-recommended value is 5 minutes (5m)
     */
    val scaleUpRebalanceDuration: Duration = Duration.ofMinutes(5),
    /**
     * The number of minutes to wait between scaling down nodes within a Spanner instance. The default and
     * strongly-recommended value is 30 minutes (30m)
     */
    val scaleDownRebalanceDuration: Duration = Duration.ofMinutes(30),
    /**
     * A list of instances to be scaled using the BalancedScaler strategy
     */
    val balancedScalers: List<ScalingStrategy.BalancedScalingStrategy> = emptyList(),
    /**
     * A list of instances to be scaled using the CronScaling strategy
     */
    val cronScalers: List<ScalingStrategy.CronScalingStrategy> = emptyList()
)

@Configuration
class ThreadPoolTaskSchedulerConfig(private val configuration: AppConfiguration) {
    @Bean
    fun threadPoolTaskScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = configuration.scalerThreadPoolSize
            setThreadNamePrefix("SpannerScaler")
        }
}
