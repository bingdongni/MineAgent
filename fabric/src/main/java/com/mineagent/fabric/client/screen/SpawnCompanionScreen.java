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
 * 伴游spawn配置界面。
 *
 * <p>玩家可在此界面配置 name/provider/model/apikey/baseurl/temperature/effort，
 * 然后通过 /mineagent quick 命令快速spawn伴游。
 *
 * <p><b>UI Design</b>: Uses {@link MineAgentUiComponents} for styled panel
 * background with dot grid texture, gold-accented borders, and tilted
 * corner decorations. All content is boundary-clamped to ensure it
 * never exceeds the visible screen area.
 *
 * <p>注意：所有命令发送必须使用 sendCommand 而非 sendChat，
 * 否则会被当作普通聊天消息发送而非命令执行。
 */
public class SpawnCompanionScreen extends Screen {

    private static final int BTN_WIDTH = 200;
    private static final int BTN_HEIGHT = 20;
    private static final int INPUT_WIDTH = 200;
    private static final int INPUT_HEIGHT = 18;
    /** 紧凑行间距：18px。 */
    private static final int ROW_SPACING = 18;
    private static final int QUICK_BTN_WIDTH = 62;
    private static final int QUICK_BTN_HEIGHT = 18;
    private static final int QUICK_SPACING = 2;
    /** Panel padding (inset from panel border to content). */
    private static final int PANEL_PADDING = 8;

    /** Provider快速选择按钮：{providerId, 显示文本} */
    private static final String[][] QUICK_PROVIDERS = {
            {"deepseek",  "§bDeepSeek"},
            {"openai",    "§aOpenAI"},
            {"anthropic", "§eAnthropic"},
            {"gemini",    "§6Gemini"},
            {"qwen",      "§9Qwen"},
            {"glm",       "§dGLM"},
            {"grok",      "§cGrok"},
            {"moonshot",  "§fKimi"},
            {"minimax",   "§5MiniMax"}
    };

    private EditBox nameField;
    private EditBox providerField;
    private EditBox modelField;
    private EditBox apiKeyField;
    private EditBox baseUrlField;
    private EditBox temperatureField;
    private EditBox thinkingEffortField;

    /** Computed panel bounds (clamped to screen in init). */
    private int panelX, panelY, panelWidth, panelHeight;
    /** Computed content start Y (after title band). */
    private int contentStartY;

