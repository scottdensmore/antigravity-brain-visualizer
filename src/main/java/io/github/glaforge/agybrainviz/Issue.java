package io.github.glaforge.agybrainviz;

import dev.langchain4j.model.output.structured.Description;

public record Issue(
    @Description("Short summary of the error. MAX 1 SENTENCE. DO NOT REPEAT WORDS.") String error,
    @Description("How it was fixed. MAX 1 SENTENCE. DO NOT REPEAT WORDS.") String circumvention
) {}
