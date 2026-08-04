package com.mineagent.engine.planning;

/** Implemented by body tasks that can expose precise planning semantics. */
public interface IntentAwareTask {
    IntentContract intentContract();
}
