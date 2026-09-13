package ninja.trek.mc.goldgolem.world.entity;

import net.minecraft.world.entity.ai.attributes.Attributes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldGolemEntityAttributesTest {
    @Test
    void inheritsVanillaMovementAttributesAndKeepsCustomValues() {
        var attributes = GoldGolemEntity.createAttributes().build();

        assertTrue(attributes.hasAttribute(Attributes.AIR_DRAG_MODIFIER));
        assertTrue(attributes.hasAttribute(Attributes.FRICTION_MODIFIER));
        assertEquals(1.0, attributes.getBaseValue(Attributes.AIR_DRAG_MODIFIER), 0.0001);
        assertEquals(0.28, attributes.getBaseValue(Attributes.MOVEMENT_SPEED), 0.0001);
        assertEquals(32.0, attributes.getBaseValue(Attributes.FOLLOW_RANGE), 0.0001);
    }
}
