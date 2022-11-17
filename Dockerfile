FROM openjdk:11
COPY /target/paas_dashboard.jar  app.jar
ENTRYPOINT ["java","-jar","/app.jar"]