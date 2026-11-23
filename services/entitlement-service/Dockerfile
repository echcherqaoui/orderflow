# Use JRE image for runtime
FROM eclipse-temurin:21.0.13.1_2-jre

# Set a consistent working directory
WORKDIR /app

# Create a non-privileged user for security
RUN addgroup --system spring && adduser --system spring --ingroup spring
USER spring:spring

# Copy the built JAR, owned by the non-root user
COPY --chown=spring:spring target/*.jar app.jar

# Define the runtime execution
ENTRYPOINT ["java", "-jar", "app.jar"]