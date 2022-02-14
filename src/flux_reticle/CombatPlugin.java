package flux_reticle;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.util.Misc;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Cursor;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.util.vector.Vector2f;

import java.awt.*;
import java.io.IOException;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL11.glPopAttrib;

public class CombatPlugin implements EveryFrameCombatPlugin {
    public static final String ID = "sun_flux_reticle",
            SETTINGS_PATH = "FLUX_RETICLE_OPTIONS.ini",
            COMMON_DATA_PATH = "sun_fr/auto_turn_choices.json";

    static final float
            MAX_LENGTH = 80,
            MIN_LENGTH = 20,
            MAX_OPACITY = 2,
            MIN_OPACITY = 0,
            DISTANCE_FULL = 1.0f,
            DISTANCE_HIDE = 0.1f,
            BAR_WIDTH = 7f;
    static final int
            ESCAPE_KEY_VALUE = 1;
    static org.lwjgl.input.Cursor hiddenCursor, originalCursor;
    static boolean cursorNeedsReset = true, wasAutoTurnModePriorToActivation = false;

    float scale = 1f, damageFlash = 0, fluxLastFrame = 0;
    int toggleStrafeAndTurnToCursorKey = 37, glowOpacity = 64;
    SpriteAPI frontKeyTurn, frontMouseTurn, back, half, quarter, hardBar, glowKeyTurn, glowMouseTurn;
    CombatEngineAPI engine;
    boolean escapeMenuIsOpen = false, needToLoadSettings = true, showReticle, showReticleWhenInterfaceIsHidden;
    Vector2f mouse = new Vector2f(), at = new Vector2f(), normal = new Vector2f();
    Color reticleColor = Misc.getPositiveHighlightColor(),
            gaugeColor = Misc.getHighlightColor(),
            barColor = Misc.getNegativeHighlightColor(),
            warnColor = Color.WHITE,
            gaugeBackgroundColor = Color.BLACK;
    ViewportAPI viewport;
    JSONObject commonData;
    String prevHullId = "";

    static void resetCursor() {
        try {
            if (originalCursor == null) originalCursor = Mouse.getNativeCursor();

            if(cursorNeedsReset) {
                cursorNeedsReset = false;
                Mouse.setNativeCursor(originalCursor);
                Global.getSettings().setAutoTurnMode(wasAutoTurnModePriorToActivation);
            }
        } catch (Exception e) {
            reportCrash(e);
        }
    }

