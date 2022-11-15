package io.github.homchom.recode.mod.features.social.chat.message.finalizers;

import io.github.homchom.recode.mod.features.social.chat.message.*;
import io.github.homchom.recode.mod.features.social.chat.message.checks.DirectMessageCheck;
import io.github.homchom.recode.mod.features.streamer.StreamerModeMessageCheck;

public class StreamerModeFinalizer extends MessageFinalizer {

    private static final String[] HIDE_DMS_EXEMPTIONS = new String[]{
            "RyanLand",
            "Vattendroppen236",
            "Reasonless"
    };

    @Override
    protected void receive(Message message) {
        MessageCheck check = message.getCheck();

        if (
                check instanceof StreamerModeMessageCheck
                && ((StreamerModeMessageCheck) check).streamerHideEnabled()
                && !matchesDirectMessageExemptions(message)
        ) {
            message.cancel();
        }
    }

    private static boolean matchesDirectMessageExemptions(Message message) {
        if (message.typeIs(MessageType.DIRECT_MESSAGE)) {
            String stripped = message.getStripped();

            for (String username : HIDE_DMS_EXEMPTIONS) {
                if (DirectMessageCheck.usernameMatches(message, username)) {
                    return true;
                }
            }
        }
        return false;
    }
}
