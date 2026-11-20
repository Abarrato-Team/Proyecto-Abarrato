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
# Seguridad (ISO 27002 8.2, OWASP A02:2025): el servidor no corre como administrador (root).
# Si alguien lograra ejecutar código dentro del contenedor, no podría modificar el sistema.
RUN groupadd --system abarrato && useradd --system --gid abarrato --no-create-home abarrato
COPY --from=build --chown=abarrato:abarrato /app/target/*.jar app.jar
USER abarrato
# Cloud Run envía el tráfico al puerto de la variable PORT (8080) en todas las interfaces.
ENV SERVER_ADDRESS=0.0.0.0
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Duser.timezone=America/Santiago"
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
