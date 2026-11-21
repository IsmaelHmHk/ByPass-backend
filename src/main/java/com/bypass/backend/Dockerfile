# Etapa 1: Compilacion aislada
FROM maven:3.9.9-eclipse-temurin-25-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Etapa 2: Imagen de ejecucion minima y sin privilegios
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Crear grupo y usuario sin privilegios (non-root)
RUN addgroup -S bypassgroup && adduser -S bypassuser -G bypassgroup

# Copiar unicamente el artefacto compilado con permisos restringidos
COPY --from=build --chown=bypassuser:bypassgroup /app/target/*.jar app.jar

USER bypassuser

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]