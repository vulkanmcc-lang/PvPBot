package com.pvpbot.voice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// Turns a speech-to-text transcript ("okay everyone focus nexar void") into a
// bot order. Pure string logic - no Bukkit - so it's unit-testable and the
// same rules apply however the transcript arrived.
//
// Two ways to give an order:
//  - addressed: "everyone ...", "guys ...", "red team ..." - the command may
//    appear anywhere after the address;
//  - unaddressed: the command must open a short sentence ("focus Steve",
//    "follow me", "get over here"), and an attack must name a real player,
//    so ordinary chatter ("I'm gonna go get food", "let's go eat after
//    this") isn't mistaken for an order.
public final class VoiceCommandParser {
    public enum Intent {
        ATTACK,     // everyone on one target (NAME / LOOK / NEAREST / FACTION)
        RUSH,       // each bot takes the nearest enemy ("push them", "rush them")
        STAND_DOWN, // stop fighting for a while ("hold your fire", "chill")
        ENGAGE,     // weapons free again
        COME,       // gather at the speaker
        FOLLOW,     // stick with the speaker for a while
        WAIT,       // hold current position
        ADVANCE,    // move up the way the speaker is facing ("push forward")
        ALERT,      // "watch out", "they're coming" - get ready, hit nearby threats
        FORMATION,  // grid formation behind the speaker that follows them
        BREAK_FORMATION,
        MINE,       // excavate the area the speaker is looking at
        DESTROY,    // level it - TNT if they carry it, tools otherwise
        WORK_STOP   // stop mining / destroying
    }

    public enum TargetKind { NONE, NAME, LOOK, NEAREST, FACTION }

    // subjectFaction == null and subjectBot == null means "everyone the
    // speaker commands"; subjectBot is set for "<bot> come here".
    public record Parsed(String subjectFaction, String subjectBot, boolean addressed, Intent intent,
                         TargetKind targetKind, String target, String heardTarget) {
        Parsed(String subjectFaction, boolean addressed, Intent intent,
               TargetKind targetKind, String target, String heardTarget) {
            this(subjectFaction, null, addressed, intent, targetKind, target, heardTarget);
        }

        Parsed withBot(String bot) {
            return new Parsed(subjectFaction, bot, addressed, intent, targetKind, target, heardTarget);
        }
    }

    private static final Set<String> EVERYONE = Set.of(
            "everyone", "everybody", "all", "bots", "team", "guys", "squad", "army",
            "boys", "lads", "yall", "troops", "crew", "gang", "fellas");

    // Allowed before an unaddressed command: "okay focus steve", "yo follow me".
    private static final Set<String> LEAD_IN = Set.of(
            "ok", "okay", "hey", "yo", "alright", "right", "so", "and", "uh", "um", "now",
            "quick", "quickly", "come", "on", "oi", "hmm", "please");

    private static final Set<String> TRAILING = Set.of(
            "now", "please", "right", "already", "quick", "quickly", "fast", "asap", "again",
            "too", "guys", "everyone", "boys", "team", "go");

    private static final Set<String> NAME_FILLER = Set.of(
            "the", "a", "an", "that", "this", "player", "guy", "dude", "person", "kid",
            "on", "at", "over", "there", "here", "up");

    private static final Set<String> LOOK_WORDS = Set.of(
            "him", "her", "it", "he", "she", "that", "this", "one", "guy", "dude");

    private static final Set<String> GROUP_WORDS = Set.of("them", "they", "those", "these", "enemies", "enemy");

    private static final Set<String> NEAREST_WORDS = Set.of("nearest", "closest");

    // Unaddressed commands only count in sentences this short (words beyond
    // the command itself), so they can't hide in the middle of chatter.
    private static final int UNADDRESSED_SLACK = 3;

