# speed-intranet Java 8

Reimplementation of the network bandwidth measurement tool in Java 8.

## Requirements

- JDK 8+
- Maven 3.6+

## Build

```bash
cd java
mvn clean package
```

Output JAR:

- `target/speed-intranet-java8-1.0.0.jar`

## Run

Server:

```bash
java -jar target/speed-intranet-java8-1.0.0.jar server --port 5201
```

Client:

```bash
java -jar target/speed-intranet-java8-1.0.0.jar client --server 192.168.1.2 --tests all --direction both --repeat 3 --timeout 10 --output results.json
```

Auto mode with root config:

```bash
java -jar target/speed-intranet-java8-1.0.0.jar auto --config ../config.ini --output results.csv
```

## CLI options

- `--version`
- `--server <ip>`
- `--port <int>`
- `--config <ini file>`
- `--tests <all|small|medium|large|small,medium>`
- `--direction <upload|download|both>`
- `--repeat <int>`
- `--timeout <seconds>`
- `--output <file.json|file.csv>`
