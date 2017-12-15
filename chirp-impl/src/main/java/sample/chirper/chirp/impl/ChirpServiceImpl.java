/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package sample.chirper.chirp.impl;

import akka.NotUsed;
import akka.stream.javadsl.Source;
import com.lightbend.lagom.javadsl.api.ServiceCall;
import com.lightbend.lagom.javadsl.persistence.PersistentEntityRegistry;
import org.pcollections.PSequence;
import sample.chirper.chirp.api.Chirp;
import sample.chirper.chirp.api.ChirpService;
import sample.chirper.chirp.api.HistoricalChirpsRequest;
import sample.chirper.chirp.api.LiveChirpsRequest;
import sample.chirper.chirp.impl.ChirpTimelineCommand.AddChirp;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ChirpServiceImpl implements ChirpService {
    private final PersistentEntityRegistry persistentEntities;
    private final ChirpTopic topic;
    private final ChirpRepository chirps;

    @Inject
    public ChirpServiceImpl(PersistentEntityRegistry persistentEntities, ChirpTopic topic, ChirpRepository chirps) {
        this.persistentEntities = persistentEntities;
        this.topic = topic;
        this.chirps = chirps;

        persistentEntities.register(ChirpTimelineEntity.class);
    }

    @Override
    public ServiceCall<Chirp, NotUsed> addChirp(String userId) {
        return chirp -> {
            if (!userId.equals(chirp.userId))
                throw new IllegalArgumentException("UserId " + userId + " did not match userId in " + chirp);

            return persistentEntities.refFor(ChirpTimelineEntity.class, userId)
                    .ask(new AddChirp(chirp))
                    .thenApply(done -> NotUsed.getInstance());
        };
    }


    @Override
    public ServiceCall<LiveChirpsRequest, Source<Chirp, ?>> getLiveChirps() {
        return req -> chirps.getRecentChirps(req.userIds).thenApply(recentChirps -> {
            List<Source<Chirp, ?>> sources = new ArrayList<>();
            for (String userId : req.userIds) {
                sources.add(topic.subscriber(userId));
            }
            HashSet<String> users = new HashSet<>(req.userIds);
            Source<Chirp, ?> publishedChirps = Source.from(sources).flatMapMerge(sources.size(), s -> s)
                    .filter(c -> users.contains(c.userId));

            // We currently ignore the fact that it is possible to get duplicate chirps
            // from the recent and the topic. That can be solved with a de-duplication stage.
            return Source.from(recentChirps).concat(publishedChirps);
        });
    }

    @Override
    public ServiceCall<HistoricalChirpsRequest, Source<Chirp, ?>> getHistoricalChirps() {
        return req -> {
            PSequence<String> userIds = req.userIds;
            long timestamp = req.fromTime.toEpochMilli();
            Source<Chirp, ?> result = chirps.getHistoricalChirps(userIds, timestamp);
            return CompletableFuture.completedFuture(result);
        };
    }
}
