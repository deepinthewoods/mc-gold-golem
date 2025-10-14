package ninja.trek.mc.goldgolem.world.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventories;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.particle.ParticleTypes;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.util.ActionResult;
import net.minecraft.world.World;
import ninja.trek.mc.goldgolem.screen.GolemScreens;

public class GoldGolemEntity extends PathAwareEntity {
    public static final int INVENTORY_SIZE = 27;

    private final SimpleInventory inventory = new SimpleInventory(INVENTORY_SIZE);
    private final String[] gradient = new String[9];
    private int gradientWindow = 1; // window width in slot units (0..9)
    private int pathWidth = 3;
    private boolean buildingPaths = false;
    private Vec3d trackStart = null;
    private java.util.ArrayDeque<LineSeg> pendingLines = new java.util.ArrayDeque<>();
    private LineSeg currentLine = null;
    private int placeCooldown = 0;
    private final LongOpenHashSet recentPlaced = new LongOpenHashSet(8192);
    private final long[] placedRing = new long[8192];
    private int placedHead = 0;
    private int placedSize = 0;
    private int stuckTicks = 0;
    public boolean isBuildingPaths() { return buildingPaths; }

    public GoldGolemEntity(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return DefaultAttributeContainer.builder()
                .add(EntityAttributes.MAX_HEALTH, 40.0)
                .add(EntityAttributes.MOVEMENT_SPEED, 0.28)
                .add(EntityAttributes.FOLLOW_RANGE, 32.0)
                .add(EntityAttributes.ARMOR, 0.0)
                .add(EntityAttributes.ARMOR_TOUGHNESS, 0.0)
                .add(EntityAttributes.WAYPOINT_TRANSMIT_RANGE, 0.0)
                .add(EntityAttributes.STEP_HEIGHT, 0.6)
                .add(EntityAttributes.MOVEMENT_EFFICIENCY, 1.0)
                .add(EntityAttributes.GRAVITY, 0.08)
                .add(EntityAttributes.SAFE_FALL_DISTANCE, 3.0)
                .add(EntityAttributes.FALL_DAMAGE_MULTIPLIER, 1.0)
                .add(EntityAttributes.JUMP_STRENGTH, 0.42);
    }

