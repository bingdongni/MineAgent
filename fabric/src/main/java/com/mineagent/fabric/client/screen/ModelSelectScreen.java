package com.mineagent.fabric.client.screen;

import com.mineagent.api.llm.model.ThinkingEffortSpec;
import com.mineagent.fabric.client.ui.MineAgentUiComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 模型与Provider选择界面。
 *
 * <p>支持中转站URL配置，玩家可以：
 * <ul>
 *   <li>通过3x3快速按钮选择常见Provider</li>
 *   <li>手动输入Provider ID和模型名</li>
 *   <li>填写API Key和Base URL（中转站）</li>
 *   <li>一键应用所有配置到服务器</li>
 * </ul>
 *
 * <p>所有配置通过 /mineagent setconfig 命令保存到服务器配置文件。
 *
 * <p><b>UI Design</b>: Uses {@link MineAgentUiComponents} for styled panel
 * background with dot grid texture, gold-accented borders, tilted corner
 * decorations, and section headers. All content is boundary-clamped.
 */
public class ModelSelectScreen extends Screen {

    private static final int BTN_HEIGHT = 20;
    private static final int INPUT_WIDTH = 200;
    private static final int INPUT_HEIGHT = 20;
    private static final int GRID_BTN_WIDTH = 62;
    private static final int GRID_BTN_HEIGHT = 18;
    private static final int GRID_SPACING = 2;
    /** 每个输入块的高度：12(label) + 20(box) + 6(间距)。 */
    private static final int FIELD_GAP = 38;
    private static final int MARGIN = 10;
    private static final int PANEL_PADDING = 8;

    /** 快速选择Provider按钮（id, 显示文本）。 */
    private static final String[][] QUICK_PROVIDERS = {
            {"deepseek", "§bDeepSeek"},
            {"openai", "§aOpenAI"},
            {"anthropic", "§eAnthropic"},
            {"gemini", "§6Gemini"},
            {"qwen", "§9Qwen"},
            {"glm", "§dGLM"},
            {"grok", "§cGrok"},
            {"moonshot", "§fKimi"},
            {"minimax", "§5MiniMax"}
    };

    private EditBox providerField;
    private EditBox modelField;
    private EditBox apiKeyField;
    private EditBox baseUrlField;
    private EditBox thinkingEffortField;

    /** Computed panel bounds (clamped to screen in init). */
    private int panelX, panelY, panelWidth, panelHeight;
    private int contentStartY;

