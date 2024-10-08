package signal.broadcast.model.enums;

public enum  CONSOLE_COLORS {
    RESET("\u001B[0m"),
    BLACK("\u001B[30m"),
    RED("\u001B[31m"),
    GREEN("\u001B[32m"),
    YELLOW("\u001B[33m"),
    BLUE("\u001B[34m"),
    PURPLE("\u001B[35m"),
    CYAN("\u001B[36m"),
    WHITE("\u001B[37m"),
    BRIGHT_BLACK("\u001B[90m"),
    BRIGHT_RED("\u001B[91m"),
    BRIGHT_GREEN("\u001B[92m"),
    BRIGHT_YELLOW("\u001B[93m"),
    BRIGHT_BLUE("\u001B[94m"),
    BRIGHT_PURPLE("\u001B[95m"),
    BRIGHT_CYAN("\u001B[96m"),
    BRIGHT_WHITE("\u001B[97m"),
    BACKGROUND_BLACK("\u001B[40m"),
    BACKGROUND_RED("\u001B[41m"),
    BACKGROUND_GREEN("\u001B[42m"),
    BACKGROUND_YELLOW("\u001B[43m"),
    BACKGROUND_BLUE("\u001B[44m"),
    BACKGROUND_PURPLE("\u001B[45m"),
    BACKGROUND_CYAN("\u001B[46m"),
    BACKGROUND_WHITE("\u001B[47m"),
    BRIGHT_BACKGROUND_BLACK("\u001B[100m"),
    BRIGHT_BACKGROUND_RED("\u001B[101m"),
    BRIGHT_BACKGROUND_GREEN("\u001B[102m"),
    BRIGHT_BACKGROUND_YELLOW("\u001B[103m"),
    BRIGHT_BACKGROUND_BLUE("\u001B[104m"),
    BRIGHT_BACKGROUND_PURPLE("\u001B[105m"),
    BRIGHT_BACKGROUND_CYAN("\u001B[106m"),
    BRIGHT_BACKGROUND_WHITE("\u001B[107m");

    private final String code;

    CONSOLE_COLORS(String code) {
        this.code = code;
    }

    @Override
    public String toString() {
        return code;
    }
}
