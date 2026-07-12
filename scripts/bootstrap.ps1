param(
    [switch]$SkipToolCheck
)

$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host " Automation Studio Bootstrap" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""

function Test-Command {
    param(
        [Parameter(Mandatory = $true)]
        [string]$CommandName
    )

    return $null -ne (Get-Command $CommandName -ErrorAction SilentlyContinue)
}

function Ensure-Directory {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path $Path)) {
        New-Item -ItemType Directory -Path $Path -Force | Out-Null
        Write-Host "Created directory: $Path" -ForegroundColor Green
    }
    else {
        Write-Host "Directory exists:   $Path" -ForegroundColor DarkGray
    }
}

function Ensure-File {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [string]$Content = ""
    )

    if (-not (Test-Path $Path)) {
        $parent = Split-Path $Path -Parent

        if ($parent -and -not (Test-Path $parent)) {
            New-Item -ItemType Directory -Path $parent -Force | Out-Null
        }

        Set-Content -Path $Path -Value $Content -Encoding UTF8
        Write-Host "Created file:      $Path" -ForegroundColor Green
    }
    else {
        Write-Host "File exists:       $Path" -ForegroundColor DarkGray
    }
}

function Assert-ProjectRoot {
    $requiredIndicators = @(
        ".git",
        "README.md",
        "docs",
        "apps",
        "engines"
    )

    $matches = 0

    foreach ($item in $requiredIndicators) {
        if (Test-Path $item) {
            $matches++
        }
    }

    if ($matches -lt 2) {
        throw "Run this script from the Automation Studio repository root."
    }
}

