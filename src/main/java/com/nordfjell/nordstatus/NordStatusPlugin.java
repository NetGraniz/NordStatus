package com.nordfjell.nordstatus;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NordStatusPlugin extends JavaPlugin {
    private static final long RETRY_DELAY_SECONDS = 5L;

    private final AtomicBoolean heartbeatInFlight = new AtomicBoolean();
    private ScheduledExecutorService httpExecutor;
    private volatile DnsFailoverHttpsClient httpsClient;
    private BukkitTask heartbeatTask;
    private volatile URI heartbeatUri;
    private volatile Duration requestTimeout;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        httpExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "NordStatus-HTTP");
            thread.setDaemon(true);
            return thread;
        });
        if (!reloadSettings()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("Paper health heartbeat enabled.");
    }

    @Override
    public void onDisable() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }
        heartbeatInFlight.set(false);
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
            httpExecutor = null;
        }
        httpsClient = null;
    }

    private boolean reloadSettings() {
        reloadConfig();

        String url = getConfig().getString("heartbeat-url", "").trim();
        try {
            heartbeatUri = URI.create(url);
            if (!"https".equalsIgnoreCase(heartbeatUri.getScheme()) || heartbeatUri.getHost() == null) {
                throw new IllegalArgumentException("URL must use HTTPS");
            }
        } catch (IllegalArgumentException exception) {
            getLogger().severe("Invalid heartbeat-url in config.yml: " + exception.getMessage());
            return false;
        }

        long timeoutSeconds = Math.max(3L, getConfig().getLong("request-timeout-seconds", 10L));
        long intervalSeconds = Math.max(30L, getConfig().getLong("interval-seconds", 60L));
        long initialDelaySeconds = Math.max(1L, getConfig().getLong("initial-delay-seconds", 10L));
        requestTimeout = Duration.ofSeconds(timeoutSeconds);
        httpsClient = new DnsFailoverHttpsClient();

        if (heartbeatTask != null) heartbeatTask.cancel();
        heartbeatTask = getServer().getScheduler().runTaskTimer(
                this,
                () -> sendHeartbeat(),
                initialDelaySeconds * 20L,
                intervalSeconds * 20L
        );
        return true;
    }

    private boolean sendHeartbeat() {
        if (!heartbeatInFlight.compareAndSet(false, true)) {
            return false;
        }
        sendAttempt(false);
        return true;
    }

    private void sendAttempt(boolean retry) {
        ScheduledExecutorService executor = httpExecutor;
        if (!isEnabled() || httpsClient == null || executor == null || executor.isShutdown()) {
            heartbeatInFlight.set(false);
            return;
        }

        try {
            executor.execute(() -> performAttempt(retry));
        } catch (RuntimeException exception) {
            getLogger().warning("Could not start Instatus heartbeat: " + describe(exception));
            heartbeatInFlight.set(false);
        }
    }

    private void performAttempt(boolean retry) {
        DnsFailoverHttpsClient client = httpsClient;
        if (!isEnabled() || client == null) {
            heartbeatInFlight.set(false);
            return;
        }

        try {
            int status = client.get(heartbeatUri, requestTimeout, "NordStatus/1.2").statusCode();
            if (!retry && (status == 429 || status >= 500)) {
                scheduleRetry("HTTP " + status);
                return;
            }
            if (status < 200 || status >= 300) {
                getLogger().warning("Instatus heartbeat returned HTTP " + status);
            }
            heartbeatInFlight.set(false);
        } catch (Exception exception) {
            handleFailure(exception, retry);
        }
    }

    private void handleFailure(Throwable error, boolean retry) {
        if (!retry) {
            scheduleRetry(describe(error));
            return;
        }
        getLogger().warning("Could not send Instatus heartbeat after retry: " + describe(error));
        heartbeatInFlight.set(false);
    }

    private void scheduleRetry(String firstFailure) {
        ScheduledExecutorService executor = httpExecutor;
        if (!isEnabled() || executor == null || executor.isShutdown()) {
            heartbeatInFlight.set(false);
            return;
        }

        try {
            executor.schedule(() -> performAttempt(true), RETRY_DELAY_SECONDS, TimeUnit.SECONDS);
        } catch (RuntimeException exception) {
            getLogger().warning("Could not schedule Instatus heartbeat retry after " + firstFailure
                    + ": " + describe(exception));
            heartbeatInFlight.set(false);
        }
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (reloadSettings()) {
                sender.sendMessage("NordStatus configuration reloaded.");
            } else {
                sender.sendMessage("NordStatus configuration is invalid. Check the console.");
            }
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("test")) {
            if (sendHeartbeat()) {
                sender.sendMessage("NordStatus test heartbeat requested. Check Instatus in a few seconds.");
            } else {
                sender.sendMessage("A NordStatus heartbeat is already in progress.");
            }
            return true;
        }
        return false;
    }
}
