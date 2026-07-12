# Automation Studio Naming Conventions

**Version:** 1.0

**Last Updated:** 2026-07-12

---

# Purpose

This document defines the naming conventions used throughout Automation Studio.

Following consistent naming conventions improves readability, maintainability, traceability, and collaboration.

---

# Product Code

| Product | Code |
|----------|------|
| Automation Studio | AS |
| MedhOrbit | MO |

---

# GitHub Issue Naming

## Epic

Format

EPIC-001

Example

EPIC-001 Project Foundation

EPIC-002 Platform Core

EPIC-003 Playwright Engine

EPIC-004 REST API

---

## User Story

Format

AS-XXX

Examples

AS-001 Initialize Repository

AS-002 Documentation Structure

AS-005 Software Architecture Specification

AS-012 Authentication Module

---

## Technical Task

Format

TASK-XXX

Examples

TASK-001 Create Spring Boot Project

TASK-002 Configure PostgreSQL

TASK-003 Setup Flyway

---

## Bug

Format

BUG-XXX

Examples

BUG-001 Login Failure

BUG-002 Execution Timeout

---

## Spike

Research / Proof of Concept

Format

SPIKE-XXX

Examples

SPIKE-001 Evaluate Playwright

SPIKE-002 AI Integration

---

## Architecture Decision Record

Format

ADR-XXX

Examples

ADR-001 Modular Engine Architecture

ADR-002 Spring Boot

ADR-003 PostgreSQL

---

# Git Branch Naming

Main branches

main

develop

Feature

feature/AS-005-software-architecture

Bug

fix/BUG-001-login-timeout

Hotfix

hotfix/BUG-015-null-pointer

Refactor

refactor/execution-service

Documentation

docs/sprint-0

Release

release/v0.1.0

---

# Git Commit Convention

Automation Studio follows Conventional Commits.

Format

type(scope): short description

Examples

feat(api): add project management endpoints

feat(playwright): implement login execution

fix(database): resolve execution status mapping

docs(architecture): add software architecture specification

docs(project): establish Sprint 0 product planning foundation

refactor(core): simplify execution engine registration

test(api): add integration tests for execution service

build(maven): configure parent project

ci(github): add Maven build workflow

chore(project): update bootstrap script

---

# Java Package Naming

Base Package

com.automationstudio

Examples

com.automationstudio.project

com.automationstudio.execution

com.automationstudio.environment

com.automationstudio.engine

com.automationstudio.report

---

# Module Naming

Examples

studio-api

studio-web

engine-contract

playwright-engine

selenium-engine

karate-engine

restassured-engine

report-service

artifact-service

execution-service

---

# Database Naming

Tables

Use snake_case

Examples

project

environment

test_suite

execution

execution_step

execution_artifact

execution_result

Columns

Examples

project_id

created_at

updated_at

execution_status

environment_name

---

# REST API Naming

Base URL

/api/v1

Examples

GET /api/v1/projects

POST /api/v1/projects

GET /api/v1/projects/{id}

PUT /api/v1/projects/{id}

DELETE /api/v1/projects/{id}

---

# DTO Naming

Examples

CreateProjectRequest

ProjectResponse

ExecutionSummaryResponse

LoginRequest

---

# Entity Naming

Examples

Project

Execution

Environment

TestSuite

Artifact

ExecutionResult

---

# Service Naming

Examples

ProjectService

ExecutionService

EnvironmentService

ReportService

---

# Repository Naming

Examples

ProjectRepository

ExecutionRepository

ArtifactRepository

---

# Controller Naming

Examples

ProjectController

ExecutionController

EnvironmentController

---

# Configuration Classes

Examples

DatabaseConfiguration

SecurityConfiguration

SwaggerConfiguration

EngineConfiguration

---

# Enum Naming

Examples

ExecutionStatus

EnvironmentType

EngineType

ArtifactType

---

# Test Naming

Examples

ProjectServiceTest

ExecutionControllerIT

EnvironmentRepositoryTest

---

# Constants

Java Constants

public static final String DEFAULT_ENGINE

Application Constants

ENGINE_PLAYWRIGHT

ENGINE_SELENIUM

ENGINE_KARATE

---

# Docker

Container Names

automation-studio-api

automation-studio-db

automation-studio-web

playwright-engine

---

# Environment Variables

Upper Snake Case

Examples

DATABASE_URL

DATABASE_USERNAME

DATABASE_PASSWORD

PLAYWRIGHT_BROWSER

SPRING_PROFILES_ACTIVE

---

# YAML Naming

Examples

application.yml

application-dev.yml

application-int.yml

application-prod.yml

---

# Documentation Naming

Examples

README.md

ROADMAP.md

CHANGELOG.md

PROJECT.md

CONTRIBUTING.md

SECURITY.md

---

# Diagram Naming

Examples

system-architecture.drawio

deployment-diagram.drawio

execution-sequence.drawio

---

# Sprint Naming

Sprint 0

Sprint 1

Sprint 2

Sprint 3

---

# Release Naming

v0.1.0

v0.2.0

v1.0.0

---

# Pull Request Naming

Examples

AS-005 Add Software Architecture Specification

AS-010 Implement Project Management Module

BUG-002 Fix Execution Timeout

---

# Labels

epic

story

task

bug

architecture

backend

frontend

playwright

karate

database

documentation

enhancement

good first issue

help wanted

---

# Principles

- Use meaningful names.
- Avoid abbreviations unless they are well known.
- Prefer consistency over creativity.
- Keep identifiers short but descriptive.
- Follow existing project conventions.
- One concept should have one name throughout the project.

---

# Future Updates

This document will evolve as Automation Studio grows.

Any change to naming conventions should be discussed and documented through an ADR.