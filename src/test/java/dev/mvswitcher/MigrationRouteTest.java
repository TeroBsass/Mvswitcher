package dev.mvswitcher;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MigrationRouteTest {
    private MigrationEngine.MigrationRule edge(String from, String to) {
        var rule = new MigrationEngine.MigrationRule();
        rule.id = from + "-to-" + to;
        rule.from = from;
        rule.to = to;
        rule.find = "old";
        rule.replace = "new";
        return rule;
    }

    @Test void findsMultiHopRouteInBothDirectionsWhenEdgesExist() {
        var forward = List.of(edge("1.21.1", "1.21.11"), edge("1.21.11", "26.3"));
        var path = MigrationEngine.findRoute(forward, "1.21.1", "26.3");
        assertEquals(2, path.size());
        assertEquals("26.3", path.get(1).to);

        var reverse = List.of(edge("26.3", "1.21.11"), edge("1.21.11", "1.21.1"));
        assertEquals(2, MigrationEngine.findRoute(reverse, "26.3", "1.21.1").size());
    }

    @Test void returnsEmptyWhenNoRouteExists() {
        assertTrue(MigrationEngine.findRoute(List.of(edge("1.21.1", "1.21.11")),
                "1.21.1", "26.3").isEmpty());
    }
}
