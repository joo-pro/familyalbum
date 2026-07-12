FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline
COPY src src
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S familyalbum && adduser -S familyalbum -G familyalbum
COPY --from=build /app/target/family-album-0.0.1-SNAPSHOT.jar app.jar
USER familyalbum
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]