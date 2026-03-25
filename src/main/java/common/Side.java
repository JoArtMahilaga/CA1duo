package common;

public enum Side {
    BUY("B"),
    SELL("S");

    private final String code;

    Side(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static Side fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Invalid side");
        }

        return switch (code.trim().toUpperCase()) {
            case "B" -> BUY;
            case "S" -> SELL;
            default -> throw new IllegalArgumentException("Invalid side");
        };
    }
}