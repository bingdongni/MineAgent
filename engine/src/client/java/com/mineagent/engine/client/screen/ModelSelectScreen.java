package com.mineagent.engine.client.screen;

import com.mineagent.engine.client.ui.MineAgentUiComponents;
import com.mineagent.engine.client.ui.ProviderPresets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Edits the global LLM provider, model and HTTP connection settings. */
public class ModelSelectScreen extends Screen {

    private static final int INPUT_HEIGHT = 20;
    private static final int BUTTON_HEIGHT = 20;
    private static final int FIELD_STEP = 34;
    private static final int COLUMN_GAP = 12;
    private static final int PRESET_HEIGHT = 18;
    private static final int PRESET_GAP = 3;

    private final List<ProviderButton> providerButtons = new ArrayList<>();

    private EditBox providerField;
    private EditBox modelField;
    private EditBox apiKeyField;
    private EditBox baseUrlField;

    private MineAgentUiComponents.Rect panel;
    private int leftX;
    private int rightX;
    private int columnWidth;
    private int fieldsTop;
    private int presetHeaderY;
    private int presetButtonY;
    private int actionY;
    private boolean showProviderPresets;

    public ModelSelectScreen() {
        super(Component.translatable("screen.mineagent.connection.title"));
    }

    @Override
    protected void init() {
        super.init();
        providerButtons.clear();

        int preferredWidth = 460;
        int fittedWidth = Math.min(preferredWidth,
                Math.max(1, this.width - 2 * MineAgentUiComponents.MARGIN));
        int contentWidth = Math.max(1,
                fittedWidth - MineAgentUiComponents.PANEL_PADDING * 2);
        showProviderPresets = this.height >= 220 && contentWidth >= 280;
        int presetColumns = contentWidth >= 400 ? 5 : 3;
        int presetRows = (ProviderPresets.ALL.size() + presetColumns - 1) / presetColumns;
        int presetGridHeight = presetRows * PRESET_HEIGHT
                + Math.max(0, presetRows - 1) * PRESET_GAP;
        int presetBlockHeight = showProviderPresets
                ? 14 + presetGridHeight + 10 : 0;
        int preferredHeight = MineAgentUiComponents.TITLE_BAND_HEIGHT + 10
                + presetBlockHeight + 100;

        panel = MineAgentUiComponents.centeredPanel(
                this.width, this.height, preferredWidth, preferredHeight);
        int innerWidth = panel.width() - MineAgentUiComponents.PANEL_PADDING * 2;
        columnWidth = Math.max(80, (innerWidth - COLUMN_GAP) / 2);
        leftX = panel.x() + MineAgentUiComponents.PANEL_PADDING;
        rightX = leftX + columnWidth + COLUMN_GAP;

        int contentTop = panel.y() + MineAgentUiComponents.TITLE_BAND_HEIGHT + 10;
        presetHeaderY = contentTop;
        presetButtonY = contentTop + 14;
        fieldsTop = showProviderPresets
                ? presetButtonY + presetGridHeight + 10 : contentTop;
        actionY = fieldsTop + 70;

        if (showProviderPresets) {
            int buttonWidth = Math.max(38,
                    (innerWidth - (presetColumns - 1) * PRESET_GAP) / presetColumns);
            for (int i = 0; i < ProviderPresets.ALL.size(); i++) {
                ProviderPresets.Preset preset = ProviderPresets.ALL.get(i);
                int row = i / presetColumns;
                int column = i % presetColumns;
                Button button = Button.builder(Component.literal(preset.label()),
                        ignored -> selectProvider(preset))
                        .bounds(leftX + column * (buttonWidth + PRESET_GAP),
                                presetButtonY + row * (PRESET_HEIGHT + PRESET_GAP),
                                buttonWidth, PRESET_HEIGHT)
                        .build();
                providerButtons.add(new ProviderButton(preset, button));
                this.addRenderableWidget(button);
            }
        }

        providerField = createField(leftX, fieldsTop + 11, columnWidth, 32,
                "screen.mineagent.provider_hint", "deepseek");
        modelField = createField(leftX, fieldsTop + FIELD_STEP + 11,
                columnWidth, 256, "screen.mineagent.model_hint", "deepseek-v4-flash");
        apiKeyField = createField(rightX, fieldsTop + 11, columnWidth, 256,
                "screen.mineagent.apikey_hint", "");
        baseUrlField = createField(rightX, fieldsTop + FIELD_STEP + 11,
                columnWidth, 256, "screen.mineagent.baseurl_hint", "");
        providerField.setResponder(ignored -> refreshProviderButtons());
        refreshProviderButtons();

        int gap = 4;
        int actionWidth = (innerWidth - gap * 2) / 3;
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.mineagent.apply"),
                ignored -> applySelection())
                .bounds(leftX, actionY, actionWidth, BUTTON_HEIGHT).build());
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.mineagent.providers"),
                ignored -> showProviders())
                .bounds(leftX + actionWidth + gap, actionY,
                        actionWidth, BUTTON_HEIGHT).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.back"),
                ignored -> Minecraft.getInstance().setScreen(new MineAgentMainMenuScreen()))
                .bounds(leftX + (actionWidth + gap) * 2, actionY,
                        innerWidth - (actionWidth + gap) * 2, BUTTON_HEIGHT).build());

        this.setInitialFocus(providerField);
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

    private void selectProvider(ProviderPresets.Preset preset) {
        providerField.setValue(preset.id());
        modelField.setValue(preset.preferredModel());
        refreshProviderButtons();
    }

    private void refreshProviderButtons() {
        if (providerField == null) return;
        String selected = providerField.getValue().trim();
        for (ProviderButton providerButton : providerButtons) {
            boolean active = providerButton.preset().id().equalsIgnoreCase(selected);
            providerButton.button().setMessage(Component.literal(
                    (active ? "> " : "") + providerButton.preset().label()));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        MineAgentUiComponents.drawPanel(graphics, panel.x(), panel.y(),
                panel.width(), panel.height(), this.title, true,
                this.width, this.height);

        if (showProviderPresets) {
            MineAgentUiComponents.drawSectionHeader(graphics,
                    Component.translatable("screen.mineagent.provider_presets"),
                    leftX, presetHeaderY,
                    panel.width() - MineAgentUiComponents.PANEL_PADDING * 2);
        }
        MineAgentUiComponents.drawFieldLabel(graphics,
                Component.translatable("screen.mineagent.field.provider"), leftX, fieldsTop);
        MineAgentUiComponents.drawFieldLabel(graphics,
                Component.translatable("screen.mineagent.field.model"),
                leftX, fieldsTop + FIELD_STEP);
        MineAgentUiComponents.drawFieldLabel(graphics,
                Component.translatable("screen.mineagent.field.apikey"), rightX, fieldsTop);
        MineAgentUiComponents.drawFieldLabel(graphics,
                Component.translatable("screen.mineagent.field.baseurl"),
                rightX, fieldsTop + FIELD_STEP);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void applySelection() {
        if (this.minecraft.player == null) return;
        String rawProvider = providerField.getValue().trim();
        String provider = ClientCommandInput.word(rawProvider, 32);
        String model = ClientCommandInput.greedy(modelField.getValue(), 256);
        String apiKey = ClientCommandInput.greedy(apiKeyField.getValue(), 256);
        String baseUrl = ClientCommandInput.greedy(baseUrlField.getValue(), 256);

        if (provider.isEmpty() || !provider.equals(rawProvider)) {
            showError("screen.mineagent.error.provider");
            return;
        }
        if (model.isEmpty()) {
            showError("screen.mineagent.error.model");
            return;
        }

        this.minecraft.player.connection.sendCommand(
                "mineagent setconfig provider " + provider);
        this.minecraft.player.connection.sendCommand(
                "mineagent setconfig model " + model);
        // Blank secrets/URLs mean "keep the stored value"; this avoids
        // accidentally erasing a working connection merely by opening Apply.
        if (!apiKey.isEmpty()) {
            this.minecraft.player.connection.sendCommand(
                    "mineagent setconfig apikey " + apiKey);
        }
        if (!baseUrl.isEmpty()) {
            this.minecraft.player.connection.sendCommand(
                    "mineagent setconfig baseurl " + baseUrl);
        }
        this.minecraft.player.displayClientMessage(
                Component.translatable("screen.mineagent.connection.applied",
                        provider, model), false);
        Minecraft.getInstance().setScreen(new MineAgentMainMenuScreen());
    }

    private void showProviders() {
        this.onClose();
        if (this.minecraft.player != null) {
            this.minecraft.player.connection.sendCommand("mineagent providers");
        }
    }

    private void showError(String translationKey) {
        this.minecraft.player.displayClientMessage(Component.translatable(translationKey), false);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    private record ProviderButton(ProviderPresets.Preset preset, Button button) {}
}
