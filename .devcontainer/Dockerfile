FROM mcr.microsoft.com/devcontainers/base:ubuntu-24.04

ARG DEBIAN_FRONTEND=noninteractive
ARG TARGETARCH

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bash \
        ca-certificates \
        curl \
        git \
        gnupg \
        libasound2t64 \
        libdrm2 \
        libgbm1 \
        libgl1 \
        libglu1-mesa \
        libgtk-3-0 \
        libnss3 \
        libx11-6 \
        libxcursor1 \
        libxext6 \
        libxi6 \
        libxinerama1 \
        libxrandr2 \
        libxrender1 \
        libxtst6 \
        libxxf86vm1 \
        mesa-utils \
        procps \
        software-properties-common \
        sudo \
        unzip \
        wget \
        zip \
    && apt-get update \
    && apt-get install -y --no-install-recommends maven \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

RUN set -eux; \
    case "${TARGETARCH:-amd64}" in \
      amd64) jdk_arch="x64" ;; \
      arm64) jdk_arch="aarch64" ;; \
      *) echo "Unsupported architecture: ${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    mkdir -p /opt; \
    curl -fsSL "https://api.adoptium.net/v3/binary/latest/11/ga/linux/${jdk_arch}/jdk/hotspot/normal/eclipse" -o /tmp/temurin11.tar.gz; \
    tar -xzf /tmp/temurin11.tar.gz -C /opt; \
        rm -f /tmp/temurin11.tar.gz

RUN set -eux; \
    jdk_dir="$(find /opt -maxdepth 1 -type d -name 'jdk-11*' | sort | tail -n 1)"; \
    test -n "$jdk_dir"; \
    ln -sfn "$jdk_dir" /opt/temurin-11

ENV JAVA_HOME=/opt/temurin-11
ENV PATH="${JAVA_HOME}/bin:${PATH}"
ENV MAVEN_OPTS="-Dmaven.repo.local=/root/.m2/repository"