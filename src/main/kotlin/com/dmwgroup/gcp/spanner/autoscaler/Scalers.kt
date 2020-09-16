/*
 * Copyright 2020 Andrew James <andrew.james@dmwgroup.co.uk> and DMW Group Ltd.
 *
 * This file is subject to the terms and conditions defined in
 * file 'LICENSE.txt', which is part of this source code package.
 */

package com.dmwgroup.gcp.spanner.autoscaler

import arrow.core.Either
import com.dmwgroup.gcp.spanner.autoscaler.dto.PollingEvent
import com.dmwgroup.gcp.spanner.autoscaler.dto.ScalingEvent
import com.dmwgroup.gcp.spanner.autoscaler.repositories.InstanceRepository
import com.dmwgroup.gcp.spanner.autoscaler.repositories.PollingEventRepository
import com.dmwgroup.gcp.spanner.autoscaler.repositories.ScalingEventRepository
import com.dmwgroup.gcp.spanner.autoscaler.util.generateSpannerInstanceUUID
import com.google.auth.oauth2.GoogleCredentials
import com.google.gson.GsonBuilder
import com.google.protobuf.FieldMask
import com.google.spanner.admin.instance.v1.Instance
import com.google.spanner.admin.instance.v1.InstanceName
import kong.unirest.Unirest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Scope
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.support.CronTrigger
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.ExecutionException
import javax.validation.constraints.Max
import javax.validation.constraints.Min
import javax.validation.constraints.NotEmpty

