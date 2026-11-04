package util;

import java.lang.management.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import javax.management.ObjectName;

/** Plain-text snapshot of where this JVM's memory is: heap, non-heap, threads, direct buffers, top classes. */
public final class MemoryReport {
    private MemoryReport() {}

    public static String snapshot() {
        StringBuilder sb = new StringBuilder();
        Runtime rt = Runtime.getRuntime();
        long mb = 1048576L;
        sb.append("Memory report ").append(LocalDateTime.now()).append('\n');
        sb.append("JVM args : ").append(ManagementFactory.getRuntimeMXBean().getInputArguments()).append('\n');
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans())
            sb.append("GC       : ").append(g.getName()).append(" runs=").append(g.getCollectionCount())
              .append(" time=").append(g.getCollectionTime()).append("ms\n");
        sb.append("Heap     : used ").append((rt.totalMemory() - rt.freeMemory()) / mb).append(" MB, committed ")
          .append(rt.totalMemory() / mb).append(" MB, max ").append(rt.maxMemory() / mb).append(" MB\n");
        MemoryMXBean m = ManagementFactory.getMemoryMXBean();
        sb.append("Non-heap : used ").append(m.getNonHeapMemoryUsage().getUsed() / mb).append(" MB, committed ")
          .append(m.getNonHeapMemoryUsage().getCommitted() / mb).append(" MB\n");
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans())
            sb.append("  pool ").append(p.getName()).append(": used ").append(p.getUsage().getUsed() / mb)
              .append(" / committed ").append(p.getUsage().getCommitted() / mb).append(" MB\n");
        for (BufferPoolMXBean b : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class))
            sb.append("Buffers  : ").append(b.getName()).append(" count=").append(b.getCount())
              .append(" used=").append(b.getMemoryUsed() / mb).append(" MB\n");
        ThreadMXBean t = ManagementFactory.getThreadMXBean();
        sb.append("Threads  : ").append(t.getThreadCount()).append(" (peak ").append(t.getPeakThreadCount()).append(")\n");
        sb.append("Classes  : ").append(ManagementFactory.getClassLoadingMXBean().getLoadedClassCount()).append('\n');
        try {
            String h = (String) ManagementFactory.getPlatformMBeanServer().invoke(
                    new ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram",
                    new Object[]{new String[0]}, new String[]{String[].class.getName()});
            sb.append("\nTop live classes (after a full GC; this shows what is genuinely retained):\n");
            int n = 0;
            for (String line : h.split("\\R")) { sb.append(line).append('\n'); if (++n > 28) break; }
        } catch (Throwable e) {
            sb.append("Class histogram unavailable: ").append(e).append('\n');
        }
        return sb.toString();
    }

    public static void write(java.io.File dir, String who) {
        try {
            Files.createDirectories(dir.toPath());
            Files.write(new java.io.File(dir, "memory-" + who + ".txt").toPath(),
                    snapshot().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }
}
