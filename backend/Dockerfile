FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -Dmaven.test.skip=true clean package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/lanlink-shopping.jar app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"
EXPOSE 8080
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar app.jar"]