@Component
@Scope("singleton")
class SpannerScaler(
    private val configuration: AppConfiguration,
    private val instanceRepository: InstanceRepository,
    private val pollingEventRepository: PollingEventRepository,
    private val scalingEventRepository: ScalingEventRepository,
    private val taskScheduler: ThreadPoolTaskScheduler
) {
    private val log: Logger = LoggerFactory.getLogger(SpannerScaler::class.java)
    private val gson = GsonBuilder().create()

    /**
     * Add new nodes to an existing Spanner instance, returning an exception if there's an error.
     */
    private fun setSpannerNodes(projectId: String, instanceId: String, nodes: Int):
        Either<Exception, Instance> =
        try {
            com.google.cloud.spanner.admin.instance.v1.InstanceAdminClient.create().use { instanceAdmin ->
                log.info("${projectId}/${instanceId}: Scaling Spanner instance to ${nodes} nodes.")
                val operationalResult = instanceAdmin.updateInstanceAsync(
                    Instance.newBuilder()
                        .setName("projects/${projectId}/instances/${instanceId}")
                        .setNodeCount(nodes)
                        .build(),
                    FieldMask.newBuilder()
                        .addPaths("node_count")
                        .build()
                ).get()

                log.info("${projectId}/${instanceId}: Spanner instance scaled to ${operationalResult.nodeCount} nodes.")
                Either.right(operationalResult)
            }
        } catch (ex: ExecutionException) {
            Either.left(ex)
        } catch (ex: InterruptedException) {
            Either.left(ex)
        }

    /**
     * Scale an instance to the number of nodes specified in the Scaling Action.
     */
    @Transactional
    fun scaleInstance(action: ScalingAction) {
        when (action) {
            is ScalingAction.SetNodes -> {
                com.google.cloud.spanner.admin.instance.v1.InstanceAdminClient.create().use { instanceAdmin ->
                    instanceAdmin.getInstance(InstanceName.of(action.projectId, action.instanceId)).let { instance ->
                        val instanceUUID = generateSpannerInstanceUUID(action.projectId, action.instanceId)
                        // Ensure that no actions are taken if a recent scaling event has taken place
                        scalingEventRepository.latestScalingEvent(instanceUUID)?.let { lastScalingEvent ->

                            // Check for scale up rebalance time
                            if (Duration.between(lastScalingEvent.eventTimestamp, Instant.now()) <= configuration.scaleUpRebalanceDuration && instance.nodeCount < action.nodes) {
                                log.info("${action.projectId}/${action.instanceId}: Last scale up event was at ${lastScalingEvent.eventTimestamp}, less than ${configuration.scaleUpRebalanceDuration} ago. No scaling action taken.")
                                return
                            }

                            // Check for scale down rebalance time
                            if (Duration.between(lastScalingEvent.eventTimestamp, Instant.now()) <= configuration.scaleDownRebalanceDuration && instance.nodeCount > action.nodes) {
                                log.info("${action.projectId}/${action.instanceId}: Last scale down event was at ${lastScalingEvent.eventTimestamp}, less than ${configuration.scaleDownRebalanceDuration} ago. No scaling action taken.")
                                return
                            }
                        }

                        // Only scale if the number of recommended nodes would change the current node count.
                        if (instance.nodeCount == action.nodes) {
                            log.info("${action.projectId}/${action.instanceId}: Current node count the same as recommendation (${instance.nodeCount} nodes). Not scaling action taken.")
                        } else {

                            when (val updatedInstance = setSpannerNodes(action.projectId, action.instanceId, action.nodes)) {
                                is Either.Left -> {
                                    log.info("${action.projectId}/${action.instanceId}: Error updating Spanner nodes: ${updatedInstance.a.message}")
                                }
                                is Either.Right -> {
                                    // Persist the record of the scaling event
                                    scalingEventRepository.save(ScalingEvent(
                                        instanceId = instanceUUID,
                                        scalingEventId = UUID.randomUUID(),
                                        eventTimestamp = Instant.now(),
                                        action = gson.toJson(action),
                                        nodesBefore = instance.nodeCount,
                                        nodesAfter = updatedInstance.b.nodeCount
                                    ))
                                }
                            }
                        }
                    }
                }
            }
            is ScalingAction.NoChange -> {
                log.info("${action.projectId}/${action.instanceId}: No scaling action taken.")
            }
        }

    }

    /**
     * Schedule tasks for each of the cron scaling strategies
     */
    fun scheduleCronScalers() {
        taskScheduler.poolSize = configuration.scalerThreadPoolSize
        configuration.cronScalers.forEach { strategy: ScalingStrategy.CronScalingStrategy ->
            strategy.scheduleTriggers(taskScheduler, spannerScaler = this)
        }
    }

    /**
     * Persist polling events into the database.
     */
    @Transactional
    protected fun logPollingEvent(metrics: InstanceMetrics) {
        val instanceUUID = generateSpannerInstanceUUID(metrics.projectId, metrics.instanceId)

        // Persist logging information
        if (!instanceRepository.existsById(instanceUUID)) {
            instanceRepository.save(com.dmwgroup.gcp.spanner.autoscaler.dto.Instance(
                instanceUUID, metrics.projectId, metrics.instanceId
            ))
        }

        pollingEventRepository.save(PollingEvent(
            instanceUUID,
            UUID.randomUUID(),
            metrics.timestamp,
            gson.toJson(metrics)
        ))
    }

    /**
     * Perform a check to retrieve Spanner monitoring metrics from Cloud Monitoring and obtain scaling recommendations
     * for each Spanner instance found in the configuration file.
     */
    @Scheduled(fixedRateString = "#{T(org.springframework.boot.convert.DurationStyle).detectAndParse('\${application.check-interval-duration}')}")
    @Transactional
    fun performApplicationCheck() {
        log.info("Scaling check starting: ${Instant.now()}...")
        SpannerMetricsRetriever(configuration.monitoringProjectId, configuration.metricAggregationDuration).latestMetrics().let {
            when (it) {
                is Either.Left -> log.info("Could not retrieve cloud monitoring metrics: ${it.a}")
                is Either.Right ->
                    it.b.forEach { metrics ->

                        log.info("${metrics.projectId}/${metrics.instanceId}: Metrics found for instance ($metrics)")
                        logPollingEvent(metrics)

                        configuration.balancedScalers.filter { scaler: ScalingStrategy.BalancedScalingStrategy ->
                            scaler.instance.projectId == metrics.projectId && scaler.instance.instanceId == metrics.instanceId
                        }.map { scaler ->
                            when (val recommendation = scaler.scalingRecommendation(metrics)) {
                                is Either.Right -> {
                                    log.info("${metrics.projectId}/${metrics.instanceId}: Scaler found for instance")
                                    log.info("${metrics.projectId}/${metrics.instanceId}: Scaler recommendation is ${recommendation.b}.")
                                    scaleInstance(action = recommendation.b)
                                }
                                is Either.Left -> {
                                    log.info("Error retrieving recommendations: ${recommendation.a}")
                                }
                            }
                        }
                    }
            }
        }
    }

}

