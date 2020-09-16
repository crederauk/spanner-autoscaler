/*
 * Copyright 2020 Andrew James <andrew.james@dmwgroup.co.uk> and DMW Group Ltd.
 *
 * This file is subject to the terms and conditions defined in
 * file 'LICENSE.txt', which is part of this source code package.
 */

package com.dmwgroup.gcp.spanner.autoscaler.controllers

import arrow.core.Either
import com.dmwgroup.gcp.spanner.autoscaler.AppConfiguration
import com.dmwgroup.gcp.spanner.autoscaler.InstanceMetrics
import com.dmwgroup.gcp.spanner.autoscaler.SpannerMetricsRetriever
import com.dmwgroup.gcp.spanner.autoscaler.SpannerScaler
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
class MetricsController(val configuration: AppConfiguration) {

    @GetMapping("/metrics/latest")
    fun latestMetrics(): ResponseEntity<List<InstanceMetrics>> =
        when (val metrics = SpannerMetricsRetriever(configuration.monitoringProjectId,
            configuration.metricAggregationDuration).latestMetrics()) {
            is Either.Left -> throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, metrics.a)
            is Either.Right -> ResponseEntity.ok(metrics.b)
        }
}

@RestController
class CheckController(
    val configuration: AppConfiguration,
    val spannerScaler: SpannerScaler) {

    @PostMapping("/check")
    fun latestMetrics(): ResponseEntity<String> {
        spannerScaler.performApplicationCheck()
        return ResponseEntity.ok().body(null)
    }
}

@RestController
class ConfigController(val configuration: AppConfiguration) {

    @GetMapping("/configuration")
    fun getConfiguration(): ResponseEntity<AppConfiguration> {
        return ResponseEntity.ok(configuration)
    }

}
