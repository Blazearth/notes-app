package com.weavr.api.gemini;

/**
 * Proof that the daily Gemini budget has been checked and approved for one
 * request to a specific model.
 *
 * <p>The constructor is package-private: only {@link GeminiBudgetService} can
 * mint tokens. This makes it structurally impossible to call
 * {@link GeminiClient} without first passing through the budget guard —
 * the compiler enforces it, not a runtime check.
 */
public final class BudgetApproved {

    /** The model for which budget was approved. */
    private final String model;

    /** The RPD ceiling that was checked. */
    private final int rpd;

    BudgetApproved(String model, int rpd) {
        this.model = model;
        this.rpd = rpd;
    }

    public String model() {
        return model;
    }

    public int rpd() {
        return rpd;
    }

    @Override
    public String toString() {
        return "BudgetApproved[model=" + model + ", rpd=" + rpd + "]";
    }
}
