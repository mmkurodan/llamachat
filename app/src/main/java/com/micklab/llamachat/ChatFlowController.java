package com.micklab.llamachat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ChatFlowController {
    private final ExpertSelector expertSelector;
    private final WebSearchExpertHandler webSearchExpertHandler;

    public ChatFlowController(ExpertSelector expertSelector, WebSearchExpertHandler webSearchExpertHandler) {
        this.expertSelector = expertSelector;
        this.webSearchExpertHandler = webSearchExpertHandler;
    }

    public ChatFlowResult route(String userInput, boolean webAvailable, boolean calendarAvailable) {
        List<ExpertType> expertTypes = expertSelector.selectAll(userInput, webAvailable, calendarAvailable);
        if (expertTypes.isEmpty()) {
            return new ChatFlowResult(Collections.emptyList());
        }
        List<ChatFlowStep> steps = new ArrayList<>();
        for (ExpertType expertType : expertTypes) {
            String webSearchQuery = expertType == ExpertType.WEB
                    ? webSearchExpertHandler.buildSearchQuery(userInput, expertType)
                    : null;
            steps.add(new ChatFlowStep(expertType, webSearchQuery));
        }
        return new ChatFlowResult(steps);
    }

    public static final class ChatFlowStep {
        private final ExpertType expertType;
        private final String webSearchQuery;

        private ChatFlowStep(ExpertType expertType, String webSearchQuery) {
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

    public static final class ChatFlowResult {
        private final List<ChatFlowStep> steps;

        private ChatFlowResult(List<ChatFlowStep> steps) {
            this.steps = steps == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(steps));
        }

        public ExpertType getExpertType() {
            return steps.isEmpty() ? ExpertType.NONE : steps.get(0).getExpertType();
        }

        public String getWebSearchQuery() {
            for (ChatFlowStep step : steps) {
                if (step.getExpertType() == ExpertType.WEB) {
                    return step.getWebSearchQuery();
                }
            }
            return null;
        }

        public List<ChatFlowStep> getSteps() {
            return steps;
        }
    }
}
