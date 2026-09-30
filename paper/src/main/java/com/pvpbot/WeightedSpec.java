package com.pvpbot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public final class WeightedSpec {
    public record Entry(String value, double weight) {}

    private final List<Entry> entries;
    private final double total;

    private WeightedSpec(List<Entry> entries, double total) {
        this.entries = entries;
        this.total = total;
    }

    public List<Entry> entries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public String roll() {
        if (entries.isEmpty()) return null;
        double target = ThreadLocalRandom.current().nextDouble() * total;
        double running = 0.0;
        for (Entry e : entries) {
            running += e.weight();
            if (target < running) return e.value();
        }

        return entries.get(entries.size() - 1).value();
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.value()).append(' ')
              .append(Math.round(e.weight() / total * 100.0)).append('%');
        }
        return sb.toString();
    }

    public static final class SpecException extends Exception {
        public SpecException(String message) { super(message); }
    }

    public static WeightedSpec parse(String raw) throws SpecException {
        if (raw == null) throw new SpecException("Nothing to parse.");

        String cleaned = raw.replace(" ", "").trim();
        while (cleaned.length() >= 2
                && (cleaned.startsWith("(") || cleaned.startsWith("[") || cleaned.startsWith("{"))) {
            char last = cleaned.charAt(cleaned.length() - 1);
            if (last != ')' && last != ']' && last != '}') break;
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        if (cleaned.isEmpty()) throw new SpecException("Nothing to parse.");

        List<String> names = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        List<Integer> bareIndexes = new ArrayList<>();
        double explicitTotal = 0.0;

        for (String token : cleaned.split(",")) {
            if (token.isEmpty()) continue;

            int pct = token.indexOf('%');
            String name;
            Double weight = null;

            if (pct >= 0) {
                String number = token.substring(0, pct);
                if (number.isEmpty()) {
                    throw new SpecException("Missing weight before '%' in '" + token + "'.");
                }
                try {
                    weight = Double.parseDouble(number);
                } catch (NumberFormatException e) {
                    throw new SpecException("'" + number + "' is not a number in '" + token + "'.");
                }
                if (weight <= 0) {
                    throw new SpecException("Weight must be greater than zero in '" + token + "'.");
                }
                name = token.substring(pct + 1);
            } else {
                name = token;
            }

            name = name.toLowerCase(Locale.ROOT);
            if (name.isEmpty()) throw new SpecException("Missing a name in '" + token + "'.");

            if (weight == null) bareIndexes.add(names.size());
            else explicitTotal += weight;

            names.add(name);
            weights.add(weight);
        }

        if (names.isEmpty()) throw new SpecException("No entries found.");

        if (!bareIndexes.isEmpty()) {
            double remainder = Math.max(explicitTotal * 0.25, 100.0 - explicitTotal);
            double each = remainder / bareIndexes.size();
            for (int i : bareIndexes) weights.set(i, each);
        }

        Map<String, Double> merged = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            merged.merge(names.get(i), weights.get(i), Double::sum);
        }

        List<Entry> entries = new ArrayList<>(merged.size());
        double total = 0.0;
        for (var e : merged.entrySet()) {
            entries.add(new Entry(e.getKey(), e.getValue()));
            total += e.getValue();
        }
        return new WeightedSpec(entries, total);
    }

    public static boolean looksLikeSpec(String token) {
        return token != null && (token.indexOf('%') >= 0
                || (token.indexOf(',') >= 0 && token.length() > 2));
    }
}
