/*
 * VelocityAutoReconnect
 * Copyright (C) 2021 Flori4nK <contact@flori4nk.de>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package de.flori4nk.velocityautoreconnect;

import com.google.inject.Inject;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import de.flori4nk.velocityautoreconnect.listeners.ConnectionListener;
import de.flori4nk.velocityautoreconnect.listeners.KickListener;
import de.flori4nk.velocityautoreconnect.misc.Utility;
import de.flori4nk.velocityautoreconnect.storage.ConfigurationManager;
import de.flori4nk.velocityautoreconnect.storage.PlayerManager;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@Plugin(id = "velocityautoreconnect", name = "VelocityAutoReconnect", version = "1.3.0", authors = {"Flori4nK", "siebsie23"})
public class VelocityAutoReconnect {

    private static ProxyServer proxyServer;
    private static Logger logger;
    private static RegisteredServer limboServer;
    private static RegisteredServer directConnectServer;

    private static ConfigurationManager configurationManager;
    private static PlayerManager playerManager;

    @Inject
    public VelocityAutoReconnect(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        VelocityAutoReconnect.proxyServer = server;
        VelocityAutoReconnect.logger = logger;
        VelocityAutoReconnect.configurationManager = new ConfigurationManager(new File(dataDirectory.toFile(), "velocityautoreconnect.conf"));
        VelocityAutoReconnect.playerManager = new PlayerManager();
    }

    public static RegisteredServer getLimboServer() {
        return limboServer;
    }

    public static RegisteredServer getDirectConnectServer() {
        return directConnectServer;
    }

    public static ProxyServer getProxyServer() {
        return proxyServer;
    }

    public static Logger getLogger() {
        return logger;
    }

    public static ConfigurationManager getConfigurationManager() {
        return configurationManager;
    }

    public static PlayerManager getPlayerManager() {
        return playerManager;
    }

    @Subscribe(order = PostOrder.NORMAL)
    public void onInitialize(ProxyInitializeEvent event) {
        // do not remove.
        logger.info("VelocityAutoReconnect Copyright (c) 2021 Flori4nK <contact@flori4nk.de>");
        logger.info("VelocityAutoReconnect is licensed under the terms of the GNU General Public License, version 3.");

        EventManager eventManager = proxyServer.getEventManager();

        limboServer = Utility.getServerFromProperty("limbo-name");
        directConnectServer = Utility.getServerFromProperty("directconnect-server");

        // If either server is null, "self-destruct"
        if (limboServer == null || directConnectServer == null) {
            eventManager.unregisterListeners(this);
            return;
        }

        eventManager.register(this, new ConnectionListener());
        eventManager.register(this, new KickListener());

        // Schedule the reconnector task
        proxyServer.getScheduler().buildTask(this, () -> {
            Collection<Player> connectedPlayers = limboServer.getPlayersConnected();
            // Prevent NullPointerException when Limbo is empty
            if (connectedPlayers.isEmpty()) return;

            Player nextPlayer = connectedPlayers.iterator().next();

            // Check if the player has the bypass permission
            if (VelocityAutoReconnect.getConfigurationManager().getBooleanProperty("bypasscheck")
                    && nextPlayer.hasPermission("velocityautoreconnect.bypass")) {
                return;
            }

            RegisteredServer previousServer = playerManager.getPreviousServer(nextPlayer);

            // If enabled, check if a server responds to pings before connecting
            try {
                if (configurationManager.getBooleanProperty("pingcheck")) {
                    try {
                        previousServer.ping().join();
                    } catch (CompletionException completionException) {
                        // Server failed to respond to ping request, return to prevent spam
                        return;
                    }
                }
                Utility.logInformational(String.format("Connecting %s to %s.", nextPlayer.getUsername(), previousServer.getServerInfo().getName()));
                nextPlayer.createConnectionRequest(previousServer).connect();
            } catch (CompletionException exception) {
                // Prevent console from being spammed when a server is offline and ping-check is disabled
            }
        })
            .repeat(configurationManager.getIntegerProperty("task-interval-ms"), TimeUnit.MILLISECONDS)
            .schedule();
    }


}
