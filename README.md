# ByPass Backend API

RESTful backend service for **ByPass**, a real-time commuter transit alerts, crowd-level monitoring, and smart rerouting mobile application built with Java, Spring Boot, and MySQL.

## Core Features
- **Real-Time Transit Ingestion:** Automated scheduled workers parsing GTFS & GTFS-Realtime feeds (vehicle positions, trip updates, and official service alerts).
- **Smart Rerouting Engine ("In a Hurry" Mode):** Dynamic route recalculation factoring in live train delays, active line disruptions, and verified community reports.
- **Train Crowd Thermometer:** Real-time carriage occupancy tracking (head, middle, tail) and time-slot congestion analytics.
- **Community Reports & Trust Score:** GPS-validated incident reporting with consensus verification, anti-spam reputation scoring, and structured operator reporting.
- **Security-First Architecture:** Stateless JWT authentication, BCrypt password hashing, role-based access control (`VIAJERO`, `MODERADOR`, `ADMIN`), and API rate limiting.

## Tech Stack
- **Language:** Java 21
- **Framework:** Spring Boot 3 (Spring Web, Spring Security, Spring Data JPA)
- **Database:** MySQL 8.x (`bypass_db`)
- **Data Standards:** GTFS / GTFS-Realtime (Protocol Buffers)

## Project Roadmap
1. **Phase 1:** Relational database schema (`bypass_db`) & GTFS data ingestion service.
2. **Phase 2:** Spring Boot REST API, JWT security, and smart rerouting algorithm.
3. **Phase 3:** Native Android client (Kotlin + Jetpack Compose + Room offline cache).
4. **Phase 4:** Web analytics dashboard & operator incident export tools.
