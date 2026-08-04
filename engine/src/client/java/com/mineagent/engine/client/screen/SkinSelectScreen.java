package com.mineagent.engine.client.screen;

import com.mineagent.engine.client.ui.MineAgentUiComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Companion skin selection with all content contained by one panel. */
public class SkinSelectScreen extends Screen {

    private static final int BUTTON_HEIGHT = 20;
    private static final int INPUT_HEIGHT = 20;
    private static final int GAP = 4;

    private EditBox skinNameField;
    private MineAgentUiComponents.Rect panel;
    private int contentX;
    private int contentWidth;
    private int builtinHeaderY;
    private int playerHeaderY;
    private int inputY;

    public SkinSelectScreen() {
        super(Component.translatable("screen.mineagent.skin.title"));
    }

    @Override
    protected void init() {
        super.init();
        int preferredHeight = MineAgentUiComponents.TITLE_BAND_HEIGHT
                + 10 + 14 + BUTTON_HEIGHT + 10 + 14 + INPUT_HEIGHT
                + 10 + BUTTON_HEIGHT + 12;
        panel = MineAgentUiComponents.centeredPanel(
                this.width, this.height, 400, preferredHeight);
        contentX = panel.x() + MineAgentUiComponents.PANEL_PADDING;
        contentWidth = panel.width() - MineAgentUiComponents.PANEL_PADDING * 2;
        builtinHeaderY = panel.y() + MineAgentUiComponents.TITLE_BAND_HEIGHT + 10;
        int builtinY = builtinHeaderY + 14;
        playerHeaderY = builtinY + BUTTON_HEIGHT + 10;
        inputY = playerHeaderY + 14;

        int quickWidth = (contentWidth - GAP * 2) / 3;
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.mineagent.skin.steve"),
                ignored -> setSkin("steve"))
                .bounds(contentX, builtinY, quickWidth, BUTTON_HEIGHT).build());
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.mineagent.skin.alex"),
                ignored -> setSkin("alex"))
                .bounds(contentX + quickWidth + GAP, builtinY,
                        quickWidth, BUTTON_HEIGHT).build());
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.mineagent.skin.auto"),
                ignored -> resetSkin())
                .bounds(contentX + (quickWidth + GAP) * 2, builtinY,
                        contentWidth - (quickWidth + GAP) * 2,
                        BUTTON_HEIGHT).build());

        int applyWidth = Math.min(92, Math.max(64, contentWidth / 3));
        int inputWidth = Math.max(60, contentWidth - applyWidth - GAP);
        skinNameField = new EditBox(this.minecraft.font, contentX, inputY,
                inputWidth, INPUT_HEIGHT,
                Component.translatable("screen.mineagent.skin.player_hint"));
        skinNameField.setMaxLength(16);
        skinNameField.setHint(Component.translatable("screen.mineagent.skin.player_hint"));
        this.addRenderableWidget(skinNameField);
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.mineagent.skin.apply"),
                ignored -> applySkin())
                .bounds(contentX + inputWidth + GAP, inputY,
                        contentWidth - inputWidth - GAP, BUTTON_HEIGHT).build());

        int backY = inputY + INPUT_HEIGHT + 10;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.back"),
                ignored -> Minecraft.getInstance().setScreen(new MineAgentMainMenuScreen()))
                .bounds(contentX, backY, contentWidth, BUTTON_HEIGHT).build());
        this.setInitialFocus(skinNameField);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        MineAgentUiComponents.drawPanel(graphics, panel.x(), panel.y(),
                panel.width(), panel.height(), this.title, true,
                this.width, this.height);
        MineAgentUiComponents.drawSectionHeader(graphics,
                Component.translatable("screen.mineagent.skin.builtin"),
                contentX, builtinHeaderY, contentWidth);
        MineAgentUiComponents.drawSectionHeader(graphics,
                Component.translatable("screen.mineagent.skin.player"),
                contentX, playerHeaderY, contentWidth);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void setSkin(String skin) {
        this.onClose();
        if (this.minecraft.player != null) {
            this.minecraft.player.connection.sendCommand("mineagent setskin " + skin);
        }
    }

    private void resetSkin() {
        this.onClose();
        if (this.minecraft.player != null) {
            this.minecraft.player.connection.sendCommand("mineagent resetskin");
        }
    }

    private void applySkin() {
        if (this.minecraft.player == null) return;
        String skinName = skinNameField.getValue().trim();
        if (!skinName.matches("[A-Za-z0-9_]{1,16}")) {
            this.minecraft.player.displayClientMessage(Component.translatable(
                    "screen.mineagent.error.skin_name"), false);
            return;
        }
        this.minecraft.player.connection.sendCommand("mineagent setskin " + skinName);
        this.onClose();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }
}
