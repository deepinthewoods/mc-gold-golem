package ninja.trek.mc.goldgolem.world.entity;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Encapsulates arm animation state and logic for GoldGolemEntity.
 * Manages animation ticks, target positions, arm angles, and eye positions.
 */
public class ArmAnimationState {

    // Animation constants
    public static final int ANIMATION_DURATION_TICKS = 12;
    public static final float ARM_SWING_MIN_ANGLE = 15.0f;
    public static final float ARM_SWING_MAX_ANGLE = 70.0f;
    private static final int ARM_SWING_DURATION_TICKS = 15;

    // Animation tick state (-1 = idle, 0-11 = animation cycle)
    private int leftHandAnimationTick = -1;
    private int rightHandAnimationTick = -1;

    // Just-activated flags for triggering animations
    private boolean leftHandJustActivated = false;
    private boolean rightHandJustActivated = false;

    // Target block positions for arm pointing
    private Vec3d leftArmTargetBlock = null;
    private Vec3d rightArmTargetBlock = null;

    // Next block positions for smooth transitions
    private BlockPos nextLeftBlock = null;
    private BlockPos nextRightBlock = null;

    // Arm rotation angles
    private float leftArmYaw = 0.0f;
    private float leftArmPitch = 0.0f;  // Called "Rotation" in original code
    private float rightArmYaw = 0.0f;
    private float rightArmPitch = 0.0f;

    // Walking animation state
    private float leftArmTarget = 0.0f;
    private float rightArmTarget = 0.0f;
    private int armSwingTimer = 0;

    // Eye look directions
    private float leftEyeYaw = 0.0f;
    private float leftEyePitch = 0.0f;
    private float rightEyeYaw = 0.0f;
    private float rightEyePitch = 0.0f;
    private int eyeUpdateCooldown = 0;

    // Eye update cooldown bounds
    public static final int EYE_UPDATE_COOLDOWN_MIN = 5;
    public static final int EYE_UPDATE_COOLDOWN_MAX = 10;

    // Placement tick counter for alternating hands
    private int placementTickCounter = 0;
    private boolean leftHandActive = true;

    /**
     * Begin an animation for the specified hand at the target position.
     * @param isLeft true for left hand, false for right hand
     * @param target Block position the arm should point at
     */
    public void beginAnimation(boolean isLeft, Vec3d target) {
        if (isLeft) {
            leftHandAnimationTick = 0;
            leftArmTargetBlock = target;
            leftHandJustActivated = true;
        } else {
            rightHandAnimationTick = 0;
            rightArmTargetBlock = target;
            rightHandJustActivated = true;
        }
    }

    /**
     * Advance animation ticks for both hands.
     */
    public void advanceAnimationTicks() {
        if (leftHandAnimationTick >= 0) {
            leftHandAnimationTick++;
            if (leftHandAnimationTick >= ANIMATION_DURATION_TICKS) {
                leftHandAnimationTick = -1;
                leftArmTargetBlock = null;
            }
        }
        if (rightHandAnimationTick >= 0) {
            rightHandAnimationTick++;
            if (rightHandAnimationTick >= ANIMATION_DURATION_TICKS) {
                rightHandAnimationTick = -1;
                rightArmTargetBlock = null;
            }
        }
    }

    /**
     * Update arm and eye positions based on target blocks.
     * @param entity The golem entity
     */
    public void updateArmAndEyePositions(GoldGolemEntity entity) {
        // Update left arm and eye
        if (leftArmTargetBlock != null) {
            updateArmToTarget(entity, true, leftArmTargetBlock,
                    leftHandAnimationTick >= 6 && nextLeftBlock != null
                            ? new Vec3d(nextLeftBlock.getX() + 0.5, nextLeftBlock.getY() + 0.5, nextLeftBlock.getZ() + 0.5)
                            : null);
        } else if (leftHandAnimationTick >= 0) {
            // Animation active but no block position - use default "placing" pose
            leftArmPitch = 70.0f;
            leftArmYaw = 0.0f;
            leftEyeYaw = 0.0f;
            leftEyePitch = 15.0f;
        } else {
            // Idle - neutral
            leftArmYaw = 0.0f;
            leftEyeYaw = 0.0f;
            leftEyePitch = 0.0f;
        }

        // Update right arm and eye
        if (rightArmTargetBlock != null) {
            updateArmToTarget(entity, false, rightArmTargetBlock,
                    rightHandAnimationTick >= 6 && nextRightBlock != null
                            ? new Vec3d(nextRightBlock.getX() + 0.5, nextRightBlock.getY() + 0.5, nextRightBlock.getZ() + 0.5)
                            : null);
        } else if (rightHandAnimationTick >= 0) {
            // Animation active but no block position - use default "placing" pose
            rightArmPitch = 70.0f;
            rightArmYaw = 0.0f;
            rightEyeYaw = 0.0f;
            rightEyePitch = 15.0f;
        } else {
            // Idle - neutral
            rightArmYaw = 0.0f;
            rightEyeYaw = 0.0f;
            rightEyePitch = 0.0f;
        }
    }

