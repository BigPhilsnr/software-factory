# The software factory app (Spring Boot, Java 21, module `factory`, port 8000).
#
# Build with the cluster BuildKit worker (see /home/admin/ai-chatkit/
# scripts/build-image.sh for the established pattern), e.g.:
#
#   buildctl --addr tcp://<buildkit-pod-ip>:1234 build \
#     --frontend dockerfile.v0 \
#     --local context=. --local dockerfile=. \
#     --opt platform=linux/amd64 \
#     --output type=docker,name=<registry>/lakelyon/software-factory:<tag>,dest=image.tar
#
# The image runs the boot jar from the `factory` module; the DB, address and
# port come from environment (CONTROL_DB_URL/USER/PASSWORD, SERVER_ADDRESS,
# SERVER_PORT).

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn -B -ntp -pl factory -am package \
      -DskipTests \
      -Dspotless.check.skip=true -Dspotless.apply.skip=true \
      -Djacoco.skip=true -Dspotbugs.skip=true -Dpmd.skip=true \
      -Dcheckstyle.skip=true -Denforcer.skip=true

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/factory/target/factory-*.jar /app/app.jar
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
