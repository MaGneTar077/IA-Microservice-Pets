# ---------- Etapa de build ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -Dmaven.test.skip=true
# El artifactId es IA-service: el wildcard se resuelve aquí, no en el COPY de abajo
# (el wildcard directo en COPY --from ya falló antes con "no source files were specified").
RUN mv target/*.jar app.jar

# ---------- Etapa de runtime ----------
# Jammy explícito (Ubuntu/glibc): el tag sin sufijo puede cambiar de base, y con Alpine
# (musl) las librerías nativas de gRPC/Netty de spring-cloud-gcp-starter-pubsub mueren
# con "Uncaught signal: 6 (SIGABRT)" al arrancar.
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN groupadd --system spring && useradd --system --gid spring spring
COPY --from=build /app/app.jar app.jar
RUN chown -R spring:spring /app
USER spring:spring
EXPOSE 8083
# 1g de heap: los adjuntos llegan hasta 20MB por archivo, se pasan a base64 (+33%) y
# viajan dentro del JSON que se le manda a Gemini; la verificación procesa 4 documentos
# en paralelo. Con 512m da OOM.
ENV JAVA_OPTS="-Xmx1g -Xms512m"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
