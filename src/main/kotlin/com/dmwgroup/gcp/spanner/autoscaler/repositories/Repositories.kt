/*
 * Copyright 2020 Andrew James <andrew.james@dmwgroup.co.uk> and DMW Group Ltd.
 *
 * This file is subject to the terms and conditions defined in
 * file 'LICENSE.txt', which is part of this source code package.
 */

package com.dmwgroup.gcp.spanner.autoscaler.repositories

import com.dmwgroup.gcp.spanner.autoscaler.dto.Instance
import com.dmwgroup.gcp.spanner.autoscaler.dto.InstanceLock
import com.dmwgroup.gcp.spanner.autoscaler.dto.PollingEvent
import com.dmwgroup.gcp.spanner.autoscaler.dto.ScalingEvent
import org.springframework.cloud.gcp.data.spanner.repository.SpannerRepository
import org.springframework.cloud.gcp.data.spanner.repository.query.Query
import java.util.*

interface InstanceRepository : SpannerRepository<Instance, UUID>
interface LockRepository : SpannerRepository<InstanceLock, Array<UUID>>
interface PollingEventRepository : SpannerRepository<PollingEvent, Array<UUID>>
interface ScalingEventRepository : SpannerRepository<ScalingEvent, Array<UUID>> {

    /**
     * Return the last scaling event for a Spanner instance.
     */
    @Query("SELECT * FROM ScalingEvents WHERE InstanceId = @instanceId ORDER BY EventTimestamp DESC LIMIT 1")
    fun latestScalingEvent(instanceId: UUID): ScalingEvent?

}
