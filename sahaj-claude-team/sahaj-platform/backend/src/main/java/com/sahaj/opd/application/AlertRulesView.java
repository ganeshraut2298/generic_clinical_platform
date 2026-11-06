package com.sahaj.opd.application;

import com.sahaj.opd.application.AlertRuleRepository.AlertRuleRow;
import java.util.List;

/** SAH-18: the rules page: the clinic's rules, suggestions not yet added, and the builder lists. */
public record AlertRulesView(List<AlertRuleRow> rules, List<Suggestion> suggestions, Options options) {

    public record Suggestion(int index, String when, String cond, String then, String why) {
    }

    public record Options(List<String> when, List<String> cond, List<String> then) {
    }
}
