package az.borcdefteri;

/** Test access to the AI character check (package-private in the assistant package). */
final class CardDesignAiAccess {
    private CardDesignAiAccess() {}

    static String safe(String svg) {
        return az.borcdefteri.assistant.CardDesignAiTestHook.safeSvg(svg);
    }
}
