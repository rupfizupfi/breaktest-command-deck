# Breaktest Command Deck
This project is a Command Deck for the Breaktest machine. It's built with Java, TypeScript, SQL, Spring Boot, Gradle, JavaScript, npm, and React.

## Table of Contents

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

This is a simple mono module project with the following structure:

- `src/main/java` - Java source files
- `src/main/frontend` - Frontend source files
    - `src/main/frontend/generated` - Generated frontend files (api, models...)
- `src/main/resources` - Resources like static files, templates, and the application properties

## Contributing

We welcome contributions! Please see our [Contributing Guide](CONTRIBUTING.md) for more details. 

## Useful links

- Read the documentation at [hilla.dev/docs](https://hilla.dev/docs/).
- Ask questions on [Stack Overflow](https://stackoverflow.com/questions/tagged/hilla) or join our [Discord channel](https://discord.gg/MYFq5RTbBn).
- Report issues, create pull requests in [GitHub](https://github.com/vaadin/hilla).


## Deploying using Docker

To build the Dockerized version of the project, run

```
mvn clean package -Pproduction
docker build . -t breaktest-command-deck:latest
```

Once the Docker image is correctly built, you can test it locally using

```
docker run -p 8080:8080 breaktest-command-deck:latest
```