    public ModelSelectScreen() {
        super(Component.literal("Select Model & Provider"));
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;

        // ── Compute panel dimensions ──
        // 3 rows of quick buttons + 5 input fields + button row
        int quickGridHeight = 3 * (GRID_BTN_HEIGHT + GRID_SPACING);
        int inputsHeight = FIELD_GAP * 5 + INPUT_HEIGHT;
        int buttonsHeight = BTN_HEIGHT;
        int contentHeight = quickGridHeight + 4 + inputsHeight + 6 + buttonsHeight;
        panelWidth = Math.min(INPUT_WIDTH + PANEL_PADDING * 2 + 4,
                this.width - 2 * MARGIN);
        panelHeight = Math.min(contentHeight + MineAgentUiComponents.TITLE_BAND_HEIGHT
                        + PANEL_PADDING * 2 + 16,
                this.height - 2 * MARGIN);

        panelX = MineAgentUiComponents.clamp(centerX - panelWidth / 2, MARGIN,
                Math.max(MARGIN, this.width - panelWidth - MARGIN));
        panelY = MineAgentUiComponents.clamp((this.height - panelHeight) / 2, MARGIN,
                Math.max(MARGIN, this.height - panelHeight - MARGIN));

        contentStartY = panelY + MineAgentUiComponents.TITLE_BAND_HEIGHT + PANEL_PADDING + 8;

        int inputX = centerX - INPUT_WIDTH / 2;
        // Clamp inputX to panel bounds
        inputX = Math.max(inputX, panelX + PANEL_PADDING);
        inputX = Math.min(inputX, panelX + panelWidth - INPUT_WIDTH - PANEL_PADDING);

        // --- 3x3 grid of Provider quick-select buttons ---
        int gridTotalWidth = GRID_BTN_WIDTH * 3 + GRID_SPACING * 2;
        int gridStartX = centerX - gridTotalWidth / 2;
        gridStartX = Math.max(gridStartX, panelX + PANEL_PADDING);
        int quickStartY = contentStartY;

        for (int i = 0; i < QUICK_PROVIDERS.length; i++) {
            int row = i / 3;
            int col = i % 3;
            int x = gridStartX + col * (GRID_BTN_WIDTH + GRID_SPACING);
            int y = quickStartY + row * (GRID_BTN_HEIGHT + GRID_SPACING);
            // Boundary-safe: clamp to screen
            x = MineAgentUiComponents.clamp(x, MARGIN,
                    this.width - GRID_BTN_WIDTH - MARGIN);
            y = MineAgentUiComponents.clamp(y, MARGIN,
                    this.height - GRID_BTN_HEIGHT - MARGIN);
            final String providerId = QUICK_PROVIDERS[i][0];
            String display = QUICK_PROVIDERS[i][1];
            this.addRenderableWidget(Button.builder(
                    Component.literal(display),
                    btn -> providerField.setValue(providerId)
            ).bounds(x, y, GRID_BTN_WIDTH, GRID_BTN_HEIGHT).build());
        }

        // After 3 rows of quick buttons
        int afterQuickY = quickStartY + 3 * (GRID_BTN_HEIGHT + GRID_SPACING) + 4;

        // --- Provider输入框 ---
        this.providerField = new EditBox(
                this.minecraft.font, inputX, afterQuickY,
                INPUT_WIDTH, INPUT_HEIGHT, Component.literal("Provider")
        );
        this.providerField.setMaxLength(32);
        this.providerField.setValue("deepseek");
        this.providerField.setHint(Component.literal("§7Provider ID"));
        this.addRenderableWidget(this.providerField);

        // --- Model输入框 ---
        this.modelField = new EditBox(
                this.minecraft.font, inputX, afterQuickY + FIELD_GAP,
                INPUT_WIDTH, INPUT_HEIGHT, Component.literal("Model")
        );
        this.modelField.setMaxLength(64);
        this.modelField.setValue("deepseek-v4-flash");
        this.modelField.setHint(Component.literal("§7模型名"));
        this.modelField.setResponder(value -> updateEffortHint());
        this.addRenderableWidget(this.modelField);

        // --- API Key输入框 ---
        this.apiKeyField = new EditBox(
                this.minecraft.font, inputX, afterQuickY + FIELD_GAP * 2,
                INPUT_WIDTH, INPUT_HEIGHT, Component.literal("API Key")
        );
        this.apiKeyField.setMaxLength(256);
        this.apiKeyField.setHint(Component.literal("§7输入API Key并保存到配置"));
        this.addRenderableWidget(this.apiKeyField);

        // --- Base URL输入框 ---
        this.baseUrlField = new EditBox(
                this.minecraft.font, inputX, afterQuickY + FIELD_GAP * 3,
                INPUT_WIDTH, INPUT_HEIGHT, Component.literal("Base URL")
        );
        this.baseUrlField.setMaxLength(256);
        this.baseUrlField.setHint(Component.literal("§7留空用官方URL，可填中转站URL"));
        this.addRenderableWidget(this.baseUrlField);

        // --- Thinking Effort 输入框（留空=使用API默认值） ---
        this.thinkingEffortField = new EditBox(
                this.minecraft.font, inputX, afterQuickY + FIELD_GAP * 4,
                INPUT_WIDTH, INPUT_HEIGHT, Component.literal("Thinking Effort")
        );
        this.thinkingEffortField.setMaxLength(8);
        this.thinkingEffortField.setValue("");
        this.thinkingEffortField.setHint(Component.literal("§7留空=默认"));
        this.addRenderableWidget(this.thinkingEffortField);
        updateEffortHint();

        // --- 按钮区域（3个按钮并排） ---
        int buttonY = afterQuickY + FIELD_GAP * 4 + INPUT_HEIGHT + 6;
        // Boundary-safe: clamp buttonY
        buttonY = MineAgentUiComponents.clamp(buttonY, MARGIN,
                this.height - BTN_HEIGHT - MARGIN);
        int thirdBtnWidth = (INPUT_WIDTH - 8) / 3;

        this.addRenderableWidget(Button.builder(
                Component.literal("§a✔ Apply"),
                btn -> applySelection()
        ).bounds(inputX, buttonY, thirdBtnWidth, BTN_HEIGHT).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("§7 Providers"),
                btn -> {
                    this.onClose();
                    if (this.minecraft.player != null) {
                        this.minecraft.player.connection.sendCommand("mineagent providers");
                    }
                }
        ).bounds(inputX + thirdBtnWidth + 4, buttonY, thirdBtnWidth, BTN_HEIGHT).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("§7Back"),
                btn -> Minecraft.getInstance().setScreen(new MineAgentMainMenuScreen())
        ).bounds(inputX + (thirdBtnWidth + 4) * 2, buttonY, thirdBtnWidth, BTN_HEIGHT).build());

        this.setInitialFocus(this.providerField);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;

        // ── Styled panel background ──
        MineAgentUiComponents.drawPanel(
                graphics,
                panelX, panelY, panelWidth, panelHeight,
                Component.literal("§6§lSelect Model & Provider"),
                true,
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

        // ── Labels ──
        int inputX = centerX - INPUT_WIDTH / 2;
        inputX = Math.max(inputX, panelX + PANEL_PADDING);
        inputX = Math.min(inputX, panelX + panelWidth - INPUT_WIDTH - PANEL_PADDING);

        // Quick provider section header
        MineAgentUiComponents.drawSectionHeader(
                graphics, "§7Quick Select Provider:",
                inputX, contentStartY - 10,
                INPUT_WIDTH, MineAgentUiComponents.COLOR_SUBTITLE);

        // Input field labels
        int afterQuickY = contentStartY + 3 * (GRID_BTN_HEIGHT + GRID_SPACING) + 4;
        drawInputLabel(graphics, "§bProvider:", inputX, afterQuickY - 10);
        drawInputLabel(graphics, "§bModel:", inputX, afterQuickY + FIELD_GAP - 10);
        drawInputLabel(graphics, "§eAPI Key:", inputX, afterQuickY + FIELD_GAP * 2 - 10);
        drawInputLabel(graphics, "§eBase URL:", inputX, afterQuickY + FIELD_GAP * 3 - 10);
        drawInputLabel(graphics, "§eEffort:", inputX, afterQuickY + FIELD_GAP * 4 - 10);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** Draw an input label, clamped to panel bounds. */
    private void drawInputLabel(GuiGraphics graphics, String text, int x, int y) {
        // Boundary-safe: clamp Y to visible area
        y = MineAgentUiComponents.clamp(y, MARGIN,
                this.height - 10 - MARGIN);
        graphics.drawString(this.minecraft.font, text, x, y, 0xAAAAAA, false);
    }

    /**
     * Update the thinking-effort hint based on the current model field value.
     */
    private void updateEffortHint() {
        if (this.thinkingEffortField == null) return;
        String model = this.modelField != null ? this.modelField.getValue() : "";
        ThinkingEffortSpec spec = ThinkingEffortSpec.forModel(model);
        String hint = spec.supportsEffort()
                ? spec.hint()
                : "§7留空=默认 (此模型不支持思考强度)";
        this.thinkingEffortField.setHint(Component.literal(hint));
    }

    /** 应用选择：依次发送setconfig命令设置provider/model/apikey/baseurl。 */
    private void applySelection() {
        String provider = providerField.getValue().trim();
        String model = modelField.getValue().trim();
        String apiKey = apiKeyField.getValue().trim();
        String baseUrl = baseUrlField.getValue().trim();
        String effort = thinkingEffortField.getValue().trim();

        if (provider.isEmpty() || model.isEmpty()) {
            return;
        }

        this.onClose();

        if (this.minecraft.player != null) {
            // 依次发送setconfig命令
            this.minecraft.player.connection.sendCommand("mineagent setconfig provider " + provider);
            this.minecraft.player.connection.sendCommand("mineagent setconfig model " + model);
            if (!apiKey.isEmpty()) {
                this.minecraft.player.connection.sendCommand("mineagent setconfig apikey " + apiKey);
            }
            if (!baseUrl.isEmpty()) {
                this.minecraft.player.connection.sendCommand("mineagent setconfig baseurl " + baseUrl);
            }
            if (!effort.isEmpty()) {
                this.minecraft.player.connection.sendCommand("mineagent seteffort " + effort);
            }
            // 显示成功消息
            this.minecraft.player.displayClientMessage(
                    Component.literal("§a[MineAgent] 配置已应用: " + provider + "/" + model),
                    false
            );
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }
}