    @Override
    protected void initGoals() {
        // Follow players holding gold nuggets (approach within 1.5 blocks)
        this.goalSelector.add(3, new FollowGoldNuggetHolderGoal(this, 1.1, 1.5));
        this.goalSelector.add(5, new PathingAwareWanderGoal(this, 0.8));
        this.goalSelector.add(6, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
        this.goalSelector.add(7, new LookAroundGoal(this));
    }

    @Override
    protected Text getDefaultName() {
        return Text.translatable("entity.gold_golem.gold_golem");
    }

    @Override
    public void tick() {
        super.tick();
        if (this.getEntityWorld().isClient()) return;
        if (buildingPaths) {
            // Look at owner while building
            PlayerEntity owner = getOwnerPlayer();
            if (owner != null) {
                this.getLookControl().lookAt(owner, 30.0f, 30.0f);
            }
            // Render queued lines for owner when holding nugget (server-side particles)
            if (owner instanceof net.minecraft.server.network.ServerPlayerEntity sp &&
                    (owner.getMainHandStack().isOf(net.minecraft.item.Items.GOLD_NUGGET) || owner.getOffHandStack().isOf(net.minecraft.item.Items.GOLD_NUGGET))) {
                if (this.age % 4 == 0 && this.getEntityWorld() instanceof ServerWorld sw) {
                    // sample each pending segment more densely for visibility
                    for (LineSeg s : pendingLines) {
                        int samples = Math.max(4, Math.min(48, s.cells.size() * 2));
                        for (int k = 0; k <= samples; k++) {
                            double t = (double) k / (double) samples;
                            double x = MathHelper.lerp(t, s.a.x, s.b.x);
                            double y = MathHelper.lerp(t, s.a.y, s.b.y) + 0.05;
                            double z = MathHelper.lerp(t, s.a.z, s.b.z);
                            // alwaysSpawn = true so the owner always sees them; small spread for dotted line effect
                            sw.spawnParticles(sp, net.minecraft.particle.ParticleTypes.HAPPY_VILLAGER, false, true, x, y, z, 2, 0.02, 0.01, 0.02, 0.0);
                        }
                    }
                }
            }
            // Track lines while owner moves (require grounded for stability, no distance gate in pathing mode)
            if (owner != null && owner.isOnGround()) {
                Vec3d p = new Vec3d(owner.getX(), owner.getY(), owner.getZ());
                if (trackStart == null) {
                    trackStart = p;
                } else if (trackStart.distanceTo(p) >= 3.0) {
                    enqueueLine(trackStart, p);
                    trackStart = p;
                }
            }
            // Process current line
            if (currentLine == null) currentLine = pendingLines.pollFirst();
            if (currentLine != null) {
                if (placeCooldown > 0) {
                    placeCooldown--;
                } else {
                    boolean more = placeAlong(currentLine);
                    placeCooldown = 1; // throttle
                    if (!more) {
                        // Corner fill with next line if present
                        LineSeg next = pendingLines.peekFirst();
                        if (next != null) placeCornerFill(currentLine, next);
                        currentLine = null;
                    }
                }
                // Move along the current line towards next interpolation point
                if (currentLine != null) {
                    Vec3d next = currentLine.currentPoint();
                    double ty = computeGroundTargetY(next);
                    this.getNavigation().startMovingTo(next.x, ty, next.z, 1.0);
                    // Detect stuck navigation and recover by teleporting to the next target cell
                    double dx = this.getX() - next.x;
                    double dz = this.getZ() - next.z;
                    double distSq = dx * dx + dz * dz;
                    if (this.getNavigation().isIdle() && distSq > 4.0) {
                        stuckTicks++;
                        if (stuckTicks >= 20) {
                            // Enderman-like teleport particles at origin and destination
                            if (this.getEntityWorld() instanceof ServerWorld sw) {
                                sw.spawnParticles(ParticleTypes.PORTAL, this.getX(), this.getY() + 0.5, this.getZ(), 40, 0.5, 0.5, 0.5, 0.2);
                                sw.spawnParticles(ParticleTypes.PORTAL, next.x, ty + 0.5, next.z, 40, 0.5, 0.5, 0.5, 0.2);
                            }
                            this.refreshPositionAndAngles(next.x, ty, next.z, this.getYaw(), this.getPitch());
                            this.getNavigation().stop();
                            stuckTicks = 0;
                        }
                    } else {
                        stuckTicks = 0;
                    }
                } else {
                    // Idle in place during pathing mode; do not wander
                    this.getNavigation().stop();
                    stuckTicks = 0;
                }
            }
        }
    }

    private double computeGroundTargetY(Vec3d pos) {
        int bx = MathHelper.floor(pos.x);
        int bz = MathHelper.floor(pos.z);
        int y0 = MathHelper.floor(pos.y);
        var world = this.getEntityWorld();
        Integer groundY = null;
        for (int yy = y0 + 3; yy >= y0 - 8; yy--) {
            BlockPos test = new BlockPos(bx, yy, bz);
            var st = world.getBlockState(test);
            if (!st.isAir() && st.isFullCube(world, test)) { groundY = yy; break; }
        }
        if (groundY == null) return pos.y;
        // ensure stand space (two blocks of air above ground)
        int ty = groundY + 1;
        for (int up = 0; up <= 3; up++) {
            BlockPos p1 = new BlockPos(bx, ty + up, bz);
            BlockPos p2 = new BlockPos(bx, ty + up + 1, bz);
            var s1 = world.getBlockState(p1);
            var s2 = world.getBlockState(p2);
            boolean passable = s1.isAir() && s2.isAir();
            if (passable) return ty + up;
        }
        return groundY + 1.0;
    }

    // Persistence: width, gradient, inventory, owner UUID (1.21.10 storage API)
    @Override
    protected void writeCustomData(WriteView view) {
        view.putInt("PathWidth", this.pathWidth);
        view.putInt("GradWindow", this.gradientWindow);

        for (int i = 0; i < 9; i++) {
            String val = gradient[i] == null ? "" : gradient[i];
            view.putString("G" + i, val);
        }

        DefaultedList<ItemStack> stacks = DefaultedList.ofSize(INVENTORY_SIZE, ItemStack.EMPTY);
        for (int i = 0; i < INVENTORY_SIZE; i++) stacks.set(i, inventory.getStack(i));
        Inventories.writeData(view.get("Inventory"), stacks, true);

        if (ownerUuid != null) view.putString("Owner", ownerUuid.toString());
    }

    @Override
    protected void readCustomData(ReadView view) {
        this.pathWidth = Math.max(1, Math.min(9, view.getInt("PathWidth", this.pathWidth)));
        this.gradientWindow = Math.max(0, Math.min(9, view.getInt("GradWindow", this.gradientWindow)));

        for (int i = 0; i < 9; i++) {
            gradient[i] = view.getString("G" + i, "");
        }

        DefaultedList<ItemStack> stacks = DefaultedList.ofSize(INVENTORY_SIZE, ItemStack.EMPTY);
        Inventories.readData(view.getReadView("Inventory"), stacks);
        for (int i = 0; i < INVENTORY_SIZE; i++) inventory.setStack(i, stacks.get(i));

        var ownerOpt = view.getOptionalString("Owner");
        this.ownerUuid = ownerOpt.isPresent() && !ownerOpt.get().isEmpty() ? java.util.UUID.fromString(ownerOpt.get()) : null;
    }

    public Inventory getInventory() { return inventory; }

    public int getPathWidth() { return pathWidth; }
    public void setPathWidth(int width) { this.pathWidth = Math.max(1, Math.min(9, width)); }
    public int getGradientWindow() { return gradientWindow; }
    public void setGradientWindow(int w) { this.gradientWindow = Math.max(0, Math.min(9, w)); }

    public String[] getGradientCopy() {
        String[] copy = new String[9];
        for (int i = 0; i < 9; i++) {
            copy[i] = (gradient[i] == null) ? "" : gradient[i];
        }
        return copy;
    }
    public void setGradientSlot(int idx, String id) {
        if (idx < 0 || idx >= 9) return;
        String value = (id == null || id.isEmpty()) ? "" : id;
        // Debug log to help trace slot updates from client → server
        System.out.println("[GoldGolem] setGradientSlot entity=" + this.getId() +
                " idx=" + idx + " value='" + value + "'");
        gradient[idx] = value;
    }

    // Ownership (simple UUID-based)
    private java.util.UUID ownerUuid;
    public void setOwner(PlayerEntity player) { this.ownerUuid = player.getUuid(); }
    public boolean isOwner(PlayerEntity player) { return ownerUuid != null && player != null && ownerUuid.equals(player.getUuid()); }

    private PlayerEntity getOwnerPlayer() {
        if (ownerUuid == null) return null;
        for (PlayerEntity p : this.getEntityWorld().getPlayers()) {
            if (ownerUuid.equals(p.getUuid())) return p;
        }
        return null;
    }

    @Override
    public ActionResult interactMob(PlayerEntity player, net.minecraft.util.Hand hand) {
        if (!(player instanceof net.minecraft.server.network.ServerPlayerEntity sp)) {
            return ActionResult.SUCCESS;
        }
        // Feeding: start building when owner feeds a gold nugget; consume one and show hearts
        var stack = player.getStackInHand(hand);
        if (stack != null && stack.isOf(net.minecraft.item.Items.GOLD_NUGGET)) {
            if (!isOwner(player)) {
                // Claim in singleplayer if prior owner offline
                var server = sp.getEntityWorld().getServer();
                boolean singleplayer = !server.isDedicated();
                boolean ownerOnline = (ownerUuid != null) && (server.getPlayerManager().getPlayer(ownerUuid) != null);
                if (singleplayer && !ownerOnline) {
                    setOwner(player);
                } else {
                    sp.sendMessage(Text.translatable("message.gold_golem.not_owner"), true);
                    return ActionResult.FAIL;
                }
            }
            if (!this.getEntityWorld().isClient()) {
                this.buildingPaths = true;
                if (!player.isCreative()) stack.decrement(1);
                spawnHearts();
                // send initial (possibly empty) line list
                var owner = getOwnerPlayer();
                if (owner instanceof net.minecraft.server.network.ServerPlayerEntity spOwner) {
                    ninja.trek.mc.goldgolem.net.ServerNet.sendLines(spOwner, this.getId(), java.util.List.of());
                }
                // reset recent placements cache
                recentPlaced.clear();
                placedHead = placedSize = 0;
            }
            return ActionResult.CONSUME;
        }
        // Otherwise open UI as before (owner only gate)
        if (!isOwner(player)) {
            var server = sp.getEntityWorld().getServer();
            boolean singleplayer = !server.isDedicated();
            boolean ownerOnline = (ownerUuid != null) && (server.getPlayerManager().getPlayer(ownerUuid) != null);
            if (singleplayer && !ownerOnline) {
                setOwner(player);
            } else {
                sp.sendMessage(Text.translatable("message.gold_golem.not_owner"), true);
                return ActionResult.FAIL;
            }
        }
        GolemScreens.open(sp, this.getId(), this.inventory);
        return ActionResult.CONSUME;
    }

    @Override
    public boolean damage(net.minecraft.server.world.ServerWorld world, net.minecraft.entity.damage.DamageSource source, float amount) {
        var attacker = source.getAttacker();
        if (attacker instanceof PlayerEntity p && isOwner(p)) {
            // Stop building on owner hit; show angry particles; ignore damage
            this.buildingPaths = false;
            this.trackStart = null;
            this.pendingLines.clear();
            this.currentLine = null;
            spawnAngry();
            // clear client lines
            if (attacker instanceof net.minecraft.server.network.ServerPlayerEntity spOwner) {
                ninja.trek.mc.goldgolem.net.ServerNet.sendLines(spOwner, this.getId(), java.util.List.of());
            }
            recentPlaced.clear();
            placedHead = placedSize = 0;
            return false; // cancel damage
        }
        return super.damage(world, source, amount);
    }

    private void spawnHearts() {
        if (this.getEntityWorld() instanceof ServerWorld sw) {
            sw.spawnParticles(ParticleTypes.HEART, this.getX(), this.getY() + 1.0, this.getZ(), 6, 0.3, 0.3, 0.3, 0.02);
        }
    }
    private void spawnAngry() {
        if (this.getEntityWorld() instanceof ServerWorld sw) {
            sw.spawnParticles(ParticleTypes.ANGRY_VILLAGER, this.getX(), this.getY() + 1.0, this.getZ(), 6, 0.3, 0.3, 0.3, 0.02);
        }
    }

    private void enqueueLine(Vec3d a, Vec3d b) {
        LineSeg seg = new LineSeg(a, b);
        pendingLines.addLast(seg);
        // Sync to client for debug rendering (owner only)
        if (this.getEntityWorld() instanceof ServerWorld sw) {
            var owner = getOwnerPlayer();
            if (owner instanceof net.minecraft.server.network.ServerPlayerEntity sp) {
                java.util.List<net.minecraft.util.math.BlockPos> list = new java.util.ArrayList<>();
                for (LineSeg s : pendingLines) {
                    list.add(net.minecraft.util.math.BlockPos.ofFloored(s.a));
                    list.add(net.minecraft.util.math.BlockPos.ofFloored(s.b));
                }
                ninja.trek.mc.goldgolem.net.ServerNet.sendLines(sp, this.getId(), list);
            }
        }
    }

    private boolean placeAlong(LineSeg seg) {
        if (seg.cellIndex >= seg.cells.size()) return false;
        BlockPos cell = seg.cells.get(seg.cellIndex);
        double t = seg.cells.size() <= 1 ? 1.0 : (double) seg.cellIndex / (double) (seg.cells.size() - 1);
        double y = MathHelper.lerp(t, seg.a.y, seg.b.y);
        double x = cell.getX() + 0.5;
        double z = cell.getZ() + 0.5;
        // Perpendicular vector (normalize)
        double len = Math.sqrt(seg.dirX * seg.dirX + seg.dirZ * seg.dirZ);
        double px = len > 1e-4 ? (-seg.dirZ / len) : 0.0;
        double pz = len > 1e-4 ? ( seg.dirX / len) : 0.0;
        placeStripAt(x, y, z, px, pz);
        seg.cellIndex++;
        return seg.cellIndex < seg.cells.size();
    }

    private void placeStripAt(double x, double y, double z, double px, double pz) {
        int w = Math.max(1, Math.min(9, this.pathWidth));
        int half = (w - 1) / 2;
        var world = this.getEntityWorld();
        LongOpenHashSet stripSeen = new LongOpenHashSet(w * 3);
        for (int j = -half; j <= half; j++) {
            double ox = x + px * j;
            double oz = z + pz * j;
            int bx = MathHelper.floor(ox);
            int bz = MathHelper.floor(oz);
            int gIdx = sampleGradientIndex(w, j, bx, bz);
            if (gIdx < 0) continue;
            String id = gradient[gIdx] == null ? "" : gradient[gIdx];
            if (id.isEmpty()) continue;
            var ident = net.minecraft.util.Identifier.tryParse(id);
            if (ident == null) continue;
            var block = net.minecraft.registry.Registries.BLOCK.get(ident);
            if (block == null) continue;

            int y0 = MathHelper.floor(y);
            Integer groundY = null;
            for (int yy = y0 + 1; yy >= y0 - 6; yy--) {
                BlockPos test = new BlockPos(bx, yy, bz);
                var st = world.getBlockState(test);
                if (!st.isAir() && st.isFullCube(world, test)) { groundY = yy; break; }
            }
            if (groundY == null) continue;
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos rp = new BlockPos(bx, groundY + dy, bz);
                long key = rp.asLong();
                if (stripSeen.contains(key)) continue;
                stripSeen.add(key);
                var rs = world.getBlockState(rp);
                if (rs.isAir() || !rs.isFullCube(world, rp)) continue;
                if (rs.isOf(block)) continue;
                // Skip if there is a solid block directly above this placement (avoid head-bump/hidden placement for surface/above)
                if (dy >= 0) {
                    BlockPos ap = rp.up();
                    var as = world.getBlockState(ap);
                    if (as.isFullCube(world, ap)) continue;
                }
                if (!recordPlaced(key)) continue;
                int invSlot = findItem(block.asItem());
                if (invSlot < 0) { unrecordPlaced(key); continue; }
                world.setBlockState(rp, block.getDefaultState(), 3);
                var stInv = inventory.getStack(invSlot);
                stInv.decrement(1);
                inventory.setStack(invSlot, stInv);
            }
        }
    }

