package com.mineagent.engine.client.screen;

import com.mineagent.api.llm.model.ThinkingEffortSpec;
import com.mineagent.api.entity.CompanionGameMode;
import com.mineagent.api.network.payload.ClientUiActionPayload;
import com.mineagent.api.network.payload.CompanionSetupPayload;
import com.mineagent.engine.client.MineAgentClientController;
import com.mineagent.engine.client.ui.MineAgentUiComponents;
import com.mineagent.engine.client.ui.ProviderPresets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Unified companion identity and LLM connection screen.
 *
 * <p>Provider presets are field fillers, not an allow-list. The protocol
 * selector chooses one stable wire adapter while model id and base URL remain
 * completely editable for current and future compatible services.
 */
public class SpawnCompanionScreen extends Screen {

    private static final UUID NIL_UUID = new UUID(0L, 0L);
    private static final int INPUT_HEIGHT = 20;
    private static final int BUTTON_HEIGHT = 20;
    private static final int STANDARD_FIELD_STEP = 34;
    private static final int COMPACT_FIELD_STEP = 32;
    private static final int COLUMN_GAP = 12;
    private EditBox nameField;
    private EditBox protocolField;
    private EditBox modelField;
    private EditBox apiKeyField;
    private EditBox baseUrlField;
    private EditBox thinkingEffortField;
    private CycleButton<ProviderPresets.Preset> presetButton;
    private Button revealKeyButton;
    private CycleButton<CompanionGameMode> gameModeButton;

    private MineAgentUiComponents.Rect panel;
    private int leftX;
    private int rightX;
    private int columnWidth;
    private int fieldStep;
    private int presetLabelY;
    private int connectionTop;
    private int identityHeaderY;
    private int identityTop;
    private int actionY;
    private boolean showPreset;
    private boolean revealApiKey;
    private boolean applyingServerConfig;
    private boolean protocolEdited;
    private boolean modelEdited;
    private boolean baseUrlEdited;
    private boolean apiKeyConfigured;
    private String serverProtocol = "";
    private String serverModel = "";
    private String serverBaseUrl = "";
    private double temperature = 0.7;

    public SpawnCompanionScreen() {
        super(Component.translatable("screen.mineagent.create.title"));
    }

