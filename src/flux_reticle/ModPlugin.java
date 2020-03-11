package flux_reticle;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ModSpecAPI;

public class ModPlugin extends BaseModPlugin {
    static CampaignPlugin script;


    @Override
    public void onGameLoad(boolean newGame) {
        Global.getSector().addTransientScript(script = new CampaignPlugin());
    }

    @Override
    public void beforeGameSave() {
        Global.getSector().removeScript(script);
    }

    @Override
    public void afterGameSave() {
        Global.getSector().addTransientScript(script = new CampaignPlugin());
    }


}