    public SpawnCompanionScreen() {
        super(Component.literal("Spawn Companion"));
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;

        // ── Compute total content height ──
        // 3 rows of quick buttons + spacing + 7 input rows + button row
        int quickGridHeight = 3 * (QUICK_BTN_HEIGHT + QUICK_SPACING);
        int inputsHeight = 7 * ROW_SPACING;
        int buttonsHeight = BTN_HEIGHT;
        int totalContentHeight = quickGridHeight + 4 + inputsHeight + 6 + buttonsHeight;

        // ── Compute panel dimensions (clamped to screen) ──
        panelWidth = Math.min(INPUT_WIDTH + PANEL_PADDING * 2 + 4,
                this.width - 2 * MineAgentUiComponents.MARGIN);
        panelHeight = Math.min(totalContentHeight + MineAgentUiComponents.TITLE_BAND_HEIGHT
                        + PANEL_PADDING * 2 + 16,
                this.height - 2 * MineAgentUiComponents.MARGIN);

        // Center the panel, clamped to margins
        panelX = MineAgentUiComponents.clamp(
                centerX - panelWidth / 2,
                MineAgentUiComponents.MARGIN,
                Math.max(MineAgentUiComponents.MARGIN,
                        this.width - panelWidth - MineAgentUiComponents.MARGIN));
        panelY = MineAgentUiComponents.clamp(
                (this.height - panelHeight) / 2,
                MineAgentUiComponents.MARGIN,
                Math.max(MineAgentUiComponents.MARGIN,
                        this.height - panelHeight - MineAgentUiComponents.MARGIN));

        contentStartY = panelY + MineAgentUiComponents.TITLE_BAND_HEIGHT + PANEL_PADDING;

        // ── Content layout (relative to panel) ──
        int inputX = centerX - INPUT_WIDTH / 2;
        // Clamp inputX to panel bounds
        inputX = Math.max(inputX, panelX + PANEL_PADDING);
        inputX = Math.min(inputX, panelX + panelWidth - INPUT_WIDTH - PANEL_PADDING);

        // Quick provider grid
        int gridTotalWidth = QUICK_BTN_WIDTH * 3 + QUICK_SPACING * 2;
        int gridStartX = centerX - gridTotalWidth / 2;
        gridStartX = Math.max(gridStartX, panelX + PANEL_PADDING);
        int quickStartY = contentStartY;

        for (int i = 0; i < QUICK_PROVIDERS.length; i++) {
            int row = i / 3;
            int col = i % 3;
            int bx = gridStartX + col * (QUICK_BTN_WIDTH + QUICK_SPACING);
            int by = quickStartY + row * (QUICK_BTN_HEIGHT + QUICK_SPACING);
            // Boundary-safe: clamp to screen
            bx = MineAgentUiComponents.clamp(bx, MineAgentUiComponents.MARGIN,
                    this.width - QUICK_BTN_WIDTH - MineAgentUiComponents.MARGIN);
            by = MineAgentUiComponents.clamp(by, MineAgentUiComponents.MARGIN,
                    this.height - QUICK_BTN_HEIGHT - MineAgentUiComponents.MARGIN);
            final String providerId = QUICK_PROVIDERS[i][0];
            String label = QUICK_PROVIDERS[i][1];
            this.addRenderableWidget(Button.builder(
                    Component.literal(label),
                    btn -> providerField.setValue(providerId)
            ).bounds(bx, by, QUICK_BTN_WIDTH, QUICK_BTN_HEIGHT).build());
        }

        int afterQuickY = quickStartY + 3 * (QUICK_BTN_HEIGHT + QUICK_SPACING) + 4;

        // --- Name input ---
        this.nameField = new EditBox(
                this.minecraft.font,
                inputX, afterQuickY,
                INPUT_WIDTH, INPUT_HEIGHT,
                Component.literal("Name")
        );
        this.nameField.setMaxLength(32);
        this.nameField.setValue("");
        this.nameField.setHint(Component.literal("§7伴游名字 (留空则使用模型名称)"));
        this.addRenderableWidget(this.nameField);

        int providerY = afterQuickY + ROW_SPACING;
        this.providerField = new EditBox(
                this.minecraft.font,
                inputX, providerY,
                INPUT_WIDTH, INPUT_HEIGHT,
                Component.literal("Provider")
        );
        this.providerField.setMaxLength(32);
        this.providerField.setValue("deepseek");
        this.providerField.setHint(Component.literal("§7Provider ID (如 deepseek, openai...)"));
        this.addRenderableWidget(this.providerField);

        int modelY = providerY + ROW_SPACING;
        this.modelField = new EditBox(
                this.minecraft.font,
                inputX, modelY,
                INPUT_WIDTH, INPUT_HEIGHT,
                Component.literal("Model")
        );
        this.modelField.setMaxLength(64);
        this.modelField.setValue("deepseek-v4-flash");
        this.modelField.setHint(Component.literal("§7模型名 (如 deepseek-v4-flash, gpt-5.6-sol...)"));
        this.modelField.setResponder(value -> updateEffortHint());
        this.addRenderableWidget(this.modelField);

        int apiKeyY = modelY + ROW_SPACING;
        this.apiKeyField = new EditBox(
                this.minecraft.font,
                inputX, apiKeyY,
                INPUT_WIDTH, INPUT_HEIGHT,
                Component.literal("API Key")
        );
        this.apiKeyField.setMaxLength(256);
        this.apiKeyField.setHint(Component.literal("§7留空则使用config中的key"));
        this.addRenderableWidget(this.apiKeyField);

        int baseUrlY = apiKeyY + ROW_SPACING;
        this.baseUrlField = new EditBox(
                this.minecraft.font,
                inputX, baseUrlY,
                INPUT_WIDTH, INPUT_HEIGHT,
                Component.literal("Base URL")
        );
        this.baseUrlField.setMaxLength(256);
        this.baseUrlField.setHint(Component.literal("§7留空使用官方URL，可填中转站URL"));
        this.addRenderableWidget(this.baseUrlField);

        int tempY = baseUrlY + ROW_SPACING;
        this.temperatureField = new EditBox(
                this.minecraft.font,
                inputX, tempY,
                INPUT_WIDTH, INPUT_HEIGHT,
                Component.literal("Temperature")
        );
        this.temperatureField.setMaxLength(8);
        this.temperatureField.setValue("0.7");
        this.temperatureField.setHint(Component.literal("§7温度 (0.0-2.0，默认0.7)"));
        this.addRenderableWidget(this.temperatureField);

        int effortY = tempY + ROW_SPACING;
        this.thinkingEffortField = new EditBox(
                this.minecraft.font,
                inputX, effortY,
                INPUT_WIDTH, INPUT_HEIGHT,
                Component.literal("Thinking Effort")
        );
        this.thinkingEffortField.setMaxLength(8);
        this.thinkingEffortField.setValue("");
        this.thinkingEffortField.setHint(Component.literal("§7留空=默认"));
        this.addRenderableWidget(this.thinkingEffortField);
        updateEffortHint();

        // --- Spawn + Back buttons (side by side) ---
        int actionY = effortY + INPUT_HEIGHT + 6;
        // Boundary-safe: clamp actionY
        actionY = MineAgentUiComponents.clamp(actionY, MineAgentUiComponents.MARGIN,
                this.height - BTN_HEIGHT - MineAgentUiComponents.MARGIN);
        int halfBtnWidth = (BTN_WIDTH - 4) / 2;
        this.addRenderableWidget(Button.builder(
                Component.literal("§a▶ Spawn Companion"),
                btn -> doSpawn()
        ).bounds(inputX, actionY, halfBtnWidth, BTN_HEIGHT).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("§7Back"),
                btn -> Minecraft.getInstance().setScreen(new MineAgentMainMenuScreen())
        ).bounds(inputX + halfBtnWidth + 4, actionY, halfBtnWidth, BTN_HEIGHT).build());

