import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "field operator value" 형태의 표현식 문자열을 공백 정규화하는 유틸.
 * 예) "A==1&&B==2" -> "A == 1 && B == 2"
 */
public final class ExpressionFormatter {

    private ExpressionFormatter() {
    }

    // 비교 연산자 (2글자 연산자를 1글자보다 먼저 매칭해야 함: >= 가 > 보다 앞)
    // 사용하는 연산자가 == 뿐이라면 "(==)" 로 줄여도 됩니다.
    private static final Pattern COMPARISON_OPERATOR =
            Pattern.compile("(==|!=|>=|<=|>|<)");

    // 논리 연산자
    private static final Pattern LOGICAL_OPERATOR =
            Pattern.compile("&&|\\|\\|");

    public static String format(String expression) {
        if (expression == null) {
            return null;
        }
        String trimmed = expression.trim();
        if (trimmed.isEmpty()) {
            return "";
        }

        // 1) 논리 연산자(&&, ||) 기준으로 절(clause)과 연산자를 순서대로 분리
        List<String> clauses = new ArrayList<>();
        List<String> connectors = new ArrayList<>();
        Matcher matcher = LOGICAL_OPERATOR.matcher(trimmed);
        int lastEnd = 0;
        while (matcher.find()) {
            clauses.add(trimmed.substring(lastEnd, matcher.start()));
            connectors.add(matcher.group());
            lastEnd = matcher.end();
        }
        clauses.add(trimmed.substring(lastEnd));

        // 2) 각 절을 정규화 후, 원래 연산자(&&/||)를 보존하며 재조립
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < clauses.size(); i++) {
            sb.append(formatClause(clauses.get(i)));
            if (i < connectors.size()) {
                sb.append(' ').append(connectors.get(i)).append(' ');
            }
        }
        return sb.toString().trim();
    }

    /**
     * 단일 절을 "field operator value" 로 정규화.
     * 절 안에서 가장 먼저 나오는 비교 연산자를 기준으로 분리하며,
     * value 내부는 의도적으로 손대지 않는다.
     */
    private static String formatClause(String clause) {
        String c = clause.trim();
        Matcher matcher = COMPARISON_OPERATOR.matcher(c);
        if (!matcher.find()) {
            // 비교 연산자가 없으면 그대로 반환 (방어적 처리)
            return c;
        }
        String field = c.substring(0, matcher.start()).trim();
        String operator = matcher.group();
        String value = c.substring(matcher.end()).trim();
        return field + " " + operator + " " + value;
    }
}
