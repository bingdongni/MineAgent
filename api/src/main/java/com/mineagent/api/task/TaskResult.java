package com.mineagent.api.task;

/**
 * The result of a task execution — success or failure with a message and data.
 */
public final class TaskResult {

    private final boolean success;
    private final String message;
    private final String data; // JSON string or null

    private TaskResult(boolean success, String message, String data) {
        this.success = success;
        this.message = message;
        this.data = data;
    }

    public static TaskResult ok(String message) {
        return new TaskResult(true, message, null);
    }

    public static TaskResult ok(String message, String data) {
        return new TaskResult(true, message, data);
    }

    public static TaskResult fail(String message) {
        return new TaskResult(false, message, null);
    }

    public boolean success() { return success; }
    public String message() { return message; }
    public String data() { return data; }

    /** Serialize to a JSON string for the LLM. */
    public String toJson() {
        StringBuilder sb = new StringBuilder("{\"success\":");
        sb.append(success);
        if (message != null) {
            sb.append(",\"message\":\"");
            sb.append(escapeJson(message));
            sb.append("\"");
        }
        if (data != null) {
            sb.append(",\"data\":");
            sb.append(data);
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
