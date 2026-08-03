# Changelog

All notable changes to MineAgent will be documented in this file. The project follows [Semantic Versioning](https://semver.org/) where practical during its alpha stage.

## [0.1.0] - 2026-08-03

### Added

- Initial public alpha release by bingdongni.
- Supported Fabric build for Minecraft 1.21.1 with client menu, chat, HUD, and debug views.
- Experimental NeoForge build for Minecraft 1.21.1.
- LLM integrations for OpenAI, DeepSeek, Qwen, GLM, Moonshot, Grok, MiniMax, Anthropic, and Gemini.
- Fake-player lifecycle, priority scheduling, navigation, survival chains, task tools, and persistent memory.

### Known limitations

- AI actions remain model-dependent and may be slow, inefficient, or incorrect.
- NeoForge does not yet have Fabric-equivalent client UI, networking parity, or the same level of in-game validation.
- LLM API keys are stored in plain text for companion restoration; see `SECURITY.md`.
