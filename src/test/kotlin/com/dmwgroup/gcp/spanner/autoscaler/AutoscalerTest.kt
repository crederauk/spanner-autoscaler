/*
 * Copyright 2020 DMW Group Ltd.
 *
 * This file is subject to the terms and conditions defined in
 * file 'LICENSE.txt', which is part of this source code package.
 */
package com.dmwgroup.gcp.spanner.autoscaler

import com.google.api.gax.core.CredentialsProvider
import com.google.api.gax.core.NoCredentialsProvider
import com.google.cloud.spanner.InstanceConfigId
import com.google.cloud.spanner.InstanceId
import com.google.cloud.spanner.InstanceInfo
import com.google.cloud.spanner.Spanner
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.SpannerEmulatorContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName


@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class AutoscalerTest(@Autowired val spanner: Spanner) {

    companion object {
        const val PROJECT_ID = "test-project-id"
        const val INSTANCE_ID = "test-instance-id"
        const val DATABASE_ID = "test-scaling-events-db"

        @JvmStatic
        @Container
        val emulator: SpannerEmulatorContainer = SpannerEmulatorContainer(
            DockerImageName.parse("gcr.io/cloud-spanner-emulator/emulator:1.1.1")
        );

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.cloud.gcp.spanner.emulator-host", emulator::getEmulatorGrpcEndpoint)
            registry.add("spring.cloud.gcp.spanner.project-id") { PROJECT_ID }
            registry.add("spring.cloud.gcp.spanner.instance-id") { INSTANCE_ID }
        }
    }

    @TestConfiguration
    class EmulatorConfiguration {
        @Bean
        fun googleCredentials(): CredentialsProvider {
            return NoCredentialsProvider.create()
        }
    }

    @BeforeEach
    fun setUp() {
        val instanceConfigId = InstanceConfigId.of(PROJECT_ID, "emulator-config")
        val instanceId = InstanceId.of(PROJECT_ID, INSTANCE_ID)
        spanner.instanceAdminClient.createInstance(
            InstanceInfo.newBuilder(instanceId)
                .setNodeCount(1)
                .setDisplayName("Test instance")
                .setInstanceConfigId(instanceConfigId)
                .build()
        ).get()

        // TODO populate with schema.sql
        spanner.databaseAdminClient.createDatabase(INSTANCE_ID, DATABASE_ID, listOf())
    }

    @AfterEach
    fun tearDown() {
        spanner.instanceAdminClient.deleteInstance(INSTANCE_ID)
    }

    @Test
    @Timeout(5)
    @Disabled
    fun `should scale up from 1 to 2 nodes`() {
        while (spanner.instanceAdminClient.getInstance(INSTANCE_ID).nodeCount < 2) {
            Thread.sleep(250)
        }
    }
}