# Site: Astro bakes in ../results at build time.
FROM node:22-slim AS site
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
COPY results/ ../results/
RUN npm run build

# Server: the app jar plus its runtime dependency jars.
FROM eclipse-temurin:17-jdk AS server
ADD https://repo1.maven.org/maven2/org/scala-sbt/sbt-launch/1.9.9/sbt-launch-1.9.9.jar /opt/sbt-launch.jar
RUN echo "99fd0509060b9655ece7c5a34ecd7ecb471986d4191922966100057f6b6f373c  /opt/sbt-launch.jar" | sha256sum -c
ENV SBT="java -Dsbt.log.noformat=true -Dsbt.supershell=false -jar /opt/sbt-launch.jar"
WORKDIR /src/scala
COPY scala/build.sbt ./
COPY scala/project/build.properties project/
RUN $SBT update < /dev/null
COPY scala/src src
# `export` prints the classpath as the last line of output.
RUN mkdir /lib-out \
 && $SBT package "export Runtime/dependencyClasspath" < /dev/null | tail -1 \
    | tr ':' '\n' | grep '\.jar$' | xargs -I{} cp {} /lib-out/ \
 && cp target/scala-2.12/*.jar /lib-out/

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=server /lib-out lib/
COPY --from=site /src/web/dist site/
COPY results/model_coefficients_leakfree.json .
ENV HOST=0.0.0.0 PORT=8080 SITE_DIR=/app/site MODEL_COEFFICIENTS=/app/model_coefficients_leakfree.json
USER 65534
EXPOSE 8080
# Serial GC and C1-only JIT: faster cold starts on a fractional-CPU free instance.
CMD ["java", "-XX:MaxRAMPercentage=75", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-cp", "lib/*", "com.startupsurvival.web.Server"]