        this.setInitialFocus(this.nameField);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;

        // ── Draw styled panel background ──
        // This is the primary visual enhancement: a dark panel with
        // dot grid texture, gold accent border, title band, and corner
        // decorations. All bounds are clamped in drawPanel().
        MineAgentUiComponents.drawPanel(
                graphics,
                panelX, panelY, panelWidth, panelHeight,
                Component.literal("§6§lSpawn Companion"),
                true, // accent = gold border
                this.width, this.height
        );

        // ── Tilted decoration at title-left (subtle 2° rotation) ──
        // Draws a small gold square rotated 2° to the left of the title
        // band, adding visual interest without affecting readability.
        // Positioned safely inside the panel (offset 6px from left/top).
        MineAgentUiComponents.drawTiltedDecoration(
                graphics,
                panelX + 8,
                panelY + MineAgentUiComponents.TITLE_BAND_HEIGHT / 2,
                4, // small 4×4 square
                MineAgentUiComponents.COLOR_CORNER,
                -2.0f // -2° rotation
        );
        // Right side decoration (opposite rotation for symmetry)
        MineAgentUiComponents.drawTiltedDecoration(
                graphics,
                panelX + panelWidth - 8,
                panelY + MineAgentUiComponents.TITLE_BAND_HEIGHT / 2,
                4,
                MineAgentUiComponents.COLOR_CORNER,
                2.0f // +2° rotation
        );

        // ── Input labels (drawn above their fields) ──
        // These are drawn AFTER the panel background so they're visible.
        int inputX = centerX - INPUT_WIDTH / 2;
        inputX = Math.max(inputX, panelX + PANEL_PADDING);
        inputX = Math.min(inputX, panelX + panelWidth - INPUT_WIDTH - PANEL_PADDING);

        int afterQuickY = contentStartY + 3 * (QUICK_BTN_HEIGHT + QUICK_SPACING) + 4;

        // Section header for quick providers
        MineAgentUiComponents.drawSectionHeader(
                graphics, "§7Quick Provider:",
                inputX, contentStartY - 9,
                INPUT_WIDTH, MineAgentUiComponents.COLOR_SUBTITLE);

