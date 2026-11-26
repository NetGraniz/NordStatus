package com.nordfjell.nordstatus;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NordStatusPlugin extends JavaPlugin {
    private static final long RETRY_DELAY_SECONDS = 5L;

    private record HeartbeatSettings(URI uri, Duration timeout, long intervalSeconds,
                                     long initialDelaySeconds, DnsFailoverHttpsClient client) { }

    private final AtomicBoolean heartbeatInFlight = new AtomicBoolean();
    private final AtomicBoolean reloadInFlight = new AtomicBoolean();
    private ScheduledExecutorService httpExecutor;
    private ScheduledTask heartbeatTask;
    private volatile HeartbeatSettings settings;
    private String userAgent;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        userAgent = "NordStatus/" + getPluginMeta().getVersion();
        httpExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "NordStatus-HTTP");
            thread.setDaemon(true);
            return thread;
        });
        try {
            applySettings(loadSettings());
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException exception) {
            getLogger().severe("Invalid NordStatus config.yml: " + describe(exception));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("Server health heartbeat enabled (global scheduler; not a per-region health probe).");
    }

    @Override
    public void onDisable() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }
        heartbeatInFlight.set(false);
        reloadInFlight.set(false);
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
            httpExecutor = null;
        }
        settings = null;
    }

    private HeartbeatSettings loadSettings() throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(new File(getDataFolder(), "config.yml"));
        String url = yaml.getString("heartbeat-url");
        if (url == null) throw new IllegalArgumentException("heartbeat-url must be a string");
        URI uri = URI.create(url.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("heartbeat-url must use HTTPS");
        }
        long timeoutSeconds = longSetting(yaml, "request-timeout-seconds", 10L, 3L);
        long intervalSeconds = longSetting(yaml, "interval-seconds", 60L, 30L);
        long initialDelaySeconds = longSetting(yaml, "initial-delay-seconds", 10L, 1L);
        return new HeartbeatSettings(uri, Duration.ofSeconds(timeoutSeconds), intervalSeconds,
                initialDelaySeconds, new DnsFailoverHttpsClient());
    }

    private static long longSetting(YamlConfiguration yaml, String path, long fallback, long minimum) {
        if (!yaml.contains(path)) return fallback;
        if (!yaml.isLong(path) && !yaml.isInt(path)) {
            throw new IllegalArgumentException(path + " must be an integer");
        }
        long value = yaml.getLong(path);
        if (value < minimum) throw new IllegalArgumentException(path + " must be at least " + minimum);
        return value;
    }

    private void applySettings(HeartbeatSettings next) {
        ScheduledTask nextTask = getServer().getGlobalRegionScheduler().runAtFixedRate(
                this,
                ignored -> sendHeartbeat(),
                next.initialDelaySeconds() * 20L,
                next.intervalSeconds() * 20L
        );
        ScheduledTask previousTask = heartbeatTask;
        settings = next;
        heartbeatTask = nextTask;
        if (previousTask != null) previousTask.cancel();
    }

    private boolean sendHeartbeat() {
        if (!heartbeatInFlight.compareAndSet(false, true)) {
            return false;
        }
        HeartbeatSettings active = settings;
        if (active == null) {
            heartbeatInFlight.set(false);
            return false;
        }
        sendAttempt(false, active);
        return true;
    }

    private void sendAttempt(boolean retry, HeartbeatSettings active) {
        ScheduledExecutorService executor = httpExecutor;
        if (!isEnabled() || settings == null || executor == null || executor.isShutdown()) {
            heartbeatInFlight.set(false);
            return;
        }

        try {
            executor.execute(() -> performAttempt(retry, active));
        } catch (RuntimeException exception) {
            getLogger().warning("Could not start Instatus heartbeat: " + describe(exception));
            heartbeatInFlight.set(false);
        }
    }

    private void performAttempt(boolean retry, HeartbeatSettings active) {
        if (!isEnabled() || settings == null) {
            heartbeatInFlight.set(false);
            return;
        }

        try {
            int status = active.client().get(active.uri(), active.timeout(), userAgent).statusCode();
            if (!retry && (status == 429 || status >= 500)) {
                scheduleRetry("HTTP " + status, active);
                return;
            }
            if (status < 200 || status >= 300) {
                getLogger().warning("Instatus heartbeat returned HTTP " + status);
            }
            heartbeatInFlight.set(false);
        } catch (Exception exception) {
            handleFailure(exception, retry, active);
        }
    }

    private void handleFailure(Throwable error, boolean retry, HeartbeatSettings active) {
        if (!retry) {
            scheduleRetry(describe(error), active);
            return;
        }
        getLogger().warning("Could not send Instatus heartbeat after retry: " + describe(error));
        heartbeatInFlight.set(false);
    }

    private void scheduleRetry(String firstFailure, HeartbeatSettings active) {
        ScheduledExecutorService executor = httpExecutor;
        if (!isEnabled() || executor == null || executor.isShutdown()) {
            heartbeatInFlight.set(false);
            return;
        }

        try {
            executor.schedule(() -> performAttempt(true, active), RETRY_DELAY_SECONDS, TimeUnit.SECONDS);
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
            if (!reloadInFlight.compareAndSet(false, true)) {
                sender.sendMessage("NordStatus reload is already pending.");
                return true;
            }
            getServer().getAsyncScheduler().runNow(this, ignored -> {
                HeartbeatSettings next = null;
                Exception problem = null;
                try { next = loadSettings(); }
                catch (IOException | InvalidConfigurationException | IllegalArgumentException exception) { problem = exception; }
                HeartbeatSettings candidate = next;
                Exception error = problem;
                getServer().getGlobalRegionScheduler().execute(this, () -> {
                    reloadInFlight.set(false);
                    if (error != null) {
                        getLogger().warning("NordStatus reload rejected; previous settings retained: " + describe(error));
                        reply(sender, "NordStatus configuration is invalid. Check the console.");
                        return;
                    }
                    try {
                        applySettings(candidate);
                        reply(sender, "NordStatus configuration reloaded.");
                    } catch (RuntimeException exception) {
                        getLogger().warning("NordStatus reload could not replace the schedule; previous settings retained: " + describe(exception));
                        reply(sender, "NordStatus configuration could not be applied. Check the console.");
                    }
                });
            });
            sender.sendMessage("NordStatus reload queued.");
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

    private void reply(CommandSender sender, String message) {
        if (sender instanceof Player player) {
            player.getScheduler().execute(this, () -> {
                if (player.isOnline()) player.sendMessage(message);
            }, null, 1L);
        } else {
            sender.sendMessage(message);
        }
    }
}
