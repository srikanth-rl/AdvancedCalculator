# ── Build ──────────────────────────────────────────
FROM ubuntu:24.04 AS builder

RUN apt-get update && \
    apt-get install -y maven curl && \
    rm -rf /var/lib/apt/lists/*

# Auto-detect arch and download correct JDK 25
RUN ARCH=$(uname -m) && \
    if [ "$ARCH" = "x86_64" ]; then \
        JDK_URL="https://download.oracle.com/java/25/latest/jdk-25_linux-x64_bin.tar.gz"; \
    elif [ "$ARCH" = "aarch64" ]; then \
        JDK_URL="https://download.oracle.com/java/25/latest/jdk-25_linux-aarch64_bin.tar.gz"; \
    fi && \
    curl -Lo /tmp/jdk25.tar.gz $JDK_URL && \
    mkdir -p /opt/jdk25 && \
    tar -xzf /tmp/jdk25.tar.gz -C /opt/jdk25 --strip-components=1 && \
    rm /tmp/jdk25.tar.gz

ENV JAVA_HOME=/opt/jdk25
ENV PATH=$JAVA_HOME/bin:$PATH

WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src/ src/
RUN mvn clean package -DskipTests -q

# ── Runtime ─────────────────────────────────────────
FROM tomcat:11.0-jdk21-temurin

COPY --from=builder /opt/jdk25 /opt/jdk25
ENV JAVA_HOME=/opt/jdk25
ENV PATH=$JAVA_HOME/bin:$PATH

RUN rm -rf /usr/local/tomcat/webapps/*
COPY --from=builder /app/target/ROOT.war /usr/local/tomcat/webapps/ROOT.war

EXPOSE 8080