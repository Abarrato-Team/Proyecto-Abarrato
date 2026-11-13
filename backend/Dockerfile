# Imagen del backend de Abarrato para Google Cloud Run (región Santiago).
# Etapa 1: compila el .jar con Maven y Java 17.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B -q -DskipTests package

# Etapa 2: solo lo necesario para ejecutar.
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# Cloud Run envía el tráfico al puerto de la variable PORT (8080) en todas las interfaces.
ENV SERVER_ADDRESS=0.0.0.0
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Duser.timezone=America/Santiago"
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