/**
 * Logic to retrieve Spanner metrics from Cloud Monitoring.
 */
class SpannerMetricsRetriever(
    val projectId: String,
    metricAggregatorDuration: Duration
) {
    private val gson = GsonBuilder().create()

    private val credentials: GoogleCredentials = GoogleCredentials.getApplicationDefault()
        .createScoped(" https://www.googleapis.com/auth/monitoring",
            "https://www.googleapis.com/auth/monitoring.read")

    private val metricsQuery = """
        {
          spanner_instance::spanner.googleapis.com/instance/cpu/utilization
          | group_by 5m, mean(val()) | every 5m | group_by [resource.project_id, resource.instance_id];
          spanner_instance::spanner.googleapis.com/instance/node_count
          | group_by 5m, mean(val()) | every 5m | group_by [resource.project_id, resource.instance_id];
          spanner_instance::spanner.googleapis.com/instance/session_count
          | group_by 5m, mean(val()) | every 5m | group_by [resource.project_id, resource.instance_id];
          spanner_instance::spanner.googleapis.com/instance/storage/utilization
          | group_by 5m, mean(val()) | every 5m | group_by [resource.project_id, resource.instance_id];
          spanner_instance::spanner.googleapis.com/instance/cpu/smoothed_utilization
          | group_by 5m, mean(val()) | every 5m | group_by [resource.project_id, resource.instance_id];
           spanner_instance::spanner.googleapis.com/instance/storage/limit_bytes
          | group_by 5m, max(val()) | every 5m | group_by [resource.project_id, resource.instance_id];
          spanner_instance::spanner.googleapis.com/instance/storage/used_bytes
          | group_by 5m, max(val()) | every 5m | group_by [resource.project_id, resource.instance_id]
        } | join
    """.trimIndent()
        .replace('\n', ' ')
        .replace("5m", "${metricAggregatorDuration.seconds}s")

    /**
     * Retrieve the latest metrics for all Spanner instances
     */
    fun latestMetrics() = queryMetrics().let { response ->
        when (response) {
            is Either.Right -> Either.Right(response.b.timeSeriesData.map { series ->
                InstanceMetrics(
                    timestamp = Instant.parse(series.pointData.first().timeInterval.endTime),
                    projectId = series.labelValues.first().stringValue,
                    instanceId = series.labelValues.last().stringValue,
                    meanCpuUtilisation = series.pointData.first().values[0].doubleValue!!,
                    meanSmootherCpuUtilisation = series.pointData.first().values[4].doubleValue!!,
                    meanStorageUtilisation = series.pointData.first().values[3].doubleValue!!,
                    meanNodes = series.pointData.first().values[1].doubleValue!!,
                    meanSessions = series.pointData.first().values[2].doubleValue!!,
                    maxLimitBytes = series.pointData.first().values[5].int64Value!!,
                    maxUsedBytes = series.pointData.first().values[6].int64Value!!
                )
            })
            is Either.Left -> response
        }
    }

    /**
     * Query Cloud Monitoring for the latest statistics for instances
     */
    private fun queryMetrics(): Either<String, TimeSeriesQueryResponse> {
        credentials.refreshIfExpired()
        return Unirest.post("https://monitoring.googleapis.com/v3/projects/${projectId}/timeSeries:query")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${credentials.accessToken.tokenValue}")
            .queryString("name", "projects/$projectId")
            .body(gson.toJson(TimeSeriesQuery(metricsQuery)))
            .asObject(TimeSeriesQueryResponse::class.java)
            .let { response ->
                if (response.isSuccess) {
                    Either.right(response.body)
                } else {
                    Either.left(response.parsingError.get().originalBody)
                }
            }
    }
}

/**
 * A representation of the state of an instance at a point in time
 */
