package com.pipezfacades.client;

import com.pipezfacades.PipeUtil;
import com.pipezfacades.PipezFacades;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mod-bus client events: wraps every pipez pipe blockstate model in {@link FacadedPipeModel} so facade
 * quads are baked into the chunk mesh (GregTech's approach). The item ("inventory") variants are left
 * untouched.
 */
@Mod.EventBusSubscriber(modid = PipezFacades.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ModClientEvents {

    private ModClientEvents() {
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        Map<ResourceLocation, BakedModel> models = event.getModels();
        List<ResourceLocation> pipeModels = new ArrayList<>();
        for (ResourceLocation key : models.keySet()) {
            if (key instanceof ModelResourceLocation mrl
                    && PipeUtil.PIPEZ_NAMESPACE.equals(mrl.getNamespace())
                    && !"inventory".equals(mrl.getVariant())) {
                pipeModels.add(key);
            }
        }
        for (ResourceLocation key : pipeModels) {
            models.put(key, new FacadedPipeModel(models.get(key)));
        }
    }
}
