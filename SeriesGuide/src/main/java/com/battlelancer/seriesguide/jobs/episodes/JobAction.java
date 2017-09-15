package com.battlelancer.seriesguide.jobs.episodes;

public enum JobAction {
    EPISODE_COLLECTED(1),
    SEASON_COLLECTED(2),
    SHOW_COLLECTED(3),
    EPISODE_WATCHED(4),
    EPISODE_WATCHED_PREVIOUS(5),
    SEASON_WATCHED(6),
    SHOW_WATCHED(7);

    public int id;

    JobAction(int id) {
        this.id = id;
    }
}