    // Order matters: first match wins, so specific phrases come before the
    // general verbs they contain ("stop attacking" before "attack X", "get
    // ready" / "get over here" before "get X", "push them" before "push X").
    private static final List<Rule> RULES = List.of(
            rule(Intent.WORK_STOP,
                    "stop mining", "stop digging", "stop destroying", "stop blowing stuff up",
                    "stop excavating", "stop working", "quit mining", "enough mining", "stop the mining"),
            rule(Intent.STAND_DOWN,
                    "stop attacking each other", "stop fighting each other", "stop fighting for a second",
                    "stop fighting", "stop attacking", "stop hitting", "dont hit your teammates",
                    "dont hit teammates", "dont attack each other", "dont fight each other",
                    "hold your fire", "hold fire", "cease fire", "stand down", "calm down", "chill out",
                    "chill", "nobody fight", "no fighting", "save your attacks", "dont attack",
                    "dont fight", "back off", "never mind", "nevermind", "call it off", "stop", "cancel",
                    "abort", "peace"),
            rule(Intent.ENGAGE,
                    "weapons free", "fight back", "free fire", "you can fight", "engage", "go wild"),
            rule(Intent.DESTROY,
                    "destroy the area", "destroy this area", "destroy that area", "destroy everything",
                    "destroy it all", "destroy this", "destroy it", "blow up the area", "blow up this area",
                    "blow it up", "blow everything up", "blow it all up", "demolish the area",
                    "demolish this", "demolish it", "demolish", "level the area", "level this area",
                    "flatten the area", "flatten this", "tear it down", "tear it all down", "raze the area",
                    "wreck the area", "nuke it", "nuke the area"),
            rule(Intent.MINE,
                    "mine the area", "mine this area", "mine that area", "mine the place", "mine here",
                    "mine this", "mine it out", "dig here", "dig this area", "dig the area", "dig this out",
                    "dig it out", "dig out the area", "dig down", "excavate the area", "excavate this",
                    "excavate here", "excavate", "start mining", "start digging", "go mine", "get mining",
                    "get digging", "mine"),
            rule(Intent.BREAK_FORMATION,
                    "break formation", "break ranks", "at ease", "dismissed", "you are dismissed",
                    "youre free", "you are free", "free roam", "do your own thing", "spread out"),
            rule(Intent.FORMATION,
                    "go behind me", "get behind me", "stay behind me", "fall in behind me", "line up behind me",
                    "form up behind me", "get in formation", "form up", "fall in", "line up", "formation",
                    "behind me"),
            rule(Intent.ALERT,
                    "keep your eyes open", "keep your eyes peeled", "eyes open", "watch out", "look out",
                    "behind you", "theyre coming", "they are coming", "here they come", "incoming",
                    "get ready", "be ready", "heads up"),
            rule(Intent.FOLLOW,
                    "follow me in", "follow me", "stay with me", "stick with me", "with me"),
            rule(Intent.WAIT,
                    "wait here", "stay here", "hold position", "hold here", "wait for me", "wait"),
            rule(Intent.COME,
                    "come to my position", "come to me", "come over here", "come here", "come over",
                    "get over here", "get here", "group up here", "group up", "meet me here", "meet me",
                    "we need to regroup", "regroup", "stay together", "stick together", "dont split up",
                    "stay close", "on me", "rally", "fall back", "come back"),
            rule(Intent.ADVANCE,
                    "push forward", "move forward", "move up", "move out", "move together",
                    "keep moving", "keep pushing", "lets go", "lets move", "go go go", "go go",
                    "advance"),
            rule(Intent.RUSH,
                    "push them now", "push them", "rush them", "get them", "attack them", "kill them",
                    "go get them", "charge"),
            rule(Intent.ATTACK,
                    "{p} is our target", "{p} is the target", "{p} is target", "{p} is our focus",
                    "dont let {p} escape", "dont let {p} get away", "dont let {p} run",
                    "dont let {p} leave", "all on {p}", "everyone on {p}", "focus on {p}", "focus {p}",
                    "take out {p}", "take down {p}", "go after {p}", "go for {p}", "go kill {p}",
                    "kill {p}", "attack {p}", "get {p}", "target {p}", "push {p}", "hit {p}", "rush {p}",
                    "hunt {p}", "murder {p}", "destroy {p}", "eliminate {p}", "gank {p}", "jump {p}",
                    "fight {p}", "slay {p}", "wreck {p}", "on {p}"),
            rule(Intent.ADVANCE, "push"));

    private VoiceCommandParser() {
    }

    public static Parsed parse(String transcript, Collection<String> factions,
                               Collection<String> candidateNames) {
        return parse(transcript, factions, candidateNames, List.of());
    }

