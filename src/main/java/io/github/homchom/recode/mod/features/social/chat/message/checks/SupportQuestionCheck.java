package io.github.homchom.recode.mod.features.social.chat.message.checks;

import io.github.homchom.recode.mod.features.social.chat.message.*;
import io.github.homchom.recode.mod.features.streamer.*;

public class SupportQuestionCheck extends MessageCheck implements StreamerModeMessageCheck {

    private static final String SUPPORT_QUESTION_REGEX = "^.*» Support Question: \\(Click to answer\\)\\nAsked by \\w+ \\[[a-zA-Z]+]\\n.+$";

    @Override
    public MessageType getType() {
        return MessageType.SUPPORT_QUESTION;
    }

    @Override
    public boolean check(LegacyMessage message, String stripped) {
        return stripped.matches(SUPPORT_QUESTION_REGEX);
    }

    @Override
    public void onReceive(LegacyMessage message) {

    }

    @Override
    public boolean streamerHideEnabled() {
        return StreamerModeHandler.hideSupport();
    }
}
