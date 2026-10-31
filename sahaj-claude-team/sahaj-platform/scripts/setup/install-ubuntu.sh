#!/usr/bin/env bash
# Sahaj developer setup for Ubuntu 22.04/24.04 (run with sudo rights)
set -euo pipefail
sudo apt-get update
sudo apt-get install -y git openjdk-17-jdk maven curl ca-certificates gnupg
# PostgreSQL 16 from the official PostgreSQL apt repository
sudo install -d /usr/share/postgresql-common/pgdg
sudo curl -fsSL -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc https://www.postgresql.org/media/keys/ACCC4CF8.asc
echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] https://apt.postgresql.org/pub/repos/apt $(. /etc/os-release && echo "$VERSION_CODENAME")-pgdg main" | sudo tee /etc/apt/sources.list.d/pgdg.list
sudo apt-get update && sudo apt-get install -y postgresql-16
# Node 22 LTS from NodeSource
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash - && sudo apt-get install -y nodejs
sudo systemctl enable --now postgresql
echo "Set the postgres password: sudo -u postgres psql -c \"ALTER USER postgres PASSWORD 'your-strong-password';\""
echo "Then run: node scripts/setup/check-env.mjs"
