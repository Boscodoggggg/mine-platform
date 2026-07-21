FROM eclipse-temurin:21.0.7_6-jre-jammy

RUN groupadd --gid 10001 simulator \
    && useradd --uid 10001 --gid simulator --home-dir /opt/simulator --create-home simulator \
    && mkdir -p /data \
    && chown simulator:simulator /data

COPY target/mine-device-simulator-app-0.1.0-SNAPSHOT.jar /opt/simulator/app.jar

USER 10001:10001
WORKDIR /opt/simulator
VOLUME ["/data"]
EXPOSE 18090

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/opt/simulator/app.jar"]
