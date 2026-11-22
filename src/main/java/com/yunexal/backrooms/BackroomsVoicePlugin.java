package com.yunexal.backrooms;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EntitySoundPacketEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.StaticSoundPacketEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.packets.EntitySoundPacket;
import de.maxhenkel.voicechat.api.packets.StaticSoundPacket;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simple Voice Chat integration implementing canonical Level 0 radio interference and wall occlusion:
 * - Walls heavily attenuate proximity voice with realistic gypsum muffling + subtle static noise
 * - >= 3 solid corridor walls or >28 blocks cuts proximity comms entirely
 * - Group ("walkie-talkie") chat gains subtle interference over distance/walls
 */
public final class BackroomsVoicePlugin implements VoicechatPlugin {
    private final YunexalBackroomsPlugin plugin;
    private final Map<PairKey, OcclusionState> occlusionCache = new ConcurrentHashMap<>();
    private final Map<PairKey, ChannelDSP> dspChannels = new ConcurrentHashMap<>();
    private final Map<PairKey, Long> lastStaticCueAtMs = new ConcurrentHashMap<>();

    private volatile VoicechatApi voiceApi;
    private BukkitTask occlusionTask;

    private record PairKey(UUID sender, UUID receiver) {}

    private record OcclusionState(int walls, double distSq) {}

    private static final class ChannelDSP {
        final OpusDecoder decoder;
        final OpusEncoder encoder;
        float lpState = 0.0f;

        ChannelDSP(VoicechatApi api) {
            this.decoder = api.createDecoder();
            this.encoder = api.createEncoder();
        }

        byte[] filterWallMuffle(byte[] opusData, float lowpassAlpha, float gain, float staticAmp) {
            short[] pcm = decoder.decode(opusData);
            if (pcm == null || pcm.length == 0) {
                return opusData;
            }
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            float lp = this.lpState;
            for (int i = 0; i < pcm.length; i++) {
                float x = pcm[i];
                lp = lp + lowpassAlpha * (x - lp);
                float noise = staticAmp > 0f ? ((rng.nextFloat() * 2f - 1f) * staticAmp) : 0f;
                float out = lp * gain + noise;
                if (out > 32767f) out = 32767f;
                else if (out < -32767f) out = -32767f;
                pcm[i] = (short) out;
            }
            this.lpState = lp;
            return encoder.encode(pcm);
        }

        void close() {
            if (!decoder.isClosed()) decoder.close();
            if (!encoder.isClosed()) encoder.close();
        }
    }

    public BackroomsVoicePlugin(YunexalBackroomsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getPluginId() {
        return "yunexal_backrooms";
    }

    @Override
    public void initialize(VoicechatApi api) {
        this.voiceApi = api;
        if (occlusionTask != null) {
            occlusionTask.cancel();
        }
        occlusionTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateOcclusionCache, 4L, 4L);
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(EntitySoundPacketEvent.class, this::onEntitySound);
        registration.registerEvent(StaticSoundPacketEvent.class, this::onStaticSound);
    }

    public void shutdown() {
        if (occlusionTask != null) {
            occlusionTask.cancel();
            occlusionTask = null;
        }
        occlusionCache.clear();
        lastStaticCueAtMs.clear();
        for (ChannelDSP dsp : dspChannels.values()) {
            dsp.close();
        }
        dspChannels.clear();
    }

