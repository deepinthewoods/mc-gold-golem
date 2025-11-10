package ninja.trek.mc.goldgolem.client.model;

import org.junit.jupiter.api.Test;

import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class BBModelParserTest {

    @Test
    void parsesMeshElements() throws Exception {
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream("src/client/resources/assets/gold-golem/models/entity/goldgolem.bbmodel"),
                StandardCharsets.UTF_8)) {
            BBModelParser.BBModel model = BBModelParser.parse(reader);
            long meshCount = model.elements.stream().filter(element -> element.hasMeshFaces).count();
            assertTrue(meshCount > 0, "Expected mesh elements to be parsed");
        }
    }
}
