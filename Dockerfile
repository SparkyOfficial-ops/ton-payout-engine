FROM eclipse-temurin:17-jre
WORKDIR /opt/payout
COPY target/ton-payout-engine-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENV PAYOUT_HTTP_PORT=8080
ENV PAYOUT_DB_PATH=/data/payout.db
VOLUME /data
ENTRYPOINT ["java", "-jar", "/opt/payout/app.jar"]
