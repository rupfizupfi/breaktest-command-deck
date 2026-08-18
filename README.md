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

- Java
- Node & npm

## Setup and Development

1. Clone the repository to your local machine.
2. Open the project in IntelliJ Community IDEA 2024.1.2 or your preferred IDE.
3. Run the Gradle build using the command `./gradlew build`.
4. Start the application using the command `./gradlew run`.


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
- `lib` - Directory containing external JAR dependencies
- `build.gradle` - Root Gradle build file
- `settings.gradle` - Gradle settings file

## Contributing

We welcome contributions! Please see our [Contributing Guide](CONTRIBUTING.md) for more details. 

## Useful links

- Read the documentation at [hilla.dev/docs](https://hilla.dev/docs/).
- Ask questions on [Stack Overflow](https://stackoverflow.com/questions/tagged/hilla) or join our [Discord channel](https://discord.gg/MYFq5RTbBn).
- Report issues, create pull requests in [GitHub](https://github.com/vaadin/hilla).


## Deploying using Docker

To build the Dockerized version of the project, follow these steps:

1. Navigate to the `docker` directory:
    ```sh
    cd docker
    ```

2. Build the Docker images using `docker-compose`:
    ```sh
    docker-compose build
    ```

3. Start the services using `docker-compose`:
    ```sh
    docker-compose up
    ```

4. To run the services in the background, use the `-d` flag:
    ```sh
    docker-compose up -d
    ```

5. To stop the services, use:
    ```sh
    docker-compose down
    ```

The `docker-compose.yaml` file defines the services, including the CMS application and the PostgreSQL database. The CMS application is built from the `cms` module and uses the `cms/Dockerfile` for its configuration. The database service uses the official PostgreSQL image and is configured to use a secret for the database password.