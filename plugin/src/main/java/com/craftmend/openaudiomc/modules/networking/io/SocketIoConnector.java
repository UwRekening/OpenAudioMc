package com.craftmend.openaudiomc.modules.networking.io;

import com.craftmend.openaudiomc.OpenAudioMc;
import com.craftmend.openaudiomc.modules.networking.abstracts.AbstractPacket;
import com.craftmend.openaudiomc.modules.networking.payloads.AcknowledgeClientPayload;
import com.craftmend.openaudiomc.modules.players.objects.Client;
import io.socket.client.Ack;
import io.socket.client.IO;
import io.socket.client.Socket;
import lombok.Getter;
import okhttp3.OkHttpClient;
import org.bukkit.Bukkit;

import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

public class SocketIoConnector {

    private Socket socket;
    @Getter private Boolean isConnected = false;
    private SSLHelper sslHelper;

    public SocketIoConnector() throws KeyManagementException, NoSuchAlgorithmException, URISyntaxException {
        sslHelper = new SSLHelper();
        setupConnection();
    }

    public void setupConnection() throws URISyntaxException {
        if (isConnected) return;
        System.out.println(OpenAudioMc.getLOG_PREFIX() + "Setting up Socket.IO connection.");

        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .hostnameVerifier(sslHelper.getHostnameVerifier())
                .sslSocketFactory(sslHelper.getSslSocketFactory(), sslHelper.getTrustManager())
                .build();

        IO.Options opts = new IO.Options();
        opts.callFactory = okHttpClient;
        opts.webSocketFactory = okHttpClient;
        opts.query = "type=server&" +
                "secret=" + OpenAudioMc.getInstance().getAuthenticationModule().getServerKeySet().getPrivateKey().getValue() + "&" +
                "public=" + OpenAudioMc.getInstance().getAuthenticationModule().getServerKeySet().getPublicKey().getValue();

        socket = IO.socket(OpenAudioMc.getInstance().getConfigurationModule().getServer(), opts);

        registerEvents();

        socket.connect();
    }

    private void registerEvents() {
        socket.on(Socket.EVENT_CONNECT, args -> {
            //connected
            isConnected = true;
            System.out.println(OpenAudioMc.getLOG_PREFIX() + "Socket: Opened.");
        });

        socket.on(Socket.EVENT_DISCONNECT, args -> {
            //disconnected
            isConnected = false;
            System.out.println(OpenAudioMc.getLOG_PREFIX() + "Socket: closed.");
        });

        socket.on("acknowledgeClient", args -> {
            AcknowledgeClientPayload payload = (AcknowledgeClientPayload) OpenAudioMc.getGson().fromJson(args[0].toString(), AbstractPacket.class).getData();
            Client client = OpenAudioMc.getInstance().getPlayerModule().getClient(payload.getUuid());

            Ack callback = (Ack) args[1];

            if (client == null) {
                callback.call(false);
            } else if (client.getPin().equals(payload.getToken())) {
                client.onConnect();
                callback.call(true);
            } else {
                callback.call(false);
            }
        });

        socket.on("data", args -> {
            AbstractPacket abstractPacket = OpenAudioMc.getGson().fromJson(args[0].toString(), AbstractPacket.class);
            OpenAudioMc.getInstance().getNetworkingModule().triggerPacket(abstractPacket);
        });
    }

    public void send(Client client, AbstractPacket packet) {
        if (isConnected && client.getIsConnected()) {
            //check if the player is real, fake players aren't cool
            if (Bukkit.getPlayer(client.getPlayer().getUniqueId()) == null) return;
            packet.setClient(client.getPlayer().getUniqueId());
            socket.emit("data", OpenAudioMc.getGson().toJson(packet));
        }
    }
}