package trade.common;

import java.text.NumberFormat;
public class MemoryUsageMonitor {

    private static final long MEGABYTE = 1024L * 1024L;

    public static void printMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();

        NumberFormat format = NumberFormat.getInstance();

        long maxMemory = runtime.maxMemory();
        long allocatedMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();

        long totalFreeMemory = freeMemory + (maxMemory - allocatedMemory);
        long usedMemory = allocatedMemory - freeMemory;

        System.out.println("┌───────────────────┬────────────────┬────────────┐");
        System.out.println("│ Memory Type       │ Amount (MB)    │ Percentage │");
        System.out.println("├───────────────────┼────────────────┼────────────┤");
        printRow("Max Memory", maxMemory, maxMemory);
        printRow("Allocated Memory", allocatedMemory, maxMemory);
        printRow("Used Memory", usedMemory, maxMemory);
        printRow("Free Memory", freeMemory, maxMemory);
        printRow("Total Free Memory", totalFreeMemory, maxMemory);
        System.out.println("└───────────────────┴────────────────┴────────────┘");
    }

    private static void printRow(String type, long memory, long total) {
        System.out.printf("│ %-17s │ %14s │ %8.2f%% │%n",
                type,
                NumberFormat.getInstance().format(memory / MEGABYTE),
                (double) memory / total * 100);
    }

    public static void printMemoryUsageWithGC() {
        System.out.println("Memory usage before GC:");
        printMemoryUsage();

        System.out.println("\nRunning garbage collector...");
        System.gc();

        System.out.println("\nMemory usage after GC:");
        printMemoryUsage();
    }

    public static void main(String[] args) {
        printMemoryUsageWithGC();
    }
}