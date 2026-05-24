package com.micklab.llamachat;

public final class ChatFlowController {
    private final ExpertSelector expertSelector;
    private final WebSearchExpertHandler webSearchExpertHandler;

    public ChatFlowController(ExpertSelector expertSelector, WebSearchExpertHandler webSearchExpertHandler) {
        this.expertSelector = expertSelector;
        this.webSearchExpertHandler = webSearchExpertHandler;
    }

    public ChatFlowResult route(String userInput, boolean webAvailable, boolean calendarAvailable) {
        ExpertType expertType = expertSelector.select(userInput, webAvailable, calendarAvailable);
        if (expertType == ExpertType.WEB) {
            return new ChatFlowResult(expertType, webSearchExpertHandler.buildSearchQuery(userInput, expertType));
        }
        return new ChatFlowResult(expertType, null);
    }

    public static final class ChatFlowResult {
        private final ExpertType expertType;
        private final String webSearchQuery;

        private ChatFlowResult(ExpertType expertType, String webSearchQuery) {
            this.expertType = expertType == null ? ExpertType.NONE : expertType;
            this.webSearchQuery = webSearchQuery;
        }

        public ExpertType getExpertType() {
            return expertType;
        }

        public String getWebSearchQuery() {
            return webSearchQuery;
        }
    }
}
