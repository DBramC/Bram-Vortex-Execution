# ===============================
# 🏗️ STAGE 1: Build (Maven)
# ===============================
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Αντιγράφουμε το pom.xml και τον κώδικα
COPY pom.xml .
COPY src ./src

# Χτίζουμε το JAR
RUN mvn clean package -DskipTests

# ===============================
# 🚀 STAGE 2: Run (Java Runtime + Infracost)
# ===============================
FROM eclipse-temurin:21-jdk-jammy

LABEL authors="DaBram"

WORKDIR /app

# 🛠️ Εγκατάσταση Infracost CLI
# Χρειαζόμαστε το curl για να κατεβάσουμε το installation script
RUN apt-get update && apt-get install -y curl && \
    curl -fsSL https://raw.githubusercontent.com/infracost/infracost/master/scripts/install.sh | sh && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Αντιγράφουμε το JAR από το Stage 1
COPY --from=build /app/target/*.jar app.jar

# Τρέχουμε το app
ENTRYPOINT ["java", "-jar", "app.jar"]