    private void updateOcclusionCache() {
        World level0 = plugin.getLevel0World();
        if (level0 == null) {
            return;
        }
        List<Player> active = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().equals(level0) && plugin.getLimboManager().isVerified(p) && !plugin.getLimboManager().isInLimbo(p)) {
                active.add(p);
            }
        }

        occlusionCache.keySet().removeIf(k -> Bukkit.getPlayer(k.sender()) == null || Bukkit.getPlayer(k.receiver()) == null);
        dspChannels.entrySet().removeIf(e -> {
            if (Bukkit.getPlayer(e.getKey().sender()) == null || Bukkit.getPlayer(e.getKey().receiver()) == null) {
                e.getValue().close();
                return true;
            }
            return false;
        });

        int eyeY = plugin.getBackroomsConfig().getFloorY() + 2;
        for (int i = 0; i < active.size(); i++) {
            Player a = active.get(i);
            Location la = a.getLocation();
            for (int j = i + 1; j < active.size(); j++) {
                Player b = active.get(j);
                Location lb = b.getLocation();
                double distSq = la.distanceSquared(lb);
                int walls = (distSq > 34.0 * 34.0) ? 99 : countWallSegments(level0, la.getX(), la.getZ(), lb.getX(), lb.getZ(), eyeY);
                OcclusionState st = new OcclusionState(walls, distSq);
                occlusionCache.put(new PairKey(a.getUniqueId(), b.getUniqueId()), st);
                occlusionCache.put(new PairKey(b.getUniqueId(), a.getUniqueId()), st);
            }
        }
    }

    private int countWallSegments(World w, double x1, double z1, double x2, double z2, int eyeY) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double dist = Math.hypot(dx, dz);
        if (dist < 0.8) {
            return 0;
        }
        int steps = (int) Math.ceil(dist * 2.0);
        int walls = 0;
        boolean inSolid = false;
        for (int s = 1; s < steps; s++) {
            double t = (double) s / steps;
            int bx = (int) Math.floor(x1 + dx * t);
            int bz = (int) Math.floor(z1 + dz * t);
            boolean solid = w.getBlockAt(bx, eyeY, bz).getType().isSolid();
            if (solid && !inSolid) {
                walls++;
            }
            inSolid = solid;
        }
        return walls;
    }

    private void onEntitySound(EntitySoundPacketEvent event) {
        VoicechatConnection senderConn = event.getSenderConnection();
        VoicechatConnection recvConn = event.getReceiverConnection();
        if (senderConn == null || recvConn == null) {
            return;
        }
        UUID senderUid = senderConn.getPlayer().getUuid();
        UUID recvUid = recvConn.getPlayer().getUuid();
        PairKey key = new PairKey(senderUid, recvUid);

        OcclusionState st = occlusionCache.get(key);
        if (st == null) {
            event.cancel();
            return;
        }

        // Completely cut voice beyond 3 walls or 28 blocks
        if (st.walls() >= 3 || st.distSq() > 28.0 * 28.0) {
            event.cancel();
            if (st.distSq() <= 27.0 * 27.0) {
                triggerSoftStaticCue(recvUid, key);
            }
            return;
        }

        EntitySoundPacket pkt = event.getPacket();
        byte[] opus = pkt.getOpusEncodedData();
        if (opus == null || opus.length == 0 || (st.walls() == 0 && st.distSq() <= 14.0 * 14.0)) {
            return;
        }

        VoicechatServerApi serverApi = event.getVoicechat();
        ChannelDSP dsp = dspChannels.computeIfAbsent(key, ignored -> new ChannelDSP(voiceApi));
        byte[] filtered;
        synchronized (dsp) {
            if (st.walls() >= 2) {
                filtered = dsp.filterWallMuffle(opus, 0.14f, 0.25f, 190f);
            } else if (st.walls() == 1) {
                filtered = dsp.filterWallMuffle(opus, 0.28f, 0.54f, 110f);
            } else {
                filtered = dsp.filterWallMuffle(opus, 0.65f, 0.85f, 55f);
            }
        }

        float effDist = st.walls() >= 2 ? 15f : (st.walls() == 1 ? 21f : pkt.getDistance());
        EntitySoundPacket modified = pkt.entitySoundPacketBuilder()
                .opusEncodedData(filtered)
                .distance(effDist)
                .build();
        event.cancel();
        serverApi.sendEntitySoundPacketTo(recvConn, modified);
    }

    private void onStaticSound(StaticSoundPacketEvent event) {
        if (!StaticSoundPacketEvent.SOURCE_GROUP.equals(event.getSource())) {
            return;
        }
        VoicechatConnection senderConn = event.getSenderConnection();
        VoicechatConnection recvConn = event.getReceiverConnection();
        if (senderConn == null || recvConn == null) {
            return;
        }
        PairKey key = new PairKey(senderConn.getPlayer().getUuid(), recvConn.getPlayer().getUuid());
        OcclusionState st = occlusionCache.get(key);
        if (st == null || st.walls() < 2) {
            return;
        }

        StaticSoundPacket pkt = event.getPacket();
        byte[] opus = pkt.getOpusEncodedData();
        if (opus == null || opus.length == 0) {
            return;
        }
        ChannelDSP dsp = dspChannels.computeIfAbsent(key, ignored -> new ChannelDSP(voiceApi));
        byte[] filtered;
        synchronized (dsp) {
            float gain = st.walls() >= 4 ? 0.22f : 0.48f;
            float staticAmp = st.walls() >= 4 ? 260f : 130f;
            filtered = dsp.filterWallMuffle(opus, 0.26f, gain, staticAmp);
        }
        StaticSoundPacket modified = pkt.staticSoundPacketBuilder()
                .opusEncodedData(filtered)
                .build();
        event.cancel();
        event.getVoicechat().sendStaticSoundPacketTo(recvConn, modified);
    }

    private void triggerSoftStaticCue(UUID receiverUid, PairKey key) {
        long now = System.currentTimeMillis();
        long prev = lastStaticCueAtMs.getOrDefault(key, 0L);
        if (now - prev < 4500L) {
            return;
        }
        lastStaticCueAtMs.put(key, now);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(receiverUid);
            if (p != null && p.isOnline()) {
                p.playSound(p, "minecraft:backrooms.fx.radio_static", SoundCategory.PLAYERS, 0.14f, 1.0f);
            }
        });
    }
}
