# Build stage
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app

# Copy gradle files for caching
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY payment-service/build.gradle.kts payment-service/build.gradle.kts
COPY member-service/build.gradle.kts member-service/build.gradle.kts
COPY notification-service/build.gradle.kts notification-service/build.gradle.kts
COPY auction-service/build.gradle.kts auction-service/build.gradle.kts
COPY auth-service/build.gradle.kts auth-service/build.gradle.kts



# Grant execute permission and download dependencies
RUN chmod +x ./gradlew

RUN sed -i 's/\r$//' gradlew

RUN ./gradlew dependencies --no-daemon

# Copy source code and build
COPY src src
COPY notification-service notification-service
COPY payment-service payment-service
COPY auction-service auction-service
COPY auth-service auth-service
RUN ./gradlew build -x test --no-daemon

# Run stage
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Copy the built jar from the build stage
COPY --from=build /app/build/libs/*.jar app.jar

# Expose the application port
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
