FROM eclipse-temurin:21-jre-alpine@sha256:704db3c40204a44f471191446ddd9cda5d60dab40f0e15c6507b815ed897238b
RUN addgroup -S gateway && adduser -S -G gateway -u 10002 gateway
COPY target/karate-gateway-runtime-0.0.1-SNAPSHOT.jar /opt/gateway/gateway.jar
USER 10002:10002
WORKDIR /work
ENTRYPOINT ["/opt/java/openjdk/bin/java","-Xms16m","-Xmx128m","-Djava.io.tmpdir=/work/tmp","-jar","/opt/gateway/gateway.jar"]
