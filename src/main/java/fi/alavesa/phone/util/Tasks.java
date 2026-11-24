package fi.alavesa.phone.util;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Thread hand-over helpers. Bukkit API is only ever called on the main thread. */
public final class Tasks {

    private Tasks() {}

    /**
     * Runs {@code onMain} on the main thread with the result of {@code future}. A failed future is logged and dropped;
     * nothing runs after the plugin was disabled.
     */
    public static <T> void onMain(Plugin plugin, CompletableFuture<T> future, Consumer<T> onMain) {
        future.whenComplete((value, error) -> {
            if (!plugin.isEnabled()) return;
            if (error != null) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "A phone task failed", error);
                return;
            }
            if (Bukkit.isPrimaryThread()) onMain.accept(value);
            else Bukkit.getScheduler().runTask(plugin, () -> onMain.accept(value));
        });
    }
}
