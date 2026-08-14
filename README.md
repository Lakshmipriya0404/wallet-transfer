# Paytm Wallet & P2P Transfer Service

This repository contains the solution for the Paytm PML Take-Home Assignment. It is a highly robust, concurrent, and observable API for a wallet and peer-to-peer transfer service.

## Architecture & Design
Please refer to the `writeup.md` file located in the root of this repository for a detailed breakdown of the concurrency strategy, idempotency controls, and NFR priorities.

## Local Development
To run this project locally:

1. Ensure Docker and Docker Compose are installed.
2. Spin up the local database:
   ```bash
   docker-compose up -d
   ```
3. Build and Run the Spring Boot application (Requires Java 21 and Maven):
   ```bash
   mvn spring-boot:run
   ```
   Or use your IDE to run `WalletApplication.java`.

## Running the Correctness Gate (Burst Test)
The repository includes a `burst_test.sh` script to mathematically prove the system handles extreme concurrency without losing money or dropping into race conditions.

```bash
# Usage: ./burst_test.sh <API_URL> <DB_URL>
./burst_test.sh http://localhost:8080 postgres://postgres:postgres@localhost:5432/wallet
```

## Deployment Instructions (Render + Neon)

This application is designed to be deployed as a Docker container on Render, backed by a Neon PostgreSQL database.

### 1. Database Setup (Neon)
1. Create a free PostgreSQL instance on [Neon](https://neon.tech/).
2. Copy the Connection String (URI).

### 2. Application Deployment (Render)
1. Create a new **Web Service** on Render.
2. Connect this GitHub repository.
3. Choose the environment: **Docker**.
4. Set the following Environment Variables under the 12-factor app configuration:

| Variable | Description | Example |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | The active Spring profile. | `prod` |
| `DB_URL` | The JDBC URL for the Neon database. | `jdbc:postgresql://ep-xyz.region.aws.neon.tech/neondb?sslmode=require` |
| `DB_USER` | The database username. | `user_xyz` |
| `DB_PASSWORD` | The database password. | `pass_xyz` |
| `JWT_SECRET` | A secure HMAC SHA-256 secret key. | `supersecretkeythatisatleast32byteslongforhmacsha256` |

5. Deploy. Render will automatically build the multi-stage Dockerfile, run the database migrations via Flyway on startup, and expose the service securely.

## Observability
- **Health Checks:** `/healthz` (liveness) and `/readyz` (readiness with DB check).
- **Metrics:** Exposed at `/actuator/prometheus` for scraping.
- **Logging:** Structured JSON logging is enabled by default. Every request is tagged with an `X-Correlation-Id` which is included in the logs.
