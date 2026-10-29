# Versions in use

Recorded on 2026-10-29 from the tools' own output (Stage 0).

| Component | Version | How it was read |
|---|---|---|
| PostgreSQL server | 18.6 (Debian 18.6-1.pgdg13+2) | `SHOW server_version;` |
| psql client (in the container) | psql (PostgreSQL) 18.6 (Debian 18.6-1.pgdg13+2) | `psql --version` |
| MongoDB server | 8.2.12 | `db.version()` |
| mongosh (in the container) | 2.9.2 | `mongosh --version` |
| Docker Compose | 5.5.0 | `docker compose version --short` |
| Gitleaks (CI) | v8.30.1 | latest release of gitleaks/gitleaks, pinned in .github/workflows/ci.yml |