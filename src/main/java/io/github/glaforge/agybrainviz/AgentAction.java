package io.github.glaforge.agybrainviz;

import dev.langchain4j.model.output.structured.Description;

public record AgentAction(
    @Description("Name of action. MAX 1 WORD.") String action,
    @Description("Short breakdown. MAX 1 SENTENCE. DO NOT REPEAT WORDS.") String description
) {}
