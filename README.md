# Spanner Autoscaler

A standalone, stateless Spring Boot micro-service that provides an autoscaling capability for Google Cloud Spanner instances.

![GitHub Workflow Status](https://img.shields.io/github/workflow/status/dmwgroup/spanner-autoscaler/Build)
![GitHub](https://img.shields.io/github/license/dmwgroup/spanner-autoscaler)

## Background
Google has recently released an excellent [Spanner auto-scaler](https://github.com/cloudspannerecosystem/autoscaler) solution which uses a combination of Cloud Pub/Sub, Cloud Functions, Firestore and Cloud Scheduler.

This solution comprises a single, stateless container which provides similar auto-scaling functionality for multiple database instances. It can be deployed into Cloud Run or GKE and may be useful for organisations with security or compliance requirements (like VPC-SC) that limit the use of products such as Cloud Scheduler and Cloud Functions, or who simply wish for a simpler deployment approach.

## Capabilities
The autoscaler currently has the following capabilities:
* Simple deployment; a single, stateless container and backing Spanner database;
* Specification of maximum number of nodes to prevent runaway costs;
* Efficient querying of metrics for multiple Spanner instances in the same MQL query to minimise queries and cost;
* Node scaling based on manual triggering, a CRON schedule or instance CPU and storage utilisation.

Scaling and polling events are recorded in a Spanner database.

For each of the above strategies, this application must be configured with a list of instances that will be scaled according to that strategy. `AppConfiguration` contains all possible configuration options, which can be overridden according to the usual Spring Boot mechanism.

The following endpoints are provide:
- `GET /metrics/latest`: Obtain the latest metrics from Cloud Monitoring.
- `POST /check`: Manually obtain metrics from Cloud Monitoring and use them to scale each instance according to its configured strategy. Returns 204.
- `GET /configuration`: Returns the current application configuration.

## Building
JAR:
```
./gradlew build
```
Docker image:
```
./gradlew jibDockerBuild
```

## Running
### Prerequisites
You will need to install and authenticate using the Google Cloud SDK, as described [here](https://github.com/googleapis/java-spanner/tree/v1.61.0#getting-started).

You will need to create a database for scaling events that contains the database objects specified in `src/main/resources/sql/schema.sql`. This can be located on the instance that is being monitored. 


## Configuration
The following values must be specified using one of the [Spring Boot configuration mechanisms](https://docs.spring.io/spring-boot/docs/current/reference/html/spring-boot-features.html#boot-features-external-config):
- `application.balancedScalers` or `application.cronScalers`: At least one of these must be specified with list of Spanner instance IDs.
- `application.monitoringProjectId`: A Google Cloud project ID. Required if you have specified `application.balancedScalers`.
- `spring.cloud.gcp.spanner.database`: the name of the scaling events database you created.

An example configuration that uses multiple methods of configuration:
```yaml
application:
  check-interval-duration: 30s
  metric-aggregation-duration: 5m
  monitoring-project-id: my-project-name
  balanced-scalers:
    -
      instance:
        project-id: my-project-name
        instance-id: test-instance1
        max-nodes: 10
        max-cpu-utilisation: 70

  cron-scalers:
    -
      instance:
        project-id: my-project-name
        instance-id: test-instance2
        max-nodes: 10
      schedules:
        -
          cron-expression: 0 * * * * *
          nodes: 2
        -
          cron-expression: 30 * * * * *
          nodes: 1
spring:
  cloud:
    gcp:
      spanner:
        instance-id: test-instance
        database: autoscaler
        project-id: my-project-name

```

Ensure that the service account used to run the application has appropriate permissions to change Spanner nodes.

### Run
JAR:
```
GOOGLE_APPLICATION_CREDENTIALS=/path/to/gcp-credentials.json java -jar build/libs/autoscaler-*.jar
```

## Further Reading
https://cloud.google.com/spanner/docs/instances

## Contributing
Contributions are welcome. Please read our [Code of Conduct](CODE_OF_CONDUCT.md) first, then feel free to raise issues and pull requests as appropriate.

## License
Apache-2.0

## Contact
For any queries, please email [opensource@dmwgroup.co.uk](mailto:opensource@dmwgroup.co.uk).