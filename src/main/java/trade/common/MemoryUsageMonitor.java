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

        System.out.println("==== Memory Usage ====");
        System.out.println("Free memory: " + format.format(freeMemory / MEGABYTE) + " MB");
        System.out.println("Allocated memory: " + format.format(allocatedMemory / MEGABYTE) + " MB");
        System.out.println("Max memory: " + format.format(maxMemory / MEGABYTE) + " MB");
        System.out.println("Total free memory: " + format.format((freeMemory + (maxMemory - allocatedMemory)) / MEGABYTE) + " MB");
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