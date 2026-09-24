package com.pvpbot;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public interface GroupProvider {
    boolean groupExists(String groupName);

    Set<UUID> getGroupMembers(String groupName);

    Collection<String> getGroupNames();
}
