package de.kaliburg.morefair.game.ladder.services.impl;

import static de.kaliburg.morefair.events.types.LadderEventType.PROMOTE;

import de.kaliburg.morefair.account.services.AccountSettingsService;
import de.kaliburg.morefair.api.FairController;
import de.kaliburg.morefair.api.utils.WsUtils;
import de.kaliburg.morefair.core.concurrency.CriticalRegion;
import de.kaliburg.morefair.events.Event;
import de.kaliburg.morefair.game.ladder.model.LadderEntity;
import de.kaliburg.morefair.game.ladder.model.LadderType;
import de.kaliburg.morefair.game.ladder.model.dto.LadderTickDto;
import de.kaliburg.morefair.game.ladder.services.LadderEventService;
import de.kaliburg.morefair.game.ladder.services.LadderService;
import de.kaliburg.morefair.game.ladder.services.LadderTickService;
import de.kaliburg.morefair.game.ladder.services.utils.LadderUtilsService;
import de.kaliburg.morefair.game.ranker.model.RankerEntity;
import de.kaliburg.morefair.game.ranker.services.RankerService;
import de.kaliburg.morefair.game.round.model.RoundEntity;
import de.kaliburg.morefair.game.round.services.RoundService;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class LadderTickServiceImpl implements LadderTickService {

  private static final double NANOS_IN_SECONDS = TimeUnit.SECONDS.toNanos(1);
  private final CriticalRegion semaphore = new CriticalRegion(1);
  private final AccountSettingsService accountSettingsService;
  private final LadderEventService ladderEventService;
  private final RankerService rankerService;
  private final LadderService ladderService;
  private final RoundService roundService;
  private final WsUtils wsUtils;
  private final LadderUtilsService ladderUtilsService;
  private long lastTickInNanos = System.nanoTime();

  @Scheduled(initialDelay = 1000, fixedRate = 1000)
  public void update() {
    try (var ignored = semaphore.enter()) {
      // Calculate Time passed
      long currentTimeInNanos = System.nanoTime();
      int deltaInSeconds = (int) Math.round(
          (currentTimeInNanos - lastTickInNanos) / NANOS_IN_SECONDS
      );

      if (deltaInSeconds <= 0) {
        return;
      }

      lastTickInNanos = currentTimeInNanos;

      ladderEventService.handleEvents();

      // Send the tick for everyone
      LadderTickDto tickDto = LadderTickDto.builder()
          .delta(deltaInSeconds)
          .build();
      wsUtils.convertAndSendToTopic(FairController.TOPIC_TICK_DESTINATION, tickDto);

      // Calculate the tick yourself
      RoundEntity currentRound = roundService.getCurrentRound();
      Collection<LadderEntity> ladders = ladderService.findAllByRound(currentRound);
      List<CompletableFuture<Void>> futures = ladders.stream()
          .map(ladder -> CompletableFuture.runAsync(
              () -> calculateLadder(ladder, deltaInSeconds))).toList();
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();

    } catch (ExecutionException | InterruptedException e) {
      log.error(e.getMessage(), e);
    }
  }

  private void calculateLadder(LadderEntity ladder, int delta) {
    List<RankerEntity> rankers = rankerService.findAllByCurrentLadderNumber(ladder.getNumber());
    rankers.sort(Comparator.comparing(RankerEntity::getPoints).reversed());

    for (int i = 0; i < rankers.size(); i++) {
      RankerEntity currentRanker = rankers.get(i);
      currentRanker.setRank(i + 1);
      // if the ranker is currently still on the ladder
      if (currentRanker.isGrowing()) {
        // Calculating points & Power
        if (currentRanker.getRank() != 1) {
          currentRanker.addPower(
              (i + currentRanker.getBias()) * currentRanker.getMultiplier(), delta);
        }
        currentRanker.addPoints(currentRanker.getPower(), delta);

        // Calculating Vinegar based on Grapes count
        var settings = accountSettingsService.findOrCreateByAccount(currentRanker.getAccountId());

        if (currentRanker.getRank() != 1) {
          var addedVinegar = currentRanker.getGrapes()
              .multiply(BigInteger.valueOf(settings.getVinegarSplit()))
              .divide(BigInteger.valueOf(100));
          currentRanker.addVinegar(addedVinegar, delta);

          var addedWine = currentRanker.getGrapes()
              .multiply(BigInteger.valueOf(settings.getWineSplit()))
              .multiply(BigInteger.valueOf(3))
              .divide(BigInteger.valueOf(100));
          currentRanker.addWine(addedWine, delta);
        }
        if (currentRanker.getRank() == 1 && ladderUtilsService.isLadderPromotable(ladder)) {
          currentRanker.decayVinegar(delta);
          currentRanker.decayWine(delta);
        }

        for (int j = i - 1; j >= 0; j--) {
          // If one of the already calculated Rankers have less points than this ranker
          // swap these in the list... This way we keep the list sorted, theoretically
          if (currentRanker.getPoints().compareTo(rankers.get(j).getPoints()) > 0) {
            // Move 1 Position up and move the ranker there 1 Position down

            // Move other Ranker 1 Place down
            RankerEntity temp = rankers.get(j);
            temp.setRank(j + 2);
            if (temp.isGrowing() && temp.getMultiplier() > 1) {
              temp.setGrapes(temp.getGrapes()
                  .add(BigInteger.valueOf(ladder.getPassingGrapes()))
                  .max(BigInteger.ZERO)
              );
            }
            rankers.set(j + 1, temp);

            // Move this Ranker 1 Place up
            currentRanker.setRank(j + 1);
            rankers.set(j, currentRanker);
          } else {
            break;
          }
        }
      }

      /*if (currentRanker.getAccountId() == 3) {
        log.info("{} {} {} {}", delta, currentRanker.getGrapes(), currentRanker.getVinegar(),
            currentRanker.getWine());
      }*/
    }
    // Ranker on Last Place gains 1 Grape, even if he's also in first at the same time (ladder of 1)
    if (!rankers.isEmpty()) {
      RankerEntity lastRanker = rankers.get(rankers.size() - 1);
      if (lastRanker.isGrowing()) {
        lastRanker.addGrapes(BigInteger.valueOf(ladder.getBottomGrapes()), delta);
      }
    }

    if (!rankers.isEmpty() && (rankers.get(0).isAutoPromote() || ladder.getTypes()
        .contains(LadderType.FREE_AUTO)) && rankers.get(0).isGrowing()
        && ladderUtilsService.isLadderPromotable(ladder)) {
      ladderEventService.addEvent(ladder.getNumber(),
          new Event<>(PROMOTE, rankers.get(0).getAccountId()));
    }

    rankerService.updateRankersOfLadder(ladder, rankers);
  }

  @Override
  public CriticalRegion getSemaphore() {
    return semaphore;
  }
}
