-- =============================================================================
-- docker/init.sql — PostgreSQL bootstrap hook
--
-- NOTE:
-- This file runs before the Spring Boot app and before Flyway migrations.
-- Keep it schema-agnostic. Demo seed data is created by Flyway migration:
-- V42__seed_demo_data.sql
-- =============================================================================

SELECT 1;