    // botNames: bots the speaker may order individually ("Andy come here").
    public static Parsed parse(String transcript, Collection<String> factions,
                               Collection<String> candidateNames, Collection<String> botNames) {
        List<String> tokens = tokenize(transcript);
        if (tokens.isEmpty()) return null;
        String subjectBot = null;

        // 1. Who's being addressed (optional). The address can sit anywhere
        // ("everyone come here", "calm down guys") and is cut out of the
        // sentence before the command is matched.
        int start = 0;
        String subjectFaction = null;
        boolean addressed = false;
        int subjectFrom = -1, subjectTo = -1;
        for (int i = 0; i < tokens.size() && !addressed; i++) {
            String tok = tokens.get(i);
            int end = -1;
            if (EVERYONE.contains(tok) && !isPartOfPhrase(tokens, i)) {
                end = i + 1;
                while (end < tokens.size()
                        && Set.of("bots", "of", "you", "guys", "team").contains(tokens.get(end))) {
                    end++;
                }
            } else {
                String faction = matchFaction(tokens, i, factions);
                if (faction != null) {
                    subjectFaction = faction;
                    end = i + (faction.replaceAll("[^A-Za-z0-9]", "").length()
                            > tok.length() ? 2 : 1);
                    if (end < tokens.size()
                            && Set.of("team", "faction", "side").contains(tokens.get(end))) {
                        end++;
                    }
                }
            }
            if (end >= 0) {
                addressed = true;
                subjectFrom = i;
                subjectTo = end;
            }
        }
        if (addressed) {
            List<String> without = new ArrayList<>(tokens.subList(0, subjectFrom));
            without.addAll(tokens.subList(subjectTo, tokens.size()));
            tokens = without;
        } else {
            while (start < tokens.size() && LEAD_IN.contains(tokens.get(start))
                    && !startsAnyRule(tokens, start)) {
                start++;
            }
            // "<bot name> come here": up to three words of name, then a
            // command straight after it.
            if (botNames != null && !botNames.isEmpty() && !startsAnyRule(tokens, start)) {
                for (int n = 1; n <= 3 && start + n < tokens.size(); n++) {
                    if (!startsAnyRule(tokens, start + n)) continue;
                    String bot = bestName(tokens.subList(start, start + n), botNames);
                    if (bot != null) {
                        subjectBot = bot;
                        addressed = true;
                        start += n;
                        break;
                    }
                }
            }
        }

        List<String> rest = tokens.subList(start, tokens.size());
        if (rest.isEmpty()) return null;

        // 2. What they're told to do.
        for (Rule r : RULES) {
            for (Template t : r.templates) {
                Parsed p = t.slot
                        ? matchSlot(r.intent, t, rest, addressed, subjectFaction, factions, candidateNames)
                        : matchPlain(r.intent, t, rest, addressed, subjectFaction);
                if (p != null) return subjectBot == null ? p : p.withBot(subjectBot);
            }
        }
        return null;
    }

    private static Parsed matchPlain(Intent intent, Template t, List<String> rest,
                                     boolean addressed, String subject) {
        int at = indexOf(rest, t.prefix, 0);
        if (at < 0) return null;
        if (!addressed) {
            // One-word orders ("stop", "wait", "push") are too common in
            // normal talk to act on unless someone was addressed.
            if (t.prefix.size() < 2) return null;
            if (at != 0 || rest.size() > t.prefix.size() + UNADDRESSED_SLACK) return null;
        }
        return new Parsed(subject, addressed, intent, TargetKind.NONE, null, null);
    }

