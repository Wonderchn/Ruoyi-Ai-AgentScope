FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY . .
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar /app/application.jar
USER 10001
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