    private int sampleGradientIndex(int stripWidth, int j, int bx, int bz) {
        int G = 0;
        for (int i = gradient.length - 1; i >= 0; i--) {
            if (gradient[i] != null && !gradient[i].isEmpty()) { G = i + 1; break; }
        }
        if (G <= 0) return -1;

        int half = (stripWidth - 1) / 2;
        int c = j + half;
        int denom = Math.max(1, stripWidth - 1);
        double s = (double) c / (double) denom * (double) (G - 1);

        int Wcap = Math.min(this.gradientWindow, G);
        double W = (double) Wcap;
        if (W == 0.0) {
            int idx = (int) Math.round(s);
            return MathHelper.clamp(idx, 0, G - 1);
        }

        double u01 = deterministic01(bx, bz, j);
        double u = (u01 * W) - (W * 0.5);
        double sprime = s + u;

        double a = -0.5;
        double b = (double) G - 0.5;
        double L = b - a;
        double y = (sprime - a) % (2.0 * L);
        if (y < 0) y += 2.0 * L;
        double r = (y <= L) ? y : (2.0 * L - y);
        double sref = a + r;

        int idx = (int) Math.round(sref);
        return MathHelper.clamp(idx, 0, G - 1);
    }

    private double deterministic01(int bx, int bz, int j) {
        long v = 0x9E3779B97F4A7C15L;
        v ^= ((long) this.getId() * 0x9E3779B97F4A7C15L);
        v ^= ((long) bx * 0xC2B2AE3D27D4EB4FL);
        v ^= ((long) bz * 0x165667B19E3779F9L);
        v ^= ((long) j * 0x85EBCA77C2B2AE63L);
        v ^= (v >>> 33);
        v *= 0xff51afd7ed558ccdL;
        v ^= (v >>> 33);
        v *= 0xc4ceb9fe1a85ec53L;
        v ^= (v >>> 33);
        return (Double.longBitsToDouble((v >>> 12) | 0x3FF0000000000000L) - 1.0);
    }

