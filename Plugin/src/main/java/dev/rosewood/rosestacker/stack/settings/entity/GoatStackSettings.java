package dev.rosewood.rosestacker.stack.settings.entity;

import com.google.gson.JsonObject;
import dev.rosewood.rosegarden.config.CommentedFileConfiguration;
import dev.rosewood.rosestacker.stack.EntityStackComparisonResult;
import dev.rosewood.rosestacker.stack.StackedEntity;
import dev.rosewood.rosestacker.stack.settings.EntityStackSettings;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Goat;

public class GoatStackSettings extends EntityStackSettings {

    private final boolean dontStackIfScreaming;

    public GoatStackSettings(CommentedFileConfiguration entitySettingsFileConfiguration, JsonObject jsonObject) {
        super(entitySettingsFileConfiguration, jsonObject);

        this.dontStackIfScreaming = this.settingsConfiguration.getBoolean("dont-stack-if-screaming");
    }

    @Override
    protected EntityStackComparisonResult canStackWithInternal(StackedEntity stack1, StackedEntity stack2) {
        Goat goat1 = (Goat) stack1.getEntity();
        Goat goat2 = (Goat) stack2.getEntity();

        if (this.dontStackIfScreaming && (goat1.isScreaming() || goat2.isScreaming()))
            return EntityStackComparisonResult.SCREAMING;

        return EntityStackComparisonResult.CAN_STACK;
    }

    @Override
    protected void setDefaultsInternal() {
        this.setIfNotExists("dont-stack-if-screaming", false);
    }

    @Override
    public EntityType getEntityType() {
        return EntityType.GOAT;
    }

}