    public static boolean reportCrash(Exception exception) {
        try {
            String stackTrace = "", message = "Flux reticle encountered an error!\nPlease let the mod author know.";

            for(int i = 0; i < exception.getStackTrace().length; i++) {
                StackTraceElement ste = exception.getStackTrace()[i];
                stackTrace += "    " + ste.toString() + System.lineSeparator();
            }

            Global.getLogger(CombatPlugin.class).error(exception.getMessage() + System.lineSeparator() + stackTrace);

            if (Global.getCombatEngine() != null && Global.getCurrentState() == GameState.COMBAT) {
                Global.getCombatEngine().getCombatUI().addMessage(2, Color.RED, message);
                Global.getCombatEngine().getCombatUI().addMessage(1, Color.ORANGE, exception.getMessage());
            } else if (Global.getSector() != null) {
                CampaignUIAPI ui = Global.getSector().getCampaignUI();

                ui.addMessage(message, Color.RED);
                ui.addMessage(exception.getMessage(), Color.ORANGE);
                ui.showConfirmDialog(message + "\n\n" + exception.getMessage(), "Ok", null, null, null);

                if(ui.getCurrentInteractionDialog() != null) ui.getCurrentInteractionDialog().dismiss();
            } else return false;

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String getFlagshipHullId() {
        return engine.getPlayerShip().getHullSpec().getBaseHullId();
    }
    void drawGauge(float length, float level, Color c, float opacity, float colorLerp) {
        Vector2f m = new Vector2f(mouse.x, mouse.y);

        normal.normalise(normal);
        Vector2f perp = new Vector2f(normal.y, -normal.x);

        c = Misc.interpolateColor(c, warnColor, colorLerp);

        Vector2f.add(m, (Vector2f) normal.scale(length), m);
        m.translate(perp.x * BAR_WIDTH * 0.5f * scale, perp.y * BAR_WIDTH * 0.5f * scale);

        glPushAttrib(GL_ALL_ATTRIB_BITS);
        glDisable(GL_TEXTURE_2D);
        glEnable(GL_BLEND);

        glEnable(GL_LINE_SMOOTH);
        glEnable(GL_POLYGON_SMOOTH);
        glHint(GL_LINE_SMOOTH_HINT, GL_NICEST);
        glHint(GL_POLYGON_SMOOTH_HINT, GL_NICEST);

        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glTranslatef(0.01f, 0.01f, 0);
        glBegin(GL_QUADS);
        {
            glColor4f(c.getRed()/255f, c.getGreen()/255f, c.getBlue()/255f, c.getAlpha()/255f * opacity);

            glVertex2f(m.x, m.y);
            Vector2f.sub(m, (Vector2f) perp.scale(BAR_WIDTH * scale), m);
            glVertex2f(m.x, m.y);
            Vector2f.sub(m, (Vector2f) normal.normalise().scale(length * level), m);
            glVertex2f(m.x, m.y);
            Vector2f.add(m, perp, m);
            glVertex2f(m.x, m.y);
        }
        glEnd();
        glDisable(GL_BLEND);
        glPopAttrib();

        glColor4f(1, 1, 1, 1);
    }
    boolean isAutoTurnModeForCurrentFlagshipClass()  throws IOException, JSONException{
        if(engine != null && engine.getPlayerShip() != null) {
            return commonData.has(getFlagshipHullId())
                    ? commonData.getBoolean(getFlagshipHullId())
                    : wasAutoTurnModePriorToActivation;
        }

        return false;
    }
    boolean isNotInProperToggleState() {
        return viewport == null || engine == null || engine.isUIShowingDialog() || engine.getCombatUI() == null
                || engine.getCombatUI().isShowingCommandUI()
                || escapeMenuIsOpen || needToLoadSettings || engine.getPlayerShip() == null
                || engine.getPlayerShip().getLocation() == null || Global.getCurrentState() != GameState.COMBAT
                || engine.isCombatOver() || engine.getPlayerShip().isShuttlePod();
    }
    void setAutoTurnModeForCurrentFlagshipClass(boolean useStrafeMode) throws IOException, JSONException {
        if(engine != null && engine.getPlayerShip() != null) {
            commonData.put(getFlagshipHullId(), useStrafeMode);
            Global.getSettings().writeTextFileToCommon(COMMON_DATA_PATH, commonData.toString());
        }
    }
    Color getColor(JSONArray c) throws JSONException {
        return new Color(
                Math.min(255, Math.max(0, c.getInt(0))),
                Math.min(255, Math.max(0, c.getInt(1))),
                Math.min(255, Math.max(0, c.getInt(2))),
                Math.min(255, Math.max(0, c.getInt(3)))
        );
    }
    Color getColor(Color c, float alphaMult) {
        return new Color(
                Math.min(1, Math.max(0, c.getRed() / 255f)),
                Math.min(1, Math.max(0, c.getGreen() / 255f)),
                Math.min(1, Math.max(0, c.getBlue() / 255f)),
                Math.min(1, Math.max(0, (c.getAlpha() / 255f) * alphaMult))
        );
    }

    @Override
    public void init(CombatEngineAPI engine) {
        try {
            this.engine = engine;

            resetCursor();

            frontKeyTurn = Global.getSettings().getSprite("sun_fr", "frontKeyTurn");
            frontMouseTurn = Global.getSettings().getSprite("sun_fr", "frontMouseTurn");
            glowKeyTurn = Global.getSettings().getSprite("sun_fr", "glowKeyTurn");
            glowMouseTurn = Global.getSettings().getSprite("sun_fr", "glowMouseTurn");
            back = Global.getSettings().getSprite("sun_fr", "back");
            half = Global.getSettings().getSprite("sun_fr", "half");
            quarter = Global.getSettings().getSprite("sun_fr", "quarter");
            hardBar = Global.getSettings().getSprite("sun_fr", "hardBar");


            try {
                commonData = new JSONObject(Global.getSettings().readTextFileFromCommon(COMMON_DATA_PATH));
            } catch (JSONException e) {
                Global.getSettings().writeTextFileToCommon(COMMON_DATA_PATH, "{}");
                commonData = new JSONObject(Global.getSettings().readTextFileFromCommon(COMMON_DATA_PATH));
            }
        } catch (Exception e) { reportCrash(e); }
    }

    @Override
    public void processInputPreCoreControls(float amount, List<InputEventAPI> events) {
        try {
            if (engine == null) return;

            if (!engine.isUIShowingDialog()) escapeMenuIsOpen = false;

            for (InputEventAPI e : events) {
                if (e.isConsumed() || !e.isKeyDownEvent()) continue;

                if (e.getEventValue() == ESCAPE_KEY_VALUE) {
                    escapeMenuIsOpen = true;
                } else if (e.getEventValue() == toggleStrafeAndTurnToCursorKey && !engine.isUIShowingDialog()) {
                    boolean isAutoTurnMode = !Global.getSettings().isAutoTurnMode();
                    Global.getSettings().setAutoTurnMode(isAutoTurnMode);
                    setAutoTurnModeForCurrentFlagshipClass(isAutoTurnMode);
                    Global.getSoundPlayer().playUISound(Global.getSettings().isAutoTurnMode()
                            ? "sun_fr_turn_to_cursor_on"
                            : "sun_fr_turn_to_cursor_off", 1, 1);
                }
            }
        } catch (Exception e) { reportCrash(e); }
    }

    @Override
    public void renderInUICoords(ViewportAPI viewport) {
        try {
            this.viewport = viewport;
        } catch (Exception e) { reportCrash(e); }
    }

    @Override
    public void renderInWorldCoords(ViewportAPI viewport) { }

    @Override
    public void advance(float amount, java.util.List<InputEventAPI> events) {
        try {
            if(engine == null) return;

            if(needToLoadSettings) {
                JSONObject cfg = Global.getSettings().getMergedJSONForMod(SETTINGS_PATH, ID);
                boolean overrideColors = cfg.getBoolean("overrideDefaultUiColors");

                showReticle = cfg.getBoolean("showReticle");
                showReticleWhenInterfaceIsHidden = cfg.getBoolean("showReticleWhenInterfaceIsHidden");
                glowOpacity = cfg.getInt("glowOpacity");

                scale = (float) cfg.getDouble("sizeMult");
                toggleStrafeAndTurnToCursorKey = cfg.getInt("toggleStrafeAndTurnToCursorKey");
                warnColor = getColor(cfg.getJSONArray("warningColor"));
                gaugeBackgroundColor = getColor(cfg.getJSONArray("gaugeBackgroundColor"));

                if(overrideColors) {
                    reticleColor = getColor(cfg.getJSONArray("reticleColor"));
                    gaugeColor = getColor(cfg.getJSONArray("softFluxGaugeColor"));
                    barColor = getColor(cfg.getJSONArray("hardFluxBarColor"));
                } else {
                    reticleColor = getColor(Global.getSettings().getColor("textFriendColor"), 1);
                    gaugeColor = getColor(reticleColor, 0.5f);
                    barColor = Misc.interpolateColor(reticleColor, Color.WHITE, 0.5f);
                }

                frontKeyTurn.setSize(frontKeyTurn.getWidth() * scale, frontKeyTurn.getHeight() * scale);
                frontMouseTurn.setSize(frontMouseTurn.getWidth() * scale, frontMouseTurn.getHeight() * scale);
                back.setSize(back.getWidth() * scale, back.getHeight() * scale);
                half.setSize(half.getWidth() * scale, half.getHeight() * scale);
                quarter.setSize(quarter.getWidth() * scale, quarter.getHeight() * scale);
                hardBar.setSize(hardBar.getWidth() * scale, hardBar.getHeight() * scale);

                needToLoadSettings = false;
            }

            if(isNotInProperToggleState()) {
                resetCursor();
            } else {
                if(!cursorNeedsReset) {
                    wasAutoTurnModePriorToActivation = Global.getSettings().isAutoTurnMode();
                    cursorNeedsReset = true;
                }

                if(!cursorNeedsReset || !prevHullId.equals(getFlagshipHullId())) {
                    Global.getSettings().setAutoTurnMode(isAutoTurnModeForCurrentFlagshipClass());
                    prevHullId = getFlagshipHullId();
                }

                if(!showReticle) {
                    return;
                } else if(!engine.isUIShowingHUD() && !showReticleWhenInterfaceIsHidden) {
                    resetCursor();

                    return;
                }

                if(hiddenCursor == null) hiddenCursor = new Cursor(1, 1, 0, 0, 1, BufferUtils.createIntBuffer(1), null);

                mouse.set(Global.getSettings().getMouseX(), Global.getSettings().getMouseY());
                at.set(engine.getPlayerShip().getLocation());
                at.x = viewport.convertWorldXtoScreenX(at.x);
                at.y = viewport.convertWorldYtoScreenY(at.y);
                Vector2f.sub(at, mouse, normal);

                float f = Misc.getDistance(mouse, at) / viewport.getVisibleHeight() * 2;
                f -= DISTANCE_HIDE;
                f = Math.max(0, Math.min(1, f / (DISTANCE_FULL - DISTANCE_HIDE) * viewport.getViewMult()));

                float soft = engine.getPlayerShip().getFluxLevel();
                float opacity = Math.max(MIN_OPACITY, Math.min(1, f * MAX_OPACITY));
                float hard = engine.getPlayerShip().getHardFluxLevel();
                float length = (MIN_LENGTH + f * (MAX_LENGTH - MIN_LENGTH)) * scale;
                float aimAngle = Misc.getAngleInDegrees(at, mouse);
                float warnness = (0.5f * (1 + (float)Math.sin(engine.getTotalElapsedTime(true) * 12)))
                        * Math.max(0, soft - 0.8f) * 3f;
                Color clr = new Color(reticleColor.getRGB());
                Color glowClr = new Color(clr.getRed(), clr.getGreen(), clr.getBlue(), glowOpacity);
                SpriteAPI front, glow;

                damageFlash = Math.max(0, Math.min(1, damageFlash - amount * 1 + (soft - fluxLastFrame) * 5));
                fluxLastFrame = soft;
                warnness = Math.max(0, Math.min(1, warnness + damageFlash));

                clr = Misc.interpolateColor(clr, warnColor, warnness);

                if(Global.getSettings().isAutoTurnMode() ^ org.lwjgl.input.Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)) {
                    front = frontMouseTurn;
                    glow = glowMouseTurn;
                } else {
                    front = frontKeyTurn;
                    glow = glowKeyTurn;
                }

                glPushMatrix();
                glLoadIdentity();
                glOrtho(0, Global.getSettings().getScreenWidth(), 0, Global.getSettings().getScreenHeight(), -1, 1);

                glBlendFunc(GL_ONE, GL_ONE);

                glow.setColor(glowClr);
                glow.setAngle(aimAngle);
                glow.renderAtCenter(mouse.x, mouse.y);

                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

                if(opacity > 0) drawGauge(length, 1, gaugeBackgroundColor, opacity, 0);

                front.setColor(clr);
                front.setAngle(aimAngle);
                front.renderAtCenter(mouse.x, mouse.y);

                Mouse.setNativeCursor(hiddenCursor);

                if(opacity > 0) {
                    clr = new Color(clr.getRed(), clr.getGreen(), clr.getBlue(), (int) Math.min(255, clr.getAlpha() * opacity));

                    normal.normalise().scale(length * 0.25f);
                    quarter.setColor(clr);
                    quarter.setAngle(aimAngle);
                    quarter.renderAtCenter(normal.x + mouse.x, normal.y + mouse.y);

                    normal.normalise().scale(length * 0.5f);
                    half.setColor(clr);
                    half.setAngle(aimAngle);
                    half.renderAtCenter(normal.x + mouse.x, normal.y + mouse.y);

                    normal.normalise().scale(length * 0.75f);
                    quarter.setColor(clr);
                    quarter.setAngle(aimAngle);
                    quarter.renderAtCenter(normal.x + mouse.x, normal.y + mouse.y);

                    normal.normalise().scale(length);
                    back.setColor(clr);
                    back.setAngle(aimAngle);
                    back.renderAtCenter(normal.x + mouse.x, normal.y + mouse.y);

                    clr = new Color(barColor.getRed(), barColor.getGreen(), barColor.getBlue(),
                            (int) Math.max(0, Math.min(255, barColor.getAlpha() * opacity * Math.min(1f, hard * 10f))));
                    clr = Misc.interpolateColor(clr, warnColor, warnness);

                    normal.normalise().scale(length * (1f - hard));
                    hardBar.setColor(clr);
                    hardBar.setAngle(aimAngle);
                    hardBar.renderAtCenter(normal.x + mouse.x, normal.y + mouse.y);

                    drawGauge(length, soft, gaugeColor, opacity, warnness);
                }

                glPopMatrix();
            }
        } catch (Exception e) {
            needToLoadSettings = !reportCrash(e);
        }
    }
}
