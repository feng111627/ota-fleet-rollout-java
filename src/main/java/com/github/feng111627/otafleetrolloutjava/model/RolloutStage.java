package com.github.feng111627.otafleetrolloutjava.model;

/** 灰度档位：放开到总量的百分比。 */
public class RolloutStage {
    private final int percentage;
    private boolean opened;

    public RolloutStage(int percentage) {
        this.percentage = percentage;
    }

    public int getPercentage() { return percentage; }
    public boolean isOpened() { return opened; }
    public void setOpened(boolean opened) { this.opened = opened; }
}
