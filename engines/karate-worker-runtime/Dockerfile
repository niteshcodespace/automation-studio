FROM eclipse-temurin:21-jre-alpine@sha256:704db3c40204a44f471191446ddd9cda5d60dab40f0e15c6507b815ed897238b
RUN addgroup -S worker && adduser -S -G worker -u 10001 worker
COPY target/karate-worker-runtime-0.0.1-SNAPSHOT.jar /opt/worker/worker.jar
USER 10001:10001
WORKDIR /work
ENTRYPOINT ["/opt/java/openjdk/bin/java","-Xms16m","-Xmx64m","-Djava.io.tmpdir=/work/tmp","-jar","/opt/worker/worker.jar"]