    private static Parsed matchSlot(Intent intent, Template t, List<String> rest, boolean addressed,
                                    String subject, Collection<String> factions, Collection<String> names) {
        List<String> nameWords;
        if (t.prefix.isEmpty()) {
            // "{p} is our target"
            int sfx = indexOf(rest, t.suffix, 1);
            if (sfx < 0) return null;
            nameWords = new ArrayList<>(rest.subList(0, sfx));
            if (!addressed && rest.size() > sfx + t.suffix.size() + UNADDRESSED_SLACK) return null;
        } else {
            int at = indexOf(rest, t.prefix, 0);
            if (at < 0) return null;
            if (!addressed && at != 0) return null;
            int from = at + t.prefix.size();
            if (!t.suffix.isEmpty()) {
                int sfx = indexOf(rest, t.suffix, from + 1);
                if (sfx < 0) return null;
                nameWords = new ArrayList<>(rest.subList(from, sfx));
            } else {
                nameWords = new ArrayList<>(rest.subList(from, rest.size()));
                while (!nameWords.isEmpty() && TRAILING.contains(nameWords.get(nameWords.size() - 1))) {
                    nameWords.remove(nameWords.size() - 1);
                }
            }
        }
        if (nameWords.size() > 5) return null;

        boolean lookVerb = !t.prefix.isEmpty()
                && !Set.of("push", "on", "get", "rush", "jump", "for").contains(t.prefix.get(t.prefix.size() - 1));
        Parsed resolved = resolveTarget(intent, subject, addressed, nameWords, lookVerb, factions, names);
        if (resolved == null) return null;
        // Unaddressed + couldn't tell who: almost certainly chatter, ignore.
        if (!addressed && resolved.targetKind() == TargetKind.NONE && resolved.intent() == Intent.ATTACK) {
            return null;
        }
        return resolved;
    }

    private static Parsed resolveTarget(Intent intent, String subject, boolean addressed, List<String> words,
                                        boolean lookVerb, Collection<String> factions,
                                        Collection<String> names) {
        String heard = String.join(" ", words);

        if (words.isEmpty()) {
            // "everyone attack" -> whoever the speaker is looking at. Not for
            // verbs that also mean "move" ("everyone push", "all on").
            return addressed && lookVerb ? new Parsed(subject, true, intent, TargetKind.LOOK, null, heard) : null;
        }
        for (String w : words) {
            if (NEAREST_WORDS.contains(w)) return new Parsed(subject, addressed, intent, TargetKind.NEAREST, null, heard);
        }
        if (words.size() <= 2 && GROUP_WORDS.contains(words.get(words.size() - 1))) {
            return new Parsed(subject, addressed, Intent.RUSH, TargetKind.NONE, null, heard);
        }

        for (int i = 0; i < words.size(); i++) {
            String faction = matchFaction(words, i, factions);
            if (faction != null && (subject == null || !faction.equalsIgnoreCase(subject))) {
                return new Parsed(subject, addressed, intent, TargetKind.FACTION, faction, heard);
            }
        }

        List<String> meaningful = new ArrayList<>();
        for (String w : words) if (!NAME_FILLER.contains(w)) meaningful.add(w);
        boolean deictic = words.stream().anyMatch(LOOK_WORDS::contains);
        if (meaningful.isEmpty() || (deictic && meaningful.stream().allMatch(LOOK_WORDS::contains))) {
            return deictic ? new Parsed(subject, addressed, intent, TargetKind.LOOK, null, heard) : null;
        }

        String match = bestName(meaningful, names);
        if (match != null) return new Parsed(subject, addressed, intent, TargetKind.NAME, match, heard);
        return new Parsed(subject, addressed, intent, TargetKind.NONE, null, heard);
    }

    // =====================================================================
    // Name matching.
    //
    // Speech recognisers only know dictionary words, so a gamer tag comes
    // back as whatever English it sounds like: "NexarVo1d" -> "nexar void" /
    // "next are void", "xDarkKnightx" -> "dark knight", "Steve123" -> "steve
    // one two three" or just "steve". Each online name is expanded into
    // several spellings (as typed, digits read as the letters they stand in
    // for, digits dropped, decorations like leading/trailing x stripped) and
    // compared against the heard words with an edit-distance score and a
    // "sounds alike" key. The best candidate must be clearly good and
    // clearly better than the runner-up, otherwise nobody is picked.
    // =====================================================================

    static final double MIN_SCORE = 0.66;
    static final double MIN_MARGIN = 0.07;

    private static final Map<String, String> NUMBER_WORDS = Map.ofEntries(
            Map.entry("zero", "0"), Map.entry("oh", "0"), Map.entry("one", "1"), Map.entry("won", "1"),
            Map.entry("two", "2"), Map.entry("to", "2"), Map.entry("too", "2"),
            Map.entry("three", "3"), Map.entry("four", "4"), Map.entry("for", "4"),
            Map.entry("five", "5"), Map.entry("six", "6"), Map.entry("seven", "7"),
            Map.entry("eight", "8"), Map.entry("ate", "8"), Map.entry("nine", "9"), Map.entry("ten", "10"));

