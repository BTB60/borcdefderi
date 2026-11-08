package az.borcdefteri.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money helpers. All monetary values are BigDecimal with scale 2 (AZN, qəpiklə). */
public final class Money {

    public static final int SCALE = 2;

    private Money() {}

    public static BigDecimal of(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
