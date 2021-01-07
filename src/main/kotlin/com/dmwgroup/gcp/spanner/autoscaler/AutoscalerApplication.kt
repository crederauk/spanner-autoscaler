/*
 * Copyright 2020 Andrew James <andrew.james@dmwgroup.co.uk> and DMW Group Ltd.
 *
 * This file is subject to the terms and conditions defined in
 * file 'LICENSE.txt', which is part of this source code package.
 */

package com.dmwgroup.gcp.spanner.autoscaler

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.cloud.gcp.data.spanner.repository.config.EnableSpannerRepositories
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.ScheduledTaskRegistrar

@SpringBootApplication
@EnableSpannerRepositories
@EnableConfigurationProperties(AppConfiguration::class)
class SpannerAutoscalerApplication(
    private val configuration: AppConfiguration,
    private val spannerScaler: SpannerScaler
) : ApplicationListener<ApplicationReadyEvent> {

    private val log: Logger = LoggerFactory.getLogger(SpannerAutoscalerApplication::class.java)

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        log.info(configuration.toString())
        spannerScaler.scheduleCronScalers()
    }

}

@Configuration
@EnableScheduling
class SchedulingConfiguration(
    private val configuration: AppConfiguration,
    private val spannerScaler: SpannerScaler
) : SchedulingConfigurer {

    override fun configureTasks(taskRegistrar: ScheduledTaskRegistrar) {
        if (configuration.balancedScalers.isNotEmpty()) {
            taskRegistrar.addFixedRateTask(
                { spannerScaler.performApplicationCheck() },
                configuration.checkInterval.toMillis()
            )
        }
    }
}

/**
 * Application entry point.
 */
fun main(args: Array<String>) {
    runApplication<SpannerAutoscalerApplication>(*args)
}

