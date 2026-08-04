package com.mineagent.engine.client.screen;

import com.mineagent.engine.client.ui.MineAgentUiComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Edits runtime defaults without duplicating provider connection settings. */
public class ConfigEditScreen extends Screen {

    private static final int INPUT_HEIGHT = 20;
    private static final int BUTTON_HEIGHT = 20;
    private static final int FIELD_STEP = 34;
    private static final int COLUMN_GAP = 12;

    private EditBox temperatureField;
    private EditBox nameField;
    private EditBox thinkingEffortField;

    private MineAgentUiComponents.Rect panel;
    private int leftX;
    private int rightX;
    private int columnWidth;
    private int fieldsTop;
    private int actionY;

    public ConfigEditScreen() {
        super(Component.translatable("screen.mineagent.runtime.title"));
    }

    @Override
    protected void init() {
        super.init();
        panel = MineAgentUiComponents.centeredPanel(
                this.width, this.height, 400,
                MineAgentUiComponents.TITLE_BAND_HEIGHT + 10 + 70 + 20 + 12);
        int innerWidth = panel.width() - MineAgentUiComponents.PANEL_PADDING * 2;
        columnWidth = Math.max(80, (innerWidth - COLUMN_GAP) / 2);
        leftX = panel.x() + MineAgentUiComponents.PANEL_PADDING;
        rightX = leftX + columnWidth + COLUMN_GAP;
        fieldsTop = panel.y() + MineAgentUiComponents.TITLE_BAND_HEIGHT + 10;
        actionY = fieldsTop + 70;

        temperatureField = createField(leftX, fieldsTop + 11, columnWidth, 8,
                "screen.mineagent.temperature_hint");
        thinkingEffortField = createField(rightX, fieldsTop + 11,
                columnWidth, 8, "screen.mineagent.effort_runtime_hint");
        nameField = createField(leftX, fieldsTop + FIELD_STEP + 11,
                innerWidth, 32, "screen.mineagent.name_default_hint");

        int gap = 4;
        int buttonWidth = (innerWidth - gap * 3) / 4;
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.mineagent.apply"),
                ignored -> applySettings())
                .bounds(leftX, actionY, buttonWidth, BUTTON_HEIGHT).build());
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.mineagent.show"),
                ignored -> runAndClose("mineagent config"))
                .bounds(leftX + buttonWidth + gap, actionY,
                        buttonWidth, BUTTON_HEIGHT).build());
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.mineagent.reload"),
                ignored -> runAndClose("mineagent reload"))
                .bounds(leftX + (buttonWidth + gap) * 2, actionY,
                        buttonWidth, BUTTON_HEIGHT).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.back"),
                ignored -> Minecraft.getInstance().setScreen(new MineAgentMainMenuScreen()))
                .bounds(leftX + (buttonWidth + gap) * 3, actionY,
                        innerWidth - (buttonWidth + gap) * 3,
                        BUTTON_HEIGHT).build());

        this.setInitialFocus(temperatureField);
    }

    private EditBox createField(int x, int y, int width, int maxLength,
                                String hintKey) {
        EditBox field = new EditBox(this.minecraft.font, x, y, width,
                INPUT_HEIGHT, Component.translatable(hintKey));
        field.setMaxLength(maxLength);
        field.setHint(Component.translatable(hintKey));
        this.addRenderableWidget(field);
        return field;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        MineAgentUiComponents.drawPanel(graphics, panel.x(), panel.y(),
                panel.width(), panel.height(), this.title, true,
                this.width, this.height);

        MineAgentUiComponents.drawFieldLabel(graphics,
                Component.translatable("screen.mineagent.field.temperature"),
                leftX, fieldsTop);
        MineAgentUiComponents.drawFieldLabel(graphics,
                Component.translatable("screen.mineagent.field.effort_runtime"),
                rightX, fieldsTop);
        MineAgentUiComponents.drawFieldLabel(graphics,
                Component.translatable("screen.mineagent.field.default_name"),
                leftX, fieldsTop + FIELD_STEP);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void applySettings() {
        if (this.minecraft.player == null) return;
        String rawTemperature = temperatureField.getValue().trim();
        String name = ClientCommandInput.greedy(nameField.getValue(), 32);
        String rawEffort = thinkingEffortField.getValue().trim().toLowerCase();
        String effort = ClientCommandInput.word(rawEffort, 8);

        String temperature = "";
        if (!rawTemperature.isEmpty()) {
            try {
                double parsed = Double.parseDouble(rawTemperature);
                if (!Double.isFinite(parsed) || parsed < 0.0 || parsed > 2.0) {
                    throw new NumberFormatException("out of range");
                }
                temperature = Double.toString(parsed);
            } catch (NumberFormatException invalid) {
                showError("screen.mineagent.error.temperature");
                return;
            }
        }
        if (!rawEffort.isEmpty() && (!effort.equals(rawEffort)
                || !List.of("off", "low", "medium", "high", "xhigh", "max")
                        .contains(effort))) {
            showError("screen.mineagent.error.effort");
            return;
        }
        if (temperature.isEmpty() && name.isEmpty() && effort.isEmpty()) {
            showError("screen.mineagent.error.no_changes");
            return;
        }

        // All values are checked first; an invalid later field therefore cannot
        // leave an earlier setting persisted while the UI reports failure.
        if (!temperature.isEmpty()) {
            this.minecraft.player.connection.sendCommand(
                    "mineagent setconfig temperature " + temperature);
        }
        if (!name.isEmpty()) {
            this.minecraft.player.connection.sendCommand(
                    "mineagent setconfig name " + name);
        }
        if (!effort.isEmpty()) {
            this.minecraft.player.connection.sendCommand("mineagent seteffort " + effort);
        }
        this.minecraft.player.displayClientMessage(
                Component.translatable("screen.mineagent.runtime.applied"), false);
        Minecraft.getInstance().setScreen(new MineAgentMainMenuScreen());
    }

    private void runAndClose(String command) {
        this.onClose();
        if (this.minecraft.player != null) {
            this.minecraft.player.connection.sendCommand(command);
        }
    }

    private void showError(String translationKey) {
        this.minecraft.player.displayClientMessage(Component.translatable(translationKey), false);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }
}
