package dev.jpi.ai;

/** Token counts and their cost breakdown. */
public record Usage(
        long input,
        long output,
        long cacheRead,
        long cacheWrite,
        double costInput,
        double costOutput,
        double costCacheRead,
        double costCacheWrite) {

    public static final Usage ZERO = new Usage(0, 0, 0, 0, 0, 0, 0, 0);

    public long totalTokens() {
        return input + output + cacheRead + cacheWrite;
    }

    public double totalCost() {
        return costInput + costOutput + costCacheRead + costCacheWrite;
    }
}
