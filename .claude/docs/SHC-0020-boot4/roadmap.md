# SHC-0020 — Upgrade to Spring Boot 4 — Roadmap

Flow: **simplified** — everything in `ticket.md`, gates kept.

- [x] Phase 1 — Analysis
- [x] Phase 2 — Solution design
- [ ] Phase 3 — Implementation
    - [x] Task 1 — `pom.xml`: parent 4.1.1, renamed starters, test starters, Flyway starter, properties migrator
    - [x] Task 2 — Main code compiles (Jackson 3, Tomcat packages, Security 7)
    - [x] Task 3 — Test code compiles
    - [x] Task 4 — Full suite green
    - [x] Task 5 — JSON before/after comparison
    - [x] Task 6 — Local dev run
    - [x] Task 7 — Remove migrator; docs
    - [ ] Task 8 — Deploy (on the owner's order) + check `test`
- [ ] Phase 4 — Tests
- [ ] Close ticket (ask)
