package com.battlelancer.seriesguide.jobs;

import static com.battlelancer.seriesguide.jobs.episodes.JobAction.EPISODE_COLLECTION;
import static com.battlelancer.seriesguide.jobs.episodes.JobAction.EPISODE_WATCHED_FLAG;

import android.content.Context;
import androidx.annotation.NonNull;
import com.battlelancer.seriesguide.backend.HexagonTools;
import com.battlelancer.seriesguide.jobs.episodes.JobAction;
import com.battlelancer.seriesguide.provider.SgRoomDatabase;
import com.battlelancer.seriesguide.sync.HexagonEpisodeSync;
import com.battlelancer.seriesguide.sync.NetworkJobProcessor;
import com.battlelancer.seriesguide.ui.episodes.EpisodeTools;
import com.battlelancer.seriesguide.util.Errors;
import com.google.api.client.http.HttpResponseException;
import com.uwetrottmann.seriesguide.backend.episodes.Episodes;
import com.uwetrottmann.seriesguide.backend.episodes.model.SgCloudEpisode;
import com.uwetrottmann.seriesguide.backend.episodes.model.SgCloudEpisodeList;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class HexagonEpisodeJob extends BaseNetworkEpisodeJob {

    @NonNull private final HexagonTools hexagonTools;

    public HexagonEpisodeJob(@NonNull HexagonTools hexagonTools, JobAction action,
            SgJobInfo jobInfo) {
        super(action, jobInfo);
        this.hexagonTools = hexagonTools;
    }

    @NonNull
    @Override
    public NetworkJobProcessor.JobResult execute(Context context) {
        int showTmdbIdOrZero = SgRoomDatabase.getInstance(context).sgShow2Helper()
                .getShowTmdbId(jobInfo.showId());
        if (showTmdbIdOrZero <= 0) {
            // Can't run this job (for now), report error and remove.
            return buildResult(context, NetworkJob.ERROR_HEXAGON_CLIENT);
        }

        SgCloudEpisodeList uploadWrapper = new SgCloudEpisodeList();
        uploadWrapper.setShowTmdbId(showTmdbIdOrZero);

        // upload in small batches
        List<SgCloudEpisode> smallBatch = new ArrayList<>();
        final List<SgCloudEpisode> episodes = getEpisodesForHexagon();
        while (!episodes.isEmpty()) {
            // batch small enough?
            if (episodes.size() <= HexagonEpisodeSync.MAX_BATCH_SIZE) {
                smallBatch = episodes;
            } else {
                // build smaller batch
                for (int count = 0; count < HexagonEpisodeSync.MAX_BATCH_SIZE; count++) {
                    if (episodes.isEmpty()) {
                        break;
                    }
                    smallBatch.add(episodes.remove(0));
                }
            }

            // upload
            uploadWrapper.setEpisodes(smallBatch);

            try {
                Episodes episodesService = hexagonTools.getEpisodesService();
                if (episodesService == null) {
                    return buildResult(context, NetworkJob.ERROR_HEXAGON_AUTH);
                }
                episodesService.saveSgEpisodes(uploadWrapper).execute();
            } catch (HttpResponseException e) {
                Errors.logAndReportHexagon("save episodes", e);
                int code = e.getStatusCode();
                if (code >= 400 && code < 500) {
                    return buildResult(context, NetworkJob.ERROR_HEXAGON_CLIENT);
                } else {
                    return buildResult(context, NetworkJob.ERROR_HEXAGON_SERVER);
                }
            } catch (IOException | IllegalArgumentException e) {
                // Note: JSON parser may throw IllegalArgumentException.
                Errors.logAndReportHexagon("save episodes", e);
                return buildResult(context, NetworkJob.ERROR_CONNECTION);
            }

            // prepare for next batch
            smallBatch.clear();
        }

        return buildResult(context, NetworkJob.SUCCESS);
    }

    /**
     * Builds a list of episodes ready to upload to hexagon. However, the show id is not set.
     * It should be set in the wrapping entity.
     */
    @NonNull
    private List<SgCloudEpisode> getEpisodesForHexagon() {
        boolean isWatchedNotCollected;
        if (action == EPISODE_WATCHED_FLAG) {
            isWatchedNotCollected = true;
        } else if (action == EPISODE_COLLECTION) {
            isWatchedNotCollected = false;
        } else {
            throw new IllegalArgumentException("Action " + action + " not supported.");
        }

        List<SgCloudEpisode> episodes = new ArrayList<>();
        for (int i = 0; i < jobInfo.episodesLength(); i++) {
            EpisodeInfo episodeInfo = jobInfo.episodes(i);

            SgCloudEpisode episode = new SgCloudEpisode();
            episode.setSeasonNumber(episodeInfo.season());
            episode.setEpisodeNumber(episodeInfo.number());
            if (isWatchedNotCollected) {
                episode.setWatchedFlag(jobInfo.flagValue());
                // Always upload (regardless if watched, skipped or not watched).
                // Also ensures legacy data slowly adds the new plays field.
                episode.setPlays(episodeInfo.plays());
            } else {
                episode.setIsInCollection(EpisodeTools.isCollected(jobInfo.flagValue()));
            }

            episodes.add(episode);
        }
        return episodes;
    }
}
