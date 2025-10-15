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
                .add(EntityAttributes.MAX_ABSORPTION, 0.0)
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
            if (currentLine == null) {
                currentLine = pendingLines.pollFirst();
                if (currentLine != null) {
                    currentLine.begin(this);
                    // Kick off movement toward the end of the line for steady progress
                    int endIdx = Math.max(0, currentLine.cells.size() - 1);
                    Vec3d tgt = currentLine.pointAtIndex(endIdx);
                    double ty0 = computeGroundTargetY(tgt);
                    this.getNavigation().startMovingTo(tgt.x, ty0, tgt.z, 1.1);
                }
            }
            if (currentLine != null) {
                // Debug: log progress periodically while building
                if (this.age % 20 == 0) {
                    try {
                        int progCell = currentLine.progressCellIndex(this.getX(), this.getZ());
                        int cellsCount = currentLine.cells == null ? 0 : currentLine.cells.size();
                        int processedUnits = (currentLine.processed == null) ? 0 : currentLine.processed.cardinality();
                        int totalUnits = Math.max(0, currentLine.totalBits);
                        int endIdxDbg = Math.max(0, cellsCount - 1);
                        Vec3d endDbg = currentLine.pointAtIndex(endIdxDbg);
                        double dxDbg = this.getX() - endDbg.x;
                        double dzDbg = this.getZ() - endDbg.z;
                        double distEnd = Math.sqrt(dxDbg * dxDbg + dzDbg * dzDbg);
                        boolean nearEndDbg = distEnd <= 1.25;
                        int widthSnapDbg = Math.max(1, currentLine.widthSnapshot);
                        int boundCellDbg = (nearEndDbg || progCell >= (endIdxDbg - 1)) ? endIdxDbg : progCell;
                        int boundExclusive = Math.min(totalUnits, Math.max(0, (boundCellDbg + 1) * widthSnapDbg));
                        System.out.println("[GoldGolem] build entity=" + this.getId() +
                                " segCells=" + cellsCount +
                                " progCell=" + progCell +
                                " unitsProcessed=" + processedUnits + "/" + totalUnits +
                                " bound=" + boundExclusive +
                                " distToEnd=" + String.format(java.util.Locale.ROOT, "%.2f", distEnd));
                    } catch (Throwable t) {
                        // best-effort logging; ignore
                    }
                }
                // Placement paced by golem progress along the line
                if (placeCooldown > 0) {
                    placeCooldown--;
                } else {
                    int endIdxPl = Math.max(0, currentLine.cells.size() - 1);
                    int progressCell = currentLine.progressCellIndex(this.getX(), this.getZ());
                    Vec3d endPtPl = currentLine.pointAtIndex(endIdxPl);
                    double exPl = this.getX() - endPtPl.x;
                    double ezPl = this.getZ() - endPtPl.z;
                    boolean nearEndPl = (exPl * exPl + ezPl * ezPl) <= (1.25 * 1.25);
                    int boundCell = (nearEndPl || progressCell >= (endIdxPl - 1)) ? endIdxPl : progressCell;
                    int maxOps = nearEndPl ? 48 : 12; // place more aggressively near the end
                    currentLine.placePendingUpTo(this, boundCell, maxOps);
                    placeCooldown = 1; // throttle slightly
                }

                // Always path toward the end of the current segment to ensure we reach it
                int endIdx = Math.max(0, currentLine.cells.size() - 1);
                Vec3d end = currentLine.pointAtIndex(endIdx);
                double ty = computeGroundTargetY(end);
                this.getNavigation().startMovingTo(end.x, ty, end.z, 1.1);
                // Detect stuck navigation and recover by teleporting closer to the end
                double dx = this.getX() - end.x;
                double dz = this.getZ() - end.z;
                double distSq = dx * dx + dz * dz;
                if (this.getNavigation().isIdle() && distSq > 1.0) {
                    stuckTicks++;
                    if (stuckTicks >= 20) {
                        if (this.getEntityWorld() instanceof ServerWorld sw) {
                            sw.spawnParticles(ParticleTypes.PORTAL, this.getX(), this.getY() + 0.5, this.getZ(), 40, 0.5, 0.5, 0.5, 0.2);
                            sw.spawnParticles(ParticleTypes.PORTAL, end.x, ty + 0.5, end.z, 40, 0.5, 0.5, 0.5, 0.2);
                        }
                        this.refreshPositionAndAngles(end.x, ty, end.z, this.getYaw(), this.getPitch());
                        this.getNavigation().stop();
                        stuckTicks = 0;
                    }
                } else {
                    stuckTicks = 0;
                }

                // Complete the line only when all pending done AND we've effectively reached the end
                if (currentLine.isFullyProcessed()) {
                    if (distSq <= 0.75 * 0.75 || this.getNavigation().isIdle()) {
                        LineSeg done = currentLine;
                        LineSeg next = pendingLines.peekFirst();
                        if (next != null) placeCornerFill(done, next);
                        currentLine = null;
                    }
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
    public void setPathWidth(int width) {
        int w = Math.max(1, Math.min(9, width));
        // Snap to odd widths to keep a center column
        if ((w & 1) == 0) {
            w = (w < 9) ? (w + 1) : (w - 1);
        }
        this.pathWidth = w;
    }
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

    // Place a single offset column at the given center x/z for strip index j
    private void placeOffsetAt(double x, double y, double z, double px, double pz, int stripWidth, int j) {
        int w = Math.max(1, Math.min(9, stripWidth));
        var world = this.getEntityWorld();
        double ox = x + px * j;
        double oz = z + pz * j;
        int bx = MathHelper.floor(ox);
        int bz = MathHelper.floor(oz);
        int gIdx = sampleGradientIndex(w, j, bx, bz);
        if (gIdx < 0) return;
        String id = gradient[gIdx] == null ? "" : gradient[gIdx];
        if (id.isEmpty()) return;
        var ident = net.minecraft.util.Identifier.tryParse(id);
        if (ident == null) return;
        var block = net.minecraft.registry.Registries.BLOCK.get(ident);
        if (block == null) return;

        int y0 = MathHelper.floor(y);
        Integer groundY = null;
        for (int yy = y0 + 1; yy >= y0 - 6; yy--) {
            BlockPos test = new BlockPos(bx, yy, bz);
            var st = world.getBlockState(test);
            if (!st.isAir() && st.isFullCube(world, test)) { groundY = yy; break; }
        }
        if (groundY == null) return;
        LongOpenHashSet stripSeen = new LongOpenHashSet(3);
        for (int dy = -1; dy <= 1; dy++) {
            BlockPos rp = new BlockPos(bx, groundY + dy, bz);
            long key = rp.asLong();
            if (stripSeen.contains(key)) continue;
            stripSeen.add(key);
            var rs = world.getBlockState(rp);
            if (rs.isAir() || !rs.isFullCube(world, rp)) continue;
            if (rs.isOf(block)) continue;
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

        // Map based on distance from center: left GUI slot = center, right = edges (either side)
        int half = (stripWidth - 1) / 2;
        int dist = Math.abs(j);
        int denom = Math.max(1, half);
        double s = (double) dist / (double) denom * (double) (G - 1);

        int Wcap = Math.min(this.gradientWindow, G);
        double W = (double) Wcap;
        if (W == 0.0) {
            int idx = (int) Math.round(s);
            return MathHelper.clamp(idx, 0, G - 1);
        }

        // Use symmetric jitter per distance from center so both sides match
        double u01 = deterministic01(bx, bz, dist);
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
        // Pending placement state (initialized on begin)
        int widthSnapshot = 1;
        int half = 0;
        java.util.BitSet processed; // per (cellIndex * width + (j+half))
        int totalBits = 0;
        int scanBit = 0;
        LineSeg(Vec3d a, Vec3d b) {
            this.a = a;
            this.b = b;
            this.dirX = b.x - a.x;
            this.dirZ = b.z - a.z;
            this.cells = computeCells(BlockPos.ofFloored(a.x, 0, a.z), BlockPos.ofFloored(b.x, 0, b.z));
        }
        void begin(GoldGolemEntity golem) {
            this.widthSnapshot = Math.max(1, Math.min(9, golem.getPathWidth()));
            this.half = (widthSnapshot - 1) / 2;
            this.totalBits = Math.max(0, cells.size() * widthSnapshot);
            this.processed = new java.util.BitSet(totalBits);
            this.scanBit = 0;
        }
        boolean isFullyProcessed() {
            if (totalBits == 0) return true;
            int idx = processed.nextClearBit(0);
            return idx >= totalBits;
        }
        int progressCellIndex(double gx, double gz) {
            // Project golem XZ onto the AB vector to estimate progress along the line
            double ax = a.x, az = a.z;
            double vx = dirX, vz = dirZ;
            double denom = (vx * vx + vz * vz);
            double t = 0.0;
            if (denom > 1e-6) {
                double wx = gx - ax;
                double wz = gz - az;
                t = (wx * vx + wz * vz) / denom;
            }
            t = MathHelper.clamp(t, 0.0, 1.0);
            int n = Math.max(1, cells.size());
            return MathHelper.clamp((int) Math.floor(t * (n - 1)), 0, n - 1);
        }
        Vec3d pointAtIndex(int idx) {
            if (cells.isEmpty()) return b;
            int i = MathHelper.clamp(idx, 0, cells.size() - 1);
            BlockPos c = cells.get(i);
            double t = cells.size() <= 1 ? 1.0 : (double) i / (double) (cells.size() - 1);
            double y = MathHelper.lerp(t, a.y, b.y);
            return new Vec3d(c.getX() + 0.5, y, c.getZ() + 0.5);
        }
        void placePendingUpTo(GoldGolemEntity golem, int boundCell, int maxOps) {
            if (processed == null || totalBits == 0) return;
            int boundExclusive = Math.min(totalBits, Math.max(0, (boundCell + 1) * widthSnapshot));
            // Compute perpendicular
            double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
            double px = len > 1e-4 ? (-dirZ / len) : 0.0;
            double pz = len > 1e-4 ? ( dirX / len) : 0.0;
            int ops = 0;
            int bit = processed.nextClearBit(scanBit);
            while (ops < maxOps && bit >= 0 && bit < boundExclusive) {
                int cellIndex = bit / widthSnapshot;
                int jIndex = bit % widthSnapshot;
                int j = jIndex - half;
                BlockPos cell = cells.get(cellIndex);
                double t = cells.size() <= 1 ? 1.0 : (double) cellIndex / (double) (cells.size() - 1);
                double y = MathHelper.lerp(t, a.y, b.y);
                double x = cell.getX() + 0.5;
                double z = cell.getZ() + 0.5;
                golem.placeOffsetAt(x, y, z, px, pz, widthSnapshot, j);
                processed.set(bit); // mark attempted (placed or skipped) to avoid thrash
                ops++;
                bit = processed.nextClearBit(bit + 1);
            }
            scanBit = Math.min(Math.max(0, bit), totalBits);
        }
        int suggestFollowIndex(double gx, double gz, int lookAhead) {
            int prog = progressCellIndex(gx, gz);
            int idx = Math.min(Math.max(0, prog + Math.max(1, lookAhead)), Math.max(0, cells.size() - 1));
            return idx;
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

    @Override
    protected Vec3d getWanderTarget() {
        Vec3d base = super.getWanderTarget();

        // Anchor to a player: prefer owner; otherwise nearest player
        PlayerEntity anchor = getAnchorPlayer();
        if (anchor == null) return base;

        double cx = anchor.getX();
        double cy = anchor.getY();
        double cz = anchor.getZ();
        double max = 12.0;
        double maxSq = max * max;

        if (base == null) {
            // No base target; pick a random point within the radius around the player
            java.util.Random rnd = new java.util.Random(golem.getRandom().nextLong());
            double angle = rnd.nextDouble() * Math.PI * 2.0;
            double r = 6.0 + rnd.nextDouble() * 6.0; // 6..12
            return new Vec3d(cx + Math.cos(angle) * r, cy, cz + Math.sin(angle) * r);
        }

        double dx = base.x - cx;
        double dy = base.y - cy;
        double dz = base.z - cz;
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq <= maxSq) return base;

        double dist = Math.sqrt(distSq);
        if (dist < 1e-4) return new Vec3d(cx, cy, cz);
        double scale = max / dist;
        // Clamp to the 12-block sphere around the anchor player; keep base Y for smoother nav
        return new Vec3d(cx + dx * scale, base.y, cz + dz * scale);
    }

    private PlayerEntity getAnchorPlayer() {
        // Prefer the owner if present
        PlayerEntity owner = null;
        for (PlayerEntity p : golem.getEntityWorld().getPlayers()) {
            if (golem.isOwner(p)) { owner = p; break; }
        }
        if (owner != null) return owner;

        // Otherwise, use the nearest player
        PlayerEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (PlayerEntity p : golem.getEntityWorld().getPlayers()) {
            double d = golem.squaredDistanceTo(p);
            if (d < best) { best = d; nearest = p; }
        }
        return nearest;
    }
}
