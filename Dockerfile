FROM eclipse-temurin:21-jre-alpine
RUN apk upgrade --no-cache && apk add --no-cache curl
RUN adduser -D -u 1001 app
USER 1001
WORKDIR /app
COPY gateway/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
