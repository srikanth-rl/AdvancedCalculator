## Build & Run Locally
 <!-- Build image -->
docker build -t calculator-app .

## Create & Run container
docker run -d -p 8080:8080 --name calculator calculator-app


---------------------------
## Essential Docker Commands
<!-- List running containers -->
docker ps

## List all containers (including stopped)
docker ps -a

## View live logs
docker logs -f calculator

## Stop / Start / Restart
docker stop calculator
docker start calculator
docker restart calculator

## Remove container
docker rm calculator

## Remove image
docker rmi calculator-app

## Execute shell inside running container (like SSH)
docker exec -it calculator bash

## Inspect container details (IP, mounts, env)
docker inspect calculator

## Check resource usage (CPU/RAM)
docker stats calculator

<!-- Tag for Docker Hub -->
docker tag calculator-app sri/calculator-app:v1.0

## Push to Docker Hub
docker push sri/calculator-app:v1.0

## Pull anywhere (In Oracle VM, K8s node, etc.)
docker pull sri/calculator-app:v1.0

## Environment Variables (K8s-ready pattern)
<!-- Pass JVM options via env instead of hardcoding -->
docker run -d -p 8080:8080 \
  -e JAVA_OPTS="-Xms512m -Xmx2g" \
  --name calculator calculator-app

## Volume Mount (persist logs outside container)
docker run -d -p 8080:8080 \
  -v $(pwd)/logs:/usr/local/tomcat/logs \
  --name calculator calculator-app

## Now logs survive container restarts
<!-- Build with Tag + No Cache (clean rebuild) -->
docker build --no-cache -t calculator-app:v2 .

## Prune (cleanup dangling images/containers)
bashdocker system prune -f          <!-- removes stopped containers + dangling images -->
docker image prune -a               <!-- removes ALL unused images -->

## Deploy to Oracle VM with Docker
<!-- On your Oracle VM — install Docker -->
sudo dnf install docker -y
sudo systemctl start docker
sudo systemctl enable docker
sudo usermod -aG docker opc         <!-- run docker without sudo -->

## Run it
<!-- restart always → auto-starts on VM or system reboot, replacing your current manual Tomcat setup. -->
docker run -d -p 80:8080 --restart always --name calculator sri/calculator-app:latest