# Breaktest Command Deck

Command Deck for the Breaktest machine.

## Running the application

Use the gradle commands to run the application.


## Project structure

tbd

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
