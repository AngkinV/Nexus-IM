# Java backend for the public stack. The jar and the read-only 3D catalog are prepared by
# nexus-public.sh from a git commit; nothing is compiled or downloaded here.
FROM eclipse-temurin:17-jre@sha256:541b08fc7f494edf14e436ff6bfb55767e4a115c87bfc547b8c5de957b58737f
RUN groupadd -g 10001 nexus && useradd -u 10001 -g 10001 -M -d /app -s /usr/sbin/nologin nexus \
    && mkdir -p /app/uploads /app/storage /app/logs /app/assets && chown -R 10001:10001 /app
WORKDIR /app
COPY --chown=10001:10001 assets/ /app/assets/
COPY --chown=10001:10001 app.jar /app/app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