    private void updateArmToTarget(GoldGolemEntity entity, boolean isLeft, Vec3d targetBlock, Vec3d transitionTarget) {
        double armOffsetX = isLeft ? -0.3 : 0.3;
        Vec3d armPos = new Vec3d(entity.getX() + armOffsetX, entity.getY() + 1.0, entity.getZ());
        Vec3d targetPos = transitionTarget != null ? transitionTarget : targetBlock;

        // Calculate direction to target in world space
        double dx = targetPos.x - armPos.x;
        double dy = targetPos.y - armPos.y;
        double dz = targetPos.z - armPos.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        // Calculate world-space yaw to target, then make it relative to body yaw
        // Use -dx because Minecraft yaw convention: 0=South, -90=East, 90=West
        // Add 180° because arm default is down, pitch rotates to horizontal pointing backward (-Z),
        // so yaw=0 is backward and yaw=180 is forward
        float worldYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float armYaw = worldYaw - entity.getBodyYaw() + 180.0f;
        // Normalize to -180 to 180
        while (armYaw > 180) armYaw -= 360;
        while (armYaw < -180) armYaw += 360;

        // Calculate pitch (vertical angle from horizontal)
        float pitch = (float) Math.toDegrees(Math.atan2(dy, horizontalDist));
        // Arm model points DOWN by default (0 = down, 90 = forward/horizontal)
        float armPitch = 90.0f + pitch;

        // Update eye to look at same target
        double eyeDx = targetPos.x - entity.getX();
        double eyeDy = targetPos.y - (entity.getY() + 1.5);
        double eyeDz = targetPos.z - entity.getZ();
        double eyeHorizontalDist = Math.sqrt(eyeDx * eyeDx + eyeDz * eyeDz);

        float eyeYaw = (float) Math.toDegrees(Math.atan2(-eyeDx, eyeDz));
        float eyePitch = (float) Math.toDegrees(Math.atan2(-eyeDy, eyeHorizontalDist));

        if (isLeft) {
            this.leftArmYaw = armYaw;
            this.leftArmPitch = armPitch;
            this.leftEyeYaw = eyeYaw;
            this.leftEyePitch = eyePitch;
        } else {
            this.rightArmYaw = armYaw;
            this.rightArmPitch = armPitch;
            this.rightEyeYaw = eyeYaw;
            this.rightEyePitch = eyePitch;
        }
    }

    /**
     * Update walking arm swing animation.
     * @param entity The golem entity
     * @param distanceTraveled Distance moved this tick
     */
    public void updateWalkingAnimation(GoldGolemEntity entity, double distanceTraveled) {
        if (armSwingTimer <= 0) {
            leftArmTarget = ARM_SWING_MIN_ANGLE + entity.getRandom().nextFloat() * (ARM_SWING_MAX_ANGLE - ARM_SWING_MIN_ANGLE);
            rightArmTarget = ARM_SWING_MIN_ANGLE + entity.getRandom().nextFloat() * (ARM_SWING_MAX_ANGLE - ARM_SWING_MIN_ANGLE);
            if (leftArmPitch >= 0) {
                leftArmTarget = -leftArmTarget;
            } else {
                rightArmTarget = -rightArmTarget;
            }
            armSwingTimer = ARM_SWING_DURATION_TICKS;
        }
        float progress = 1.0f - (armSwingTimer / (float) ARM_SWING_DURATION_TICKS);
        float prevLeftTarget = -leftArmTarget;
        float prevRightTarget = -rightArmTarget;
        leftArmPitch = MathHelper.lerp(progress, prevLeftTarget, leftArmTarget);
        rightArmPitch = MathHelper.lerp(progress, prevRightTarget, rightArmTarget);
        // Reset yaw to forward during walking
        leftArmYaw = MathHelper.lerp(0.2f, leftArmYaw, 0.0f);
        rightArmYaw = MathHelper.lerp(0.2f, rightArmYaw, 0.0f);
        armSwingTimer--;
    }

