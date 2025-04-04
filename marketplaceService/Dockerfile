FROM eclipse-temurin:21

WORKDIR /marketplaceService

COPY .mvn/ .mvn
COPY mvnw .
COPY pom.xml .
COPY src ./src

ENTRYPOINT ["./mvnw", "compile", "exec:java"]
