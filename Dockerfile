FROM openjdk:11
COPY /target/paas-patch-dashboard.jar  app.jar
ENTRYPOINT ["java","-jar","/app.jar"]