    private void placeCornerFill(LineSeg prev, LineSeg next) {
        // Compute end position of prev and start of next at ground y estimate and place strips with both normals
        BlockPos endCell = prev.cells.isEmpty() ? BlockPos.ofFloored(prev.b) : prev.cells.get(prev.cells.size() - 1);
        BlockPos startCell = next.cells.isEmpty() ? BlockPos.ofFloored(next.a) : next.cells.get(0);
        double yPrev = prev.b.y;
        double x = endCell.getX() + 0.5;
        double z = endCell.getZ() + 0.5;
        // prev normal
        double len1 = Math.sqrt(prev.dirX * prev.dirX + prev.dirZ * prev.dirZ);
        double px1 = len1 > 1e-4 ? (-prev.dirZ / len1) : 0.0;
        double pz1 = len1 > 1e-4 ? ( prev.dirX / len1) : 0.0;
        // next normal
        double len2 = Math.sqrt(next.dirX * next.dirX + next.dirZ * next.dirZ);
        double px2 = len2 > 1e-4 ? (-next.dirZ / len2) : 0.0;
        double pz2 = len2 > 1e-4 ? ( next.dirX / len2) : 0.0;
        // Expand width by +1 to help fill gaps
        int old = this.pathWidth;
        this.pathWidth = Math.min(9, old + 1);
        placeStripAt(x, yPrev, z, px1, pz1);
        placeStripAt(x, yPrev, z, px2, pz2);
        this.pathWidth = old;
    }

