import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ExpressionFormatterTest {

    private static Stream<Arguments> expressions() {
        return Stream.of(
                // 이미 정규화된 경우 -> 그대로 유지
                Arguments.of("FIELD == VALUE", "FIELD == VALUE"),
                Arguments.of("FIELD1 == VALUE1 && FIELD2 == VALUE2",
                             "FIELD1 == VALUE1 && FIELD2 == VALUE2"),

                // 공백이 모두 제거된 경우
                Arguments.of("FIELD==VALUE", "FIELD == VALUE"),
                Arguments.of("FIELD1==VALUE1&&FIELD2==VALUE2",
                             "FIELD1 == VALUE1 && FIELD2 == VALUE2"),

                // 공백이 뒤섞인 경우
                Arguments.of("FIELD1 ==VALUE1&& FIELD2== VALUE2",
                             "FIELD1 == VALUE1 && FIELD2 == VALUE2"),
                Arguments.of("FIELD   ==   VALUE", "FIELD == VALUE"),

                // OR 연산자
                Arguments.of("A==1||B==2", "A == 1 || B == 2"),

                // && 와 || 혼합 (연산자 종류 보존 확인)
                Arguments.of("A==1&&B==2||C==3", "A == 1 && B == 2 || C == 3"),

                // == 외 다른 비교 연산자
                Arguments.of("score>=90&&status!=DONE",
                             "score >= 90 && status != DONE"),

                // value 안에 특수문자가 있어도 보존
                Arguments.of("path==/a/b/c&&type==json",
                             "path == /a/b/c && type == json"),

                // 앞뒤 공백 제거
                Arguments.of("  A==B  ", "A == B")
        );
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" => \"{1}\"")
    @MethodSource("expressions")
    void should_normalize_whitespace_when_formatting(String input, String expected) {
        assertThat(ExpressionFormatter.format(input)).isEqualTo(expected);
    }

    @Test
    void should_return_null_when_input_is_null() {
        assertThat(ExpressionFormatter.format(null)).isNull();
    }

    @Test
    void should_return_empty_when_input_is_blank() {
        assertThat(ExpressionFormatter.format("   ")).isEmpty();
    }
}