function Check-DevelopmentTools {
    $tools = @(
        @{ Name = "Git";     Command = "git";    VersionArgs = "--version" },
        @{ Name = "Java";    Command = "java";   VersionArgs = "--version" },
        @{ Name = "Maven";   Command = "mvn";    VersionArgs = "--version" },
        @{ Name = "Docker";  Command = "docker"; VersionArgs = "--version" },
        @{ Name = "VS Code"; Command = "code";   VersionArgs = "--version" }
    )

    Write-Host "Checking development tools..." -ForegroundColor Yellow

    foreach ($tool in $tools) {
        if (Test-Command $tool.Command) {
            $versionOutput = & $tool.Command $tool.VersionArgs 2>&1 |
                Select-Object -First 1

            Write-Host ("[OK] {0}: {1}" -f $tool.Name, $versionOutput) `
                -ForegroundColor Green
        }
        else {
            Write-Warning "$($tool.Name) is not installed or not available in PATH."
        }
    }

    Write-Host ""
}

Assert-ProjectRoot

if (-not $SkipToolCheck) {
    Check-DevelopmentTools
}

$directories = @(
    "apps",
    "engines",
    "demo-projects",
    "infrastructure",
    "scripts",
    "docs\vision",
    "docs\roadmap",
    "docs\architecture",
    "docs\adr",
    "docs\agile",
    "docs\engineering",
    "docs\journal",
    "docs\releases",
    ".github\ISSUE_TEMPLATE",
    ".github\pull_request_template"
)

Write-Host "Creating directories..." -ForegroundColor Yellow

foreach ($directory in $directories) {
    Ensure-Directory -Path $directory
}

Write-Host ""

$files = @{
    "docs\vision\product-vision.md" = @"
# Automation Studio Product Vision

Automation Studio is a modular enterprise Quality Engineering platform for
managing automation projects, environments, execution, evidence, and reports.
"@

    "docs\vision\mission.md" = @"
# Mission

Build an enterprise-quality automation platform that demonstrates modular
architecture, extensibility, professional testing practices, and maintainable
software delivery.
"@

    "docs\vision\product-goals.md" = @"
# Product Goals

- Provide modular automation engines.
- Centralize test execution and reporting.
- Support configurable environments.
- Maintain professional engineering documentation.
- Enable future UI, API, database, mobile, and performance testing.
"@

    "docs\roadmap\roadmap.md" = @"
# Product Roadmap

## Phase 1

- Project foundation
- Environment management
- Playwright Java engine
- OrangeHRM smoke test execution

## Phase 2

- Selenium Java engine
- Dashboard
- Reporting

## Phase 3

- REST Assured
- Karate
- Database validation

## Phase 4

- AI-assisted failure analysis
- Mobile and performance testing
"@

    "docs\roadmap\release-plan.md" = @"
# Release Plan

## v0.1

Run an OrangeHRM Playwright smoke suite and display the execution result.
"@

    "docs\roadmap\sprint-plan.md" = @"
# Sprint Plan

## Sprint 0

Project foundation, architecture, documentation, and development standards.
"@

    "docs\architecture\system-architecture.md" = "# System Architecture"
    "docs\architecture\module-architecture.md" = "# Module Architecture"
    "docs\architecture\database-design.md" = "# Database Design"
    "docs\architecture\api-design.md" = "# API Design"
    "docs\architecture\deployment.md" = "# Deployment Architecture"
    "docs\architecture\sequence-diagrams.md" = "# Sequence Diagrams"

    "docs\adr\ADR-001-modular-engine-architecture.md" = @"
# ADR-001: Modular Automation Engine Architecture

## Status

Accepted

## Decision

Automation technologies will be implemented as independent modules that
implement a shared engine contract.
"@

    "docs\adr\ADR-002-spring-boot-backend.md" = "# ADR-002: Spring Boot Backend"
    "docs\adr\ADR-003-nextjs-frontend.md" = "# ADR-003: Next.js Frontend"
    "docs\adr\ADR-004-postgresql-database.md" = "# ADR-004: PostgreSQL Database"

    "docs\agile\epic-log.md" = "# Epic Log"
    "docs\agile\story-log.md" = "# Story Log"
    "docs\agile\sprint-retrospective.md" = "# Sprint Retrospectives"
    "docs\agile\definition-of-done.md" = @"
# Definition of Done

A story is complete when:

- Acceptance criteria are satisfied.
- Tests pass.
- Documentation is updated.
- Changes are reviewed.
- No credentials or secrets are committed.
"@

    "docs\engineering\coding-standards.md" = "# Coding Standards"
    "docs\engineering\branching-strategy.md" = @"
# Branching Strategy

- main: stable releases
- develop: integration branch
- feature/AS-XXX-description: feature development
- fix/AS-XXX-description: bug fixes
"@

    "docs\engineering\commit-convention.md" = @"
# Commit Convention

Use Conventional Commits.

Examples:

- feat: add project API
- fix: correct execution status mapping
- docs: update architecture decision
- chore: initialize repository
"@

    "docs\engineering\code-review-checklist.md" = "# Code Review Checklist"
    "docs\engineering\testing-strategy.md" = "# Testing Strategy"

    "docs\journal\day-001.md" = @"
# Day 001 — Automation Studio Started

## Objective

Establish Automation Studio as a modular Quality Engineering platform.

## First application under test

OrangeHRM.

## First automation engine

Playwright Java.
"@

    "docs\releases\v0.1.md" = "# Automation Studio v0.1"
    "docs\releases\changelog.md" = "# Changelog"

    "docs\legacy.md" = @"
# Automation Studio History

Automation Studio was started in July 2026 as a professional portfolio and
enterprise Quality Engineering project.

No proprietary employer code, credentials, architecture, or confidential
business logic will be copied into this repository.
"@

    ".github\ISSUE_TEMPLATE\user-story.md" = @"
---
name: User Story
about: Create an Automation Studio user story
title: "AS-XXX "
labels: enhancement
assignees: ""
---

## User Story

As a ...

I want ...

So that ...

## Business Value

Describe the value.

## Acceptance Criteria

- [ ]
- [ ]
- [ ]

## Definition of Done

- [ ] Acceptance criteria completed
- [ ] Tests pass
- [ ] Documentation updated
- [ ] Pull request reviewed

## Story Points

## Sprint
"@

    ".github\pull_request_template.md" = @"
## Summary

Describe the change.

## Linked Issue

Closes #

## Changes

- 
- 

## Verification

- [ ] Tests executed
- [ ] Documentation updated
- [ ] No secrets committed
- [ ] Backward compatibility considered
"@

    ".editorconfig" = @"
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
indent_style = space
indent_size = 4
trim_trailing_whitespace = true

[*.md]
trim_trailing_whitespace = false

[*.{json,yml,yaml}]
indent_size = 2
"@
}

Write-Host "Creating project files..." -ForegroundColor Yellow

foreach ($file in $files.GetEnumerator()) {
    Ensure-File -Path $file.Key -Content $file.Value
}

Write-Host ""
Write-Host "Validating created structure..." -ForegroundColor Yellow

$missingFiles = @()

foreach ($filePath in $files.Keys) {
    if (-not (Test-Path $filePath)) {
        $missingFiles += $filePath
    }
}

if ($missingFiles.Count -gt 0) {
    Write-Host "Bootstrap completed with missing files:" -ForegroundColor Red
    $missingFiles | ForEach-Object {
        Write-Host " - $_" -ForegroundColor Red
    }

    exit 1
}

Write-Host ""
Write-Host "Bootstrap completed successfully." -ForegroundColor Green
Write-Host "Next commands:" -ForegroundColor Cyan
Write-Host "  git status"
Write-Host "  git add ."
Write-Host '  git commit -m "chore: add project bootstrap foundation"'