    private boolean recordPlaced(long key) {
        if (recentPlaced.contains(key)) return false;
        if (placedSize == placedRing.length) {
            long old = placedRing[placedHead];
            recentPlaced.remove(old);
            placedRing[placedHead] = key;
            placedHead = (placedHead + 1) % placedRing.length;
        } else {
            placedRing[(placedHead + placedSize) % placedRing.length] = key;
            placedSize++;
        }
        recentPlaced.add(key);
        return true;
    }

    private void unrecordPlaced(long key) {
        // best-effort: keep it recorded to avoid thrash; no-op
    }

    private int findItem(net.minecraft.item.Item item) {
        for (int i = 0; i < inventory.size(); i++) {
            var st = inventory.getStack(i);
            if (!st.isEmpty() && st.isOf(item)) return i;
        }
        return -1;
    }

    private static class LineSeg {
        final Vec3d a;
        final Vec3d b;
        final double dirX;
        final double dirZ;
        final java.util.List<BlockPos> cells;
        int cellIndex = 0;
        LineSeg(Vec3d a, Vec3d b) {
            this.a = a;
            this.b = b;
            this.dirX = b.x - a.x;
            this.dirZ = b.z - a.z;
            this.cells = computeCells(BlockPos.ofFloored(a.x, 0, a.z), BlockPos.ofFloored(b.x, 0, b.z));
        }
        Vec3d currentPoint() {
            if (cells.isEmpty()) return b;
            BlockPos c = cells.get(Math.min(cellIndex, cells.size()-1));
            double t = cells.size() <= 1 ? 1.0 : (double) cellIndex / (double) (cells.size() - 1);
            double y = MathHelper.lerp(t, a.y, b.y);
            return new Vec3d(c.getX() + 0.5, y, c.getZ() + 0.5);
        }
        private static java.util.List<BlockPos> computeCells(BlockPos a, BlockPos b) {
            // Supercover Bresenham: cover corners when both axes change to avoid diagonal gaps
            java.util.ArrayList<BlockPos> out = new java.util.ArrayList<>();
            int x0 = a.getX();
            int z0 = a.getZ();
            int x1 = b.getX();
            int z1 = b.getZ();
            int dx = Math.abs(x1 - x0);
            int dz = Math.abs(z1 - z0);
            int sx = (x0 < x1) ? 1 : -1;
            int sz = (z0 < z1) ? 1 : -1;
            int err = dx - dz;
            int x = x0;
            int z = z0;
            out.add(new BlockPos(x, 0, z));
            while (x != x1 || z != z1) {
                int e2 = err << 1;
                if (e2 > -dz) { err -= dz; x += sx; out.add(new BlockPos(x, 0, z)); }
                if (e2 < dx) { err += dx; z += sz; out.add(new BlockPos(x, 0, z)); }
            }
            return out;
        }
    }
}