    public static String bestName(List<String> spokenWords, Collection<String> names) {
        if (names == null || names.isEmpty() || spokenWords.isEmpty()) return null;

        Set<String> spoken = new LinkedHashSet<>();
        for (int i = 0; i < spokenWords.size(); i++) {
            for (int j = i + 1; j <= spokenWords.size(); j++) {
                List<String> span = spokenWords.subList(i, j);
                spoken.add(join(span, false));
                spoken.add(join(span, true));
            }
        }
        spoken.removeIf(String::isEmpty);

        String best = null;
        double bestScore = 0.0, secondScore = 0.0;
        for (String name : names) {
            double score = 0.0;
            for (String form : nameForms(name)) {
                for (String s : spoken) {
                    score = Math.max(score, similarity(s, form));
                    score = Math.max(score, similarity(soundKey(s), soundKey(form)) - 0.05);
                    score = Math.max(score, prefixScore(s, form));
                }
            }
            if (score > bestScore) {
                secondScore = bestScore;
                bestScore = score;
                best = name;
            } else if (score > secondScore) {
                secondScore = score;
            }
        }
        if (best == null || bestScore < MIN_SCORE || bestScore - secondScore < MIN_MARGIN) return null;
        return best;
    }

    // People shorten names: "nexar" for NexarVo1d, "techno" for Technoblade.
    // A (fuzzy) match on the start of the name counts, scaled by how much of
    // the name was said, so it only wins when nobody else fits better.
    static double prefixScore(String spoken, String form) {
        if (spoken.length() < 4 || spoken.length() >= form.length()) return 0.0;
        double ratio = (double) spoken.length() / form.length();
        if (ratio < 0.4) return 0.0;
        double sim = similarity(spoken, form.substring(0, spoken.length()));
        if (sim < 0.75) return 0.0;
        return sim * (0.72 + 0.28 * ratio);
    }

    // Spellings a name could come back as.
    static Set<String> nameForms(String name) {
        Set<String> out = new LinkedHashSet<>();
        String lower = name.toLowerCase(Locale.ROOT);
        String plain = lower.replaceAll("[^a-z0-9]", "");
        out.add(plain);
        String leet = plain.replace('0', 'o').replace('1', 'i').replace('3', 'e').replace('4', 'a')
                .replace('5', 's').replace('7', 't').replace('8', 'b').replace('9', 'g');
        out.add(leet);
        out.add(plain.replace('1', 'l'));
        out.add(plain.replaceAll("[0-9]+", ""));
        // Decorations: xX_Name_Xx, ItsName, TheName, Name_YT, Name_TTV
        for (String form : new ArrayList<>(out)) {
            String s = form.replaceAll("^x+|x+$", "");
            if (s.length() >= 3) out.add(s);
            for (String prefix : List.of("its", "the", "im", "mr", "pvpbot", "bot")) {
                if (s.startsWith(prefix) && s.length() - prefix.length() >= 3) out.add(s.substring(prefix.length()));
            }
            for (String suffix : List.of("yt", "ttv", "tv", "mc", "pvp", "gaming", "official", "bot")) {
                if (s.endsWith(suffix) && s.length() - suffix.length() >= 3) {
                    out.add(s.substring(0, s.length() - suffix.length()));
                }
            }
        }
        out.removeIf(s -> s.length() < 2);
        return out;
    }

