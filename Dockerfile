FROM eclipse-temurin:21-jre-alpine
RUN apk upgrade --no-cache && apk add --no-cache curl
RUN adduser -D -u 1001 app
# STAM-68 AC1: OTel Java agent, no-code instrumentation -- same as the 5
# backend services. --chmod since ADD from a URL defaults to 0600.
ADD --chmod=644 https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.26.1/opentelemetry-javaagent.jar /otel-javaagent.jar
USER 1001
WORKDIR /app
COPY gateway/target/*.jar app.jar
ENV JAVA_TOOL_OPTIONS="-javaagent:/otel-javaagent.jar"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
