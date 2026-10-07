FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY . .
RUN mvn -B -ntp -Pprod -DskipTests package

FROM eclipse-temurin:17-jre-alpine
RUN apk add --no-cache ffmpeg
WORKDIR /app
COPY --from=build /src/ruoyi-admin/target/ruoyi-admin.jar /app/application.jar
RUN mkdir -p /app/tmp && chown -R 10001:0 /app
USER 10001
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 6039
ENTRYPOINT ["java", "-Djava.io.tmpdir=/app/tmp", "-jar", "/app/application.jar"]
