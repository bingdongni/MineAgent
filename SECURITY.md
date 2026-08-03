# Security Policy

## Reporting a vulnerability

Do not report vulnerabilities, API keys, or private logs in a public issue. Use a private [GitHub Security Advisory](https://github.com/bingdongni/MineAgent/security/advisories/new) and include:

- affected MineAgent version and loader;
- impact and realistic attack scenario;
- minimal reproduction steps or proof of concept;
- suggested mitigation, if known;
- only the smallest necessary, sanitized logs.

The maintainer will assess reports on a best-effort basis. Please allow time for validation before public disclosure.

## Sensitive local data

MineAgent currently stores LLM API keys as plain text in:

- `config/mineagent.json`;
- `<world>/data/mineagent_companions.json`.

Do not share those files or unreviewed world archives. If a key is exposed, revoke it at the provider immediately, generate a new key, and replace all stored copies. Conversation and game context may be transmitted to the configured LLM provider and is subject to that provider's security and privacy terms.

## Supported versions

Security fixes target the latest published release. Older alpha builds may receive no backports. NeoForge is experimental and may have platform-specific security or lifecycle gaps not present on the supported Fabric build.
