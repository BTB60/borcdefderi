package az.borcdefteri.assistant;

/** Lets tests in other packages reach the package-private character check. */
public final class CardDesignAiTestHook {
    private CardDesignAiTestHook() {}

    public static String safeSvg(String svg) {
        return CardDesignAi.safeSvg(svg);
    }
}
