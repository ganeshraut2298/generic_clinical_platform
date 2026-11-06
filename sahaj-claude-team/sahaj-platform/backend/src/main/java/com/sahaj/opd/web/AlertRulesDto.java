package com.sahaj.opd.web;

import com.sahaj.opd.application.AlertRulesView;
import java.util.List;
import java.util.UUID;

/** SAH-18 contract shapes: {@code AlertRules}, {@code AlertRule}, {@code AlertRuleSuggestion}, {@code AlertRuleInput}. */
public record AlertRulesDto(List<Rule> rules, List<Suggestion> suggestions, Options options) {

    public record Rule(UUID id, String when, String cond, String then, boolean enabled) {
    }

    public record Suggestion(int index, String when, String cond, String then, String why) {
    }

    public record Options(List<String> when, List<String> cond, List<String> then) {
    }

    public record AddRequest(String when, String cond, String then) {
    }

    public record ToggleRequest(Boolean enabled) {
    }

    static AlertRulesDto from(AlertRulesView view) {
        return new AlertRulesDto(
                view.rules().stream().map(r -> new Rule(r.id(), r.when(), r.cond(), r.then(), r.enabled())).toList(),
                view.suggestions().stream()
                        .map(s -> new Suggestion(s.index(), s.when(), s.cond(), s.then(), s.why())).toList(),
                new Options(view.options().when(), view.options().cond(), view.options().then()));
    }
}
