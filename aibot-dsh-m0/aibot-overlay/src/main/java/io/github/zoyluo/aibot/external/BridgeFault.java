package io.github.zoyluo.aibot.external;

public final class BridgeFault extends RuntimeException {
    public final int status;
    public final String code;
    public BridgeFault(int status, String code) { super(code); this.status=status; this.code=code; }
}
