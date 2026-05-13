# ─── Stage 1: Build ───────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Descargar dependencias antes de copiar el código fuente.
# Mientras pom.xml no cambie, Docker reutiliza esta capa cacheada.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -q

COPY src/ src/
RUN ./mvnw package -DskipTests -q

# Extraer el fat-jar en capas ordenadas por frecuencia de cambio.
# Esto convierte una sola capa enorme en 4 capas más pequeñas:do
#   dependencies (libs estables) → spring-boot-loader → snapshot-dependencies → application (tu código)
# Resultado: si solo cambia tu código, Docker solo reconstruye la última capa.
RUN java -Djarmode=layertools \
        -jar target/challenge-meli-*.jar \
        extract --destination extracted/


# ─── Stage 2: Runtime ─────────────────────────────────────────────────────────
# JRE en lugar de JDK: ~100 MB menos. Alpine: imagen base mínima.
FROM eclipse-temurin:21-jre-alpine AS runtime

# Usuario no-root dedicado. UID/GID fijos para que sean predecibles
# si se monta un volumen con permisos específicos.
RUN addgroup -S --gid 1001 app && \
    adduser  -S --uid 1001 -G app app

WORKDIR /app

# Copiar las 4 capas del fat-jar extraído en orden ascendente de cambio.
# Cada COPY es una capa Docker independiente → máximo reuso de cache.
COPY --from=builder --chown=app:app /build/extracted/dependencies/          ./
COPY --from=builder --chown=app:app /build/extracted/spring-boot-loader/    ./
COPY --from=builder --chown=app:app /build/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=app:app /build/extracted/application/           ./

USER app

# JVM_OPTS está en ENV (no ARG) para que pueda sobreescribirse en tiempo de
# despliegue sin reconstruir la imagen:
#   docker run -e JVM_OPTS="..." ...
#
# Flags explicados:
#   UseContainerSupport        → JVM lee cgroups v1/v2 para los límites de CPU y RAM del contenedor
#   MaxRAMPercentage=75        → Heap máximo = 75 % del RAM del contenedor (deja margen para metaspace, threads, etc.)
#   InitialRAMPercentage=50    → Arranca con 50 % del RAM para reducir GC del warm-up
#   UseZGC                     → ZGC generacional (default en Java 21): pausas <1 ms, ideal para APIs de baja latencia
#   ExitOnOutOfMemoryError     → Mata el proceso ante OOM en lugar de dejar el pod en estado inconsistente
#   java.security.egd          → Fuente de entropía no bloqueante: evita que SecureRandom bloquee el arranque
#   preferIPv4Stack            → Previene timeouts de resolución DNS dual-stack en redes solo IPv4
ENV JVM_OPTS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:InitialRAMPercentage=50.0 \
  -XX:+UseZGC \
  -XX:+ExitOnOutOfMemoryError \
  -Djava.security.egd=file:/dev/./urandom \
  -Djava.net.preferIPv4Stack=true \
  -Dfile.encoding=UTF-8"

EXPOSE 8080

# Healthcheck apunta al probe de liveness (no al readiness) para que Docker
# no reinicie el contenedor mientras las dependencias externas estén caídas.
# start-period cubre el warm-up de Spring Boot (~3-5 s en local, ~10 s en prod).
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/health/live || exit 1

# exec reemplaza el shell por Java como PID 1.
# Así SIGTERM llega directo al proceso y Spring Boot ejecuta su graceful shutdown.
ENTRYPOINT ["sh", "-c", "exec java $JVM_OPTS org.springframework.boot.loader.launch.JarLauncher"]