data class InstanceMetrics(
    val timestamp: Instant,
    val projectId: String,
    val instanceId: String,
    val meanCpuUtilisation: Double,
    val meanSmootherCpuUtilisation: Double,
    val meanStorageUtilisation: Double,
    val meanNodes: Double,
    val meanSessions: Double,
    val maxUsedBytes: BigInteger,
    val maxLimitBytes: BigInteger
)

/**
 * Action to take when scaling a Spanner instance
 */
sealed class ScalingAction {
    data class SetNodes(
        val projectId: String,
        val instanceId: String,
        val nodes: Int
    ) : ScalingAction()

    data class NoChange(
        val projectId: String,
        val instanceId: String
    ) : ScalingAction()
}

/**
 * Parent Scaling Strategy
 */
sealed class ScalingStrategy(
    open val instance: InstanceInformation
) {

    /**
     * Minimum number of nodes required for storage utilisation.
     */
    fun minStorageNodes(usedBytes: Double) = Math.ceil(usedBytes / (2000000000 / 100 * instance.maxStorageUtilisation)).toInt()

    /**
     * Minimum number of nodes required for session utilisation.
     */
    fun minSessionNodes(meanSessions: Double) = Math.ceil(meanSessions / 10000).toInt()

    /**
     * Recommend a scaling action given a set of instance metrics.
     */
    open fun scalingRecommendation(metrics: InstanceMetrics): Either<String, ScalingAction> = Either.right(ScalingAction.NoChange(metrics.projectId, metrics.instanceId))

    /**
     * Recommend a scaling action given a set of instance metrics.
     */
    open fun scalingRecommendation(): Either<String, ScalingAction> = Either.right(ScalingAction.NoChange(instance.projectId, instance.instanceId))

    /**
     * Scaling strategy that takes into account CPU, storage and session utilisation, aiming to keep the instance between
     * an upper and lower CPU utilisation band.
     */
    class BalancedScalingStrategy(override val instance: InstanceInformation) : ScalingStrategy(instance) {
        private val log: Logger = LoggerFactory.getLogger(BalancedScalingStrategy::class.java)

        override fun scalingRecommendation(metrics: InstanceMetrics): Either<String, ScalingAction> {

            // Calculate the minimum possible nodes
            val minimumNodes = listOf(
                instance.minNodes,
                minSessionNodes(metrics.meanSessions),
                minStorageNodes(metrics.maxUsedBytes.toDouble())
            ).max()!!

            log.info("${metrics.projectId}/${metrics.instanceId}: Calculated minimum nodes to be $minimumNodes.")

            val cpuUtilisationPerNode = metrics.meanCpuUtilisation / metrics.meanNodes

            // If average CPU utilisation is too high, increase the number of nodes
            return when {
                metrics.meanCpuUtilisation > instance.maxCpuUtilisation -> {
                    val targetNodes = listOf(Math.ceil(instance.targetCpuUtilisation / cpuUtilisationPerNode).toInt(), minimumNodes).max()
                    log.info("${metrics.projectId}/${metrics.instanceId}: Current CPU utilisation ${metrics.meanCpuUtilisation} greater than maximum CPU allowed ${instance.maxCpuUtilisation}.")
                    log.info("${metrics.projectId}/${metrics.instanceId}: Setting new target nodes to be $targetNodes.")
                    Either.right(ScalingAction.SetNodes(metrics.projectId, metrics.instanceId, targetNodes!!))
                }
                metrics.meanCpuUtilisation in instance.minCpuUtilisation..instance.maxCpuUtilisation -> {
                    log.info("${metrics.projectId}/${metrics.instanceId}: Current CPU utilisation ${metrics.meanCpuUtilisation} within permitted window of ${instance.minCpuUtilisation} - ${instance.maxCpuUtilisation}.")
                    log.info("${metrics.projectId}/${metrics.instanceId}: No change to target nodes.")
                    Either.right(ScalingAction.NoChange(metrics.projectId, metrics.instanceId))
                }
                metrics.meanCpuUtilisation < instance.minCpuUtilisation -> {
                    val targetNodes = listOf((metrics.meanCpuUtilisation / cpuUtilisationPerNode).toInt(), minimumNodes).max()
                    log.info("${metrics.projectId}/${metrics.instanceId}: Current CPU utilisation ${metrics.meanCpuUtilisation} less than minimum CPU allowed of ${instance.minCpuUtilisation}.")
                    Either.right(ScalingAction.SetNodes(metrics.projectId, metrics.instanceId, targetNodes!!))
                }
                else -> {
                    log.info("${metrics.projectId}/${metrics.instanceId}: Unexpected CPU utilisation value: ${metrics.meanCpuUtilisation}")
                    Either.left("${metrics.projectId}/${metrics.instanceId}: Unexpected CPU utilisation value: ${metrics.meanCpuUtilisation}")
                }
            }
        }
    }

    /**
     * Scale a Spanner instance with multiple Cron expressions.
     */
    class CronScalingStrategy(override val instance: InstanceInformation, val schedules: List<CronSchedule>) : ScalingStrategy(instance) {

        data class CronSchedule(
            val cronExpression: String,
            val nodes: Int
        )

        fun scheduleTriggers(taskScheduler: ThreadPoolTaskScheduler, spannerScaler: SpannerScaler) {
            schedules.forEach { schedule ->
                taskScheduler.schedule(CronTriggerScheduler(this.instance, schedule, spannerScaler), CronTrigger(schedule.cronExpression))
            }
        }

    }
}

