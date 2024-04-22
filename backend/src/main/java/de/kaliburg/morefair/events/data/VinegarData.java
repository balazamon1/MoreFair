package de.kaliburg.morefair.events.data;

import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@Data
@RequiredArgsConstructor
public class VinegarData {

  @NonNull
  private String amount;
  @NonNull
  private Integer percentage;
  private boolean success = false;
  @NonNull
  private Long targetId;
}
