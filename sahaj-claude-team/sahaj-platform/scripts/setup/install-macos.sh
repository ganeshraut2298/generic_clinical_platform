#!/usr/bin/env bash
# Sahaj developer setup for macOS (needs Homebrew: https://brew.sh)
set -euo pipefail
brew install git openjdk@17 maven node@22 postgresql@16
brew link --force node@22 postgresql@16
sudo ln -sfn "$(brew --prefix)/opt/openjdk@17/libexec/openjdk.jdk" /Library/Java/JavaVirtualMachines/openjdk-17.jdk
brew services start postgresql@16
# Homebrew PostgreSQL has no 'postgres' password by default; create the superuser login the setup script expects:
psql -d postgres -c "DO \$\$BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='postgres') THEN CREATE ROLE postgres SUPERUSER LOGIN; END IF; END\$\$;"
echo "Now set a password: psql -d postgres -c \"ALTER USER postgres PASSWORD 'your-strong-password';\""
echo "Then open a new terminal and run: node scripts/setup/check-env.mjs"
