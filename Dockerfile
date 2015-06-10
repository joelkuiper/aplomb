FROM ubuntu:14.10
ENV DEBIAN_FRONTEND noninteractive

# Set locale
RUN locale-gen en_US.UTF-8
RUN update-locale LANG=en_US.UTF-8

RUN apt-get update
RUN apt-get upgrade -y

# create deploy user
RUN useradd --create-home --home /var/lib/deploy deploy

RUN apt-get -y install openjdk-8-jre-headless --no-install-recommends --no-install-suggests

ADD ./target/ocpu-balancer.jar /var/lib/deploy/ocpu-balancer.jar

USER deploy
EXPOSE 3000
ENV HOME /var/lib/deploy
ENV DEV false
ENV PORT 3000

ENTRYPOINT ["java"]

VOLUME ["/db"]
CMD ["-jar", "/var/lib/deploy/ocpu-balancer.jar"]
