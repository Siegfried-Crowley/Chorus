# ============================================================================
# Chorus — Docker 多阶段构建
# 构建 WAR → 部署到 Tomcat 10.1（外置 Tomcat 模式）
# ============================================================================

# 第一阶段：构建
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:resolve -q
COPY src ./src
RUN mvn clean package -DskipTests -q

# 第二阶段：Tomcat 部署
FROM tomcat:10.1-jre17

# 删除 Tomcat 默认应用
RUN rm -rf /usr/local/tomcat/webapps/*

# 部署 Discord WAR（/discord 上下文路径，对齐 application.yml）
COPY --from=build /app/target/*.war /usr/local/tomcat/webapps/chorus.war

# 环境变量通过 docker-compose 或 -e 传入
ENV SPRING_PROFILES_ACTIVE=postgres
ENV DB_URL=jdbc:postgresql://postgres:5432/chorus
ENV DB_USER=chorus
ENV DB_PASSWORD=chorus_dev_2026

EXPOSE 8080

CMD ["catalina.sh", "run"]
