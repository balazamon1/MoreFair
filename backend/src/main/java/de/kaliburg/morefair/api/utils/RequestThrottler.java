package de.kaliburg.morefair.api.utils;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import de.kaliburg.morefair.account.model.AccountEntity;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

@Component
@Log4j2
public class RequestThrottler {

  private static final Integer MAX_MESSAGES = 3;

  private final LoadingCache<Integer, Integer> hasCreatedAccountInTheLastMinute =
      Caffeine.newBuilder().expireAfterWrite(1, TimeUnit.MINUTES)
          .build(integer -> 0);

  private final LoadingCache<Integer, Integer> hasCreatedAccountInTheLastHour =
      Caffeine.newBuilder().expireAfterWrite(1, TimeUnit.HOURS)
          .build(integer -> 0);

  private final LoadingCache<Integer, Integer> hasCreatedAccountInTheLastDay =
      Caffeine.newBuilder().expireAfterWrite(1, TimeUnit.DAYS)
          .build(integer -> 0);

  private final LoadingCache<UUID, Integer> hasPostedMessageRecently;

  public RequestThrottler() {
    this.hasPostedMessageRecently = Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.SECONDS)
        .build(integer -> 0);
  }

  public boolean canCreateAccount(Integer ipAddress) {
    Integer number = hasCreatedAccountInTheLastMinute.get(ipAddress);
    hasCreatedAccountInTheLastMinute.asMap().remove(ipAddress);
    hasCreatedAccountInTheLastMinute.put(ipAddress, number + 1);
    if (number > 0) {
      return false;
    }

    number = hasCreatedAccountInTheLastHour.get(ipAddress);
    hasCreatedAccountInTheLastHour.asMap().remove(ipAddress);
    hasCreatedAccountInTheLastHour.put(ipAddress, number + 1);
    if (number > 3) {
      return false;
    }

    hasCreatedAccountInTheLastDay.asMap().remove(ipAddress);
    hasCreatedAccountInTheLastDay.put(ipAddress, number + 1);
    number = hasCreatedAccountInTheLastDay.get(ipAddress);
    return number <= 5;
  }

  public boolean canPostMessage(AccountEntity account) {
    return canPostMessage(account.getUuid());
  }

  public boolean canPostMessage(UUID uuid) {
    Integer requests;
    requests = hasPostedMessageRecently.get(uuid);
    if (requests != null) {
      if (requests >= MAX_MESSAGES) {
        hasPostedMessageRecently.asMap().remove(uuid);
        hasPostedMessageRecently.put(uuid, requests);
        return false;
      }
    } else {
      requests = 0;
    }
    requests++;
    hasPostedMessageRecently.put(uuid, requests);
    return true;
  }
}
