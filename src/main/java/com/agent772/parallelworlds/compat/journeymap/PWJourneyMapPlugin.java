package com.agent772.parallelworlds.compat.journeymap;

import com.agent772.parallelworlds.ParallelWorlds;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.common.JourneyMapPlugin;

/**
 * Minimal JourneyMap plugin stub that registers Parallel Worlds as a valid
 * JourneyMap API caller. JourneyMap's ClientAPI requires every modId that
 * calls addWaypoint/removeWaypoint/etc. to be registered in its plugin map.
 * <p>
 * This class is discovered by JourneyMap's internal plugin scanner via the
 * {@link JourneyMapPlugin} annotation. The actual waypoint work is delegated
 * to {@link JourneyMapPortalWaypointHandler}.
 */
@JourneyMapPlugin(apiVersion = "2.0.0")
public class PWJourneyMapPlugin implements IClientPlugin {

    @Override
    public String getModId() {
        return ParallelWorlds.MOD_ID;
    }

    @Override
    public void initialize(IClientAPI api) {
        JourneyMapPortalWaypointHandler.onApiReady(api);
    }
}