    /**
     * Return arms to idle/neutral position.
     */
    public void updateIdleAnimation() {
        leftArmPitch = MathHelper.lerp(0.1f, leftArmPitch, 0.0f);
        rightArmPitch = MathHelper.lerp(0.1f, rightArmPitch, 0.0f);
        leftArmYaw = MathHelper.lerp(0.1f, leftArmYaw, 0.0f);
        rightArmYaw = MathHelper.lerp(0.1f, rightArmYaw, 0.0f);
        armSwingTimer = 0;
    }

    /**
     * Update eye positions with random movement.
     * @param entity The golem entity
     */
    public void updateRandomEyeMovement(GoldGolemEntity entity) {
        if (eyeUpdateCooldown <= 0) {
            // Random look direction for left eye
            leftEyeYaw = (entity.getRandom().nextFloat() - 0.5f) * 120.0f;
            leftEyePitch = (entity.getRandom().nextFloat() - 0.5f) * 60.0f;

            // Random look direction for right eye (independent)
            rightEyeYaw = (entity.getRandom().nextFloat() - 0.5f) * 120.0f;
            rightEyePitch = (entity.getRandom().nextFloat() - 0.5f) * 60.0f;

            // Set next update time
            eyeUpdateCooldown = EYE_UPDATE_COOLDOWN_MIN + entity.getRandom().nextInt(EYE_UPDATE_COOLDOWN_MAX - EYE_UPDATE_COOLDOWN_MIN + 1);
        } else {
            eyeUpdateCooldown--;
        }
    }

    /**
     * Increment placement tick counter and toggle active hand.
     */
    public void incrementPlacementCounter() {
        placementTickCounter = (placementTickCounter + 1) % 2;
    }

    /**
     * Check if an animation is currently active.
     */
    public boolean isAnimating() {
        return leftHandAnimationTick >= 0 || rightHandAnimationTick >= 0;
    }

    /**
     * Check if left hand animation is active and has a target.
     */
    public boolean isLeftAnimating() {
        return leftHandAnimationTick >= 0 && leftArmTargetBlock != null;
    }

    /**
     * Check if right hand animation is active and has a target.
     */
    public boolean isRightAnimating() {
        return rightHandAnimationTick >= 0 && rightArmTargetBlock != null;
    }

    // Getters
    public int getLeftHandAnimationTick() { return leftHandAnimationTick; }
    public int getRightHandAnimationTick() { return rightHandAnimationTick; }
    public boolean isLeftHandJustActivated() { return leftHandJustActivated; }
    public boolean isRightHandJustActivated() { return rightHandJustActivated; }
    public Vec3d getLeftArmTargetBlock() { return leftArmTargetBlock; }
    public Vec3d getRightArmTargetBlock() { return rightArmTargetBlock; }
    public BlockPos getNextLeftBlock() { return nextLeftBlock; }
    public BlockPos getNextRightBlock() { return nextRightBlock; }
    public float getLeftArmYaw() { return leftArmYaw; }
    public float getLeftArmPitch() { return leftArmPitch; }
    public float getRightArmYaw() { return rightArmYaw; }
    public float getRightArmPitch() { return rightArmPitch; }
    public float getLeftEyeYaw() { return leftEyeYaw; }
    public float getLeftEyePitch() { return leftEyePitch; }
    public float getRightEyeYaw() { return rightEyeYaw; }
    public float getRightEyePitch() { return rightEyePitch; }
    public int getPlacementTickCounter() { return placementTickCounter; }
    public boolean isLeftHandActive() { return leftHandActive; }

    // Setters
    public void setLeftHandAnimationTick(int tick) { this.leftHandAnimationTick = tick; }
    public void setRightHandAnimationTick(int tick) { this.rightHandAnimationTick = tick; }
    public void setLeftHandJustActivated(boolean activated) { this.leftHandJustActivated = activated; }
    public void setRightHandJustActivated(boolean activated) { this.rightHandJustActivated = activated; }
    public void setLeftArmTargetBlock(Vec3d target) { this.leftArmTargetBlock = target; }
    public void setRightArmTargetBlock(Vec3d target) { this.rightArmTargetBlock = target; }
    public void setNextLeftBlock(BlockPos pos) { this.nextLeftBlock = pos; }
    public void setNextRightBlock(BlockPos pos) { this.nextRightBlock = pos; }
    public void setLeftArmYaw(float yaw) { this.leftArmYaw = yaw; }
    public void setLeftArmPitch(float pitch) { this.leftArmPitch = pitch; }
    public void setRightArmYaw(float yaw) { this.rightArmYaw = yaw; }
    public void setRightArmPitch(float pitch) { this.rightArmPitch = pitch; }
    public void setLeftHandActive(boolean active) { this.leftHandActive = active; }
}
