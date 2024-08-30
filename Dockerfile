FROM openjdk:11
ARG JAR_FILE=./build/libs/trade-0.0.1-SNAPSHOT.jar
COPY ${JAR_FILE} app.jar
ENV JAVA_OPTS="-Xms4g -Xmx6g"
ENTRYPOINT ["java","-jar","/app.jar"]