/**
 * Configuration target information for an instance.
 */
@Validated
data class InstanceInformation(
    @NotEmpty
    val projectId: String,
    @NotEmpty
    val instanceId: String,
    @Min(1)
    val minNodes: Int = 1,
    @Min(1)
    val maxNodes: Int,
    @Min(0)
    @Max(1)
    val maxStorageUtilisation: Double = 0.85,
    @Min(0)
    @Max(1)
    val targetCpuUtilisation: Double = 0.65,
    @Min(0)
    @Max(1)
    val maxCpuUtilisation: Double = targetCpuUtilisation + 0.1,
    @Min(0)
    @Max(1)
    val minCpuUtilisation: Double = targetCpuUtilisation - 0.1
)

/**
 * Request for a time series query to Cloud Monitoring
 */
data class TimeSeriesQuery(
    val query: String
)

/**
 * Response for a time series query to Cloud Monitoring.
 */
data class TimeSeriesQueryResponse(
    val timeSeriesDescriptor: TimeSeriesDescriptor,
    val timeSeriesData: List<TimeSeriesData>
) {
    data class TimeSeriesDescriptor(
        val labelDescriptors: List<LabelDescriptor>,
        val pointDescriptors: List<PointDescriptor>
    ) {
        data class LabelDescriptor(
            val key: String
        )

        data class PointDescriptor(
            val key: String,
            val valueType: String,
            val metricKind: String,
            val unit: String
        )
    }

    data class TimeSeriesData(
        val labelValues: List<LabelValue>,
        val pointData: List<PointData>
    ) {
        data class LabelValue(
            val stringValue: String
        )

        data class PointData(
            val values: List<Value>,
            val timeInterval: TimeInterval
        ) {
            data class Value(
                val doubleValue: Double?,
                val int64Value: BigInteger?
            )

            data class TimeInterval(
                val startTime: String,
                val endTime: String
            )
        }
    }
}


/**
 * Scheduler for a Cron trigger, running in its own thread.
 */
class CronTriggerScheduler(val instance: InstanceInformation, val schedule: ScalingStrategy.CronScalingStrategy.CronSchedule,
                           val scaler: SpannerScaler) : Runnable {
    private val log: Logger = LoggerFactory.getLogger(CronTriggerScheduler::class.java)

    /**
     * Scale the instance to the requested number of nodes.
     */
    override fun run() {
        if (schedule.nodes in instance.minNodes..instance.maxNodes) {
            log.info("${instance.projectId}/${instance.instanceId}: Cron scheduler ${schedule.cronExpression} scaling to ${schedule.nodes} nodes. ")
            scaler.scaleInstance(ScalingAction.SetNodes(instance.projectId, instance.instanceId, schedule.nodes))
        } else {
            log.info("${instance.projectId}/${instance.instanceId}: Cron scheduler ${schedule.nodes} nodes not between ${instance.minNodes} and ${instance.maxNodes}. No action taken. ")
        }
    }

}
