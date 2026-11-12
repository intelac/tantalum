package tantalum.value.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** qagent-align 需求 3.2、3.6：折算后的量跟着四项一起加减；没折算只有一种写法。 */
class TokenUsageTest {

    private static final TokenUsage PLAIN = new TokenUsage(100, 20, 300, 40);

    @Test
    void withoutWeightsTheCountedAmountIsTheSumOfTheFour() {
        assertNull(PLAIN.weighted());
        assertEquals(460, PLAIN.total());
        assertEquals(460, PLAIN.counted());
        assertEquals(440, PLAIN.promptTotal(), "the context size is still input plus cache");
        assertEquals(0, TokenUsage.ZERO.counted());
    }

    @Test
    void eachKindIsMultipliedByItsOwnWeightAndTheSumIsRounded() {
        TokenUsage weighed = PLAIN.weighed(1, 5, 0.1, 1.25);
        // 100*1 + 20*5 + 300*0.1 + 40*1.25 = 280
        assertEquals(280L, weighed.weighted());
        assertEquals(280, weighed.counted());
        assertEquals(460, weighed.total(), "the four raw numbers stay as they were");
        assertEquals(new TokenUsage(100, 20, 300, 40, 280L), weighed);
        assertEquals(3L, new TokenUsage(5, 0, 0, 0).weighed(0.5, 1, 1, 1).weighted(), "2.5 rounds up");
        assertEquals(0L, new TokenUsage(5, 0, 0, 0).weighed(0, 1, 1, 1).weighted());
    }

    /** 全是 1 的系数折算出来和没折算一样：同一个值只有一种写法。 */
    @Test
    void anAmountEqualToTheSumIsWrittenAsNotWeighed() {
        assertEquals(PLAIN, PLAIN.weighed(1, 1, 1, 1));
        assertNull(PLAIN.weighed(1, 1, 1, 1).weighted());
        assertEquals(PLAIN, new TokenUsage(100, 20, 300, 40, 460L));
        assertEquals(PLAIN.hashCode(), new TokenUsage(100, 20, 300, 40, 460L).hashCode());
    }

    @Test
    void addingAndSubtractingCarryTheWeighedAmount() {
        TokenUsage heavy = PLAIN.weighed(1, 5, 0.1, 1.25);          // 280
        TokenUsage light = new TokenUsage(10, 10, 0, 0);             // 20, not weighed
        assertEquals(new TokenUsage(110, 30, 300, 40, 300L), heavy.plus(light));
        assertEquals(heavy.plus(light), light.plus(heavy));
        assertEquals(new TokenUsage(20, 20, 0, 0), light.plus(light), "two plain amounts stay plain");
        assertEquals(heavy, heavy.plus(light).minus(light), "a step's difference gives back what was added");
        assertEquals(light, heavy.plus(light).minus(heavy));
        assertEquals(heavy, heavy.plus(TokenUsage.ZERO));
        // 多算的和少算的正好抵消：和等于四项之和，写成没折算
        TokenUsage more = new TokenUsage(10, 0, 0, 0, 15L);
        TokenUsage less = new TokenUsage(10, 0, 0, 0, 5L);
        assertNull(more.plus(less).weighted());
        assertEquals(new TokenUsage(20, 0, 0, 0), more.plus(less));
    }

    @Test
    void negativeAmountsAndWeightsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new TokenUsage(1, 1, 1, 1, -1L));
        assertThrows(IllegalArgumentException.class, () -> new TokenUsage(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> PLAIN.weighed(-0.1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> PLAIN.weighed(1, Double.NaN, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new TokenUsage(1, 0, 0, 0, 1L).minus(new TokenUsage(1, 0, 0, 0, 5L)));
    }
}
