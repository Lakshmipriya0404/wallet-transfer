# Implementation Plan: Wallet & P2P Transfer Service

This document breaks down the implementation of the design into actionable steps.

## Phase 1: Project Initialization & Infrastructure
- [ ] Initialize Spring Boot project (Java 21, Spring Boot 3.3.x).
- [ ] Configure `pom.xml` with required dependencies: Spring Web, Spring Security, Spring JDBC, PostgreSQL Driver, Flyway, Validation, Actuator, Micrometer Prometheus, Logback JSON, Testcontainers, REST Assured.
- [ ] Create `docker-compose.yml` to spin up a local PostgreSQL 16 database for development.
- [ ] Define `application.yml` with profiles for `local` (using docker-compose DB) and `prod` (expecting environment variables for Render/Neon).
- [ ] Write the initial Flyway migration `V1__init_schema.sql` defining `wallets` and `transfers` tables with all necessary constraints.

## Phase 2: Core Domain & Exception Handling
- [ ] Create DTOs for requests and responses (`TransferRequest`, `TransferResponse`, `WalletResponse`, `ErrorResponse`).
- [ ] Create custom runtime exceptions (`InsufficientFundsException`, `IdempotencyConflictException`, `WalletNotFoundException`).
- [ ] Implement a `@RestControllerAdvice` global exception handler to map exceptions to the correct HTTP status codes (e.g., 400 for bad requests, 409 for conflicts).

## Phase 3: Security & Observability
- [ ] Implement a `CorrelationIdFilter` to inject a unique UUID into the SLF4J MDC for every request.
- [ ] Configure `logback-spring.xml` to output structured JSON logs including the MDC correlation ID.
- [ ] Implement `JwtAuthenticationFilter` to validate symmetric HMAC-SHA256 JWTs.
- [ ] Configure Spring Security (`SecurityFilterChain`) to require authentication on all endpoints except `/healthz`, `/readyz`, and `/actuator/prometheus`.

## Phase 4: Data Access & Business Logic
- [ ] Implement `WalletService` utilizing `JdbcTemplate`:
  - `getOrCreateWallet(userId)`: Uses `INSERT ... ON CONFLICT DO NOTHING`.
  - `getBalance(userId)`: Simple select.
- [ ] Implement `TransferService` utilizing `JdbcTemplate` with strict `@Transactional` boundaries:
  - Idempotency guard (`INSERT ... ON CONFLICT`).
  - Sender balance check.
  - Deterministic row locking (updating balances in alphabetical order of `user_id`).
  - Returning the `TransferResponse`.

## Phase 5: API Controllers
- [ ] Implement `AccountController`:
  - `POST /accounts` -> returns `{ balance }`
  - `GET /accounts/me` -> returns `{ balance }`
- [ ] Implement `TransferController`:
  - `POST /transfers` -> handles body `{ to_user, amount_paise, idempotency_key }`
  - `GET /transfers/{id}` -> returns transfer details with authorization check (caller must be sender or receiver).
- [ ] Implement `HealthController`:
  - `GET /healthz` and `GET /readyz` (which performs a simple `SELECT 1` DB check).

## Phase 6: Testing & The "Correctness Gate"
- [ ] Write integration tests using `@SpringBootTest` and Testcontainers to verify standard API flows.
- [ ] Write a dedicated Concurrency Integration Test that fires 100+ concurrent requests using an `ExecutorService` to guarantee the get-or-create race and deadlocks do not occur.
- [ ] Write `burst_test.sh`, a bash script that uses `curl` or a load testing tool to fire the concurrency gate against the live server.
- [ ] Write a DB script to bootstrap initial test wallets for the burst test without exposing a public funding API.

## Phase 7: Containerization & Deployment Setup
- [ ] Write a multi-stage `Dockerfile` (build using Maven, run using a minimal JRE).
- [ ] Ensure the container includes a `HEALTHCHECK`.
- [ ] Document the exact environment variables required for Render deployment.
