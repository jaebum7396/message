package message.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.NumberFormat;

public class MemoryUsageMonitor {

    private static final Logger logger = LoggerFactory.getLogger(MemoryUsageMonitor.class);
    private static final long MEGABYTE = 1024L * 1024L;

    public static void logMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();

        NumberFormat format = NumberFormat.getInstance();

        long maxMemory = runtime.maxMemory();
        long allocatedMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();

        long totalFreeMemory = freeMemory + (maxMemory - allocatedMemory);
        long usedMemory = allocatedMemory - freeMemory;

        StringBuilder sb = new StringBuilder();
        sb.append("\n┌───────────────────┬────────────────┬────────────┐\n");
        sb.append("│ Memory Type       │ Amount (MB)    │ Percentage │\n");
        sb.append("├───────────────────┼────────────────┼────────────┤\n");
        appendRow(sb, "Max Memory", maxMemory, maxMemory);
        appendRow(sb, "Allocated Memory", allocatedMemory, maxMemory);
        appendRow(sb, "Used Memory", usedMemory, maxMemory);
        appendRow(sb, "Free Memory", freeMemory, maxMemory);
        appendRow(sb, "Total Free Memory", totalFreeMemory, maxMemory);
        sb.append("└───────────────────┴────────────────┴────────────┘");

        logger.info(sb.toString());
    }

    private static void appendRow(StringBuilder sb, String type, long memory, long total) {
        sb.append(String.format("│ %-17s │ %14s │ %8.2f%% │%n",
                type,
                NumberFormat.getInstance().format(memory / MEGABYTE),
                (double) memory / total * 100));
    }

    public static void logMemoryUsageWithGC() {
        logger.info("Memory usage before GC:");
        logMemoryUsage();

        logger.info("Running garbage collector...");
        System.gc();

        logger.info("Memory usage after GC:");
        logMemoryUsage();
    }
}