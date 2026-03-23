FROM amazoncorretto:25

RUN yum install -y fontconfig freetype dejavu-sans-fonts && yum clean all

COPY target/agenthub-orchestrator-1.0.0-SNAPSHOT.jar /opt/app.jar

ENV JAVA_OPTS="-server \
    --enable-native-access=ALL-UNNAMED \
    -Xms512M \
    -Xmx2G \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -Duser.timezone=Brazil/East \
    -Duser.language=pt \
    -Duser.country=BR \
    -Djava.net.preferIPv4Stack=true \
    -Djava.awt.headless=true"

EXPOSE 8082

ENTRYPOINT exec java $JAVA_OPTS -jar /opt/app.jar
