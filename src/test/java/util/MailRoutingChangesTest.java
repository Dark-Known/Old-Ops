package util;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class MailRoutingChangesTest {

    private static List<MailRoutingRule> rules(String... keyFolder) {
        List<MailRoutingRule> l = new ArrayList<>();
        for (int i = 0; i < keyFolder.length; i += 2) l.add(new MailRoutingRule(keyFolder[i], keyFolder[i + 1]));
        return l;
    }

    @Test public void upsertAddsANewKey() {
        List<MailRoutingRule> l = rules("LDM", "Inbox/LDM");
        MailRoutingChanges.upsert(l, "PTM", "Inbox/PTM");
        assertEquals(2, l.size());
        assertEquals("PTM", l.get(1).getKey());
    }

    @Test public void upsertReplacesTheSameKeyIgnoringCase() {
        List<MailRoutingRule> l = rules("LDM", "Old", "PTM", "P");
        MailRoutingChanges.upsert(l, "ldm", "New");
        assertEquals(2, l.size());
        MailRoutingRule ldm = l.stream().filter(r -> r.getKey().equalsIgnoreCase("ldm")).findFirst().orElseThrow();
        assertEquals("New", ldm.getFolder());
        assertEquals("the replaced rule uses the newly typed key", "ldm", ldm.getKey());
    }

    @Test public void describeSaysWhatWasAddedChangedAndRemoved() {
        String d = MailRoutingChanges.describe(rules("A", "1", "B", "2", "C", "3"), rules("A", "1", "B", "9", "D", "4"));
        assertTrue(d, d.contains("\"B\": \"2\" → \"9\""));
        assertTrue(d, d.contains("added \"D\" → \"4\""));
        assertTrue(d, d.contains("removed \"C\" (was → \"3\")"));
        assertFalse(d, d.contains("\"A\""));
    }

    @Test public void describeMatchesKeysIgnoringCase() {
        assertEquals("no changes (re-saved as-is)",
                MailRoutingChanges.describe(rules("Ldm", "x"), rules("LDM", "x")));
    }

    @Test public void describeOfIdenticalListsIsNoChange() {
        assertEquals("no changes (re-saved as-is)", MailRoutingChanges.describe(rules("A", "1"), rules("A", "1")));
        assertEquals("no changes (re-saved as-is)", MailRoutingChanges.describe(rules(), rules()));
    }

    @Test public void changesAreSeparatedBySemicolons() {
        String d = MailRoutingChanges.describe(rules(), rules("A", "1", "B", "2"));
        assertEquals("added \"A\" → \"1\"; added \"B\" → \"2\"", d);
    }
}
