package com.mineagent.engine.pathing.goals;

import java.util.Arrays;
import java.util.List;

/**
 * Goal: satisfy ALL sub-goals simultaneously (AND-composite).
 * The position is in goal only when every sub-goal is satisfied.
 * The heuristic is the maximum of all sub-goal heuristics.
 */
public class GoalComposite implements Goal {

    private final List<Goal> goals;

    public GoalComposite(Goal... goals) {
        this.goals = Arrays.asList(goals);
    }

    public GoalComposite(List<Goal> goals) {
        this.goals = goals;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        for (Goal g : goals) {
            if (!g.isInGoal(x, y, z)) return false;
        }
        return true;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        double max = 0;
        for (Goal g : goals) {
            max = Math.max(max, g.heuristic(x, y, z));
        }
        return max;
    }

    @Override
    public boolean isImpossible() {
        for (Goal g : goals) {
            if (g.isImpossible()) return true;
        }
        return false;
    }

    public List<Goal> goals() { return goals; }

    @Override
    public String toString() {
        return "GoalComposite" + goals;
    }
}
