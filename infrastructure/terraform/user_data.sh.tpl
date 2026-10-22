#!/bin/bash
# Bootstraps Docker, mounts the persistent data volume and starts GitHubPulse with Docker Compose.
set -euxo pipefail
exec > >(tee /var/log/githubpulse-bootstrap.log) 2>&1

export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y ca-certificates curl git gnupg

# Docker Engine and the Compose plugin from Docker's apt repository
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" > /etc/apt/sources.list.d/docker.list
apt-get update -y
apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
systemctl enable --now docker

# Persistent data volume (attached as /dev/sdf, appears as an NVMe device on Nitro instances)
DATA_DEV=""
for i in $(seq 1 30); do
  DATA_DEV=$(lsblk -dpno NAME,SERIAL | awk '/vol/ && $1 !~ /nvme0n1$/ {print $1; exit}')
  [ -n "$DATA_DEV" ] && break
  sleep 2
done
if [ -n "$DATA_DEV" ]; then
  if ! blkid "$DATA_DEV" > /dev/null 2>&1; then
    mkfs.ext4 -L pulsedata "$DATA_DEV"
  fi
  systemctl stop docker docker.socket
  mkdir -p /var/lib/docker
  grep -q pulsedata /etc/fstab || echo "LABEL=pulsedata /var/lib/docker ext4 defaults,nofail 0 2" >> /etc/fstab
  mount -a
  systemctl start docker
fi

# Application
git clone --branch "${repo_ref}" "${repo_url}" /opt/githubpulse
cd /opt/githubpulse

cat > .env <<ENVFILE
POSTGRES_USER=githubpulse
POSTGRES_PASSWORD=${postgres_password}
GITHUB_TOKEN=${github_token}
%{ if domain == "" ~}
HTTP_PORT=80
%{ else ~}
# Caddy owns ports 80 and 443; the frontend container is only reachable through it
HTTP_PORT=8088
DOMAIN=${domain}
CORS_ALLOWED_ORIGINS=https://${domain}
%{ endif ~}
ENVFILE
chmod 600 .env

%{ if domain != "" ~}
docker compose --profile https up -d --build
%{ else ~}
docker compose up -d --build
%{ endif ~}