        // Input labels — drawn just above each field
        drawInputLabel(graphics, "§7Name:", inputX, afterQuickY - 9);
        drawInputLabel(graphics, "§7Provider:", inputX, afterQuickY + ROW_SPACING - 9);
        drawInputLabel(graphics, "§7Model:", inputX, afterQuickY + ROW_SPACING * 2 - 9);
        drawInputLabel(graphics, "§7API Key:", inputX, afterQuickY + ROW_SPACING * 3 - 9);
        drawInputLabel(graphics, "§7Base URL:", inputX, afterQuickY + ROW_SPACING * 4 - 9);
        drawInputLabel(graphics, "§7Temp:", inputX, afterQuickY + ROW_SPACING * 5 - 9);
        drawInputLabel(graphics, "§7Effort:", inputX, afterQuickY + ROW_SPACING * 6 - 9);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** Draw an input label, clamped to screen bounds. */
    private void drawInputLabel(GuiGraphics graphics, String text, int x, int y) {
        // Boundary-safe: clamp Y to visible area
        y = MineAgentUiComponents.clamp(y, MineAgentUiComponents.MARGIN,
                this.height - 10 - MineAgentUiComponents.MARGIN);
        graphics.drawString(this.minecraft.font, text, x, y, 0x999999, false);
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

    /**
     * 执行spawn逻辑：
     * - 通过 setconfig 命令保存配置到服务端
     * - 然后调用 quick 命令spawn伴游
     *
     * <p>参数安全：配置值会移除控制字符；显示名作为 Brigadier 引号字符串
     * 发送，因此空格、斜杠等合法模型字符不会改变命令结构。
     */
    private void doSpawn() {
        String name     = sanitizeForCommand(nameField.getValue().trim(), true);
        String provider = sanitizeForCommand(providerField.getValue().trim(), false);
        String model    = sanitizeForCommand(modelField.getValue().trim(), true);
        String apiKey   = sanitizeForCommand(apiKeyField.getValue().trim(), true);
        String baseUrl  = sanitizeForCommand(baseUrlField.getValue().trim(), true);
        String rawTemp  = temperatureField.getValue().trim();
        String effort   = sanitizeForCommand(thinkingEffortField.getValue().trim(), false);

        if (this.minecraft.player == null) {
            this.onClose();
            return;
        }

        // Validate every field before sending the first setconfig command.
        // The old word sanitizer replaced the decimal point in "0.7" with an
        // underscore, and later validation failures left a partially-updated
        // server config because earlier commands had already been sent.
        String temp = "";
        if (!rawTemp.isEmpty()) {
            try {
                double parsedTemperature = Double.parseDouble(rawTemp);
                if (!Double.isFinite(parsedTemperature)
                        || parsedTemperature < 0.0 || parsedTemperature > 2.0) {
                    throw new NumberFormatException("out of range");
                }
                temp = Double.toString(parsedTemperature);
            } catch (NumberFormatException invalidTemperature) {
                this.minecraft.player.displayClientMessage(Component.literal(
                        "§c[MineAgent] Temperature must be a number from 0 to 2."), false);
                return;
            }
        }

        if (!effort.isEmpty() && !java.util.List.of(
                "off", "low", "medium", "high", "xhigh", "max").contains(effort)) {
            this.minecraft.player.displayClientMessage(Component.literal(
                    "§c[MineAgent] Invalid effort. Use: off, low, medium, high, xhigh, max"), false);
            return;
        }

        String effectiveName = name.isEmpty() ? model : name;
        if (!effort.isEmpty() && effectiveName.isEmpty()) {
            this.minecraft.player.displayClientMessage(Component.literal(
                    "§c[MineAgent] A name or model is required when effort is set."), false);
            return;
        }

        if (!provider.isEmpty()) {
            this.minecraft.player.connection.sendCommand("mineagent setconfig provider " + provider);
        }
        if (!model.isEmpty()) {
            this.minecraft.player.connection.sendCommand("mineagent setconfig model " + model);
        }
        if (!apiKey.isEmpty()) {
            this.minecraft.player.connection.sendCommand("mineagent setconfig apikey " + apiKey);
        }
        if (!baseUrl.isEmpty()) {
            this.minecraft.player.connection.sendCommand("mineagent setconfig baseurl " + baseUrl);
        }
        if (!temp.isEmpty()) {
            this.minecraft.player.connection.sendCommand("mineagent setconfig temperature " + temp);
        }

        if (!effort.isEmpty()) {
            // Spawn and effort must be one server operation. Sending a later
            // unscoped /seteffort modifies the old primary companion in a
            // multi-companion session, and can mutate it even if spawn fails.
            this.minecraft.player.connection.sendCommand(
                    "mineagent quick " + quoteCommandString(effectiveName) + " " + effort);
        } else if (name.isEmpty()) {
            this.minecraft.player.connection.sendCommand("mineagent quick");
        } else {
            this.minecraft.player.connection.sendCommand(
                    "mineagent quick " + quoteCommandString(name));
        }

        this.onClose();
    }

    /**
     * Sanitize a user-entered string for safe inclusion in a slash command.
     *
     * <p>For {@code word} arguments (provider/effort), only
     * [A-Za-z0-9_+-] are retained — anything else (spaces, quotes, slashes,
     * localized chars) is replaced with {@code _}. This prevents command
     * parsing errors and the classic "name with spaces breaks /quick" bug.
     *
     * <p>For quoted/greedy arguments (name/model/apiKey/baseUrl), we only strip
     * control characters and line breaks; spaces and URL metacharacters
     * (&, ?, =) are preserved because greedyString accepts them and these
     * fields commonly contain them.
     */
    private static String sanitizeForCommand(String raw, boolean allowSpaces) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isISOControl(c)) continue;
            if (allowSpaces) {
                // greedyString args: keep printable chars, only drop newlines
                // (already done above) — preserves URL/apiKey metacharacters.
                sb.append(c);
            } else {
                // word args: keep only [A-Za-z0-9_+-]
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                        || (c >= '0' && c <= '9')
                        || c == '_' || c == '-' || c == '+') {
                    sb.append(c);
                } else if (c == ' ' || c == '.' || c == '/' || c == ':') {
                    // Replace common separators with underscore so
                    // "deepseek-v4-flash" stays intact but "My Companion"
                    // becomes "My_Companion" instead of breaking the command.
                    sb.append('_');
                }
                // other chars (quotes, semicolons, localized) are dropped
            }
        }
        return sb.toString();
    }

    /** Quote a Brigadier string argument without permitting a second command. */
    private static String quoteCommandString(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }
}
