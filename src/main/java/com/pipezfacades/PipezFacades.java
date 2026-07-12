package com.pipezfacades;

import com.pipezfacades.net.Network;
import net.minecraftforge.fml.common.Mod;

/**
 * Pipez Facades — Forge 1.20.1.
 *
 * <p>Adds a GregTech-style facade / camouflage cover to <a href="https://github.com/henkelmax/pipez">pipez</a>
 * pipes: disguise a pipe as any solid block so it can be hidden inside a wall.
 *
 * <p><b>Design.</b> This addon is deliberately decoupled from pipez — it contains <em>no compile-time
 * dependency</em> on pipez and uses <em>no mixins</em>. It recognises pipez pipes purely by their block
 * registry namespace ({@code pipez:*}), so it compiles against plain Forge and keeps working across pipez
 * updates. It also does not depend on GregTech; any full solid block (GregTech blocks included, when a
 * compatible GregTech build is installed) may be used as a facade.
 *
 * <ul>
 *     <li>{@link FacadeSavedData} — per-level storage of {@code BlockPos -> facade BlockState}.</li>
 *     <li>{@link com.pipezfacades.net.Network} + {@code S2CFacadePacket} — sync to clients for rendering.</li>
 *     <li>{@link CommonEvents} — install / remove / drop via interaction &amp; break events.</li>
 *     <li>{@code client.ClientEvents} — draws the facade block over the pipe via {@code RenderLevelStageEvent}.</li>
 * </ul>
 */
@Mod(PipezFacades.MODID)
public class PipezFacades {

    public static final String MODID = "pipezfacades";

    public PipezFacades() {
        Network.register();
    }
}
