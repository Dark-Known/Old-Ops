package util;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes the lean JVM settings independent of how the window was started. Shortcuts, copied shortcuts, a
 * double-clicked jar or a custom launcher may all start the JVM with no flags at all, which means the default
 * collector and a heap ceiling of a quarter of the machine's RAM. If the process came up without any heap
 * setting of its own, this starts one copy of itself with the lean flags and lets the first one exit.
 * A launcher that already passes -Xmx or a collector is respected and never relaunched.
 */
public final class LeanLaunch {
    private static final String MARK = "opstool.lean";
    private static final String[] FLAGS = {
        "-XX:+UseSerialGC", "-Xms32m", "-Xmx512m", "-Xmn48m",
        "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=30",
        "-XX:ReservedCodeCacheSize=48m", "-XX:TieredStopAtLevel=1",
        "-XX:MaxMetaspaceSize=96m", "-Xss512k", "-Dsun.java2d.d3d=false", "-D" + MARK + "=1"
    };

    private LeanLaunch() {}

    /** Returns true if a lean copy was started and this process should exit. */
    public static boolean relaunchIfNeeded(String[] args) {
        try {
            if (System.getProperty(MARK) != null) return false;
            for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (a.startsWith("-Xmx") || a.startsWith("-XX:+Use") && a.endsWith("GC")
                        || a.startsWith("-XX:MaxHeapSize")) return false;       // the launcher chose its own
            }
            String exe = ProcessHandle.current().info().command().orElse(null);
            if (exe == null) return false;
            List<String> cmd = new ArrayList<>();
            cmd.add(exe);
            for (String f : FLAGS) cmd.add(f);
            cmd.add("-cp");
            cmd.add(System.getProperty("java.class.path"));
            cmd.add("App");
            for (String a : args) cmd.add(a);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(System.getProperty("user.dir")));
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.start();
            return true;
        } catch (Throwable t) {
            return false;       // never block start-up: just run with what we have
        }
    }
}