class FollowGoldNuggetHolderGoal extends Goal {
    private final GoldGolemEntity golem;
    private final double speed;
    private final double stopDistance;
    private PlayerEntity target;

    public FollowGoldNuggetHolderGoal(GoldGolemEntity golem, double speed, double stopDistance) {
        this.golem = golem;
        this.speed = speed;
        this.stopDistance = stopDistance;
    }

    @Override
    public boolean canStart() {
        if (golem.isBuildingPaths()) return false;
        // Only follow the owner; find the owner player in-world
        PlayerEntity owner = null;
        for (PlayerEntity player : golem.getEntityWorld().getPlayers()) {
            if (golem.isOwner(player)) { owner = player; break; }
        }
        if (owner == null) return false;
        if (!isHoldingNugget(owner)) return false;
        if (golem.squaredDistanceTo(owner) > (24.0 * 24.0)) return false;
        this.target = owner;
        return true;
    }

    @Override
    public boolean shouldContinue() {
        if (golem.isBuildingPaths()) return false;
        if (target == null || !target.isAlive()) return false;
        // Ensure target remains the owner
        if (!golem.isOwner(target)) return false;
        if (!isHoldingNugget(target)) return false;
        double distSq = golem.squaredDistanceTo(target);
        return distSq > (stopDistance * stopDistance);
    }

    @Override
    public void stop() {
        this.target = null;
        this.golem.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (target == null) return;
        this.golem.getLookControl().lookAt(target, 30.0f, 30.0f);
        double distSq = golem.squaredDistanceTo(target);
        if (distSq > (stopDistance * stopDistance)) {
            this.golem.getNavigation().startMovingTo(target, this.speed);
        } else {
            this.golem.getNavigation().stop();
        }
    }

    private static boolean isHoldingNugget(PlayerEntity player) {
        var nugget = net.minecraft.item.Items.GOLD_NUGGET;
        return player.getMainHandStack().isOf(nugget) || player.getOffHandStack().isOf(nugget);
    }
}

class PathingAwareWanderGoal extends WanderAroundFarGoal {
    private final GoldGolemEntity golem;

    public PathingAwareWanderGoal(GoldGolemEntity golem, double speed) {
        super(golem, speed);
        this.golem = golem;
    }

    @Override
    public boolean canStart() {
        if (golem.isBuildingPaths()) return false;
        return super.canStart();
    }

    @Override
    public boolean shouldContinue() {
        if (golem.isBuildingPaths()) return false;
        return super.shouldContinue();
    }
}
