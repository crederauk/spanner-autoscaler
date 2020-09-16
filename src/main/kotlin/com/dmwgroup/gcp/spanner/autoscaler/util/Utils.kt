/*
 * Copyright 2020 Andrew James <andrew.james@dmwgroup.co.uk> and DMW Group Ltd.
 *
 * This file is subject to the terms and conditions defined in
 * file 'LICENSE.txt', which is part of this source code package.
 */

package com.dmwgroup.gcp.spanner.autoscaler.util

import com.github.f4b6a3.uuid.UuidCreator
import java.util.*

/**
 * Create a UUID5 from the project and name of an instance.
 */
fun generateSpannerInstanceUUID(projectId: String, instanceId: String) =
    UUID.fromString(UuidCreator.getNameBasedMd5("$projectId/$instanceId").toString())
