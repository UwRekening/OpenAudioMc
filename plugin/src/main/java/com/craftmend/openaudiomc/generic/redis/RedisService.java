package com.craftmend.openaudiomc.generic.redis;

import com.craftmend.openaudiomc.OpenAudioMc;
import com.craftmend.openaudiomc.generic.interfaces.OAConfiguration;
import com.craftmend.openaudiomc.generic.loggin.OpenAudioLogger;
import com.craftmend.openaudiomc.generic.redis.packets.ExecuteBulkCommandsPacket;
import com.craftmend.openaudiomc.generic.redis.packets.ExecuteCommandPacket;
import com.craftmend.openaudiomc.generic.redis.packets.adapter.RedisTypeAdapter;
import com.craftmend.openaudiomc.generic.redis.packets.channels.ChannelKey;
import com.craftmend.openaudiomc.generic.redis.packets.interfaces.OARedisPacket;
import com.craftmend.openaudiomc.generic.redis.packets.models.WaitingPacket;
import com.craftmend.openaudiomc.generic.storage.enums.StorageKey;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

public class RedisService {

    private RedisClient redisPub;
    private RedisClient redisSub;
    private RedisURI uri;
    private StatefulRedisPubSubConnection<String, String> redisSubConnection;
    private StatefulRedisPubSubConnection<String, String> redisPubConnection;
    @Getter private RedisPubSubAsyncCommands<String, String> asyncSub;
    private RedisPubSubAsyncCommands<String, String> asyncPub;
    @Getter private static final Gson GSON = new GsonBuilder().registerTypeAdapter(OARedisPacket.class, new RedisTypeAdapter()).create();
    private boolean enabled = false;
    @Getter private UUID serviceId = UUID.randomUUID();
    private ConcurrentLinkedQueue<WaitingPacket> packetQue = new ConcurrentLinkedQueue<>();

    private final Runnable messageQueHandler = () -> {
        // combine all the commands
        if (packetQue.isEmpty()) return;
        List<String> commands = new ArrayList<>();
        List<WaitingPacket> skipList = new ArrayList<>();

        // try to generify the set
        for (WaitingPacket packet : packetQue) {
            if (packet.getPacket() instanceof ExecuteCommandPacket) {
                commands.add(((ExecuteCommandPacket) packet.getPacket()).getCommand());
                skipList.add(packet);
            }
        }

        // send all other packets
        for (WaitingPacket packet : packetQue) {
            if (!skipList.contains(packet)) asyncPub.publish(packet.getChannel().getRedisChannelName(), packet.getPacket().serialize());
        }

        // if there are bulk packets waiting, send them
        if (commands.isEmpty()) return;
        asyncPub.publish(ChannelKey.TRIGGER_BULK_COMMANDS.getRedisChannelName(), new ExecuteBulkCommandsPacket(commands).serialize());

    };

    public RedisService(OAConfiguration OAConfiguration) {
        if (!OAConfiguration.getBoolean(StorageKey.REDIS_ENABLED)) return;
        enabled = true;

        OpenAudioLogger.toConsole("Enabling redis service..");

        if (OAConfiguration.getString(StorageKey.REDIS_PASSWORD).equals("none")) {
            uri = RedisURI.builder()
                    .withHost(OAConfiguration.getString(StorageKey.REDIS_HOST))
                    .withPort(OAConfiguration.getInt(StorageKey.REDIS_PORT))
                    .withSsl(OAConfiguration.getBoolean(StorageKey.REDIS_USE_SSL))
                    .build();
        } else {
            uri = RedisURI.builder()
                    .withPassword(OAConfiguration.getString(StorageKey.REDIS_PASSWORD))
                    .withHost(OAConfiguration.getString(StorageKey.REDIS_HOST))
                    .withPort(OAConfiguration.getInt(StorageKey.REDIS_PORT))
                    .withSsl(OAConfiguration.getBoolean(StorageKey.REDIS_USE_SSL))
                    .build();
        }

        // set up listener
        redisSub = RedisClient.create(uri);
        redisSub.setOptions(ClientOptions.builder().autoReconnect(true).build());
        redisSubConnection = redisSub.connectPubSub();
        redisSubConnection.addListener(new RedisChannelListener());
        asyncSub = redisSubConnection.async();

        for (ChannelKey value : ChannelKey.values()) {
            if (value.getTargetPlatform().equals(OpenAudioMc.getInstance().getPlatform())) asyncSub.subscribe(value.getRedisChannelName());
        }

        // set up publisher
        redisPub = RedisClient.create(uri);
        redisPub.setOptions(ClientOptions.builder().autoReconnect(true).build());
        redisPubConnection = redisPub.connectPubSub();
        asyncPub = redisPubConnection.async();

        // queue handler
        OpenAudioMc.getInstance().getTaskProvider().schduleAsyncRepeatingTask(messageQueHandler, 1, 1);
        OpenAudioLogger.toConsole("Enabled redis service!");
    }

    public void sendMessage(ChannelKey key, OARedisPacket packet) {
        if (!enabled) return;
        packet.setSenderUUID(serviceId);
        packetQue.add(new WaitingPacket(key, packet));
    }

    public void shutdown() {
        if (!enabled) return;
        redisSubConnection.close();
        redisSub.shutdown();
        redisPubConnection.close();
        redisPub.shutdown();
    }

}