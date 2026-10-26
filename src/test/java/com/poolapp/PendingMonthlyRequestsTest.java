package com.poolapp;

import com.poolapp.ui.PendingMonthlyRequests;
import org.junit.Test;
import java.util.UUID;
import java.util.prefs.Preferences;
import static org.junit.Assert.*;

public class PendingMonthlyRequestsTest {
    @Test public void preservesUncertainRequestAcrossReopenAndSeparatesScopes() throws Exception {
        Preferences preferences = Preferences.userNodeForPackage(PendingMonthlyRequestsTest.class)
                .node("test-" + UUID.randomUUID());
        try {
            PendingMonthlyRequests first = new PendingMonthlyRequests(preferences);
            String id = first.retain("company|employee", "10/31/2026|Bill|c1,c2", null);
            PendingMonthlyRequests reopened = new PendingMonthlyRequests(preferences);
            assertEquals(id, reopened.find("company|employee", "10/31/2026|Bill|c1,c2"));
            assertEquals(id, reopened.retain("company|employee", "10/31/2026|Bill|c1,c2", UUID.randomUUID().toString()));
            assertNull(reopened.find("another-company|employee", "10/31/2026|Bill|c1,c2"));
            assertNull(reopened.find("company|employee", "11/30/2026|Bill|c1,c2"));
            assertNull(reopened.find("company|employee", "10/31/2026|Bill|c1"));
            reopened.confirmed("company|employee", "10/31/2026|Bill|c1,c2");
            assertNull(first.find("company|employee", "10/31/2026|Bill|c1,c2"));
            assertNotEquals(id, reopened.retain("company|employee", "10/31/2026|Bill|c1,c2", null));
        } finally { preferences.removeNode(); preferences.flush(); }
    }
}
