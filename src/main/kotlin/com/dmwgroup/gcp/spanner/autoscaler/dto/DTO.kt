/*
 * Copyright 2020 Andrew James <andrew.james@dmwgroup.co.uk> and DMW Group Ltd.
 *
 * This file is subject to the terms and conditions defined in
 * file 'LICENSE.txt', which is part of this source code package.
 */

package com.dmwgroup.gcp.spanner.autoscaler.dto

import org.springframework.cloud.gcp.data.spanner.core.convert.ConverterAwareMappingSpannerEntityProcessor
import org.springframework.cloud.gcp.data.spanner.core.convert.SpannerEntityProcessor
import org.springframework.cloud.gcp.data.spanner.core.mapping.*
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import java.time.Instant
import java.util.*

@Table(name = "Instances")
data class Instance(
    @PrimaryKey
    @Column(name = "InstanceId")
    val instanceId: UUID,
    @Column(name = "ProjectId")
    val projectId: String,
    @Column(name = "InstanceName")
    val instanceName: String,
    @Interleaved
    val pollingEvents: List<PollingEvent> = emptyList(),
    @Interleaved
    val scalingEvents: List<ScalingEvent> = emptyList()
)

@Table(name = "PollingEvents")
data class PollingEvent(
    @PrimaryKey(keyOrder = 1)
    @Column(name = "InstanceId")
    val instanceId: UUID,
    @PrimaryKey(keyOrder = 2)
    @Column(name = "PollingEventId")
    val pollingEventId: UUID,
    @Column(name = "EventTimestamp")
    val eventTimestamp: Instant,
    @Column(name = "Metrics")
    val metrics: String
)

@Table(name = "ScalingEvents")
data class ScalingEvent(
    @PrimaryKey(keyOrder = 1)
    @Column(name = "InstanceId")
    val instanceId: UUID,
    @PrimaryKey(keyOrder = 2)
    @Column(name = "ScalingEventId")
    val scalingEventId: UUID,
    @Column(name = "EventTimestamp")
    val eventTimestamp: Instant,
    @Column(name = "Action")
    val action: String,
    @Column(name = "NodesBefore")
    val nodesBefore: Int?,
    @Column(name = "NodesAfter")
    val nodesAfter: Int
)

@Table(name = "InstanceLocks")
data class InstanceLock(
    @PrimaryKey(keyOrder = 1)
    @Column(name = "InstanceId")
    val instanceId: UUID,
    @PrimaryKey(keyOrder = 2)
    @Column(name = "LockId")
    val lockId: UUID,
    @Column(name = "LockTimeout")
    val lockTimeout: Instant
)


/**
 * Converters.
 */

class InstantWriteConverter : Converter<Instant, String> {
    override fun convert(instant: Instant): String = instant.toString()
}

class InstantReadConverter : Converter<String, Instant> {
    override fun convert(s: String): Instant = Instant.parse(s)
}

class UUIDWriteConverter : Converter<UUID, String> {
    override fun convert(uuid: UUID): String = uuid.toString()
}

class UUIDReadConverter : Converter<String, UUID> {
    override fun convert(s: String): UUID = UUID.fromString(s)
}

@Configuration
class ConverterConfiguration {

    @Bean
    fun spannerEntityProcessor(spannerMappingContext: SpannerMappingContext): SpannerEntityProcessor =
        ConverterAwareMappingSpannerEntityProcessor(spannerMappingContext,
            listOf(InstantWriteConverter(), UUIDWriteConverter()),
            listOf(InstantReadConverter(), UUIDReadConverter())
        )

}
