AGENT.md

RouteBot Development Rules

Project Overview

RouteBot is an enterprise-grade Android Device Agent for RouteDNS SaaS.

The project consists of:

* Android Agent (Kotlin)
* Backend API (Go)
* PostgreSQL
* Redis/Valkey
* WebSocket Communication
* Docker Deployment

The goal is to build a secure, modular, scalable, production-ready system.

⸻

Development Philosophy

Always prioritize:

* Security
* Reliability
* Maintainability
* Performance
* Simplicity
* Scalability


⸻

Architecture

Always follow:

* Clean Architecture
* SOLID Principles
* Repository Pattern
* Dependency Injection
* Feature-based modules

Business logic must never depend on UI.

⸻

Backend Standards

Language

* Go 1.26.5

Framework

* Fiber v3

Database

* PostgreSQL

Cache

* Redis / Valkey

Communication

* REST API
* WebSocket

Authentication

* JWT
* API Keys

Logging

* Structured JSON Logs

Configuration

* Environment Variables
* No hardcoded secrets

⸻

Android Standards

Language

* Kotlin

UI

* Jetpack Compose

Architecture

* MVVM

Dependency Injection

* Hilt

Database

* Room

Networking

* OkHttp
* Retrofit

Background Tasks

* WorkManager
* Foreground Service

Security

* Android Keystore

Async

* Coroutines
* Flow

⸻

Folder Structure

Always keep modules separated.

Example:

android/

* app
* core
* common
* data
* domain
* features
* services
* workers

backend/

* cmd
* internal
* api
* services
* repository
* models
* middleware
* websocket
* config
* database

⸻

API Rules

Every API must:

* Validate input
* Return proper HTTP status codes
* Return JSON only
* Have consistent response format
* Be documented

Standard response:

Success

{
“success”: true,
“data”: {}
}

Error

{
“success”: false,
“error”: {
“code”: “…”,
“message”: “…”
}
}

⸻

WebSocket Rules

Always implement:

* Auto reconnect
* Heartbeat
* Ping/Pong
* Retry with exponential backoff
* Offline queue
* Message acknowledgement

Never lose messages silently.

⸻

Security Rules

Always:

* Encrypt sensitive local data
* Store secrets in Android Keystore
* Use HTTPS only
* Verify JWT
* Verify API Keys
* Validate every request
* Sanitize every input
* Rate limit public APIs
* Implement replay protection where appropriate
* Verify webhook signatures

Never:

* Hardcode secrets
* Disable TLS verification
* Store plaintext credentials
* Commit secrets to Git


⸻

SMS Gateway

Support:

* Send SMS
* Receive SMS
* Multi-SIM 
* Delivery status 
* Webhook forwarding
* resend capability
* add more feat if u need after asking

Handle failures gracefully.

⸻

OTP Relay

Support:

* OTP detection from SMS
* Regex extraction
* Secure webhook forwarding

Never assume OTP formats.

Patterns must be configurable.

⸻

Notification Gateway

Support:

* Notification Listener
* App filtering
* JSON forwarding

Do not depend on private Android APIs.

⸻

USSD

Use any unofficial/official supported Android mechanisms.

Clearly document that:

* Support varies by Android version.
* Support varies by device manufacturer.
* Some requested operations may not be available because of platform limitations.


⸻

Device Monitor

Collect only information required for device administration.

Examples:

* Battery
* Charging
* Network
* Signal
* Storage
* Memory
* CPU
* SIM
* Android Version
* Manufacturer
* Model
* App Version

⸻

Call Monitor

Where Android permissions allow, support:

* Incoming call events
* Outgoing call events
* Missed call events
* Call state

Handle permission denial gracefully.

⸻

Remote Commands

Support predefined commands only.

Examples:

* Sync
* Ping
* Restart internal worker
* Refresh configuration
* Upload logs
* Clear cache

⸻

Webhooks

Every webhook must include:

* Timestamp
* Signature
* Retry
* Idempotency
* Request verification

⸻

Logging

Every important event should be logged.

Never log:

* JWT tokens
* API Keys
* Secrets

Use structured logs.

⸻

Error Handling

Always:

* Return meaningful errors
* Log internal errors
* Hide implementation details from API clients

Never ignore errors.

⸻

Testing

Every feature should include:

* Unit Tests
* Integration Tests (where applicable)

Critical business logic should be testable without Android UI.

⸻

Documentation

Public functions should be documented.

Major modules require:

* README
* Architecture notes
* API documentation

⸻

Git Rules

Commit messages:

feat:
fix:
refactor:
docs:
test:
chore:
ci:

Never commit generated files unless required.

⸻

Code Quality

Always:

* Small functions
* Small files
* Clear naming
* Dependency Injection
* No duplicated code
* Prefer composition over inheritance

Avoid unnecessary abstractions.

⸻

Performance

Optimize for:

* Battery usage
* Network usage
* Memory usage

 if need use  wake locks, polling, and background work.

⸻

AI Coding Rules

Before writing code:

1. Read the existing project structure.
2. Reuse existing utilities.
3. Do not introduce duplicate functionality.
4. Follow existing naming conventions.
5. Keep changes minimal and focused.
6. Explain architectural trade-offs in pull requests or documentation when introducing significant changes.


⸻

Final Goal

Every contribution should make RouteBot:

* Secure
* Stable
* Scalable
* Production Ready
* Easy to Maintain
* Easy to Test
* Enterprise Grade
