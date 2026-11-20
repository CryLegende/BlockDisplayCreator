package me.general_breddok.blockdisplaycreator.render;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * A condition « A op B » of {@code display.visible-if}, written as in SHDialogs. Both sides go through the resolving
 * function given to the test (the block's coordinates, the player's name, then PlaceholderAPI).
 * <p>
 * When A and B read as numbers (spaces and thousand separators ignored, comma or dot decimal), they are compared as
 * numbers. Otherwise they are compared as text without colour codes, ignoring case and accents; the order operators
 * are then false. {@code contient} and {@code !contient} always compare text.
 *
 * @param op null for a line that holds no operator: such a condition is never true
 */
record Condition(String left, Op op, String right) {

    enum Op {
        EQ("=="), NE("!="), GE(">="), LE("<="), GT(">"), LT("<"), CONTAINS("contient"), NOT_CONTAINS("!contient");

        private final String token;

        Op(String token) {
            this.token = token;
        }
    }

    /**
     * The operators written with signs, longest first.
     */
    private static final Op[] SYMBOLS = {Op.EQ, Op.NE, Op.GE, Op.LE, Op.GT, Op.LT};
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NUMBER = Pattern.compile("[+-]?\\d+(?:\\.\\d+)?");
    private static final Pattern COLOR_CODE = Pattern.compile("[&§](?:#[0-9a-fA-F]{6}|[0-9a-fk-orxA-FK-ORX])");
    /**
     * A whole number grouped by thousands: 1.000.000 or 1,234,567.
     */
    private static final String THOUSANDS = "[+-]?\\d{1,3}(?:[.,]\\d{3})+";

    /**
     * The condition written « A op B », or null when it holds no operator. The first operator met splits the two
     * sides; « contient » and « !contient » must stand between spaces.
     */
    static Condition parse(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        for (int i = 0; i < text.length(); i++) {
            for (Op op : SYMBOLS) {
                if (text.startsWith(op.token, i)) {
                    return new Condition(text.substring(0, i).trim(), op, text.substring(i + op.token.length()).trim());
                }
            }
            if (i > 0 && Character.isWhitespace(text.charAt(i - 1))) {
                for (Op op : new Op[]{Op.NOT_CONTAINS, Op.CONTAINS}) {
                    int end = i + op.token.length();
                    if (text.startsWith(op.token, i) && (end == text.length() || Character.isWhitespace(text.charAt(end)))) {
                        return new Condition(text.substring(0, i).trim(), op, text.substring(end).trim());
                    }
                }
            }
        }
        return null;
    }

    /**
     * The conditions of a key written as one line or as a list (empty when the key is missing). A line that is not
     * a condition is reported, and kept as a condition that is never true: the block is then shown to nobody rather
     * than to everyone.
     *
     * @param value    the value of the key in the file
     * @param problems receives the lines that are not conditions
     */
    static List<Condition> readAll(Object value, String key, List<String> problems) {
        List<String> lines = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object line : list) {
                if (line != null) {
                    lines.add(line.toString());
                }
            }
        } else if (value != null) {
            lines.add(value.toString());
        }
        lines.removeIf(String::isBlank);
        List<Condition> conditions = new ArrayList<>(lines.size());
        for (String line : lines) {
            Condition condition = parse(line);
            if (condition == null) {
                problems.add(key + ": not a condition (« A == B », with ==, !=, >=, <=, >, <, contient or !contient), the block is shown to nobody: " + line);
                condition = new Condition(line, null, "");
            }
            conditions.add(condition);
        }
        return List.copyOf(conditions);
    }

    /**
     * Whether every condition is true (an empty list is).
     */
    static boolean all(List<Condition> conditions, UnaryOperator<String> resolve) {
        for (Condition condition : conditions) {
            if (!condition.test(resolve)) {
                return false;
            }
        }
        return true;
    }

    boolean test(UnaryOperator<String> resolve) {
        if (this.op == null) {
            return false;
        }
        String a = resolve.apply(this.left);
        String b = resolve.apply(this.right);
        BigDecimal numberA = number(a);
        BigDecimal numberB = number(b);
        boolean numeric = numberA != null && numberB != null;
        String textA = normalize(a);
        String textB = normalize(b);
        return switch (this.op) {
            case EQ -> numeric ? numberA.compareTo(numberB) == 0 : textA.equals(textB);
            case NE -> numeric ? numberA.compareTo(numberB) != 0 : !textA.equals(textB);
            case GE -> numeric && numberA.compareTo(numberB) >= 0;
            case LE -> numeric && numberA.compareTo(numberB) <= 0;
            case GT -> numeric && numberA.compareTo(numberB) > 0;
            case LT -> numeric && numberA.compareTo(numberB) < 0;
            case CONTAINS -> textA.contains(textB);
            case NOT_CONTAINS -> !textA.contains(textB);
        };
    }

    static String stripColors(String text) {
        return text == null ? "" : COLOR_CODE.matcher(text).replaceAll("");
    }

    /**
     * Comparable text: without colour codes, without accents, in lower case.
     */
    static String normalize(String text) {
        String plain = Normalizer.normalize(stripColors(text), Normalizer.Form.NFD);
        return MARKS.matcher(plain).replaceAll("").toLowerCase(Locale.ROOT).trim();
    }

    /**
     * The number this text expresses, or null. Spaces (non-breaking ones too) are ignored; a single separator is
     * decimal (« 1,5 », « 2.25 »), several identical separators or a group of three digits after another separator
     * group thousands (« 1.000.000 », « 1,234.5 »).
     */
    static BigDecimal number(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder compact = new StringBuilder();
        for (char c : stripColors(text).toCharArray()) {
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) {
                compact.append(c);
            }
        }
        String s = compact.toString();
        if (s.isEmpty() || s.chars().noneMatch(Character::isDigit)) {
            return null;
        }
        int dots = (int) s.chars().filter(c -> c == '.').count();
        int commas = (int) s.chars().filter(c -> c == ',').count();
        if (dots > 0 && commas > 0) {
            // the last separator is the decimal one, the others group thousands
            int last = Math.max(s.lastIndexOf('.'), s.lastIndexOf(','));
            String whole = s.substring(0, last);
            String fraction = s.substring(last + 1);
            if (!whole.matches(THOUSANDS) || !fraction.matches("\\d+")) {
                return null;
            }
            s = whole.replace(".", "").replace(",", "") + "." + fraction;
        } else if (dots + commas > 1) {
            if (!s.matches(THOUSANDS)) {
                return null;
            }
            s = s.replace(".", "").replace(",", "");
        } else if (commas == 1) {
            s = s.replace(',', '.');
        }
        if (!NUMBER.matcher(s).matches()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
