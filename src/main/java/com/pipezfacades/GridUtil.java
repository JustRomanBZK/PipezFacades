package com.pipezfacades;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Faithful port of GregTech's cover grid math ({@code GTUtil.determineWrenchingSide}): the hit face is
 * divided at 0.25/0.75 into a 3×3 grid. Centre → the face itself; edge cells → the respective
 * perpendicular side; corner cells → the <b>opposite</b> face (that's how GT lets you reach the far side).
 */
public final class GridUtil {

    private GridUtil() {
    }

    public static Direction gridSide(BlockHitResult hit) {
        Direction facing = hit.getDirection();
        float x = (float) (hit.getLocation().x - hit.getBlockPos().getX());
        float y = (float) (hit.getLocation().y - hit.getBlockPos().getY());
        float z = (float) (hit.getLocation().z - hit.getBlockPos().getZ());
        Direction opposite = facing.getOpposite();

        switch (facing) {
            case DOWN, UP -> {
                if (x < 0.25f) {
                    if (z < 0.25f || z > 0.75f) return opposite;
                    return Direction.WEST;
                }
                if (x > 0.75f) {
                    if (z < 0.25f || z > 0.75f) return opposite;
                    return Direction.EAST;
                }
                if (z < 0.25f) return Direction.NORTH;
                if (z > 0.75f) return Direction.SOUTH;
                return facing;
            }
            case NORTH, SOUTH -> {
                if (x < 0.25f) {
                    if (y < 0.25f || y > 0.75f) return opposite;
                    return Direction.WEST;
                }
                if (x > 0.75f) {
                    if (y < 0.25f || y > 0.75f) return opposite;
                    return Direction.EAST;
                }
                if (y < 0.25f) return Direction.DOWN;
                if (y > 0.75f) return Direction.UP;
                return facing;
            }
            default -> { // WEST, EAST
                if (z < 0.25f) {
                    if (y < 0.25f || y > 0.75f) return opposite;
                    return Direction.NORTH;
                }
                if (z > 0.75f) {
                    if (y < 0.25f || y > 0.75f) return opposite;
                    return Direction.SOUTH;
                }
                if (y < 0.25f) return Direction.DOWN;
                if (y > 0.75f) return Direction.UP;
                return facing;
            }
        }
    }
}
