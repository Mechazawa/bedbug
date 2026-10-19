package at.ioexception.mcaddon.client.integration;

//? if >=26 {
import at.ioexception.mcaddon.client.screen.AddonOptionsScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Opens the add-on options from Mod Menu's list of mods. */
public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return AddonOptionsScreen::new;
    }
}
//?}
