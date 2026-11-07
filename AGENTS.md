# AGENTS.md

This repository is part of the FLAMORIS ecosystem.

AI agents and human contributors should inspect the current repository before making substantial changes. Do not assume that setup, build, deployment, service names, paths, configuration, or architecture match another FLAMORIS repository.

## Core principles

1. **Current implementation is authoritative**
   - Read the repository documentation, configuration, tests, and relevant source before changing behavior.
   - Do not invent repository-specific commands, paths, services, or configuration.

2. **Keep responsibility clear**
   - Keep this repository focused on its documented purpose.
   - Preserve application and service ownership boundaries.
   - Do not create a second source of truth for state owned elsewhere.

3. **Reuse deliberately**
   - Check existing FLAMORIS shared packages and repositories before duplicating common infrastructure.
   - Reuse code only when the dependency direction and ownership boundary remain clear.
   - Avoid speculative abstractions for requirements that do not yet exist.

4. **Security and privacy are architectural requirements**
   - Never commit or log secrets, credentials, tokens, private keys, or sensitive user data.
   - Prefer least-privilege access and bounded resource use.
   - Treat external input and remote responses as untrusted.

5. **Stable behavior over cleverness**
   - Prefer explicit, testable contracts and straightforward implementations.
   - Preserve existing public behavior unless a change intentionally modifies it.
   - Document externally visible behavior and compatibility impact.

6. **Documentation must track reality**
   - Mark the current implementation/status explicitly when a repository has both shipped behavior and future phases.
   - Do not describe implemented behavior as merely planned, and do not describe planned behavior as already shipped.
   - Keep public architecture portable. Machine names, private topology, credentials, and deployment-only paths belong in private deployment documentation rather than public repository defaults.

7. **AI-native, human-authoritative**
   - AI-assisted development is welcome.
   - Humans remain responsible for reviewing behavior, security, licensing, and compatibility.

## Before implementing a substantial change

- read this file and README.md;
- identify **what this repository is, what it owns, what it does not own, its current status, and where it fits in FLAMORIS**;
- read the [organization map](https://github.com/flamoris-jp/.github) and the relevant family map when cross-repository context matters;
- read relevant docs, Issues, and Pull Requests;
- inspect current implementation and tests;
- identify the source of truth and dependency direction;
- check whether reusable FLAMORIS infrastructure already exists;
- verify repository-specific setup and deployment details instead of guessing.

## Testing

Add or update tests where practical.

Prefer deterministic tests and explicit contracts. When behavior differs by platform, runtime, provider, or environment, document the supported boundary and test the relevant cases.

## Licensing

Unless stated otherwise, code in this repository is licensed under Apache License 2.0.

Do not add third-party code, models, model weights, datasets, fonts, media, or generated assets unless their licenses are compatible and clearly documented.

## Support

FLAMORIS does not provide guaranteed individual support.

Use the repository documentation, Issues, tests, logs, and source code as primary references when diagnosing problems.

## FLAMORIS Pudding: research-specific rules

- This repository owns virtual pudding representations, comparison methods, and research records. Read docs/RESEARCH.md, docs/ROADMAP.md, and PROGRESS.md before extending the scope.
- Current status is planned/documentation only. Do not invent a CLI, API, MCP tool, service, test command, data schema, or deployment target.
- Treat the proposed attributes and experiments as proposals until their definitions and acceptance criteria are agreed in an Issue or PR.
- Keep observations, model outputs, interpretations, and character dialogue distinguishable. A generated reaction is not evidence of subjective taste or emotion.
- Use Misaki (海上 美祥 / Misaki Kaisho) and Akino (愛乃) for character names. Do not assume character artwork is licensed with the code.
- Conversation/personality/memory, inference execution, media generation, rendering, and MCP routing remain owned by their respective FLAMORIS projects. Add integrations only for a concrete experiment, with explicit state ownership.
- Update PROGRESS.md when implementation or acceptance changes. Add real setup/run/verification instructions alongside the first implementation.
