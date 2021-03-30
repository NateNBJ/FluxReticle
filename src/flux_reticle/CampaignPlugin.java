package flux_reticle;

import com.fs.starfarer.api.EveryFrameScript;

public class CampaignPlugin implements EveryFrameScript {
    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public boolean runWhilePaused() {
        return true;
    }

    @Override
    public void advance(float amount) {
        try {
            CombatPlugin.resetCursor();
        } catch (Exception e) {
            CombatPlugin.reportCrash(e);
        }
    }
}