    private static String join(List<String> words, boolean numbersAsDigits) {
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            String d = numbersAsDigits ? NUMBER_WORDS.get(w) : null;
            sb.append(d != null ? d : w);
        }
        return sb.toString().replaceAll("[^a-z0-9]", "");
    }

    static String soundKey(String s) {
        String k = s.toLowerCase(Locale.ROOT)
                .replace('0', 'o').replace('1', 'i').replace('3', 'e').replace('4', 'a').replace('5', 's')
                .replaceAll("[^a-z]", "")
                .replace("ph", "f").replace("gh", "g").replace("ck", "k").replace("qu", "kw")
                .replace("x", "ks").replace("th", "t").replace("wh", "w")
                .replace('z', 's').replace('c', 'k').replace('q', 'k').replace('y', 'i')
                .replace("ee", "i").replace("ea", "i").replace("oo", "u").replace("ou", "u")
                .replace("ai", "a").replace("ay", "a").replace("ei", "a");
        // Trailing silent e and a final "er"/"ar"/"or"/"a" all blur together.
        k = k.replaceAll("(?<=[a-z]{2})e(?=$|[^aeiou])", "");
        k = k.replace("er", "r").replace("ar", "r").replace("or", "r").replace("ur", "r");
        StringBuilder sb = new StringBuilder();
        char prev = 0;
        for (char ch : k.toCharArray()) {
            if (ch != prev) sb.append(ch);
            prev = ch;
        }
        return sb.toString();
    }

    static double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        if (a.equals(b)) return 1.0;
        int d = levenshtein(a, b);
        return 1.0 - (double) d / Math.max(a.length(), b.length());
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    // =====================================================================
    // Plumbing
    // =====================================================================

    private record Template(List<String> prefix, List<String> suffix, boolean slot) {
    }

    private record Rule(Intent intent, List<Template> templates) {
    }

    private static Rule rule(Intent intent, String... phrases) {
        List<Template> ts = new ArrayList<>();
        for (String p : phrases) {
            int slot = p.indexOf("{p}");
            if (slot < 0) {
                ts.add(new Template(words(p), List.of(), false));
            } else {
                ts.add(new Template(words(p.substring(0, slot)), words(p.substring(slot + 3)), true));
            }
        }
        return new Rule(intent, ts);
    }

    private static List<String> words(String s) {
        String t = s.trim();
        return t.isEmpty() ? List.of() : List.of(t.split("\\s+"));
    }

    // Every word that can start or form part of a command - the client mod
    // uses this list to decide which utterances are worth sending at all.
    public static Set<String> vocabulary() {
        Set<String> out = new LinkedHashSet<>(EVERYONE);
        for (Rule r : RULES) {
            for (Template t : r.templates) {
                out.addAll(t.prefix);
                out.addAll(t.suffix);
            }
        }
        out.removeAll(Set.of("a", "an", "the", "is", "to", "me", "my", "your", "on", "up", "here",
                "over", "for", "in", "out", "with", "of", "you", "let", "it", "no"));
        return out;
    }

    private static boolean startsAnyRule(List<String> tokens, int at) {
        for (Rule r : RULES) {
            for (Template t : r.templates) {
                if (!t.prefix.isEmpty() && indexOf(tokens, t.prefix, at) == at) return true;
            }
        }
        return false;
    }

    // "all" in "all on steve" or "everyone" in "everyone on steve" is part of
    // the command, but still counts as addressing - only phrases where the
    // word isn't an address at all are excluded here.
    private static boolean isPartOfPhrase(List<String> tokens, int i) {
        String tok = tokens.get(i);
        // "calm down guys", "stop fighting guys": trailing address word -
        // still an address, fine. "kill them all": "all" after a verb isn't.
        return tok.equals("all") && i > 0 && GROUP_WORDS.contains(tokens.get(i - 1));
    }

    static List<String> tokenize(String transcript) {
        if (transcript == null) return List.of();
        String cleaned = transcript.toLowerCase(Locale.ROOT)
                .replace("'", "")
                .replace("’", "")
                .replaceAll("[^a-z0-9 ]", " ")
                .trim();
        if (cleaned.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(Arrays.asList(cleaned.split("\\s+")));
        for (int i = 0; i + 1 < out.size(); i++) {
            if (out.get(i).equals("you") && out.get(i + 1).equals("all")) {
                out.set(i, "yall");
                out.remove(i + 1);
            }
        }
        return out;
    }

    private static int indexOf(List<String> tokens, List<String> phrase, int from) {
        if (phrase.isEmpty()) return from <= tokens.size() ? from : -1;
        outer:
        for (int i = Math.max(0, from); i + phrase.size() <= tokens.size(); i++) {
            for (int j = 0; j < phrase.size(); j++) {
                if (!tokens.get(i + j).equals(phrase.get(j))) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static String matchFaction(List<String> tokens, int i, Collection<String> factions) {
        if (factions == null) return null;
        String tok = tokens.get(i);
        for (String f : factions) {
            String fn = f.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (fn.isEmpty()) continue;
            if (tok.equals(fn)) return f;
            if (i + 1 < tokens.size() && (tok + tokens.get(i + 1)).equals(fn)) return f;
        }
        return null;
    }
}