    @Override
    protected void init() {
        super.init();
        protocolEdited = false;
        modelEdited = false;
        baseUrlEdited = false;
        applyingServerConfig = true;

        int preferredWidth = 500;
        int fittedWidth = Math.min(preferredWidth,
                Math.max(1, this.width - 2 * MineAgentUiComponents.MARGIN));
        int contentWidth = Math.max(1,
                fittedWidth - MineAgentUiComponents.PANEL_PADDING * 2);
        // The full panel is 226px tall plus two 10px screen margins. Using the
        // actual fitted requirement prevents the action row from being drawn
        // below a clamped panel at intermediate GUI scales.
        showPreset = this.height >= 280;
        fieldStep = showPreset ? STANDARD_FIELD_STEP : COMPACT_FIELD_STEP;
        int preferredHeight = showPreset ? 260 : 214;
        panel = MineAgentUiComponents.centeredPanel(
                this.width, this.height, preferredWidth, preferredHeight);

        int innerWidth = panel.width() - MineAgentUiComponents.PANEL_PADDING * 2;
        columnWidth = Math.max(72, (innerWidth - COLUMN_GAP) / 2);
        leftX = panel.x() + MineAgentUiComponents.PANEL_PADDING;
        rightX = leftX + columnWidth + COLUMN_GAP;

        int contentTop = panel.y() + MineAgentUiComponents.TITLE_BAND_HEIGHT + 9;
        presetLabelY = contentTop;
        if (showPreset) {
            presetButton = CycleButton.<ProviderPresets.Preset>builder(
                            ProviderPresets.Preset::label)
                    .withValues(ProviderPresets.ALL)
                    .withInitialValue(ProviderPresets.ALL.get(1))
                    .displayOnlyValue()
                    .create(leftX, contentTop + 11, innerWidth, BUTTON_HEIGHT,
                            Component.translatable("screen.mineagent.field.preset"),
                            (button, preset) -> selectPreset(preset));
            this.addRenderableWidget(presetButton);
            connectionTop = contentTop + 39;
        } else {
            connectionTop = contentTop;
        }

        protocolField = createField(leftX, connectionTop + 11, columnWidth,
                CompanionSetupPayload.MAX_PROVIDER_ID,
                "screen.mineagent.protocol_hint", "openai-compatible");

        modelField = createField(rightX, connectionTop + 11, columnWidth, 256,
                "screen.mineagent.model_hint", "deepseek-chat");

        int keyRow = connectionTop + fieldStep;
        int revealWidth = Math.min(46, Math.max(34, columnWidth / 4));
        apiKeyField = createField(leftX, keyRow + 11,
                Math.max(36, columnWidth - revealWidth - 3),
                CompanionSetupPayload.MAX_API_KEY,
                "screen.mineagent.apikey_hint", "");
        revealKeyButton = Button.builder(
                        Component.translatable("screen.mineagent.show"),
                        ignored -> toggleApiKeyVisibility())
                .bounds(leftX + columnWidth - revealWidth, keyRow + 11,
                        revealWidth, BUTTON_HEIGHT).build();
        this.addRenderableWidget(revealKeyButton);
        updateApiKeyFormatter();

        baseUrlField = createField(rightX, keyRow + 11, columnWidth,
                CompanionSetupPayload.MAX_BASE_URL,
                "screen.mineagent.baseurl_hint", "https://api.deepseek.com");

        identityHeaderY = keyRow + fieldStep + 1;
        identityTop = identityHeaderY + (showPreset ? 16 : 15);
        nameField = createField(leftX, identityTop + 11, columnWidth,
                CompanionSetupPayload.MAX_NAME,
                "screen.mineagent.create.name_hint", "");
        thinkingEffortField = createField(rightX, identityTop + 11, columnWidth,
                CompanionSetupPayload.MAX_EFFORT,
                "screen.mineagent.effort_hint", "");
        updateEffortHint();

        int modeTop = identityTop + fieldStep;
        gameModeButton = CycleButton.<CompanionGameMode>builder(
                        this::gameModeLabel)
                .withValues(List.of(CompanionGameMode.values()))
                .withInitialValue(CompanionGameMode.SURVIVAL)
                .create(leftX, modeTop + 11, innerWidth, BUTTON_HEIGHT,
                        Component.translatable("screen.mineagent.field.game_mode"),
                        (button, mode) -> { /* selection is local until submit */ });
        this.addRenderableWidget(gameModeButton);

        // Responders are attached after initial values so only actual user
        // input can suppress a late server summary from overwriting edits.
        modelField.setResponder(ignored -> {
            if (!applyingServerConfig) modelEdited = true;
            updateEffortHint();
        });
        protocolField.setResponder(ignored -> {
            if (!applyingServerConfig) protocolEdited = true;
        });
        baseUrlField.setResponder(ignored -> {
            if (!applyingServerConfig) baseUrlEdited = true;
        });

        actionY = modeTop + fieldStep + (showPreset ? 7 : 5);
        int actionGap = 5;
        int actionWidth = (innerWidth - actionGap) / 2;
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.mineagent.create.save_and_create"),
                ignored -> doSpawn())
                .bounds(leftX, actionY, actionWidth, BUTTON_HEIGHT).build());
        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.back"),
                ignored -> Minecraft.getInstance().setScreen(new MineAgentMainMenuScreen()))
                .bounds(leftX + actionWidth + actionGap, actionY,
                        innerWidth - actionWidth - actionGap, BUTTON_HEIGHT).build());

        applyingServerConfig = false;
        this.setInitialFocus(nameField);
        MineAgentClientController.sendUiAction(new ClientUiActionPayload(
                NIL_UUID, "request_llm_config", ""));
    }

    private EditBox createField(int x, int y, int width, int maxLength,
                                String hintKey, String value) {
        EditBox field = new EditBox(this.minecraft.font, x, y, width,
                INPUT_HEIGHT, Component.translatable(hintKey));
        field.setMaxLength(maxLength);
        field.setHint(Component.translatable(hintKey));
        field.setValue(value);
        this.addRenderableWidget(field);
        return field;
    }

    private void selectPreset(ProviderPresets.Preset preset) {
        if (preset == null || preset == ProviderPresets.CUSTOM) {
            if (!applyingServerConfig) {
                protocolEdited = true;
                modelEdited = true;
                baseUrlEdited = true;
            }
            return;
        }
        boolean userSelection = !applyingServerConfig;
        boolean previousApplyingState = applyingServerConfig;
        applyingServerConfig = true;
        protocolField.setValue(preset.protocolId());
        modelField.setValue(preset.preferredModel());
        baseUrlField.setValue(preset.baseUrl());
        applyingServerConfig = previousApplyingState;
        if (userSelection) {
            protocolEdited = true;
            modelEdited = true;
            baseUrlEdited = true;
        }
    }

    /** Apply a non-secret configuration summary returned by the server. */
    public void applyServerConfig(String providerId, String model, String baseUrl,
                                  double configuredTemperature,
                                  boolean hasStoredApiKey) {
        applyingServerConfig = true;
        ProviderPresets.Preset preset = ProviderPresets.find(providerId, baseUrl)
                .orElse(ProviderPresets.CUSTOM);
        String protocol = ProviderPresets.protocolForProvider(providerId);
        String resolvedBase = baseUrl != null && !baseUrl.isBlank()
                ? baseUrl : preset != ProviderPresets.CUSTOM ? preset.baseUrl() : "";
        if (!protocolEdited) protocolField.setValue(protocol);
        if (!modelEdited && model != null && !model.isBlank()) modelField.setValue(model);
        if (!baseUrlEdited) baseUrlField.setValue(resolvedBase);
        if (presetButton != null && !protocolEdited && !modelEdited && !baseUrlEdited) {
            presetButton.setValue(preset);
        }
        temperature = Double.isFinite(configuredTemperature)
                && configuredTemperature >= 0.0 && configuredTemperature <= 2.0
                ? configuredTemperature : 0.7;
        apiKeyConfigured = hasStoredApiKey;
        serverProtocol = protocol;
        serverModel = model == null ? "" : model.trim();
        serverBaseUrl = normalizeBase(resolvedBase);
        apiKeyField.setHint(Component.translatable(hasStoredApiKey
                ? "screen.mineagent.apikey_saved_hint"
                : "screen.mineagent.apikey_required_hint"));
        applyingServerConfig = false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        MineAgentUiComponents.drawPanel(graphics, panel.x(), panel.y(),
                panel.width(), panel.height(), this.title, true,
                this.width, this.height);

        int innerWidth = panel.width() - MineAgentUiComponents.PANEL_PADDING * 2;
        if (showPreset) {
            MineAgentUiComponents.drawFieldLabel(graphics,
                    Component.translatable("screen.mineagent.field.preset"),
                    leftX, presetLabelY);
        }
        MineAgentUiComponents.drawFieldLabel(graphics,
                Component.translatable("screen.mineagent.field.protocol"),
                leftX, connectionTop);
        MineAgentUiComponents.drawFieldLabel(graphics,
                Component.translatable("screen.mineagent.field.model"),
                rightX, connectionTop);
        MineAgentUiComponents.drawFieldLabel(graphics,
                Component.translatable("screen.mineagent.field.apikey"),
                leftX, connectionTop + fieldStep);
        MineAgentUiComponents.drawFieldLabel(graphics,
                Component.translatable("screen.mineagent.field.baseurl"),
                rightX, connectionTop + fieldStep);
        MineAgentUiComponents.drawSectionHeader(graphics,
                Component.translatable("screen.mineagent.section.companion"),
                leftX, identityHeaderY, innerWidth);
        MineAgentUiComponents.drawFieldLabel(graphics,
                Component.translatable("screen.mineagent.field.name"), leftX, identityTop);
        MineAgentUiComponents.drawFieldLabel(graphics,
                Component.translatable("screen.mineagent.field.effort"), rightX, identityTop);
        MineAgentUiComponents.drawFieldLabel(graphics,
                Component.translatable("screen.mineagent.field.game_mode"),
                leftX, identityTop + fieldStep);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void updateEffortHint() {
        if (thinkingEffortField == null) return;
        String model = modelField != null ? modelField.getValue() : "";
        ThinkingEffortSpec spec = ThinkingEffortSpec.forModel(model);
        thinkingEffortField.setHint(spec.supportsEffort()
                ? Component.literal(spec.hint())
                : Component.translatable("screen.mineagent.effort_unsupported"));
    }

    private void toggleApiKeyVisibility() {
        revealApiKey = !revealApiKey;
        updateApiKeyFormatter();
        revealKeyButton.setMessage(Component.translatable(revealApiKey
                ? "screen.mineagent.hide" : "screen.mineagent.show"));
    }

    private void updateApiKeyFormatter() {
        if (apiKeyField == null) return;
        apiKeyField.setFormatter((value, offset) -> FormattedCharSequence.forward(
                revealApiKey ? value : "*".repeat(value.length()), Style.EMPTY));
    }

    private void doSpawn() {
        if (this.minecraft.player == null) return;
        String model = modelField.getValue().trim();
        String apiKey = apiKeyField.getValue();
        String baseUrl = baseUrlField.getValue().trim();
        String effort = thinkingEffortField.getValue().trim().toLowerCase(Locale.ROOT);

        if (model.isEmpty()) {
            showError("screen.mineagent.error.model");
            return;
        }
        if (!baseUrl.isEmpty() && !isValidBaseUrl(baseUrl)) {
            showError("screen.mineagent.error.baseurl");
            return;
        }
        if (!effort.isEmpty() && !List.of(
                "off", "low", "medium", "high", "xhigh", "max").contains(effort)) {
            showError("screen.mineagent.error.effort");
            return;
        }

        try {
            boolean reuseStoredKey = apiKey.isEmpty() && apiKeyConfigured
                    && protocolField.getValue().trim().equalsIgnoreCase(serverProtocol)
                    && model.equals(serverModel)
                    // URL paths and queries can be case-sensitive. Reuse is
                    // intentionally conservative because a false mismatch is
                    // harmless while a false match can disclose a credential.
                    && normalizeBase(baseUrl).equals(serverBaseUrl);
            MineAgentClientController.sendCompanionSetup(new CompanionSetupPayload(
                    nameField.getValue(), protocolField.getValue(), apiKey,
                    reuseStoredKey,
                    model, baseUrl, temperature, effort,
                    gameModeButton.getValue().wireName()));
            this.onClose();
        } catch (IllegalArgumentException invalidInput) {
            showError("screen.mineagent.error.setup");
        }
    }

    private Component gameModeLabel(CompanionGameMode mode) {
        return Component.translatable("screen.mineagent.game_mode."
                + (mode == null ? CompanionGameMode.SURVIVAL : mode).wireName());
    }

    private static boolean isValidBaseUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getQuery() == null
                    && uri.getFragment() == null;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static String normalizeBase(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private void showError(String translationKey) {
        if (this.minecraft.player != null) {
            this.minecraft.player.displayClientMessage(
                    Component.translatable(translationKey), false);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

}
