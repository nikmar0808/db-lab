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
| Java (local) | openjdk version "21.0.11" 2026-04-21 LTS | `java -version` |
| Spring Boot | 4.1.2 | Maven, `dependency:list` |
| Hibernate ORM | 7.4.13.Final | Maven, `dependency:list` |
| Flyway | 12.4.0 | Maven, `dependency:list` |
| HikariCP | 7.0.2 | Maven, `dependency:list` |
| PostgreSQL JDBC driver | 42.7.14 | Maven, `dependency:list` |
| Testcontainers | 2.0.5 | Maven, `dependency:list` |
| JUnit Jupiter | 6.0.3 | Maven, `dependency:list` |
