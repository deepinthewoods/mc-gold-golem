package ninja.trek.mc.goldgolem.client.screen.layout;

import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.client.screen.GolemHandledScreen;
import ninja.trek.mc.goldgolem.client.screen.GroupModeStrategy;
import ninja.trek.mc.goldgolem.client.screen.layout.sections.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Factory for creating GUI sections based on build mode.
 * Each mode gets a specific combination of sections.
 */
public class SectionFactory {
    /**
     * Result of section creation, containing sections and references to specific section types.
     */
    public static class SectionConfiguration {
        public final List<GuiSection> sections;
        public final GradientsSection gradientsSection;
        public final GroupModeSection groupModeSection;
        public final SettingsSection settingsSection;
        public final InventoriesSection inventoriesSection;

        public SectionConfiguration(List<GuiSection> sections,
                                     GradientsSection gradientsSection,
                                     GroupModeSection groupModeSection,
                                     SettingsSection settingsSection,
                                     InventoriesSection inventoriesSection) {
            this.sections = sections;
            this.gradientsSection = gradientsSection;
            this.groupModeSection = groupModeSection;
            this.settingsSection = settingsSection;
            this.inventoriesSection = inventoriesSection;
        }
    }

    /**
     * Create the appropriate sections for the given build mode.
     *
     * Per-mode configuration:
     * - PATH/GRADIENT: GradientsSection(2 rows) + SettingsSection(width slider) + Inventories
     * - TERRAFORMING: GradientsSection(3 rows) + SettingsSection(radius slider) + Inventories
     * - EXCAVATION: SettingsSection(height/depth sliders, ore button) + Inventories
     * - MINING: SettingsSection(ore button) + Inventories
     * - WALL/TOWER/TREE: GroupModeSection + SettingsSection(mode-specific) + Inventories
     *
     * @param mode Current build mode
     * @param golemRows Number of rows in golem inventory
     * @param screen Parent screen (for widget callbacks and state access)
     * @return SectionConfiguration with all sections
     */
    public static SectionConfiguration createSectionsForMode(BuildMode mode, int golemRows, GolemHandledScreen screen) {
        List<GuiSection> sections = new ArrayList<>();
        GradientsSection gradientsSection = null;
        GroupModeSection groupModeSection = null;
        SettingsSection settingsSection = null;

        // All modes have an inventories section at the end
        InventoriesSection inventories = new InventoriesSection(
                golemRows,
                screen.getTextRenderer(),
                screen.getPlayerInventoryTitle());

        switch (mode) {
            case PATH:
            case GRADIENT:
                // GradientsSection for PATH mode (2 rows)
                gradientsSection = new GradientsSection(
                        GradientsSection.GradientMode.PATH,
                        screen,
                        screen.getTextRenderer());
                sections.add(gradientsSection);

                // SettingsSection for width slider (will be populated during init)
                settingsSection = new SettingsSection();
                sections.add(settingsSection);

                sections.add(inventories);
                break;

            case TERRAFORMING:
                // GradientsSection for TERRAFORMING (3 rows)
                gradientsSection = new GradientsSection(
                        GradientsSection.GradientMode.TERRAFORMING,
                        screen,
                        screen.getTextRenderer());
                sections.add(gradientsSection);

                // SettingsSection for radius slider (will be populated during init)
                settingsSection = new SettingsSection();
                sections.add(settingsSection);

                sections.add(inventories);
                break;

            case EXCAVATION:
                // SettingsSection for height/depth sliders + ore button
                settingsSection = new SettingsSection();
                sections.add(settingsSection);

                sections.add(inventories);
                break;

            case MINING:
                // SettingsSection for ore button
                settingsSection = new SettingsSection();
                sections.add(settingsSection);

                sections.add(inventories);
                break;

            case WALL:
            case TOWER:
            case TREE:
                // GroupModeSection (paginable)
                GroupModeStrategy strategy = screen.getGroupModeStrategy();
                if (strategy != null) {
                    groupModeSection = new GroupModeSection(strategy, screen.getTextRenderer());
                    sections.add(groupModeSection);
                }

                // SettingsSection (mode-specific - will be populated during init)
                settingsSection = new SettingsSection();
                sections.add(settingsSection);

                sections.add(inventories);
                break;

            default:
                // Fallback: just inventories
                sections.add(inventories);
                break;
        }

        return new SectionConfiguration(sections, gradientsSection, groupModeSection, settingsSection, inventories);
    }
}

