FROM mcr.microsoft.com/openjdk/jdk:25-ubuntu
WORKDIR /app
COPY . .
ENV MODEL_HOST=http://host.docker.internal:8081
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*
EXPOSE 8080
CMD ["mvn", "spring-boot:run"]