# Breaktest Command Deck
This project is a Command Deck for the Breaktest machine. It's built with Java, TypeScript, SQL, Spring Boot, Gradle, JavaScript, npm, and React.
The Breaktest Machine is a sophisticated electric motor-based testing apparatus designed for conducting comprehensive material analysis. It specializes in destructive testing (break tests) and cyclic load testing capabilities. This versatile machine enables precise measurement and evaluation of material properties through controlled application of tensile forces and repeated stress cycles. It serves as an essential tool for quality control, research and development,  and material characterization in engineering applications.

## Documentation

In-depth documentation lives under [`doc/`](doc/README.md) — start with
[`doc/README.md`](doc/README.md) for the suggested reading order, C4
diagrams and a fresh-clone quickstart.

## Table of Contents

- [Documentation](#documentation)
- [Prerequisites](#prerequisites)
- [Setup and Development](#setup-and-development)
- [Project Structure](#project-structure)
- [Contributing](#contributing)
- [Useful Links](#useful-links)
- [Deploying using Docker](#deploying-using-docker)

## Prerequisites

Before you begin, ensure you have the following installed:

- Java 26
- Node & pnpm

## Setup and Development

1. Clone the repository to your local machine.
2. Open the project in IntelliJ IDEA or your preferred IDE.
3. Run the Gradle build using the command `./gradlew build`.
4. Start a module with `./gradlew :cms:bootRun` or `./gradlew :command-deck:bootRun`.


## Project Structure

This is a multi-module project with the following structure:

- `cms` - CMS application module (All about managing the content)
  - `src/main/java` - Java source files
  - `src/main/resources` - Resources like static files, templates, and the application properties
  - `src/main/frontend` - Frontend source files
    - `src/main/frontend/generated` - Generated frontend files (api, models...)
- `command-deck` - Command Deck application module (includes the connection to the scale and frequency converter)
  - `src/main/java` - Java source files
  - `src/main/resources` - Resources like static files, templates, and the application properties
  - `src/main/frontend` - Frontend source files
    - `src/main/frontend/generated` - Generated frontend files (api, models...)
- `drivers` - Driver plugin JARs, loaded at launch and never built in (see `drivers/README.md`)
- `build.gradle` - Root Gradle build file
- `settings.gradle` - Gradle settings file

## Contributing

We welcome contributions! Please see our [Contributing Guide](CONTRIBUTING.md) for more details. 

## Useful links

- Read the documentation at [hilla.dev/docs](https://hilla.dev/docs/).
- Ask questions on [Stack Overflow](https://stackoverflow.com/questions/tagged/hilla) or join our [Discord channel](https://discord.gg/MYFq5RTbBn).
- Report issues, create pull requests in [GitHub](https://github.com/vaadin/hilla).


## Deploying using Docker

One compose file serves two deployments on two hosts, chosen by profile: `cms` on the cloud
host, `deck` wherever tests and simulations run (the deck container always runs the simulated
bench; driving the real bench is the native Windows deployment,
[`doc/ops/bench-deployment.md`](doc/ops/bench-deployment.md)). Both bind host port 8043, so a
host runs exactly one.

1. Copy `docker/.env.example` to `docker/.env` and set `DECK_ADMIN_PASSWORD` for the first
   start (no account is seeded outside `dev`).
2. Put the Postgres password in `.secrets/db-password.txt` at the repo root.
3. Start the profile for this host:
    ```sh
    docker compose -f docker/docker-compose.yaml --profile cms up -d   # cloud host
    docker compose -f docker/docker-compose.yaml --profile deck up -d  # tester / simulation host
    ```
4. Stop with `docker compose -f docker/docker-compose.yaml --profile <profile> down`.

The full host preparation (keystore, secrets, volumes) is in
[`doc/ops/docker-and-profiles.md`](doc/ops/docker-and-profiles.md#required-host-preparation).
