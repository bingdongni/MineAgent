package com.mineagent.fabric.client.screen;

import com.mineagent.api.llm.provider.LLMProviderRegistry;
import com.mineagent.api.llm.provider.LLMProvider;
import com.mineagent.fabric.client.ui.MineAgentUiComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * The main MineAgent control panel — a beautifully styled menu screen
 * that serves as the central hub for all MineAgent operations.
 *
 * <p><b>UI Design</b>: Uses {@link MineAgentUiComponents} for a styled
 * panel with dot grid texture, gold-accented borders, title band, and
 * tilted corner decorations. All elements are boundary-clamped.
 *
 * <p>Opened with the {@code M} key binding.
 */
public class MineAgentMainMenuScreen extends Screen {

    private static final int BTN_WIDTH = 200;
    private static final int BTN_HEIGHT = 20;
    private static final int BTN_SPACING = 4;
    private static final int MARGIN = 10;
    private static final int GRID_BTN_WIDTH = 98;
    private static final int GRID_H_SPACING = 4;
    private static final int PANEL_PADDING = 8;

    private String selectedProvider = "";

    /** Computed panel bounds (clamped to screen in init). */
    private int panelX, panelY, panelWidth, panelHeight;
    private int contentStartY;

    public MineAgentMainMenuScreen() {
        super(Component.literal("MineAgent"));
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;

        // ── Compute panel dimensions ──
        // 4 rows of buttons (3 grid + 1 full + 1 close)
        int contentHeight = 5 * (BTN_HEIGHT + BTN_SPACING) + 10;
        panelWidth = Math.min(BTN_WIDTH + PANEL_PADDING * 2 + 4,
                this.width - 2 * MARGIN);
        panelHeight = Math.min(contentHeight + MineAgentUiComponents.TITLE_BAND_HEIGHT
                        + PANEL_PADDING * 2 + 30,
                this.height - 2 * MARGIN);

        panelX = MineAgentUiComponents.clamp(centerX - panelWidth / 2, MARGIN,
                Math.max(MARGIN, this.width - panelWidth - MARGIN));
        panelY = MineAgentUiComponents.clamp((this.height - panelHeight) / 2, MARGIN,
                Math.max(MARGIN, this.height - panelHeight - MARGIN));

        contentStartY = panelY + MineAgentUiComponents.TITLE_BAND_HEIGHT + PANEL_PADDING + 16;

        int col1X = centerX - GRID_BTN_WIDTH - GRID_H_SPACING / 2;
        int col2X = centerX + GRID_H_SPACING / 2;
        // Clamp columns to panel bounds
        col1X = Math.max(col1X, panelX + PANEL_PADDING);
        col2X = Math.min(col2X, panelX + panelWidth - GRID_BTN_WIDTH - PANEL_PADDING);

        int startY = contentStartY;

        // Row 0: Quick Spawn | Select Model
        this.addRenderableWidget(Button.builder(
                Component.literal("§a▶ Quick Spawn"),
                btn -> openSpawnScreen()
        ).bounds(col1X, startY, GRID_BTN_WIDTH, BTN_HEIGHT).build());
        this.addRenderableWidget(Button.builder(
                Component.literal("§b Select Model"),
                btn -> openModelSelectScreen()
        ).bounds(col2X, startY, GRID_BTN_WIDTH, BTN_HEIGHT).build());

        // Row 1: Skin Settings | Edit Config
        this.addRenderableWidget(Button.builder(
                Component.literal("§d👕 Skins"),
                btn -> openSkinSelectScreen()
        ).bounds(col1X, startY + (BTN_HEIGHT + BTN_SPACING), GRID_BTN_WIDTH, BTN_HEIGHT).build());
        this.addRenderableWidget(Button.builder(
                Component.literal("§e Config"),
                btn -> openConfigScreen()
        ).bounds(col2X, startY + (BTN_HEIGHT + BTN_SPACING), GRID_BTN_WIDTH, BTN_HEIGHT).build());

        // Row 2: Companion List | Remove Companion
        this.addRenderableWidget(Button.builder(
                Component.literal("§7📋 List"),
                btn -> requestCompanionList()
        ).bounds(col1X, startY + (BTN_HEIGHT + BTN_SPACING) * 2, GRID_BTN_WIDTH, BTN_HEIGHT).build());
        this.addRenderableWidget(Button.builder(
                Component.literal("§c✖ Remove"),
                btn -> removeCompanion()
        ).bounds(col2X, startY + (BTN_HEIGHT + BTN_SPACING) * 2, GRID_BTN_WIDTH, BTN_HEIGHT).build());

        // Row 3: Help (full width)
        int fullBtnX = centerX - BTN_WIDTH / 2;
        fullBtnX = Math.max(fullBtnX, panelX + PANEL_PADDING);
        fullBtnX = Math.min(fullBtnX, panelX + panelWidth - BTN_WIDTH - PANEL_PADDING);
        this.addRenderableWidget(Button.builder(
                Component.literal("§f❓ Help & Commands"),
                btn -> openHelpScreen()
        ).bounds(fullBtnX, startY + (BTN_HEIGHT + BTN_SPACING) * 3, BTN_WIDTH, BTN_HEIGHT).build());

        // Close button at bottom
        int closeY = startY + (BTN_HEIGHT + BTN_SPACING) * 4 + 10;
        closeY = MineAgentUiComponents.clamp(closeY, MARGIN,
                this.height - BTN_HEIGHT - MARGIN);
        this.addRenderableWidget(Button.builder(
                Component.literal("Done"),
                btn -> this.onClose()
        ).bounds(fullBtnX, closeY, BTN_WIDTH, BTN_HEIGHT).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;

        // ── Styled panel background ──
        MineAgentUiComponents.drawPanel(
                graphics,
                panelX, panelY, panelWidth, panelHeight,
                Component.literal("§6§lMineAgent"),
                true, // accent = gold border
                this.width, this.height
        );

        // ── Tilted decorations at title corners ──
        MineAgentUiComponents.drawTiltedDecoration(
                graphics,
                panelX + 8,
                panelY + MineAgentUiComponents.TITLE_BAND_HEIGHT / 2,
                4, MineAgentUiComponents.COLOR_CORNER, -2.0f);
        MineAgentUiComponents.drawTiltedDecoration(
                graphics,
                panelX + panelWidth - 8,
                panelY + MineAgentUiComponents.TITLE_BAND_HEIGHT / 2,
                4, MineAgentUiComponents.COLOR_CORNER, 2.0f);

        // ── Subtitle ──
        graphics.drawCenteredString(
                this.minecraft.font,
                Component.literal("§7AI Companion System"),
                centerX, panelY + MineAgentUiComponents.TITLE_BAND_HEIGHT + 2,
                MineAgentUiComponents.COLOR_SUBTITLE
        );

        // ── Status line ──
        UUID companionId = com.mineagent.fabric.client.MineAgentClient.getCompanionId();
        String statusText;
        int statusColor;
        if (companionId != null) {
            statusText = "§a● Companion Online [" + companionId.toString().substring(0, 8) + "...]";
            statusColor = 0x55FF55;
        } else {
            statusText = "§c● No Companion";
            statusColor = 0xFF5555;
        }
        graphics.drawCenteredString(
                this.minecraft.font, statusText,
                centerX, contentStartY - 4, statusColor);

        // Render buttons
        super.render(graphics, mouseX, mouseY, partialTick);

        // ── Footer ──
        int footerY = this.height - MARGIN - 10;
        footerY = MineAgentUiComponents.clamp(footerY, MARGIN,
                this.height - 10 - MARGIN);
        graphics.drawCenteredString(
                this.minecraft.font,
                Component.literal("§8Press ESC to close | Chat with companion using T"),
                centerX, footerY, 0x666666
        );
    }

    // ── Navigation ──────────────────────────────────────────────

    private void openSpawnScreen() { Minecraft.getInstance().setScreen(new SpawnCompanionScreen()); }
    private void openModelSelectScreen() { Minecraft.getInstance().setScreen(new ModelSelectScreen()); }
    private void openSkinSelectScreen() { Minecraft.getInstance().setScreen(new SkinSelectScreen()); }
    private void openConfigScreen() { Minecraft.getInstance().setScreen(new ConfigEditScreen()); }
    private void openHelpScreen() { Minecraft.getInstance().setScreen(new HelpScreen()); }

    private void requestCompanionList() {
        this.onClose();
        if (this.minecraft.player != null) {
            this.minecraft.player.connection.sendCommand("mineagent list");
        }
    }

    private void removeCompanion() {
        this.onClose();
        if (this.minecraft.player != null) {
            this.minecraft.player.connection.sendCommand("mineagent remove");
        }
    }

    @Override
    public boolean shouldCloseOnEsc() { return true; }
}
