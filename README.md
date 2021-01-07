# Spanner Autoscaler

A web application that provides autoscaling capability for nodes in Google Cloud Spanner instances.

![GitHub Workflow Status](https://img.shields.io/github/workflow/status/dmwgroup/spanner-autoscaler/Build)
![GitHub](https://img.shields.io/github/license/dmwgroup/spanner-autoscaler)

## Description

A background task will scale nodes up or down according to one of the following strategies:
1. Instance CPU utilization, obtained from Cloud Monitoring. 
1. A schedule, in CRON format.

Scaling events are recorded in a Spanner database.

For each of the above strategies, this application must be configured with a list of instances that will be scaled according to that strategy. `AppConfiguration` contains all possible configuration options, which can be overridden according to the usual Spring Boot mechanism.

The following endpoints are provide:
- `GET /metrics/latest`: Obtain metrics from Cloud Monitoring.
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

The following values must be specified using one of the [Spring Boot configuration mechanisms](https://docs.spring.io/spring-boot/docs/current/reference/html/spring-boot-features.html#boot-features-external-config):
- `application.balancedScalers` or `application.cronScalers`: At least one of these must be specified with list of Spanner instance IDs.
- `application.monitoringProjectId`: A Google Cloud project ID. Required if you have specified `application.balancedScalers`.
- `spring.cloud.gcp.spanner.database`: the name of the scaling events database you created.

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