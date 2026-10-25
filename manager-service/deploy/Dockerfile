FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY pixelmc-manager-service.jar /app/pixelmc-manager-service.jar
EXPOSE 18800
STOPSIGNAL SIGTERM
CMD ["java","-Xms128m","-Xmx512m","-XX:+ExitOnOutOfMemoryError","-jar","/app/pixelmc-manager-service.jar","run","/data/manager.json"]
