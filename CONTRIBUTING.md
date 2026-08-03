# Contributing to MineAgent

Thank you for helping improve MineAgent. The project accepts focused bug fixes, loader compatibility work, tests, documentation, and behavior improvements that can be reproduced and evaluated.

## Before opening an issue

- Search existing issues first.
- Confirm Minecraft 1.21.1, Java 21, and the exact loader version.
- Reproduce in a test world with the smallest practical mod set.
- Remove API keys, access tokens, player identifiers, server addresses, and private chat from logs.
- State whether the issue occurs on supported Fabric or experimental NeoForge.

Use [GitHub Security Advisories](https://github.com/bingdongni/MineAgent/security/advisories/new) instead of a public issue for vulnerabilities or secret exposure.

## Development setup

1. Install JDK 21.
2. Clone the repository.
3. Build both loader artifacts:

```bash
./gradlew :fabric:build :neoforge:build
```

On Windows PowerShell, use `./gradlew.bat` instead. Fabric is the primary runtime validation target. NeoForge changes must preserve its experimental build even when the feature has no NeoForge client implementation yet.

## Pull requests

- Keep each pull request limited to one coherent problem.
- Explain the root cause, behavior before and after, and verification performed.
- Add or update tests when logic can be tested outside a live Minecraft client.
- Do not commit build output, IDE state, logs, worlds, generated configuration, or secrets.
- Preserve loader-independent logic in `api`, `engine`, or `tools`; keep loader-specific integrations in `fabric` or `neoforge`.
- Avoid unrelated formatting, mass renames, or speculative abstractions.

Before submitting, run:

```bash
./gradlew clean :fabric:build :neoforge:build
```

If a live-game test is required, include the loader, exact reproduction steps, expected result, observed result, and relevant sanitized log lines in the pull request.

## License

By contributing, you agree that your contribution is licensed under the repository's `LGPL-3.0-only` license.
