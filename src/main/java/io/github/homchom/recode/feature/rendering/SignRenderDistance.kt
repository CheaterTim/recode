package io.github.homchom.recode.feature.rendering

import io.github.homchom.recode.feature.feature
import io.github.homchom.recode.mc
import io.github.homchom.recode.mod.config.Config
import io.github.homchom.recode.render.RenderBlockEntityEvent
import net.minecraft.world.level.block.entity.SignBlockEntity

val FSignRenderDistance = feature("Sign Render Distance") {
    onLoad {
        RenderBlockEntityEvent.hook { blockEntity, render ->
            if (blockEntity is SignBlockEntity) {
                val cameraPos = mc.cameraEntity!!.blockPosition()
                val distance = Config.getInteger("signRenderDistance").toDouble()
                if (!blockEntity.getBlockPos().closerThan(cameraPos, distance)) {
                    return@hook false
                }
            }
            render
        }
    }
}