# Build from this repository:
#   docker build -t ton-payout-engine .
FROM eclipse-temurin:17-jdk-jammy AS build

RUN apt-get update \
    && apt-get install -y --no-install-recommends maven cmake g++ libssl-dev git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ARG CTON_SDK_REPO=https://github.com/SparkyOfficial-ops/cton-sdk-main.git
ARG CTON_SDK_REF=main

WORKDIR /src
RUN git clone --depth 1 --branch "${CTON_SDK_REF}" "${CTON_SDK_REPO}" /src/cton-sdk-main

COPY . /src/ton-payout-engine

WORKDIR /src/cton-sdk-main/cpp
RUN cmake -S . -B build -DCMAKE_BUILD_TYPE=Release \
    && cmake --build build --target cton-sdk-core

WORKDIR /src/cton-sdk-main
RUN mvn -q install -DskipTests

WORKDIR /src/ton-payout-engine
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends libssl3 \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /opt/payout/lib /data

WORKDIR /opt/payout
COPY --from=build /src/cton-sdk-main/cpp/build/libcton-sdk-core.so /opt/payout/lib/libcton-sdk-core.so
COPY --from=build /src/ton-payout-engine/target/ton-payout-engine-0.1.0-SNAPSHOT.jar /opt/payout/app.jar

ENV LD_LIBRARY_PATH=/opt/payout/lib
ENV JAVA_TOOL_OPTIONS=-Djna.library.path=/opt/payout/lib
ENV PAYOUT_HTTP_HOST=0.0.0.0
ENV PAYOUT_HTTP_PORT=8080
ENV PAYOUT_DB_PATH=/data/payout.db
ENV WALLET_KEY_PATH=/secrets/wallet.key

EXPOSE 8080
VOLUME /data
ENTRYPOINT ["java", "-jar", "/opt/payout/app.jar"]
