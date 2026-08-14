# Implementation Plan: Wallet & P2P Transfer Service

This document breaks down the implementation of the design into actionable steps.

## Phase 1: Project Initialization & Infrastructure
- [x] Initialize Spring Boot project (Java 21, Spring Boot 3.3.x).
- [x] Configure `pom.xml` with required dependencies: Spring Web, Spring Security, Spring JDBC, PostgreSQL Driver, Flyway, Validation, Actuator, Micrometer Prometheus, Logback JSON, Testcontainers, REST Assured.
- [x] Create `docker-compose.yml` to spin up a local PostgreSQL 16 database for development.
- [x] Define `application.yml` with profiles for `local` (using docker-compose DB) and `prod` (expecting environment variables for Render/Neon).
- [x] Write the initial Flyway migration `V1__init_schema.sql` defining `wallets` and `transfers` tables with all necessary constraints.

## Phase 2: Core Domain & Exception Handling
- [x] Create DTOs for requests and responses (`TransferRequest`, `TransferResponse`, `WalletResponse`, `ErrorResponse`).
- [x] Create custom runtime exceptions (`InsufficientFundsException`, `IdempotencyConflictException`, `WalletNotFoundException`).
- [x] Implement a `@RestControllerAdvice` global exception handler to map exceptions to the correct HTTP status codes (e.g., 400 for bad requests, 409 for conflicts).

## Phase 3: Security & Observability
- [x] Implement a `CorrelationIdFilter` to inject a unique UUID into the SLF4J MDC for every request.
- [x] Configure `logback-spring.xml` to output structured JSON logs including the MDC correlation ID.
- [x] Implement `JwtAuthenticationFilter` to validate symmetric HMAC-SHA256 JWTs.
- [x] Configure Spring Security (`SecurityFilterChain`) to require authentication on all endpoints except `/healthz`, `/readyz`, and `/actuator/prometheus`.

## Phase 4: Data Access & Business Logic
- [x] Implement `WalletService` utilizing `JdbcTemplate`:
  - `getOrCreateWallet(userId)`: Uses `INSERT ... ON CONFLICT DO NOTHING`.
  - `getBalance(userId)`: Simple select.
- [x] Implement `TransferService` utilizing `JdbcTemplate` with strict `@Transactional` boundaries:
  - Idempotency guard (`INSERT ... ON CONFLICT`).
  - Sender balance check.
  - Deterministic row locking (updating balances in alphabetical order of `user_id`).
  - Returning the `TransferResponse`.

## Phase 5: API Controllers
- [x] Implement `AccountController`:
  - `POST /accounts` -> returns `{ balance }`
  - `GET /accounts/me` -> returns `{ balance }`
- [x] Implement `TransferController`:
  - `POST /transfers` -> handles body `{ to_user, amount_paise, idempotency_key }`
  - `GET /transfers/{id}` -> returns transfer details with authorization check (caller must be sender or receiver).
- [x] Implement `HealthController`:
  - `GET /healthz` and `GET /readyz` (which performs a simple `SELECT 1` DB check).

## Phase 6: Testing & The "Correctness Gate"
- [x] Write integration tests using `@SpringBootTest` and Testcontainers to verify standard API flows.
- [x] Write a dedicated Concurrency Integration Test that fires 100+ concurrent requests using an `ExecutorService` to guarantee the get-or-create race and deadlocks do not occur.
- [x] Write `burst_test.sh`, a bash script that uses `curl` or a load testing tool to fire the concurrency gate against the live server.
- [x] Write a DB script to bootstrap initial test wallets for the burst test without exposing a public funding API.

## Phase 7: Containerization & Deployment Setup
- [x] Write a multi-stage `Dockerfile` (build using Maven, run using a minimal JRE).
- [x] Ensure the container includes a `HEALTHCHECK`.
- [x] Document the exact environment variables required for Render deployment.
