package de.kaliburg.morefair.statistics.records.model;

import de.kaliburg.morefair.account.model.AccountEntity;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NonNull;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "login")
@Data
public class LoginRecordEntity {

  private Instant createdOn = Instant.now();
  @NonNull
  private Account account;

  public LoginRecordEntity(AccountEntity account) {
    this.account = new Account(account.getId(), account.getDisplayName());
  }

  @Data
  @AllArgsConstructor
  private class Account {

    private Long id;
    private String name;
  }
}


